# Payment integration guide for module services

How a Uyir module (teleconsultation, ambulance, appointments, lab/scan, home care, ...) takes a payment through
payment-service. Everything is plain HTTP, so any language works. You need no shared library, queue or database,
and the module never talks to Razorpay.

```
 module                                  payment-service                                Razorpay
 ──────                                  ───────────────                                ────────
 1. create booking (PENDING_PAYMENT)
 2. POST /api/payments/intents ────────▶ payment PENDING ─────── create payment link ──▶
    ◀──────────────── { id, status, checkoutUrl }
 3. app opens checkoutUrl ─────────────────────────────────────────────────────────────▶ hosted checkout page
                                         ◀────────────── signed webhook: link paid ──────
                                         payment PAID
 4. POST <module webhook-url> ◀───────── signed callback { eventId, paymentId, referenceId, status, amount }
 5. verify, mark booking paid, start the service (e.g. create the meeting room)
```

## 1. Register the module in payment-service (one time)

Add your `referenceType` to payment-service's configuration. You can use `application.properties`, or an
external `config/application.properties` next to the jar if you don't want to rebuild.

```properties
payment.consumers[TELECONSULTATION].webhook-url=${TELECONSULT_PAYMENT_WEBHOOK_URL:}
payment.consumers[TELECONSULTATION].webhook-secret=${TELECONSULT_PAYMENT_WEBHOOK_SECRET:}
# optional: where the browser goes after checkout (defaults to CHECKOUT_SUCCESS_URL)
payment.consumers[TELECONSULTATION].return-url=https://app.uyir.in/teleconsultation/payment-result
```

- Keep the `[...]` brackets. Without them Spring strips the `_` from the key.
- `referenceType` must match `[A-Z][A-Z0-9_]*`, max 64 chars.
- Generate the secret with `openssl rand -hex 32`. Set the same value in your module, for example as
  `PAYMENT_WEBHOOK_SECRET`.
- With the real provider, payment-service refuses intents for a `referenceType` until its `webhook-url` is set.
  This means money is never taken for a module that would not be told about it.
- The webhook URL must answer directly. A redirect (3xx, e.g. `http` → `https`) is **not followed** and is
  counted as delivered.

## 2. Authentication when calling payment-service

| Path | What to send | payment-service config |
|---|---|---|
| Through the api-gateway (recommended) | The user's JWT. The gateway forwards `X-User-Id`, `X-User-Role`, `X-Gateway-Secret` | `PAYMENT_GATEWAY_SECRET` |
| Module → payment-service directly | `Authorization: Bearer <the patient's JWT>` | `AUTH_JWKS_URL` |
| Local development | `X-User-Id` / `X-User-Role` headers, or `node scripts/dev-jwt.mjs` | `local` profile |

There is no service-to-service credential. An intent is always created **as the payer**: `payerId` must equal
the caller's user id, unless the caller has the `ADMIN` or `SUPER_ADMIN` role. Create the intent while the user
is in the request, and forward their token.

## 3. Create the booking first, then the payment intent

Create your own record first, for example an appointment in `PENDING_PAYMENT`. Then pass its id as
`referenceId`. payment-service does not check that the booking exists. It stores and echoes back whatever you
send.

```http
POST /api/payments/intents
Authorization: Bearer <patient JWT>
Idempotency-Key: teleconsult-<appointmentId>
Content-Type: application/json

{
  "referenceType": "TELECONSULTATION",
  "referenceId": "<appointmentId>",
  "payerId": "<patient user id = JWT sub>",
  "amount": 500.00,
  "currency": "INR",
  "description": "Teleconsultation with Dr. X"
}
```

| Field | Rule |
|---|---|
| `Idempotency-Key` header | Required, 8-128 chars of `A-Z a-z 0-9 . _ : -`. One key = one payment, forever. Derive it from the booking id |
| `referenceType` | Must be registered (step 1) |
| `referenceId` | Your booking id, max 128 chars |
| `payerId` | The calling user's id |
| `amount` | ≥ 1.00, at most 2 decimals. Compute it on the server, never take it from the app |
| `currency` | `INR` only |
| `description` | Optional, max 500 chars, shown on the checkout page |

Response (`200`):

```json
{
  "id": "a936fcae-...", "status": "PENDING",
  "checkoutUrl": "https://rzp.io/i/...", "checkoutExpiresAt": "2026-10-22T08:20:00Z",
  "referenceType": "TELECONSULTATION", "referenceId": "<appointmentId>", "payerId": "patient-42",
  "amount": 500.00, "currency": "INR", "refundedAmount": 0.00, "failureReason": null,
  "createdAt": "...", "paidAt": null, "refunds": []
}
```

Store `id` (the paymentId) on the booking and return `checkoutUrl` to the app. The link is valid for 20 minutes.

Calling again with the **same key** is always safe:

| Current state | Result |
|---|---|
| `PAID` | The paid payment, no checkout |
| `PENDING`, checkout has > 2 min left | The same `checkoutUrl` |
| Checkout expired, or last attempt `FAILED` | A new checkout, status back to `PENDING` |
| Same key, different amount / booking / payer | `409 Conflict` |

Errors use RFC 7807 problem details:

| Status | Meaning | What to do |
|---|---|---|
| 400 | Bad key, unknown/disabled `referenceType`, wrong currency, invalid field | Fix the request |
| 401 / 403 | No valid identity, or `payerId` is not the caller | Forward the user's token |
| 409 | Key reused with different data | Use a new key only for a genuinely new payment |
| 502 | Razorpay rejected the request | Show an error and log it |
| 503 + `Retry-After` | Razorpay unreachable or rate-limited | Retry after the given seconds with the same key |

## 4. After checkout: instant status for the app

Razorpay sends the browser to the return URL. The app should then call:

```http
POST /api/payments/{paymentId}/refresh      (with the patient's token)
```

This asks Razorpay right away. If the payment is paid, it is settled at once and the module's callback follows
within about 3 seconds. You can also poll `GET /api/payments/{paymentId}`.

If both the user and Razorpay's webhook are lost, a background job settles payments within about 3 minutes.

## 5. Receive the payment callback

When a payment becomes `PAID` or `FAILED`, payment-service POSTs to your `webhook-url`:

```http
POST https://teleconsultation/api/teleconsultation/payments/webhook
Content-Type: application/json
X-Payment-Timestamp: 1761120072
X-Payment-Signature: 3f1c...e9 (64 lowercase hex chars)

{
  "eventId": "5d0e41b2-...",
  "paymentId": "a936fcae-...",
  "referenceType": "TELECONSULTATION",
  "referenceId": "<appointmentId>",
  "bookingId": "<appointmentId>",
  "payerId": "patient-42",
  "status": "PAID",
  "amount": 500.00,
  "currency": "INR",
  "refundedAmount": 0.00,
  "failureReason": null,
  "occurredAt": "2026-10-22T08:01:12Z"
}
```

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID string | Unique per event and **the same on every retry**. Deduplicate on it |
| `paymentId` | UUID string | The `id` returned by the intent call |
| `referenceType` | string | Your registered type |
| `referenceId` | string | Your booking id, exactly as sent |
| `bookingId` | string | **Deprecated** copy of `referenceId`. Do not use in new code |
| `payerId` | string | User who paid |
| `status` | `PAID` \| `FAILED` | Callbacks are only sent for these two (`PENDING` is never sent) |
| `amount` | JSON number, 2 decimals | e.g. `500.00`, never a string |
| `currency` | string | `INR` |
| `refundedAmount` | JSON number, 2 decimals | `0.00` at payment time |
| `failureReason` | string \| null | Set when `FAILED` |
| `occurredAt` | ISO-8601 UTC | When the status changed (not when this attempt was sent) |

Callbacks queued before `eventId` was introduced do not carry it. If `eventId` is missing, fall back to
deduplicating on `paymentId` + `status` + `occurredAt`.

### 5.1 Verify the signature

```
X-Payment-Signature = lowercase_hex( HMAC-SHA256( key = webhookSecret (UTF-8),
                                                  message = X-Payment-Timestamp + "." + rawBody ) )
```

- Use the **raw request bytes**. Parsing the JSON and serialising it again changes the bytes and breaks the
  signature.
- Compare in constant time.
- Reject a timestamp more than 5 minutes from your clock. Each retry is signed again with a fresh timestamp, so
  this does not block retries.
- Permit this path without a JWT in your security config. The signature is what authenticates the request.

**Java (Spring)**

```java
@PostMapping("/api/teleconsultation/payments/webhook")
public ResponseEntity<Void> paymentWebhook(@RequestHeader("X-Payment-Timestamp") String timestamp,
                                           @RequestHeader("X-Payment-Signature") String signature,
                                           @RequestBody byte[] rawBody) throws Exception {
    long ts = Long.parseLong(timestamp);
    if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody)).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    PaymentCallback event = objectMapper.readValue(rawBody, PaymentCallback.class);
    paymentCallbacks.handle(event); // idempotent, see 5.2
    return ResponseEntity.ok().build();
}

// amount as BigDecimal: no floating point
public record PaymentCallback(UUID eventId, UUID paymentId, String referenceType, String referenceId,
                              String payerId, String status, BigDecimal amount, String currency,
                              BigDecimal refundedAmount, String failureReason, Instant occurredAt) {
}
```

**Node (Express)**

```js
app.post('/api/teleconsultation/payments/webhook', express.raw({ type: 'application/json' }), (req, res) => {
  const ts = req.get('X-Payment-Timestamp') || '';
  const sig = (req.get('X-Payment-Signature') || '').trim().toLowerCase();
  if (Math.abs(Date.now() / 1000 - Number(ts)) > 300) return res.sendStatus(401);
  const expected = crypto.createHmac('sha256', SECRET).update(`${ts}.`).update(req.body).digest('hex');
  if (sig.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) {
    return res.sendStatus(401);
  }
  const event = JSON.parse(req.body);
  const amountPaise = Math.round(event.amount * 100); // exact for 2-decimal amounts
  handle(event, amountPaise)                          // idempotent, see 5.2
    .then(() => res.sendStatus(200))
    .catch(() => res.sendStatus(500));                // 5xx -> payment-service retries
});
```

**Python**

```python
import hmac, hashlib, json, time
from decimal import Decimal

def verify(timestamp: str, signature: str, raw_body: bytes, secret: str) -> dict | None:
    if abs(time.time() - int(timestamp)) > 300:
        return None
    expected = hmac.new(secret.encode(), timestamp.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature.strip().lower()):
        return None
    return json.loads(raw_body, parse_float=Decimal)
```

### 5.2 Handle it idempotently

Callbacks are **at least once**. A callback can arrive more than once, and callbacks can arrive out of order.

1. If you have already processed this `eventId`, answer `200` and stop.
2. Load the booking by `referenceId` (and check `referenceType`). If it is unknown, answer `404`. It is retried,
   which helps if your own write has not committed yet.
3. If `status = PAID`:
   - Check `currency == "INR"` and `amount >= expected fee`, compared as decimals or paise. Never use floats.
     If the check fails, do **not** mark the booking paid. Log and alert, and answer `200` so the callback is not
     retried for hours.
   - If the booking is already paid, answer `200` and do nothing.
   - Otherwise, mark it paid and store `paymentId`.
4. If `status = FAILED`:
   - If the booking is already paid, ignore it. A late `FAILED` can arrive after `PAID`.
   - Otherwise, optionally show "payment failed, try again". Do not cancel the booking: the user can retry with
     the same `Idempotency-Key` and a later `PAID` may follow.
5. Record the `eventId` in the same transaction as the booking change.
6. Answer `2xx` within **10 seconds**. Slower replies count as failures and are sent again.

`PAID` is sent at most once per payment. `FAILED` can be sent several times, once per failed attempt.

### 5.3 Retries

| Your answer | payment-service does |
|---|---|
| `2xx` | Delivered, done |
| `3xx` | Counted as delivered (redirects are not followed). Do not redirect |
| `4xx`, `5xx`, timeout (connect 3 s / read 10 s), connection error | Retries after 10 s, 20 s, 40 s, ... capped at 1 h |
| 12 failed attempts (about 5 h) | Marked `DEAD` and payment-service ops is alerted. Your module can still read `GET /api/payments/{id}` |

## 6. Example: teleconsultation with a meeting room

```
patient books slot  ──▶ appointment PENDING_PAYMENT
                    ──▶ POST /api/payments/intents (key teleconsult-<appointmentId>)
                    ◀── checkoutUrl  ──▶ app opens it, patient pays
app returns         ──▶ POST /api/payments/{id}/refresh
callback PAID       ──▶ verify signature + eventId + amount
                    ──▶ appointment CONFIRMED (one transaction, eventId recorded)
                    ──▶ create meeting room, idempotent on appointmentId
                    ◀── 200
```

- Key the meeting room on the **appointment id**, for example a unique constraint or an
  "upsert where appointment_id = ?". A duplicate callback, or a second payment for the same appointment, then can
  never create two rooms.
- If creating the room calls a slow external API, mark the appointment confirmed, answer `200`, and create the room
  in a background job or outbox of your own. Don't hold the callback open for more than 10 seconds.
- Show the app "confirming your payment..." until the appointment is `CONFIRMED`. Poll your own API, which the
  callback updates.

## 7. Refunds

- Only `ADMIN` / `SUPER_ADMIN` can refund: `POST /api/payments/{id}/refunds` with
  `{ "amount": 200.00, "reason": "REQUESTED_BY_CUSTOMER", "note": "..." }`. Omit `amount` for a full refund.
  `reason` is one of `DUPLICATE`, `FRAUDULENT`, `REQUESTED_BY_CUSTOMER`, `OTHERS`.
- **Refunds do not send a callback.** `status` stays `PAID`. Read `refundedAmount` and `refunds[]` from
  `GET /api/payments/{id}` if your module needs to react to a refund (e.g. cancel a meeting room).

## 8. Test locally

```sh
# payment-service
docker compose up -d
TELECONSULT_PAYMENT_WEBHOOK_URL=http://localhost:<port>/api/teleconsultation/payments/webhook \
TELECONSULT_PAYMENT_WEBHOOK_SECRET=local-teleconsult-secret \
./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

- The `local` profile uses the mock provider. Every checkout is paid immediately and the callback follows.
- Start with `PAYMENT_MOCK_AUTO_PAY=false` to keep payments `PENDING`. Then simulate Razorpay with the
  `[Simulate Razorpay]` requests in `docs/postman/`.
- Folder 5 of the Postman collection sends a signed callback to your module exactly the way payment-service does.
  It is useful for testing your signature check on its own.
- If your module calls payment-service with its own users' tokens, set `AUTH_PUBLIC_KEY_LOCATION` to the public
  key those tokens are signed with.

## 9. Checklist before going live

- [ ] `payment.consumers[<TYPE>]` webhook URL and secret set in payment-service; same secret in the module
- [ ] Webhook path open without a JWT, reachable from payment-service, and not redirecting
- [ ] Booking created before the intent; `Idempotency-Key` derived from the booking id
- [ ] Amount computed on the server; callback checks `amount >= fee` with decimals or paise
- [ ] Signature verified over raw bytes, constant-time compare, ±5 min timestamp window
- [ ] Handler idempotent on `eventId`; `PAID` is final; late `FAILED` ignored
- [ ] Side effects (meeting room, notifications) keyed on the booking id
- [ ] Handler answers `2xx` in under 10 s
- [ ] App calls `POST /api/payments/{id}/refresh` on return from checkout
- [ ] Refund handling decided (poll `refundedAmount` if needed)

## Not supported today

INR only. One-time payments only (no subscriptions or mandates). No service-to-service intents (the payer's
token is required). No automatic Route transfers to linked accounts. No refund callbacks.
