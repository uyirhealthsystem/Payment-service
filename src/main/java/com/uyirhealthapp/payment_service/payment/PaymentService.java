package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.common.ApiException;
import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Session;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.SessionRequest;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.CreateIntentRequest;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.PaymentView;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.RefundView;
import com.uyirhealthapp.payment_service.refund.RefundRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creating payments and handing out checkouts. The flow for every module:
 *
 * <pre>
 *   module    --POST /api/payments/intents (Idempotency-Key)-->  here: payment PENDING + Razorpay payment link
 *   user      --checkoutUrl-->  Razorpay hosted checkout page
 *   Razorpay  --POST /api/payments/webhooks/razorpay-->  here: PAID  --signed callback-->  module
 * </pre>
 *
 * No DB transaction is held while Razorpay is called.
 */
@Service
@Slf4j
public class PaymentService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:\\-]{8,128}");
    private static final Duration PROVIDER_RETRY_AFTER = Duration.ofSeconds(10);

    private final PaymentProperties props;
    private final PaymentRepository payments;
    private final CheckoutSessionRepository sessions;
    private final RefundRepository refunds;
    private final PaymentGateway gateway;
    private final PaymentSettlement settlement;
    private final CheckoutReconciler reconciler;
    private final Callers callers;
    private final TransactionTemplate tx;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public PaymentService(PaymentProperties props, PaymentRepository payments, CheckoutSessionRepository sessions,
                          RefundRepository refunds, PaymentGateway gateway, PaymentSettlement settlement,
                          CheckoutReconciler reconciler, Callers callers, TransactionTemplate tx,
                          PaymentMetrics metrics, Clock clock) {
        this.props = props;
        this.payments = payments;
        this.sessions = sessions;
        this.refunds = refunds;
        this.gateway = gateway;
        this.settlement = settlement;
        this.reconciler = reconciler;
        this.callers = callers;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Creates the payment, or, for a key seen before, returns the same one: PAID stays PAID, an open checkout is
     * handed out again, and an expired or failed one is replaced by a fresh checkout (the user can retry).
     */
    public PaymentView createIntent(CreateIntentRequest req, String idempotencyKey, Jwt caller) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("Idempotency-Key header is required: 8-128 chars of A-Z a-z 0-9 . _ : -");
        }
        PaymentProperties.Consumer consumer = props.consumer(req.referenceType());
        if (consumer == null) {
            throw ApiException.badRequest("Unknown referenceType " + req.referenceType());
        }
        // real money only for a module that will be told the result (mock payments are not real)
        if (!props.isMock() && !consumer.notifies()) {
            throw ApiException.badRequest("referenceType " + req.referenceType()
                    + " is not enabled: its webhook-url is not configured in the payment service");
        }
        if (!props.currency().equalsIgnoreCase(req.currency())) {
            throw ApiException.badRequest("Only " + props.currency() + " is supported");
        }
        if (!req.payerId().equals(caller.getSubject()) && !callers.isAdmin(caller)) {
            throw ApiException.forbidden("payerId must be the calling user");
        }

        Payment p = findOrCreate(req, idempotencyKey);
        if (p.getStatus() == PaymentStatus.PAID) {
            return view(p, null);
        }
        Instant usableUntil = clock.instant().plus(props.checkout().minRemaining());
        CheckoutSession current = sessions.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElse(null);
        if (current != null && current.isUsableAt(usableUntil)) {
            return view(p, current);
        }
        return openCheckout(p, usableUntil);
    }

    public PaymentView get(UUID id, Jwt caller) {
        Payment p = accessible(id, caller);
        return view(p, sessions.findFirstByPaymentIdOrderByCreatedAtDesc(id).orElse(null));
    }

    /** Payments for one booking of one module. Non-admins only see their own. */
    public List<PaymentView> findByReference(String referenceType, String referenceId, Jwt caller) {
        boolean admin = callers.isAdmin(caller);
        return payments.findByReferenceTypeAndReferenceIdOrderByCreatedAtDesc(referenceType, referenceId).stream()
                .filter(p -> admin || p.getPayerId().equals(caller.getSubject()))
                .map(p -> view(p, sessions.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElse(null)))
                .toList();
    }

    /**
     * Asks the provider right now instead of waiting for its webhook; for the app to call when the user returns from
     * the checkout page.
     */
    public PaymentView refresh(UUID id, Jwt caller) {
        Payment p = accessible(id, caller);
        if (p.getStatus() != PaymentStatus.PAID) {
            for (CheckoutSession s : sessions.findByPaymentIdAndStatus(id, CheckoutSession.Status.OPEN)) {
                try {
                    reconciler.check(s);
                } catch (GatewayException e) {
                    throw unavailable(e);
                }
            }
        }
        return get(id, caller);
    }

    private Payment findOrCreate(CreateIntentRequest req, String key) {
        Payment existing = payments.findByIdempotencyKey(key).orElse(null);
        if (existing == null) {
            try {
                existing = tx.execute(s -> payments.save(newPayment(req, key)));
                metrics.intentCreated(req.referenceType());
                log.info("Payment {} created: {} {} for {} {}", existing.getId(), req.amount(), req.currency(),
                        req.referenceType(), req.referenceId());
            } catch (DataIntegrityViolationException e) {
                // the same key raced us (double tap): use the winner's payment
                existing = payments.findByIdempotencyKey(key).orElseThrow(() -> e);
            }
        }
        if (!sameRequest(existing, req)) {
            throw ApiException.conflict("Idempotency-Key was already used for a different payment");
        }
        return existing;
    }

    private Payment newPayment(CreateIntentRequest req, String key) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setIdempotencyKey(key);
        p.setReferenceType(req.referenceType());
        p.setReferenceId(req.referenceId());
        p.setPayerId(req.payerId());
        p.setAmount(req.amount().setScale(2));
        p.setCurrency(req.currency().toUpperCase());
        p.setDescription(StringUtils.hasText(req.description()) ? req.description()
                : defaultDescription(req.referenceType(), req.referenceId()));
        p.setStatus(PaymentStatus.PENDING);
        p.setProvider(gateway.name());
        return p;
    }

    /** "AMBULANCE_BOOKING" + "7f3c..." -> "Ambulance booking 7f3c..." */
    private static String defaultDescription(String referenceType, String referenceId) {
        String type = referenceType.replace('_', ' ').toLowerCase();
        String text = Character.toUpperCase(type.charAt(0)) + type.substring(1) + " " + referenceId;
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static boolean sameRequest(Payment p, CreateIntentRequest req) {
        return p.getReferenceType().equals(req.referenceType())
                && p.getReferenceId().equals(req.referenceId())
                && p.getPayerId().equals(req.payerId())
                && p.getAmount().compareTo(req.amount()) == 0
                && p.getCurrency().equalsIgnoreCase(req.currency());
    }

    private PaymentView openCheckout(Payment p, Instant usableUntil) {
        PaymentProperties.Consumer consumer = props.consumer(p.getReferenceType());
        String returnUrl = consumer == null ? null : consumer.returnUrl();
        SessionRequest request = new SessionRequest(p.getId(), p.getAmount(), p.getCurrency(), p.getDescription(),
                p.getReferenceType(), p.getReferenceId(),
                StringUtils.hasText(returnUrl) ? returnUrl : props.checkout().successUrl());
        Session created;
        try {
            created = gateway.createSession(request);
        } catch (GatewayException e) {
            throw unavailable(e);
        }

        CheckoutSession saved = tx.execute(s -> {
            Payment locked = payments.lockById(p.getId()).orElseThrow();
            if (locked.getStatus() == PaymentStatus.PAID) {
                return null;
            }
            // a concurrent request may have opened a checkout meanwhile: hand out that one, ours is cancelled below
            CheckoutSession other = sessions.findFirstByPaymentIdOrderByCreatedAtDesc(locked.getId()).orElse(null);
            if (other != null && other.isUsableAt(usableUntil)) {
                return other;
            }
            CheckoutSession cs = new CheckoutSession();
            cs.setId(UUID.randomUUID());
            cs.setPaymentId(locked.getId());
            cs.setProviderSessionId(created.sessionId());
            cs.setCheckoutUrl(created.checkoutUrl());
            cs.setStatus(CheckoutSession.Status.OPEN);
            cs.setExpiresAt(created.expiresAt());
            if (locked.getStatus() == PaymentStatus.FAILED) {
                locked.setStatus(PaymentStatus.PENDING); // a new try
                locked.setFailureReason(null);
            }
            return sessions.save(cs);
        });
        cancelSuperseded(p.getId(), saved == null ? null : saved.getProviderSessionId(), created.sessionId());

        if (created.instantPaymentId() != null && saved != null) { // mock provider: already paid
            settlement.paid(p.getId(), created.sessionId(), created.instantPaymentId(), p.getAmount(),
                    p.getCurrency());
        }
        Payment current = payments.findById(p.getId()).orElseThrow();
        return view(current, saved);
    }

    /**
     * Only one checkout of a payment may stay payable, or the user can pay twice: cancels the ones handed out before
     * (replaced because they were about to expire) and the one created here if it lost a race (keep = the checkout
     * now handed out, null when the payment is already PAID). A checkout that cannot be cancelled (e.g. just paid)
     * stays OPEN; its webhook or the reconciler settles it.
     */
    private void cancelSuperseded(UUID paymentId, String keep, String created) {
        List<String> stale = new ArrayList<>(sessions.findByPaymentIdAndStatus(paymentId, CheckoutSession.Status.OPEN)
                .stream().map(CheckoutSession::getProviderSessionId).filter(id -> !id.equals(keep)).toList());
        if (!created.equals(keep) && !stale.contains(created)) {
            stale.add(created); // lost the race: never stored, never handed out
        }
        for (String id : stale) {
            try {
                gateway.cancelSession(id);
                settlement.expired(id);
                log.info("Checkout {} of payment {} cancelled: superseded", id, paymentId);
            } catch (GatewayException e) {
                log.warn("Could not cancel superseded checkout {} of payment {}: {}", id, paymentId, e.getMessage());
            }
        }
    }

    private Payment accessible(UUID id, Jwt caller) {
        Payment p = payments.findById(id).orElse(null);
        // someone else's payment looks exactly like a missing one
        if (p == null || !callers.mayAccess(caller, p)) {
            throw ApiException.notFound("Payment not found");
        }
        return p;
    }

    private PaymentView view(Payment p, CheckoutSession session) {
        boolean showCheckout = p.getStatus() != PaymentStatus.PAID && session != null
                && session.isUsableAt(clock.instant());
        List<RefundView> refundViews = p.getStatus() == PaymentStatus.PAID
                ? refunds.findByPaymentIdOrderByCreatedAtDesc(p.getId()).stream().map(RefundView::of).toList()
                : List.of();
        return new PaymentView(p.getId(), p.getStatus(),
                showCheckout ? session.getCheckoutUrl() : null,
                showCheckout ? session.getExpiresAt() : null,
                p.getReferenceType(), p.getReferenceId(), p.getPayerId(), p.getAmount(), p.getCurrency(),
                p.getRefundedAmount(), p.getFailureReason(), p.getCreatedAt(), p.getPaidAt(), refundViews);
    }

    private static ApiException unavailable(GatewayException e) {
        log.error("Payment provider call failed: {}", e.getMessage());
        return e.isRetryable()
                // Retry-After: apps polling /refresh back off instead of pushing Razorpay further into rate limiting
                ? ApiException.serviceUnavailable("Payment provider unavailable, please retry", PROVIDER_RETRY_AFTER)
                : ApiException.badGateway("Payment provider rejected the request");
    }
}
