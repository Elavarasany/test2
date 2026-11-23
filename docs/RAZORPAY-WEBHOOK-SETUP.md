# Razorpay webhook setup

## Endpoint

Configure the Razorpay webhook URL as:

`https://YOUR_BACKEND_HOST/api/payment/webhook`

Use HTTPS. Do not expose the webhook secret in frontend code or commit it to Git.

## Events

Enable at least:

- `payment.captured`

Other signed event types are stored for audit but do not currently change order/payment state.

## Secret

Set the backend environment variable `RAZORPAY_WEBHOOK_SECRET` to the exact webhook secret configured in the Razorpay dashboard. It is separate from `RAZORPAY_KEY_SECRET`.

The endpoint verifies the HMAC SHA-256 signature against the exact raw request body before storing anything. It also requires the `X-Razorpay-Event-Id` header. Duplicate event IDs are acknowledged without creating a second inbox row.

## Database prerequisite

Before deploying this code, apply the SQL in:

`src/main/resources/db/manual/razorpay-webhook-retry-columns.sql`

to a verified staging database first, inspect the resulting schema, and run the application with Hibernate `ddl-auto=validate`. Do not run the script against production without a separate approval and backup/restore gate. Flyway remains disabled.

## Processing behaviour

- Webhook receipt only stores the verified event and returns success after the database commit.
- A scheduled worker processes up to 25 due events every 15 seconds.
- Only `payment.captured` can confirm an order.
- The worker checks payment ID, Razorpay order ID and amount against the local order, then fetches the payment from Razorpay before confirmation.
- Transient provider/API failures retry with exponential backoff, up to eight attempts.
- Missing local orders, amount mismatches, late captures after reservation release, and second captured payment IDs are marked `MANUAL_REVIEW`; they are not silently marked paid.
- Other signed event types are recorded and marked `PROCESSED` without changing order state.

## Operational checks

Inspect only event metadata/status when monitoring. Restrict access to the raw payload column because provider payloads may contain customer/payment metadata. Review `MANUAL_REVIEW` rows and reconcile/refund any additional or late captured payment through an approved process.

This implementation does not yet provide an automated refund workflow or a durable SMS outbox. Those remain separate release requirements.
