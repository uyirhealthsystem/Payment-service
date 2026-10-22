package com.uyirhealthapp.payment_service.linkedaccount;

import com.uyirhealthapp.payment_service.common.ApiException;
import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.DetailsRejectedException;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.ProductState;
import com.uyirhealthapp.payment_service.linkedaccount.LinkedAccountDtos.LinkedAccountView;
import com.uyirhealthapp.payment_service.linkedaccount.LinkedAccountDtos.OnboardRequest;
import com.uyirhealthapp.payment_service.payment.Callers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Onboards a business as a Razorpay Route linked account and tracks its activation. A caller onboards only
 * themselves: the account is keyed by (ownerType, caller's user id), so nobody can register or replace someone
 * else's bank account. Each provider step is saved as soon as it succeeds, so calling onboard again after a
 * failure resumes instead of creating a second Razorpay account.
 */
@Service
@Slf4j
public class LinkedAccountService {

    private final LinkedAccountRepository accounts;
    private final LinkedAccountGateway gateway;
    private final LinkedAccountProperties props;
    private final PaymentProperties paymentProps;
    private final Callers callers;
    private final Clock clock;

    public LinkedAccountService(LinkedAccountRepository accounts, LinkedAccountGateway gateway,
                                LinkedAccountProperties props, PaymentProperties paymentProps, Callers callers,
                                Clock clock) {
        this.accounts = accounts;
        this.gateway = gateway;
        this.props = props;
        this.paymentProps = paymentProps;
        this.callers = callers;
        this.clock = clock;
    }

    public LinkedAccountView onboard(OnboardRequest req, Jwt caller) {
        String role = props.roleFor(req.ownerType());
        if (role == null) {
            throw ApiException.badRequest("Unknown ownerType " + req.ownerType());
        }
        if (!role.equals(caller.getClaimAsString(paymentProps.security().roleClaim()))) {
            throw ApiException.forbidden("Only a " + role + " user can onboard a " + req.ownerType() + " account");
        }
        LinkedAccount account = findOrCreate(req, caller.getSubject());
        if (!account.getReferenceId().equals(req.referenceId())) {
            throw ApiException.conflict("This user's " + req.ownerType() + " linked account belongs to "
                    + account.getReferenceId());
        }

        Instant now = clock.instant();
        if (accounts.claim(account.getId(), now, now.plus(props.claimTimeout())) == 0) {
            throw ApiException.conflict("Onboarding is already in progress for this account, retry shortly");
        }
        try {
            account = accounts.findById(account.getId()).orElseThrow();
            return LinkedAccountView.of(runSteps(account, req));
        } catch (GatewayException e) {
            throw toApi(e);
        } finally {
            accounts.release(account.getId());
        }
    }

    /** The caller's own linked account for an ownerType. */
    public LinkedAccountView mine(String ownerType, Jwt caller) {
        return accounts.findByOwnerTypeAndOwnerUserId(ownerType, caller.getSubject())
                .map(LinkedAccountView::of)
                .orElseThrow(() -> ApiException.notFound("No " + ownerType + " linked account for this user"));
    }

    public LinkedAccountView get(UUID id, Jwt caller) {
        return LinkedAccountView.of(accessible(id, caller));
    }

    /** Asks the provider where the account's activation stands now. */
    public LinkedAccountView refresh(UUID id, Jwt caller) {
        LinkedAccount account = accessible(id, caller);
        try {
            return LinkedAccountView.of(refresh(account));
        } catch (GatewayException e) {
            throw toApi(e);
        }
    }

    public enum UpdateResult {
        UPDATED, NOT_OURS, BUSY
    }

    /**
     * A provider webhook about a Route product: re-read it from the provider (the webhook only says "something
     * changed", our own API call is the source of truth). BUSY while an onboarding request is writing the account:
     * the webhook should be retried later.
     */
    public UpdateResult onProviderUpdate(String providerProductId, String providerAccountId) {
        Optional<LinkedAccount> found = providerProductId != null
                ? accounts.findByProviderProductId(providerProductId) : Optional.empty();
        if (found.isEmpty() && providerAccountId != null) {
            found = accounts.findByProviderAccountId(providerAccountId);
        }
        if (found.isEmpty()) {
            return UpdateResult.NOT_OURS;
        }
        LinkedAccount account = found.get();
        if (isClaimed(account)) {
            return UpdateResult.BUSY;
        }
        refresh(account);
        return UpdateResult.UPDATED;
    }

    /** Used by the reconciler; skips accounts an onboarding request holds right now. */
    LinkedAccount refresh(LinkedAccount account) {
        if (account.getProviderProductId() == null || isClaimed(account)) {
            return account;
        }
        ProductState state = gateway.getRoute(account.getProviderAccountId(), account.getProviderProductId());
        apply(account, state);
        return accounts.save(account);
    }

    private LinkedAccount runSteps(LinkedAccount a, OnboardRequest req) {
        if (a.getProviderAccountId() == null) {
            var addr = req.address();
            a.setProviderAccountId(gateway.createAccount(new LinkedAccountGateway.AccountRequest(
                    req.email(), req.phone(), req.legalBusinessName(), req.businessType(), req.contactName(),
                    req.category(), req.subcategory(),
                    new LinkedAccountGateway.Address(addr.street1(), addr.street2(), addr.city(), addr.state(),
                            addr.postalCode(), addr.country() != null ? addr.country() : "IN"),
                    req.pan(), req.gst())));
            a = accounts.save(a);
            log.info("Linked account {} ({} {}): created {}", a.getId(), a.getOwnerType(), a.getReferenceId(),
                    a.getProviderAccountId());
        }
        if (a.getProviderStakeholderId() == null) {
            var s = req.stakeholder();
            a.setProviderStakeholderId(gateway.createStakeholder(a.getProviderAccountId(),
                    new LinkedAccountGateway.StakeholderRequest(s.name(), s.email(), s.phone(), s.pan())));
            a = accounts.save(a);
        }
        if (a.getProviderProductId() == null) {
            ProductState state = gateway.requestRoute(a.getProviderAccountId());
            a.setProviderProductId(state.productId());
            apply(a, state);
            a = accounts.save(a);
        }
        var bank = req.bankAccount();
        String last4 = bank.accountNumber().substring(bank.accountNumber().length() - 4);
        boolean bankChanged = a.getBankAccountLast4() == null
                || !last4.equals(a.getBankAccountLast4())
                || !bank.ifscCode().equals(a.getIfscCode())
                || !bank.beneficiaryName().equals(a.getBeneficiaryName());
        if (bankChanged) {
            ProductState state = gateway.submitBankAccount(a.getProviderAccountId(), a.getProviderProductId(),
                    new LinkedAccountGateway.BankAccount(bank.accountNumber(), bank.ifscCode(),
                            bank.beneficiaryName()));
            a.setBankAccountLast4(last4);
            a.setIfscCode(bank.ifscCode());
            a.setBeneficiaryName(bank.beneficiaryName());
            apply(a, state);
            a = accounts.save(a);
            log.info("Linked account {}: bank account ...{} submitted, status {}", a.getId(), last4, a.getStatus());
        }
        return a;
    }

    private LinkedAccount findOrCreate(OnboardRequest req, String ownerUserId) {
        return accounts.findByOwnerTypeAndOwnerUserId(req.ownerType(), ownerUserId).orElseGet(() -> {
            LinkedAccount a = new LinkedAccount();
            a.setId(UUID.randomUUID());
            a.setOwnerType(req.ownerType());
            a.setOwnerUserId(ownerUserId);
            a.setReferenceId(req.referenceId());
            a.setProvider(paymentProps.isMock() ? "mock" : "razorpay");
            a.setStatus(Activation.PENDING_DETAILS);
            a.setLegalBusinessName(req.legalBusinessName());
            a.setEmail(req.email());
            try {
                return accounts.saveAndFlush(a);
            } catch (DataIntegrityViolationException e) {
                // a concurrent first call created it
                return accounts.findByOwnerTypeAndOwnerUserId(req.ownerType(), ownerUserId).orElseThrow(() -> e);
            }
        });
    }

    private void apply(LinkedAccount a, ProductState state) {
        Activation before = a.getStatus();
        a.setStatus(state.activation());
        a.setRequirements(state.requirements().isEmpty() ? null : truncate(String.join(", ", state.requirements())));
        if (state.activation() == Activation.ACTIVATED && a.getActivatedAt() == null) {
            a.setActivatedAt(clock.instant());
        }
        if (!Objects.equals(before, state.activation())) {
            log.info("Linked account {} ({} {}): {} -> {}", a.getId(), a.getOwnerType(), a.getReferenceId(), before,
                    state.activation());
        }
    }

    private boolean isClaimed(LinkedAccount a) {
        return a.getOnboardingUntil() != null && a.getOnboardingUntil().isAfter(clock.instant());
    }

    private LinkedAccount accessible(UUID id, Jwt caller) {
        LinkedAccount a = accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Linked account " + id + " not found"));
        if (!callers.isAdmin(caller) && !a.getOwnerUserId().equals(caller.getSubject())) {
            // same answer as a missing one: do not reveal which ids exist
            throw ApiException.notFound("Linked account " + id + " not found");
        }
        return a;
    }

    private static ApiException toApi(GatewayException e) {
        if (e instanceof DetailsRejectedException) {
            return ApiException.badRequest(e.getMessage());
        }
        return e.isRetryable()
                ? ApiException.serviceUnavailable("Payment provider unavailable, please retry", Duration.ofSeconds(30))
                : ApiException.badGateway("Payment provider refused the request: " + e.getMessage());
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
