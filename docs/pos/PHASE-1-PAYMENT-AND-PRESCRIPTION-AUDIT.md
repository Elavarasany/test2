# NMK Opticals — Phase 1 Payment and Prescription Storage Audit

**Status:** Source-code audit of the GitHub `master` snapshot.  
**Repository:** `Elavarasany/nmk-backend`  
**Scope:** Razorpay checkout/verification/reservation lifecycle and prescription image persistence/access.  
**Companion documents:** `PHASE-1-DATABASE-DESIGN.md`, `PHASE-1-CANONICAL-SCHEMA.md`, `PHASE-1-BACKEND-COMPATIBILITY-AUDIT.md`

> This document records what the inspected source proves. A method existing is not proof that it is wired into a controller or production configuration. A file path not found among the targeted candidates is not proof that no equivalent implementation exists anywhere in the repository or deployment.

## 1. Executive summary

The online payment flow has meaningful safeguards already:

- Checkout reserves stock before creating the Razorpay order.
- Razorpay checkout signature is verified server-side.
- The backend fetches the payment from Razorpay and checks captured status, Razorpay order ID and expected amount.
- Payment confirmation locks the local order and is idempotent for already-paid orders.
- Reservation expiry checks Razorpay order/payment attempts before releasing stock and retains stock when the provider state is uncertain.
- Webhook-signature verification logic exists as a method in `RazorpayService`.

However, several important points remain unresolved:

1. No Razorpay webhook controller/endpoint was identified in the targeted controller paths inspected. The service has a webhook HMAC method, but the inspected code does not establish that it is called by a live endpoint.
2. No webhook event persistence/deduplication record was identified in the inspected entities/services.
3. The payment verification endpoint is public by design and relies on Razorpay signature + provider fetch + order/amount checks. It needs rate limiting/abuse controls and strong integration tests.
4. The reservation scheduler performs a provider check before locking the order, then rechecks local order state under lock. A narrow provider/local-state race must be tested and closed before moving to the new inventory ledger.
5. Refund handling is explicitly not implemented in the inspected admin order controller.
6. The prescription JSON builder accepts an HTTPS image URL, but the inspected code does not prove private storage, ownership, upload validation, authorized delivery or secure deletion.
7. The actual prescription upload endpoint/storage implementation was not located in the targeted candidate paths. The upload source and deployment storage configuration remain an open audit item; do not infer that no uploader exists solely from these missing paths.

**Conclusion:** Preserve the existing server-side payment verification and fail-closed reservation principles. Before POS/payment schema implementation, complete the webhook and file-storage discovery, then define durable payment events, refund states and private prescription-file metadata.

## 2. Files inspected

The following files were fetched from GitHub `master` and reviewed:

- `service/RazorpayService.java`
- `controller/OrderController.java`
- `controller/RazorpayController.java`
- `service/PaymentVerificationService.java`
- `service/StockReservationScheduler.java`
- `service/OrderService.java`
- `repository/OrderRepository.java`
- `entity/Order.java`
- `entity/OrderItem.java`
- `dto/VerifyPaymentRequest.java`
- `controller/AdminOrderController.java`
- `config/SecurityConfig.java`
- `resources/application.properties`
- `pom.xml`

Targeted candidate paths for a webhook controller and prescription upload/storage classes were also checked (including likely Razorpay/Webhook, PrescriptionUpload, ImageUpload, FileStorage and Cloudinary controller/service names). Those candidate paths returned not found. This is a bounded path check, not a full repository tree enumeration.

## 3. Razorpay checkout and verification

### 3.1 Payment-order creation

**Observed in `OrderController.createCheckout`:**

1. Calls `OrderService.createPendingOrder`, which persists a pending order and reserves product stock.
2. Converts the stored order total to paise using `HALF_UP` and `intValueExact()`.
3. Calls `RazorpayService.createOrder`.
4. Stores the returned Razorpay order ID on the local order.
5. Returns local order ID, Razorpay order ID, amount, currency and public key ID to the frontend.
6. On an exception, attempts to release the stock reservation.

**Observed in `RazorpayService.createOrder`:**

- Creates a new Razorpay client using configured key ID/secret.
- Sends amount in paise and currency `INR`.
- Uses a timestamp-derived receipt `nmk_<millis>`.

**Assessment: REUSE WITH EXTENSIONS.**

The backend—not the browser—calculates the amount from the persisted order. This is the correct trust boundary. For the new design, payment creation should also store a durable local payment-attempt record before/around the provider call, with idempotency/correlation keys and a clear retry policy.

**Risks / required checks:**

- Timestamp-derived receipt is not a robust idempotency key.
- Verify Razorpay's amount maximum and current SDK numeric type/limits against the expected order values.
- Define what happens when Razorpay creates an order but the local database update fails.
- Ensure frontend cannot create a second provider order for the same active checkout without a controlled retry.
- Avoid logging secrets or full provider payloads.
- Keep amount/currency immutable for a created payment attempt.

### 3.2 Checkout signature verification

**Observed in `RazorpayService.verifySignature`:**

- Builds payload as `razorpayOrderId|razorpayPaymentId`.
- Computes HMAC-SHA256 using the Razorpay API key secret.
- Parses a 64-character hexadecimal signature.
- Uses `MessageDigest.isEqual` for comparison.

**Assessment: REUSE.**

The constant-time comparison and strict hex-length validation are positive. Keep this server-side verification.

**Required tests:**

- Valid signature.
- Wrong order ID.
- Wrong payment ID.
- Wrong secret.
- Null, malformed, short, long and non-hex signature.
- Replay of a valid request after order already paid.
- Signature from another merchant/account environment.

### 3.3 Provider-side captured payment verification

**Observed in `RazorpayService.isPaymentCapturedForOrder`:**

- Fetches the payment by payment ID from Razorpay.
- Checks provider status equals `captured`.
- Checks provider order ID equals the expected Razorpay order ID.
- Checks provider amount equals expected paise amount.

**Observed in `RazorpayController.verifyPayment`:**

- Verifies the checkout signature first.
- Finds the local order by Razorpay order ID.
- Computes expected paise from the persisted local total.
- Calls the provider fetch check before confirming the payment locally.

**Assessment: REUSE, with validation hardening.**

This is stronger than trusting only the frontend callback. Keep the provider-side check as a required payment-confirmation condition.

**Additional validation to implement/test:**

- Confirm currency is INR in the fetched payment, not only the local checkout response.
- Verify provider payment belongs to the expected merchant account/environment.
- Confirm payment ID is not already linked to a different local order/payment attempt.
- Validate local order is in an allowed payment state and reservation policy permits confirmation.
- Treat network timeouts/provider errors as unknown, never as payment failure.
- Do not trust frontend-supplied amount or customer/order metadata.

## 4. Payment confirmation and idempotency

**Observed in `PaymentVerificationService.confirmPayment`:**

- Loads the order by Razorpay order ID using a pessimistic write lock.
- If payment status is already PAID, returns `newlyConfirmed=false`.
- Rejects confirmation if `stockReserved` is false.
- Saves Razorpay payment ID and PAID status.
- Moves PENDING order to CONFIRMED.
- Ensures a tracking token exists.
- Sets `stockReserved=false` because stock was already deducted during checkout; it does not deduct again.

**Assessment: REUSE THE SAFETY MODEL; EXTEND THE PERSISTENCE MODEL.**

The row lock and already-paid guard are important. The inventory migration must preserve the invariant that online payment confirmation converts an existing reservation into a completed sale exactly once, without a second stock deduction.

**Gaps / risks:**

- Idempotency is represented primarily by order payment status and one payment ID field; there is no separately inspected payment-attempt/event table.
- A second, different captured payment ID for an already-paid order is currently returned as already paid without an explicit duplicate-capture/refund exception workflow.
- The service requires the stock reservation still to be active. If payment is captured after a reservation has been safely released, the endpoint returns a conflict/manual-review condition; a durable exception queue and refund/recovery process are needed.
- A provider event arriving before the frontend verification call must be handled consistently if webhook support is enabled later.

**Required design:**

- Add durable payment-attempt and provider-event records.
- Store provider payment ID, provider order ID, amount, currency, state, created/updated timestamps and idempotency references.
- Enforce uniqueness for provider event IDs and appropriate provider payment references.
- Model duplicate/late capture as an explicit exception requiring reconciliation, not as a silent no-op.
- Record payment state transitions append-only or with an immutable event history.
- Keep local sale/order confirmation transactional with reservation conversion.

## 5. Reservation expiry and race conditions

**Observed in `StockReservationScheduler`:**

- Runs every 60 seconds.
- Fetches expired local reservation IDs.
- Reads the Razorpay order ID without a database lock.
- Calls `hasUnsettledOrSuccessfulPayment` before trying to release stock.
- If Razorpay reports paid, unsettled, successful or uncertain state, retains the reservation.
- If provider check is clear, starts a transaction, locks the local order and rechecks reservation flag, payment status and expiry.
- Calls `OrderService.releaseReservation` only after those checks.
- Exceptions fail closed: reservation is not released.

**Observed in `RazorpayService.hasUnsettledOrSuccessfulPayment`:**

- Fetches the Razorpay order.
- Retains reservation when order status is paid.
- Fetches all payment attempts for the provider order.
- Treats any payment status other than failed or refunded as a reason to retain.
- Treats attempts with no returned payment records as uncertain and retains.
- Provider/API exceptions bubble to the scheduler catch, which retains stock.

**Assessment: STRONG FAIL-CLOSED BASELINE — PRESERVE IT.**

**Race requiring explicit test:**

The provider state is fetched before the local order row is locked. The code then rechecks local order state under lock, which prevents duplicate local release, but does not repeat the external provider check under that lock (and should not hold a database lock across network I/O). Test the boundary where a new payment attempt or capture changes provider state between the provider check and local release transaction.

**Required approach:**

- Keep provider calls outside database locks.
- Use durable payment-attempt/event state and a carefully designed reconciliation protocol to close the provider/local race.
- Consider a short additional safety grace period after expiry, but choose it from observed payment behaviour and business requirements rather than guessing.
- Preserve the rule: unknown provider state means retain inventory and raise an operational exception.
- Add metrics/alerts for reservations retained beyond expiry and manual-review backlog.
- Ensure all release paths are idempotent and ledger-backed after inventory cutover.

## 6. Webhook audit

### 6.1 What was found

**Observed in `RazorpayService.verifyWebhookSignature`:**

- Accepts raw request body and signature.
- Computes HMAC-SHA256 using `razorpay.webhook.secret`.
- Parses the signature through the same strict hex helper.
- Uses constant-time `MessageDigest.isEqual`.

The webhook secret is configured through `RAZORPAY_WEBHOOK_SECRET` in `application.properties`.

### 6.2 What was not established

No webhook controller/endpoint was identified in the targeted controller candidates reviewed for this audit. The existence of `verifyWebhookSignature` alone does not prove that webhook events are received, verified, persisted or processed.

No durable webhook event entity/repository was identified among the inspected payment files. A full repository tree search and deployment/dashboard check remain necessary before concluding whether a webhook is configured elsewhere.

### 6.3 Required webhook design before relying on webhooks

- Receive the exact raw request bytes/body needed for signature verification; do not parse and reserialize JSON before HMAC verification.
- Verify signature before trusting event content.
- Persist provider event ID and payload digest with a unique constraint before acknowledging successful processing.
- Deduplicate repeated deliveries.
- Handle out-of-order events using provider state fetch/reconciliation rather than assuming arrival order.
- Process events asynchronously or with bounded work after durable receipt.
- Never let a webhook alone blindly mutate inventory without checking the linked local order/payment attempt and idempotency state.
- Record processing outcome, retries, failure reason and timestamps.
- Protect endpoint availability with appropriate request-size limits, rate controls and operational monitoring.
- Keep webhook secrets separate from API key secrets and rotate them safely.

**Decision:** Webhook controller and event processing = **AUDIT REQUIRED / CREATE OR REUSE AFTER DISCOVERY**. Do not claim webhook support is production-ready until a live endpoint and end-to-end tests are demonstrated.

## 7. Refunds and payment reconciliation

**Observed in `AdminOrderController.updateOrderStatus`:**

- Paid confirmed orders cannot be cancelled through the normal status endpoint.
- The response explicitly says paid-order refund handling is not implemented.

No refund service/controller/record was identified in the inspected payment paths.

**Decision: CREATE.**

The canonical POS design needs payment refunds, but online order refunds must remain linked to the original Razorpay payment and local order. Do not mark a refund complete merely because a request was sent.

Required model/workflow:

1. Refund request and approval.
2. Provider refund request with idempotency/reference.
3. Provider refund status tracking.
4. Confirmed/failed/pending/unknown outcomes.
5. Partial refund allocation to original order items and amounts where supported.
6. Audit actor, reason, approver and timestamps.
7. Inventory disposition separately from refund status.
8. Reconciliation for provider refunds that succeed while local persistence fails.

UPI/card settlement into the bank is a separate reconciliation concern from successful customer payment capture.

## 8. Prescription data and image references

### 8.1 Current data representation

**Observed in `OrderItem`:**

- Prescription type, lens type and photochromic flag are stored as fields.
- `prescription_json` is a string column with length 4000.

**Observed in `OrderService.toPrescriptionJson`:**

- Manual eyeglasses prescription stores mode, flags and right/left eye values.
- Contact lens prescription stores right/left values including sph/cyl/axis/boxes.
- Image mode stores `imageUrl` and a supplied filename.
- The URL must begin with `https://`; values over the allowed URL length are rejected.
- Prescription JSON is attached to the order item.

**Observed in `AdminOrderController`:**

- Parses the stored JSON into a map for admin order response DTOs.
- If parsing fails, it logs an error and returns null prescription data.

### 8.2 Security conclusion

An HTTPS URL is not evidence of private storage. The inspected code does not establish:

- Which service actually accepts and uploads the file.
- Whether upload is authenticated or publicly callable.
- Whether file type is detected from content rather than trusting extension/MIME.
- Maximum file size, image dimension or PDF page limits.
- Malware/content scanning.
- Randomized object keys and overwrite protection.
- Private bucket/container configuration.
- Whether access is served through a backend authorization check or long-lived public URL.
- Whether a signed URL is short-lived and scoped to a single authorized request.
- Whether prescription downloads are audited.
- Deletion/retention policy or object-store backup.
- Whether file metadata is tied to the correct customer/order item.

Targeted likely upload/storage class paths were not found. The real upload implementation must be located before a final storage migration design is approved.

### 8.3 Required target model

- Dedicated prescription record/version with customer and relevant order-item relationship.
- Structured prescription values stored separately from image/PDF file metadata.
- Private object storage; MySQL stores object key, file name, media type, size, checksum, upload timestamp and status—not a public permanent URL.
- Backend authorization before creating short-lived download access.
- Explicit access audit for view/download/update/delete.
- Role-based minimum access; cashier does not receive unrestricted prescription-image access.
- Validate actual file content, type, size and dimensions/pages; reject unsafe or unexpected formats.
- Do not include prescription values or file contents in logs.
- Retention, correction, deletion and backup/restore policy approved before rollout.
- Preserve existing prescription JSON as read-only legacy data until migration and retrieval are verified.

**Decision:** Existing structured prescription JSON = **REUSE AS LEGACY SOURCE / EXTEND TEMPORARILY**. Canonical prescription version and private file metadata = **CREATE**. Actual file-storage implementation = **AUDIT REQUIRED**.

## 9. Configuration and secret handling

**Observed in `application.properties`:**

- Razorpay key ID and secret are environment placeholders.
- Webhook secret is an environment placeholder.
- JPA schema auto-update remains enabled in the inspected configuration.
- SMS is disabled in the current checked-in properties.

**Required controls:**

- Ensure production secrets are configured only in deployment secret storage/environment, never committed.
- Ensure test/live Razorpay credentials and webhook secrets are not mixed.
- Rotate secrets if exposed; do not print them in logs or diagnostic endpoints.
- Confirm webhook secret configured in Razorpay dashboard exactly matches deployment secret.
- Use separate test and production webhook endpoints/secrets.
- Remove or mask stack traces/provider payloads from public API responses and production logs.
- Review `spring.jpa.hibernate.ddl-auto=update` before schema migration; move to reviewed versioned migrations.
- Confirm production CORS and HTTPS configuration independently.

## 10. Compatibility decisions for canonical POS design

| Existing area | Decision | Reason |
|---|---|---|
| Razorpay order creation | REUSE / EXTEND | Server computes amount and creates provider order; add durable attempt/idempotency records |
| Checkout HMAC verification | REUSE | Existing HMAC-SHA256 + constant-time comparison |
| Provider captured-payment fetch | REUSE / EXTEND | Checks captured state, provider order ID and amount; add currency and duplicate-payment validation |
| Local payment confirmation | REUSE / EXTEND | Pessimistic lock and already-paid idempotency; add durable payment events/exceptions |
| Reservation expiry scheduler | REUSE safety rules / REPLACE stock adapter | Fail-closed provider checks are valuable; stock release must use canonical reservation ledger |
| Webhook HMAC method | REUSE only if endpoint is wired and tested | Helper exists; endpoint/event persistence not established |
| Webhook event storage/processing | AUDIT REQUIRED / CREATE | No durable event implementation identified in inspected payment files |
| Online refunds | CREATE | Paid cancellation explicitly reports refund handling is not implemented |
| Prescription JSON | REUSE as legacy / migrate | Existing values must remain readable during staged migration |
| Prescription upload/storage | AUDIT REQUIRED | Actual uploader/provider/privacy/access path not established |
| Prescription version and file metadata | CREATE | Needed for private, auditable and reusable prescription records |

## 11. Tests required before migration/cutover

### Payment tests

- [ ] Valid signature with captured payment and correct provider order/amount/currency.
- [ ] Invalid signature never confirms payment.
- [ ] Provider fetch timeout leaves order/reservation in a safe pending/manual-review state.
- [ ] Payment ID linked to a different order is rejected.
- [ ] Captured payment after local reservation release enters explicit exception handling.
- [ ] Repeated verification requests produce one confirmation and one downstream notification.
- [ ] Concurrent verification requests do not duplicate confirmation or stock conversion.
- [ ] Scheduler and payment confirmation race does not release paid inventory.
- [ ] Multiple payment attempts for one Razorpay order are reconciled correctly.
- [ ] Duplicate and out-of-order webhook events are harmless once webhook support is implemented.
- [ ] Provider refund success/failure/unknown states reconcile correctly.

### Prescription tests

- [ ] Upload requires intended authorization and validates ownership.
- [ ] File type is detected/validated from content.
- [ ] Oversized, malformed and unsupported files are rejected.
- [ ] Stored object is private and cannot be fetched anonymously.
- [ ] Expired/unauthorized download access is rejected.
- [ ] Access and download events are audited without logging prescription content.
- [ ] Replacing a prescription creates a new version and preserves historical order context.
- [ ] Database/object backup restore produces readable authorized records.
- [ ] Legacy JSON and image references remain accessible only through approved migration/compatibility paths.

## 12. Remaining discovery tasks

Before final database DDL or payment implementation:

1. Enumerate the full backend repository tree and locate any upload controller/storage adapter not found by targeted candidate paths.
2. Inspect frontend upload flow to identify the actual endpoint, payload, storage provider and returned URL format.
3. Verify the Razorpay dashboard webhook configuration and whether a live endpoint is registered.
4. Search all backend routes for payment/refund/webhook handlers and all persistence entities for payment event records.
5. Inspect deployed logs/configuration only through authorized operational access; never request or place secret values in this document.
6. Obtain sanitized examples of existing prescription JSON and image URL formats, without exposing patient data.
7. Verify actual object storage privacy and backup configuration.

## 13. Final conclusion

The existing Razorpay checkout verification and fail-closed stock reservation logic are worth preserving. The POS/inventory migration must replace only the stock adapter and add durable payment/exception records without weakening those controls.

Webhook support is **not proven** by the presence of a signature helper. Refund processing is **not implemented** in the inspected order controller. Prescription image privacy and actual storage are **not established** by accepting HTTPS URLs.

**No Java source, SQL migration, database record, Razorpay dashboard setting, payment transaction, prescription file or deployment secret was changed by this audit document.** This is documentation only. Any later implementation requires its own review and explicit approval under the project's step-by-step GitHub workflow.
