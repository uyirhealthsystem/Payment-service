# payment-service

One payment service for every Uyir module (ambulance, doctor appointments, lab/scan, home care, ...), backed by
**Razorpay**. Modules never talk to Razorpay. They say "user U owes ₹X for booking B", send the user to the
checkout URL they get back, and receive a signed callback once the payment settles.

```
 module (ambulance, ...)                payment-service                            Razorpay
 ───────────────────────                ───────────────                            ────────
 POST /api/payments/intents  ─────────▶ payment PENDING ──── POST /payment_links ──▶ payment link
   Idempotency-Key                      ◀── { id, status, checkoutUrl }
 app opens checkoutUrl ──────────────────────────────────────────────────────▶ hosted payment page
                                        ◀── POST /api/payments/webhooks/razorpay (signed) ── payment_link.paid
                                        payment PAID
 POST <module webhook-url>  ◀────────── signed callback { paymentId, referenceId, status: PAID, amount }
```

- **Self-contained.** It owns its database and talks to no other Uyir service by name. Modules are registered
  through configuration only: a `referenceType` plus a callback URL and secret. User identity comes from the
  api-gateway's headers (see [Authentication](#authentication)). Startup needs only its own Postgres.
- Port **8083** by default (`PORT`).
- The gateway routes `/api/payments/**` (JWT required) and `/api/payments/webhooks/**` (public, signature-checked).
- PostgreSQL; the schema is Flyway-owned (`src/main/resources/db/migration`).
- Stateless and safe to run as several replicas (see [Operations](#operations)).

## API

All endpoints except the provider webhooks need an authenticated user (id + role, see
[Authentication](#authentication)). Swagger UI is at `/swagger-ui.html`.

### Authentication

| How the request arrives | What proves who the user is | Config |
|---|---|---|
| Through the **api-gateway** (production) | The gateway verifies the JWT and sends `X-User-Id`, `X-User-Role` and `X-Gateway-Secret`. The headers are trusted only when the secret matches. No JWT is checked here, and no call is made to the auth service. | `PAYMENT_GATEWAY_SECRET` = the gateway's `PAYMENT_GATEWAY_SECRET` (≥ 32 chars, `openssl rand -hex 32`) |
| **Directly**, skipping the gateway (e.g. a module calling with the user's token) | `Authorization: Bearer <JWT>`, verified against the JWKS. `X-User-*` headers without the secret are ignored. | `AUTH_JWKS_URL` (optional; leave it empty to accept gateway traffic only) |
| **Local** profile | `X-User-Id` / `X-User-Role` set by yourself (`TRUST_IDENTITY_HEADERS=true`, development only), or a dev token | nothing to set |

The gateway strips any `X-User-*` / `X-Gateway-Secret` headers that a client sends, so only the gateway can set them.

| Method | Path | Who | What |
|---|---|---|---|
| POST | `/api/payments/intents` | the payer (or ADMIN) | Create the payment, or return the existing one for this `Idempotency-Key` |
| GET | `/api/payments/{id}` | payer / ADMIN | Status, checkout URL while pending, refunds |
| GET | `/api/payments?referenceType=&referenceId=` | payer / ADMIN | Payments for one booking |
| POST | `/api/payments/{id}/refresh` | payer / ADMIN | Ask the provider now (call it when the user returns from the checkout page) |
| POST | `/api/payments/{id}/refunds` | ADMIN, SUPER_ADMIN | Full or partial refund |
| POST | `/api/payments/webhooks/razorpay` | Razorpay | Payment link/refund events, `X-Razorpay-Signature` |

### Create a payment

```http
POST /api/payments/intents
Authorization: Bearer <the payer's JWT>
Idempotency-Key: ambulance-booking-7f3c...        (8-128 chars; one key = one payment, forever)

{ "referenceType": "AMBULANCE_BOOKING", "referenceId": "7f3c...", "payerId": "<sub of the token>",
  "amount": 640.00, "currency": "INR", "description": "Ambulance ride 7f3c..." }   (description optional)
```

```json
{ "id": "a936fcae-...", "status": "PENDING", "checkoutUrl": "https://rzp.io/i/...",
  "checkoutExpiresAt": "2026-09-25T08:14:00Z", "amount": 640.00, "currency": "INR", "refundedAmount": 0.00, ... }
```

Calling it again with the same key is always safe:

| Current state | Result |
|---|---|
| `PAID` | the paid payment, no checkout |
| `PENDING` and the checkout has > 2 min left | the same checkout URL |
| checkout expired, or the last attempt `FAILED` | a new checkout, status back to `PENDING` |
| same key but a different amount / booking / payer | `409` |

`status` is always one of `PENDING | PAID | FAILED`. Modules deserialize it into their own enum, so that set is
fixed. Refunds show up in `refundedAmount` / `refunds`, not in `status`.

### The result callback (payment-service → module)

When a payment becomes `PAID` or `FAILED`, it is POSTed to the module's `webhook-url`:

```http
POST https://ambulance/api/ambulance/payments/webhook
X-Payment-Timestamp: 1727222400                                          (epoch seconds)
X-Payment-Signature: hex(HMAC-SHA256(webhookSecret, timestamp + "." + rawBody))

{ "eventId": "5d0e41b2-...", "paymentId": "a936fcae-...", "referenceType": "AMBULANCE_BOOKING",
  "referenceId": "7f3c...", "bookingId": "7f3c...", "payerId": "patient-42", "status": "PAID", "amount": 640.00, "currency": "INR",
  "refundedAmount": 0.00, "failureReason": null, "occurredAt": "2026-09-25T08:01:12Z" }
```

- `eventId` is unique per event and stays the same on every retry of that event, so modules can deduplicate on it.
- `bookingId` is a **deprecated** copy of `referenceId`, kept for existing consumers. New modules read
  `referenceId`.
- The callback comes from a transactional outbox. It is retried with backoff (10 s, 20 s, 40 s … up to 1 h,
  12 attempts, about 5 h) until the module answers 2xx, so **handlers must be idempotent**. The same event can
  arrive twice, and a late `FAILED` can arrive after `PAID` (ignore it).
- **Check the amount.** The payer's app is what creates the intent, so a module must confirm that
  `amount` ≥ what it charges before it marks the booking paid.

## Adding a new module (e.g. doctor appointments)

Step-by-step guide for module developers, with signature-check code in Java, Node and Python:
[docs/IMPLEMENTATION_GUIDE.md](docs/IMPLEMENTATION_GUIDE.md).

1. **Here:** register it in `application.properties`. The key is the `referenceType` the module will send.
   `DOCTOR_APPOINTMENT`, `LAB_SCAN_BOOKING` and `HOME_CARE_BOOKING` are already listed and only need env vars:
   ```properties
   payment.consumers[DOCTOR_APPOINTMENT].webhook-url=${APPOINTMENT_PAYMENT_WEBHOOK_URL:}
   payment.consumers[DOCTOR_APPOINTMENT].webhook-secret=${APPOINTMENT_PAYMENT_WEBHOOK_SECRET:}
   # optional: send users back to a module-specific page after checkout
   payment.consumers[DOCTOR_APPOINTMENT].return-url=https://app.uyir.in/appointments/payment-result
   ```
   Keep the `[...]` brackets. Without them Spring strips the `_` from the key. To add a module without a rebuild,
   put these lines in an external `config/application.properties` next to the jar (or the equivalent in your
   deployment's config). With a real provider, a `referenceType` is rejected until its `webhook-url` is set, so
   money is never taken for a module that would not be told.
2. **In the module:** implement the HTTP contract above, in any language: create intents, then verify and
   handle the signed callback. Give the module the same secret as its `PAYMENT_WEBHOOK_SECRET`.
3. Permit the module's webhook path without a JWT in its security config. The HMAC signature authenticates it.

## Linked accounts (Razorpay Route)

A business that is paid its share of payments directly into its own bank account (today: hospitals, for
appointment commissions) is registered as a Razorpay Route **linked account**. Route must first be enabled on the
Razorpay account (Dashboard → *Route*, or ask Razorpay support).

| | |
|---|---|
| `PUT /api/payments/linked-accounts` | Onboard, continue onboarding, or change the bank account. Send the full details every time; steps already done at Razorpay are skipped |
| `GET /api/payments/linked-accounts?ownerType=HOSPITAL` | The caller's own linked account |
| `GET /api/payments/linked-accounts/{id}` | Owner or admin |
| `POST /api/payments/linked-accounts/{id}/refresh` | Ask Razorpay for the activation status now |

- **Who may onboard:** `payment.linked-accounts.owner-roles[<ownerType>]=<role>`. Only `HOSPITAL` is configured.
  The account is keyed by (ownerType, caller's user id), so a user can only ever create or change their own bank
  account.
- **Steps**, each saved as soon as it succeeds so a failure resumes instead of creating a second account:
  `POST /v2/accounts` → `POST /v2/accounts/{acc}/stakeholders` → `POST /v2/accounts/{acc}/products` (route) →
  `PATCH /v2/accounts/{acc}/products/{prd}` (bank account). The bank account is sent again only if its last 4
  digits, IFSC or beneficiary name changed.
- **Status:** `PENDING_DETAILS` → `UNDER_REVIEW` → `ACTIVATED`, or `NEEDS_CLARIFICATION` with `requirements`, or
  `SUSPENDED`. It is updated by the `product.route.*` webhooks (re-read from Razorpay, never trusted from the
  payload) and, in case a webhook is lost, every `payment.linked-accounts.refresh-interval`.
- **Bank details:** only the last 4 digits are stored, and the account number is never logged. A `400` carries
  Razorpay's reason ("The pan field is invalid.").
- `payment.provider=mock`: every account is `ACTIVATED` as soon as its bank account is submitted.

## Razorpay setup

Checkouts are Razorpay **Payment Links**: one hosted page per attempt, valid for 20 minutes
(`payment.razorpay.link-expiry`, Razorpay's minimum is 15). A failed attempt leaves the page open so the user can
try again; the payment only ends as paid or, after expiry, gets a fresh link on the next intent call.

1. **API keys:** Razorpay Dashboard → *Accounts & Settings* → *API Keys* → generate a key. Use **Test mode**
   (`rzp_test_...`) for local and staging, **Live mode** (`rzp_live_...`) for production. Each mode has its own
   keys and its own webhooks.
2. **Webhook:** Dashboard → *Accounts & Settings* → *Webhooks* → *Add new webhook*:
   - URL: `https://<public gateway host>/api/payments/webhooks/razorpay`
   - Secret: a random value you choose (`openssl rand -hex 32`)
   - Events: `payment_link.paid`, `payment_link.expired`, `payment_link.cancelled`, `refund.processed`,
     `refund.failed`, and for linked accounts `product.route.activated`, `product.route.under_review`,
     `product.route.needs_clarification`, `product.route.suspended`
3. Set the environment:

| Variable | |
|---|---|
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | from step 1 |
| `RAZORPAY_WEBHOOK_SECRET` | the secret from step 2 |
| `CHECKOUT_SUCCESS_URL` | app page Razorpay sends the user to after paying. It should call `POST /api/payments/{id}/refresh`. |
| `<MODULE>_PAYMENT_WEBHOOK_URL`, `<MODULE>_PAYMENT_WEBHOOK_SECRET` | one pair per module: `AMBULANCE`, `APPOINTMENT`, `LABSCAN`, `HOMECARE`. The secret must equal that module's `PAYMENT_WEBHOOK_SECRET` |
| `DB_URL`, `DB_USER`, `DB_PASS` | this service's own PostgreSQL (required) |
| `PAYMENT_GATEWAY_SECRET` | shared with the api-gateway (its `PAYMENT_GATEWAY_SECRET`); trusts its `X-User-*` headers |
| `AUTH_JWKS_URL` | JWKS for bearer tokens from callers that skip the gateway (ambulance calls this service directly with the patient's token, so set it). At least one of these two is required |
| `JWT_ISSUER` | optional: reject tokens with a different `iss` |

No variable has a default that points at another service or at localhost. Startup fails with a clear message if a
required value is missing.

**Payments from an earlier provider:** each payment remembers the provider it was made with. Checkouts still
open at an earlier provider are no longer looked up and close after 24 h, and payments made through it must be
refunded in that provider's own dashboard (`POST .../refunds` answers 409).

## Running locally

```sh
docker compose up -d                                   # Postgres on localhost:5435
./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

The `local` profile uses the **mock provider**, where every checkout is paid immediately. No Razorpay account is
needed, and no other service has to run. Send the identity the way the gateway would:

```sh
curl -X POST localhost:8083/api/payments/intents -H "X-User-Id: patient-42" -H "X-User-Role: PATIENT" \
     -H "Idempotency-Key: test-0001" -H "Content-Type: application/json" \
     -d '{"referenceType":"LAB_SCAN_BOOKING","referenceId":"lab-1","payerId":"patient-42","amount":300,"currency":"INR"}'
```

Use `X-User-Role: ADMIN` for refunds. Bearer tokens work too: `node scripts/dev-jwt.mjs patient-42 PATIENT`
mints one, signed with this repo's throw-away key in `dev-keys/` (git-ignored, created on first use).

In IntelliJ, set **Active profiles** to `local` in the run configuration.

Locally, no module is called back by default. To test against a module running on your machine, set its
`<MODULE>_PAYMENT_WEBHOOK_URL`. If that module forwards its own users' tokens, also set
`AUTH_PUBLIC_KEY_LOCATION` to the public key those tokens are signed with. To try a real test account, set `PAYMENT_PROVIDER=razorpay` with test-mode `RAZORPAY_*` keys.

### Postman

`docs/postman/` has a collection that covers every endpoint, plus two environments: `local` (:8083) and
`via gateway` (:8080). Setup steps are in the collection's description.

- Start the service with `PAYMENT_MOCK_AUTO_PAY=false`. Checkouts then stay `PENDING` until a signed Razorpay
  webhook is simulated by a `[Simulate Razorpay]` request, since Razorpay cannot reach localhost.
- Folders 1-4 and 6 run unattended in the Collection Runner or with
  `npx newman run docs/postman/payment-service.postman_collection.json -e docs/postman/payment-service.local.postman_environment.json --env-var patientToken=...`.
- Folder 5 calls a module's callback endpoint the way this service does. Folder 7 talks to Razorpay directly, to
  check the API keys.
- To change the collection, edit `docs/postman/generate.mjs` and run `node docs/postman/generate.mjs docs/postman`.

Tests: `./mvnw test` runs the unit tests. `./mvnw verify` also runs `PaymentFlowIT` against Postgres in Docker.

## Operations

- **Lost webhooks:** every minute, checkouts open for more than 3 min are looked up at the provider
  (`GET /payment_links/{id}`) and settled from the answer. Checkouts older than 24 h are closed as expired.
- **Multiple replicas:** callbacks are claimed with `FOR UPDATE SKIP LOCKED`. All state changes lock the payment
  row, and settling twice is a no-op. No Redis or leader election is needed.
- **Idempotency:** provider event ids are stored (`provider_event`), and the `Idempotency-Key` is unique per payment.
- **Probes and metrics:** `/actuator/health/liveness`, `/actuator/health/readiness` (includes the DB) and
  `/actuator/prometheus`. Alert on `payments_anomalies_total` (`amount_mismatch`, `double_payment`,
  `unknown_payment`, `foreign_payment`) and on `payments_callbacks_total{outcome="dead"}`. Each of these needs
  a person to look at it.
- **Double payment** (the user paid on two checkouts of the same payment): the payment is logged as
  `DOUBLE PAYMENT` with both provider payment ids. Refund one with `POST /api/payments/{id}/refunds`.
- **Refund stuck in `PENDING`:** the provider could not be reached while the refund was being created. Look it up in the
  provider's dashboard before trying again.


S_ID:dcbb466b-4ffb-40ab-939a-d46d7f3255cc
claude --resume f9fdf7f2-6059-4043-85d1-d956a317deba