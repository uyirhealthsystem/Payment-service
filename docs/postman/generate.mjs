// Generates the Postman collection + environments in this folder. Edit here, then run:
//   node docs/postman/generate.mjs docs/postman
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = process.argv[2];
mkdirSync(out, { recursive: true });

// ------------------------------------------------------------------------------------------ helpers

function url(raw) {
  const [base, qs] = raw.split('?');
  const m = base.match(/^(\{\{\w+\}\})(.*)$/);
  const u = { raw, host: [m[1]], path: m[2].split('/').filter(Boolean) };
  if (qs) u.query = qs.split('&').map((kv) => { const [key, value] = kv.split('='); return { key, value }; });
  return u;
}

const bearer = (v) => ({ type: 'bearer', bearer: [{ key: 'token', value: `{{${v}}}`, type: 'string' }] });
const noauth = { type: 'noauth' };
const js = (lines) => ({ type: 'text/javascript', exec: lines.flatMap((l) => l.split('\n')) });

function req(name, method, raw, o = {}) {
  const header = [...(o.headers || [])];
  const r = { method, header, url: url(raw) };
  if (o.description) r.description = o.description;
  if (o.json !== undefined) {
    header.unshift({ key: 'Content-Type', value: 'application/json' });
    r.body = { mode: 'raw', raw: typeof o.json === 'string' ? o.json : JSON.stringify(o.json, null, 2),
      options: { raw: { language: 'json' } } };
  }
  if (o.form) r.body = { mode: 'urlencoded', urlencoded: o.form.map(([key, value]) => ({ key, value, type: 'text' })) };
  if (o.auth) r.auth = o.auth;
  const event = [];
  if (o.pre) event.push({ listen: 'prerequest', script: js(o.pre) });
  if (o.tests) event.push({ listen: 'test', script: js(o.tests) });
  return { name, ...(event.length ? { event } : {}), request: r };
}

const folder = (name, description, item, auth) => ({ name, description, ...(auth ? { auth } : {}), item });
const status = (code) => `pm.test("status is ${code}", () => pm.response.to.have.status(${code}));`;
const idem = { key: 'Idempotency-Key', value: 'ambulance-booking-{{bookingId}}' };

const intentBody = (booking = '{{bookingId}}', amount = '{{amount}}', extra = '') => `{
  "referenceType": "AMBULANCE_BOOKING",
  "referenceId": "${booking}",
  "payerId": "{{patientId}}",
  "amount": ${amount},
  "currency": "INR"${extra}
}`;

// Simulated Razorpay webhook, signed exactly like Razorpay: X-Razorpay-Signature: hex(HMAC(secret, body))
function razorpayPre(event, vars) {
  const payload = event.startsWith('refund')
    ? `{
    refund: { entity: {
      id: "rfnd_" + Date.now(),
      entity: "refund",
      amount: 20000,
      currency: "INR",
      payment_id: "pay_simulated",
      status: "${event === 'refund.processed' ? 'processed' : 'failed'}",
      notes: { refund_id: pm.collectionVariables.get("refundId") }
    } }
  }`
    : `{
    payment_link: { entity: {
      id: pm.collectionVariables.get("${vars.session}"),
      entity: "payment_link",
      amount: paise,
      amount_paid: ${event === 'payment_link.paid' ? 'paise' : '0'},
      currency: "INR",
      status: "${event.split('.')[1]}",
      notes: { payment_id: pm.collectionVariables.get("${vars.payment}"), reference_type: "AMBULANCE_BOOKING" }
    } }${event === 'payment_link.paid' ? `,
    payment: { entity: {
      id: "pay_" + Date.now(),
      entity: "payment",
      amount: paise,
      currency: "INR",
      status: "captured",
      method: "upi"
    } }` : ''}
  }`;
  return [`// Builds and signs the event the way Razorpay does (the body must be sent byte-for-byte as signed).
const paise = Math.round(Number(pm.variables.get("amount")) * 100);
const body = JSON.stringify({
  entity: "event",
  account_id: "acc_simulated",
  event: "${event}",
  contains: ${JSON.stringify(event.startsWith('refund') ? ['refund'] : event === 'payment_link.paid'
    ? ['payment_link', 'payment'] : ['payment_link'])},
  payload: ${payload},
  created_at: Math.floor(Date.now() / 1000)
});
pm.collectionVariables.set("razorpayBody", body);
pm.collectionVariables.set("razorpayEventId", "evt_" + Date.now() + Math.floor(Math.random() * 1000));
pm.collectionVariables.set("razorpaySignature",
  CryptoJS.HmacSHA256(body, pm.variables.get("razorpayWebhookSecret")).toString(CryptoJS.enc.Hex));`];
}

const razorpayHeaders = [
  { key: 'X-Razorpay-Signature', value: '{{razorpaySignature}}' },
  { key: 'X-Razorpay-Event-Id', value: '{{razorpayEventId}}' },
];

const razorpayWebhook = (name, event, vars, o = {}) => req(name, 'POST', '{{baseUrl}}/api/payments/webhooks/razorpay', {
  auth: noauth,
  headers: razorpayHeaders,
  json: '{{razorpayBody}}',
  pre: razorpayPre(event, vars),
  tests: [status(200)],
  ...o,
});

const saveIntent = (prefix = '') => [
  status(200),
  `{
  const saved = pm.response.json();
  pm.collectionVariables.set("${prefix}paymentId", saved.id);
  pm.collectionVariables.set("${prefix}checkoutUrl", saved.checkoutUrl || "");
  // mock checkout URLs end with the session id (real Razorpay short URLs do not; webhooks then match by notes.payment_id)
  pm.collectionVariables.set("${prefix}sessionId", saved.checkoutUrl ? saved.checkoutUrl.split("/").pop() : "");
}`,
];

// ------------------------------------------------------------------------------------------ folders

const happy = folder('1. Pay for a booking (happy path)',
  'The flow every module (ambulance, appointments, ...) goes through. Run in order.\n\n'
  + 'Locally, start the service with PAYMENT_MOCK_AUTO_PAY=false, so the checkout stays PENDING until step 5 '
  + 'simulates Razorpay\'s webhook. With the default auto-pay, step 1 already returns PAID.',
  [
    req('1. Create payment (intent)', 'POST', '{{baseUrl}}/api/payments/intents', {
      description: 'What a module calls with the payer\'s token. Idempotency-Key = one payment per booking: '
        + 'retries and double taps return the same payment. Send the user to checkoutUrl.',
      headers: [idem],
      json: intentBody('{{bookingId}}', '{{amount}}', ',\n  "description": "Ambulance ride {{bookingId}}"'),
      pre: ['// a fresh booking every run\npm.collectionVariables.set("bookingId", pm.variables.replaceIn("{{$guid}}"));'],
      tests: [...saveIntent(), `const d = pm.response.json();
pm.test("PENDING with a checkout URL", () => {
  pm.expect(d.status).to.eql("PENDING");
  pm.expect(d.checkoutUrl).to.be.a("string");
});
pm.test("amount and reference echoed", () => {
  pm.expect(Number(d.amount)).to.eql(Number(pm.variables.get("amount")));
  pm.expect(d.referenceId).to.eql(pm.collectionVariables.get("bookingId"));
});
if (d.status === "PAID") console.warn("Mock auto-pay is on: restart with PAYMENT_MOCK_AUTO_PAY=false to walk through the webhook steps.");`],
    }),
    req('2. Same request again (idempotent retry)', 'POST', '{{baseUrl}}/api/payments/intents', {
      description: 'Same Idempotency-Key: same payment and the same checkout URL. No second Razorpay payment link.',
      headers: [idem],
      json: intentBody(),
      tests: [status(200), `const d = pm.response.json();
pm.test("same payment", () => pm.expect(d.id).to.eql(pm.collectionVariables.get("paymentId")));
pm.test("same checkout", () => pm.expect(d.checkoutUrl || "").to.eql(pm.collectionVariables.get("checkoutUrl")));`],
    }),
    req('3. Get payment', 'GET', '{{baseUrl}}/api/payments/{{paymentId}}', {
      description: 'Payer or ADMIN. Anyone else gets 404.',
      tests: [status(200), 'pm.test("found", () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get("paymentId")));'],
    }),
    req('4. Payments of a booking', 'GET',
      '{{baseUrl}}/api/payments?referenceType=AMBULANCE_BOOKING&referenceId={{bookingId}}', {
        description: 'For a module or the app to find the payment of a booking. Non-admins see only their own.',
        tests: [status(200), 'pm.test("one payment", () => pm.expect(pm.response.json()).to.have.lengthOf(1));'],
      }),
    razorpayWebhook('5. [Simulate Razorpay] payment_link.paid', 'payment_link.paid',
      { session: 'sessionId', payment: 'paymentId' }, {
        description: 'Stands in for Razorpay calling us (Razorpay cannot reach localhost). Signed with '
          + 'razorpayWebhookSecret, which must equal the service\'s RAZORPAY_WEBHOOK_SECRET '
          + '(local profile default: local-razorpay-webhook-secret).\n\n'
          + 'The service marks the payment PAID and queues the signed callback to the module '
          + '(AMBULANCE_BOOKING -> ambulance service). If the ambulance service is not running, the log shows '
          + 'the callback being retried.',
      }),
    req('6. Razorpay retries the same event (deduplicated)', 'POST', '{{baseUrl}}/api/payments/webhooks/razorpay', {
      auth: noauth,
      description: 'Byte-for-byte the event from step 5 (same X-Razorpay-Event-Id). Accepted with 200, applied '
        + 'only once.',
      headers: razorpayHeaders,
      json: '{{razorpayBody}}',
      tests: [status(200)],
    }),
    req('7. Get payment (now PAID)', 'GET', '{{baseUrl}}/api/payments/{{paymentId}}', {
      tests: [status(200), `const d = pm.response.json();
pm.test("PAID", () => pm.expect(d.status).to.eql("PAID"));
pm.test("no checkout any more", () => pm.expect(d.checkoutUrl).to.eql(null));
pm.test("paidAt set", () => pm.expect(d.paidAt).to.be.a("string"));`],
    }),
    req('8. Pay again (returns the PAID payment)', 'POST', '{{baseUrl}}/api/payments/intents', {
      description: 'Calling intents for a paid booking is harmless: it returns PAID, no new checkout.',
      headers: [idem],
      json: intentBody(),
      tests: [status(200), 'pm.test("PAID", () => pm.expect(pm.response.json().status).to.eql("PAID"));'],
    }),
    req('9. Refresh from Razorpay', 'POST', '{{baseUrl}}/api/payments/{{paymentId}}/refresh', {
      description: 'The app calls this when the user returns from the checkout page, instead of waiting '
        + 'for Razorpay\'s webhook. Asks Razorpay about any open checkout and returns the current state.',
      tests: [status(200)],
    }),
  ]);

const refunds = folder('2. Refunds (ADMIN)',
  'Refund the payment from folder 1. Needs adminToken (role ADMIN or SUPER_ADMIN).',
  [
    req('1. Partial refund', 'POST', '{{baseUrl}}/api/payments/{{paymentId}}/refunds', {
      description: 'reason: DUPLICATE | FRAUDULENT | REQUESTED_BY_CUSTOMER | OTHERS. With Razorpay the refund '
        + 'usually comes back PENDING and settles later via the refund.processed webhook.',
      json: { amount: 200.0, reason: 'REQUESTED_BY_CUSTOMER', note: 'Trip cancelled halfway' },
      tests: [status(201), `const d = pm.response.json();
pm.collectionVariables.set("refundId", d.id);
pm.test("refund created", () => pm.expect(["SUCCEEDED", "PENDING"]).to.include(d.status));`],
    }),
    req('2. Refund more than is left (409)', 'POST', '{{baseUrl}}/api/payments/{{paymentId}}/refunds', {
      description: 'Refunds (pending + succeeded) can never exceed what was paid.',
      json: { amount: 99999.0, reason: 'REQUESTED_BY_CUSTOMER' },
      tests: [status(409)],
    }),
    razorpayWebhook('3. [Simulate Razorpay] refund.processed', 'refund.processed', {}, {
      description: 'What Razorpay sends when a PENDING refund completes. Matched by our refund id in notes. '
        + 'Harmless if the refund already SUCCEEDED.',
    }),
    req('4. Refund the rest (no amount = everything left)', 'POST', '{{baseUrl}}/api/payments/{{paymentId}}/refunds', {
      json: { reason: 'OTHERS', note: 'Remaining amount' },
      tests: [status(201)],
    }),
    req('5. Get payment (refunds listed)', 'GET', '{{baseUrl}}/api/payments/{{paymentId}}', {
      auth: bearer('patientToken'),
      description: 'Status stays PAID. Refunds show in refundedAmount and refunds[].',
      tests: [status(200), `const d = pm.response.json();
pm.test("still PAID", () => pm.expect(d.status).to.eql("PAID"));
pm.test("two refunds", () => pm.expect(d.refunds).to.have.lengthOf(2));`],
    }),
    req('6. Patient cannot refund (403)', 'POST', '{{baseUrl}}/api/payments/{{paymentId}}/refunds', {
      auth: bearer('patientToken'),
      json: { amount: 1.0, reason: 'REQUESTED_BY_CUSTOMER' },
      tests: [status(403)],
    }),
  ], bearer('adminToken'));

const failure = folder('3. Expired checkout and retry',
  'The user leaves the checkout page unpaid until the link expires; calling intents again gives a fresh checkout '
  + 'for the same payment. (A failed attempt does not end a Razorpay link: the user retries on the same page.) '
  + 'Needs PAYMENT_MOCK_AUTO_PAY=false locally.',
  [
    req('1. Create payment', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'ambulance-booking-{{failBookingId}}' }],
      json: intentBody('{{failBookingId}}'),
      pre: ['pm.collectionVariables.set("failBookingId", pm.variables.replaceIn("{{$guid}}"));'],
      tests: [...saveIntent('fail'), 'pm.test("PENDING", () => pm.expect(pm.response.json().status).to.eql("PENDING"));'],
    }),
    razorpayWebhook('2. [Simulate Razorpay] payment_link.expired', 'payment_link.expired',
      { session: 'failsessionId', payment: 'failpaymentId' }),
    req('3. Get payment (PENDING, no checkout)', 'GET', '{{baseUrl}}/api/payments/{{failpaymentId}}', {
      description: 'Still owed, so the module is not called. The checkout is gone until the user tries again.',
      tests: [status(200), `const d = pm.response.json();
pm.test("PENDING", () => pm.expect(d.status).to.eql("PENDING"));
pm.test("no checkout", () => pm.expect(d.checkoutUrl).to.eql(null));`],
    }),
    req('4. Cannot refund an unpaid payment (409)', 'POST', '{{baseUrl}}/api/payments/{{failpaymentId}}/refunds', {
      auth: bearer('adminToken'),
      json: { reason: 'OTHERS' },
      tests: [status(409)],
    }),
    req('5. Try again (same key, fresh checkout)', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'ambulance-booking-{{failBookingId}}' }],
      json: intentBody('{{failBookingId}}'),
      tests: [status(200), `const d = pm.response.json();
pm.test("same payment", () => pm.expect(d.id).to.eql(pm.collectionVariables.get("failpaymentId")));
pm.test("PENDING again", () => pm.expect(d.status).to.eql("PENDING"));
pm.test("new checkout URL", () => pm.expect(d.checkoutUrl).to.not.eql(pm.collectionVariables.get("failcheckoutUrl")));
pm.collectionVariables.set("failsessionId", d.checkoutUrl.split("/").pop());`],
    }),
    razorpayWebhook('6. [Simulate Razorpay] payment_link.paid on the retry', 'payment_link.paid',
      { session: 'failsessionId', payment: 'failpaymentId' }),
    req('7. Get payment (PAID)', 'GET', '{{baseUrl}}/api/payments/{{failpaymentId}}', {
      tests: [status(200), 'pm.test("PAID", () => pm.expect(pm.response.json().status).to.eql("PAID"));'],
    }),
  ]);

const validation = folder('4. Validation and security',
  'Each request shows one rule. Uses the booking from folder 1 where needed.',
  [
    req('Missing Idempotency-Key (400)', 'POST', '{{baseUrl}}/api/payments/intents', {
      json: intentBody('{{$guid}}'), tests: [status(400)],
    }),
    req('Unknown referenceType (400)', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'postman-{{$guid}}' }],
      json: intentBody('{{$guid}}').replace('AMBULANCE_BOOKING', 'PIZZA_ORDER'),
      description: 'Only modules registered in payment.consumers can take payments.',
      tests: [status(400)],
    }),
    req('Paying on someone else\'s behalf (403)', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'postman-{{$guid}}' }],
      json: intentBody('{{$guid}}').replace('{{patientId}}', 'another-user'),
      description: 'payerId must be the token\'s sub (unless the caller is an admin).',
      tests: [status(403)],
    }),
    req('Amount below 1.00 (400)', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'postman-{{$guid}}' }],
      json: intentBody('{{$guid}}', '0.50'), tests: [status(400)],
    }),
    req('More than 2 decimals (400)', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'postman-{{$guid}}' }],
      json: intentBody('{{$guid}}', '10.005'), tests: [status(400)],
    }),
    req('Unsupported currency (400)', 'POST', '{{baseUrl}}/api/payments/intents', {
      headers: [{ key: 'Idempotency-Key', value: 'postman-{{$guid}}' }],
      json: intentBody('{{$guid}}').replace('"INR"', '"USD"'), tests: [status(400)],
    }),
    req('Same key, different amount (409)', 'POST', '{{baseUrl}}/api/payments/intents', {
      description: 'An Idempotency-Key is bound to its first request forever.',
      headers: [idem],
      json: intentBody('{{bookingId}}', '1.00'), tests: [status(409)],
    }),
    req('No token (401)', 'GET', '{{baseUrl}}/api/payments/{{paymentId}}', {
      auth: noauth, tests: [status(401)],
    }),
    req('Someone else\'s payment (404)', 'GET', '{{baseUrl}}/api/payments/{{paymentId}}', {
      auth: bearer('otherPatientToken'),
      description: 'Looks exactly like a payment that does not exist. Needs otherPatientToken '
        + '(a PATIENT token with a different sub).',
      tests: [status(404)],
    }),
    req('Unknown payment (404)', 'GET', '{{baseUrl}}/api/payments/00000000-0000-0000-0000-000000000000', {
      tests: [status(404)],
    }),
    req('Razorpay webhook with a bad signature (401)', 'POST', '{{baseUrl}}/api/payments/webhooks/razorpay', {
      auth: noauth,
      headers: [{ key: 'X-Razorpay-Signature', value: 'deadbeef' }, { key: 'X-Razorpay-Event-Id', value: 'evt_bad' }],
      json: { entity: 'event', event: 'payment_link.paid', payload: {} },
      tests: [status(401)],
    }),
    req('Razorpay webhook without a signature (401)', 'POST', '{{baseUrl}}/api/payments/webhooks/razorpay', {
      auth: noauth,
      json: { entity: 'event', event: 'payment_link.paid' },
      tests: [status(401)],
    }),
  ]);

const moduleSide = folder('5. Module side: test your module\'s callback endpoint',
  'Calls a MODULE (not this service) exactly the way the payment service does, so you can test a module\'s '
  + 'webhook handler on its own. Signed with moduleWebhookSecret, which must equal the module\'s '
  + 'PAYMENT_WEBHOOK_SECRET and this service\'s <MODULE>_PAYMENT_WEBHOOK_SECRET.\n\n'
  + 'Header scheme: X-Payment-Timestamp = epoch seconds, '
  + 'X-Payment-Signature = hex(HMAC-SHA256(secret, timestamp + "." + rawBody)).',
  [
    req('Callback: PAID', 'POST', '{{moduleWebhookUrl}}', {
      auth: noauth,
      description: 'For the ambulance service, bookingId must be a booking in PAYMENT_PENDING whose fare is '
        + '<= amount, or it answers 404/409. Set moduleBookingId and amount first.',
      headers: [
        { key: 'X-Payment-Timestamp', value: '{{moduleTimestamp}}' },
        { key: 'X-Payment-Signature', value: '{{moduleSignature}}' },
      ],
      json: '{{moduleBody}}',
      pre: [`const booking = pm.variables.get("moduleBookingId") || pm.variables.replaceIn("{{$guid}}");
const body = JSON.stringify({
  eventId: pm.variables.replaceIn("{{$guid}}"),
  paymentId:pm.variables.replaceIn("{{$guid}}"),
  referenceType: "AMBULANCE_BOOKING",
  referenceId: booking,
  bookingId: booking,
  payerId: pm.variables.get("patientId"),
  status: "PAID",
  amount: Number(pm.variables.get("amount")),
  currency: "INR",
  refundedAmount: 0,
  failureReason: null,
  occurredAt: new Date().toISOString()
});
const ts = String(Math.floor(Date.now() / 1000));
pm.collectionVariables.set("moduleBody", body);
pm.collectionVariables.set("moduleTimestamp", ts);
pm.collectionVariables.set("moduleSignature",
  CryptoJS.HmacSHA256(ts + "." + body, pm.variables.get("moduleWebhookSecret")).toString(CryptoJS.enc.Hex));`],
      tests: ['pm.test("signature accepted (not 401)", () => pm.expect(pm.response.code).to.not.eql(401));'],
    }),
  ]);

const ops = folder('6. Ops', 'Kubernetes probes, metrics and API docs. No token needed.', [
  req('Liveness', 'GET', '{{baseUrl}}/actuator/health/liveness', { auth: noauth, tests: [status(200)] }),
  req('Readiness (includes DB)', 'GET', '{{baseUrl}}/actuator/health/readiness', { auth: noauth, tests: [status(200)] }),
  req('Prometheus metrics', 'GET', '{{baseUrl}}/actuator/prometheus', {
    auth: noauth,
    description: 'Alert on payments_anomalies_total and payments_callbacks_total{outcome="dead"}. '
      + 'Not exposed through the gateway.',
    tests: [status(200)],
  }),
  req('OpenAPI spec', 'GET', '{{baseUrl}}/v3/api-docs', {
    auth: noauth, description: 'Swagger UI: {{baseUrl}}/swagger-ui.html', tests: [status(200)],
  }),
]);

const razorpayAuth = { type: 'basic', basic: [
  { key: 'username', value: '{{razorpayKeyId}}', type: 'string' },
  { key: 'password', value: '{{razorpayKeySecret}}', type: 'string' },
] };
const razorpay = folder('7. Razorpay direct (setup and debugging)',
  'Talks to Razorpay itself, not to this service, to check that the API keys work. Basic auth with '
  + 'razorpayKeyId / razorpayKeySecret (use test-mode keys, rzp_test_...). Skip this folder in the Collection '
  + 'Runner unless the razorpay* variables are set.',
  [
    req('1. Create a ₹1 payment link', 'POST', '{{razorpayApiUrl}}/payment_links', {
      description: 'What the service does for every checkout. The test prints the hosted checkout URL; open it '
        + 'to pay with a Razorpay test card or UPI id.',
      json: {
        amount: 100, currency: 'INR', description: 'Postman credential check',
        notes: { payment_id: 'postman-check' }, notify: { sms: false, email: false }, reminder_enable: false,
        callback_url: 'https://example.com/payments/result', callback_method: 'get',
      },
      tests: [status(200), `const l = pm.response.json();
pm.test("link created", () => pm.expect(l.id).to.be.a("string"));
pm.environment.set("razorpayLinkId", l.id);
console.log("Hosted checkout:", l.short_url);`],
    }),
    req('2. Get a payment link', 'GET', '{{razorpayApiUrl}}/payment_links/{{razorpayLinkId}}', {
      description: 'What reconciliation asks for checkouts whose webhook never arrived.',
      tests: [status(200)],
    }),
  ], razorpayAuth);

// ------------------------------------------------------------------------------------------ collection

const collection = {
  info: {
    name: 'Payment Service',
    schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    description: `One payment service for every module (ambulance, doctor appointments, lab/scan, home care), backed by Razorpay. See README.md.

SETUP
1. Import this collection and one environment: "local" (service on :8083) or "via gateway" (:8080).
2. Tokens: this service only verifies JWTs. Locally (profile "local") mint them with this repo's dev key:
     node scripts/dev-jwt.mjs patient-42 PATIENT   -> patientToken   (patientId must be the same: patient-42)
     node scripts/dev-jwt.mjs patient-99 PATIENT   -> otherPatientToken
     node scripts/dev-jwt.mjs admin-1 ADMIN        -> adminToken
   Otherwise log in through the auth service. Dev tokens last 12 h.
3. Start the service so checkouts wait for the (simulated) Razorpay webhook:
     PAYMENT_MOCK_AUTO_PAY=false ./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"

RUN
Folders 1-4 run top to bottom in the Collection Runner (deselect 5 and 7 unless their variables are set). Requests marked [Simulate Razorpay] sign events with razorpayWebhookSecret exactly as Razorpay does, because Razorpay cannot reach localhost. Against real Razorpay (test mode), pay on the checkoutUrl with a test card and use "Refresh from Razorpay" instead.

AUTH
Collection default: Bearer {{patientToken}}. The refunds folder uses {{adminToken}}. Webhooks and ops use no token; folder 7 uses Basic auth with the Razorpay API key.`,
  },
  auth: bearer('patientToken'),
  variable: ['bookingId', 'paymentId', 'checkoutUrl', 'sessionId', 'refundId', 'failBookingId', 'failpaymentId',
    'failcheckoutUrl', 'failsessionId', 'razorpayBody', 'razorpaySignature', 'razorpayEventId', 'moduleBody',
    'moduleTimestamp', 'moduleSignature'].map((key) => ({ key, value: '' })),
  item: [happy, refunds, failure, validation, moduleSide, ops, razorpay],
};

// ------------------------------------------------------------------------------------------ environments

const v = (key, value, secret = false) => ({ key, value, enabled: true, type: secret ? 'secret' : 'default' });
const common = [
  v('patientToken', '', true), v('otherPatientToken', '', true), v('adminToken', '', true),
  v('patientId', 'patient-42'), v('amount', '640.00'),
  v('razorpayWebhookSecret', 'local-razorpay-webhook-secret', true),
  v('moduleWebhookUrl', 'http://localhost:8085/api/ambulance/payments/webhook'),
  v('moduleWebhookSecret', 'local-ambulance-secret', true), v('moduleBookingId', ''),
  v('razorpayApiUrl', 'https://api.razorpay.com/v1'),
  v('razorpayKeyId', ''), v('razorpayKeySecret', '', true), v('razorpayLinkId', ''),
];
const env = (name, baseUrl) => ({ name, values: [v('baseUrl', baseUrl), ...common], _postman_variable_scope: 'environment' });

const write = (file, data) => writeFileSync(join(out, file), JSON.stringify(data, null, 2) + '\n');
write('payment-service.postman_collection.json', collection);
write('payment-service.local.postman_environment.json', env('Payment Service - local', 'http://localhost:8083'));
write('payment-service.gateway.postman_environment.json', env('Payment Service - via gateway', 'http://localhost:8080'));
console.log('written to', out);
