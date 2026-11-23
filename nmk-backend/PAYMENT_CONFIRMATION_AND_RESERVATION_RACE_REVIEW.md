# Payment Confirmation, Webhook and Reservation-Expiry Review

Branch reviewed: `feature/pos-operations-v1`

## Verified protections
- Checkout verifies payment credentials before creating a reservation.
- The application persists `payment_creation_status=IN_PROGRESS` before calling Razorpay. An ambiguous provider result deliberately retains stock rather than releasing it.
- Checkout verification validates the HMAC signature, then fetches the payment from Razorpay and checks captured status, provider order ID, INR currency and exact amount before confirming locally.
- The webhook endpoint verifies the HMAC against the raw request bytes before storing the event. It rejects missing headers/body and payloads larger than 1 MiB.
- Webhook event IDs are unique; duplicate deliveries are acknowledged as already durable.
- Webhook processing uses a short claim transaction, an expiring worker lease, bounded retries, claim-token ownership checks and separate finalization. Provider API calls are not made while holding the webhook-event row lock.
- Only `payment.captured` can confirm an order. The processor fetches the payment from Razorpay and checks provider status/order/amount/currency before calling the same locked, idempotent payment-confirmation service.
- Payment confirmation locks the order, rejects a second distinct payment ID for an already-paid order, consumes variant reservation and marks the order paid in one transaction.
- Refund webhooks are intentionally routed to manual review; they are not falsely represented as reconciled.
- Reservation expiry fails closed when payment creation is `IN_PROGRESS`, when provider lookup fails, or when Razorpay reports any unsettled/successful attempt.

## Confirmed race condition: provider lookup versus local reservation release
The expiry worker currently performs these operations:
1. Reads an expired order snapshot.
2. Calls Razorpay outside a database transaction to check whether any payment attempt needs retention.
3. If the provider reports no unsettled/successful attempt, starts a local transaction, locks the order, rechecks local eligibility, and releases the reservation.

The payment verification/webhook path independently:
1. Fetches and verifies a captured payment with Razorpay.
2. Locks the same local order in `PaymentVerificationService`.
3. Consumes the reservation and marks the order paid.

These two paths are locally serialized only after their external provider lookups. A payment can become captured after the expiry worker's provider lookup reports no unsettled payment but before the expiry worker acquires the order lock and releases stock. If the expiry worker wins the local lock, the later payment confirmation sees `stockReserved=false` and goes to conflict/manual reconciliation even though the customer may have paid.

The final local recheck prevents stale local state from being released, but it cannot make the earlier external payment-state observation atomic with the later stock release. This is a real timing window, not proof that it has occurred.

## Additional operational gap
Orders whose payment creation remains `IN_PROGRESS` are deliberately excluded from automatic release. This protects against releasing stock after an ambiguous Razorpay create-order response, but such orders can retain reservations indefinitely unless a separate reconciliation process resolves them. Do not automatically release these orders based only on age.

## Required remediation design before changing code
- Establish the provider-supported way to prevent or close further payment attempts for an expired Razorpay order, if available for the account/API version in use.
- Define a durable local expiry/reconciliation state machine so an order is not considered safely releasable until provider-side payment attempts are definitively terminal and late captures are handled.
- Ensure webhook and checkout verification can reconcile a late captured payment even if a reservation was already released; this must not silently mark an order paid without stock or an approved fulfilment/refund path.
- Add deterministic integration tests for the race: provider lookup returns no payment, then a capture is confirmed before/during the release transaction; assert that the system never silently loses the paid order.
- Add an operational queue/report for stale `IN_PROGRESS` payment-creation orders and reservation holds, with explicit resolution actions and audit records.

Do not solve this by simply holding a database row lock during the Razorpay network request: that lengthens lock duration and still cannot prevent a payment from being captured immediately after the provider response. Do not invent a timeout/grace period as a guarantee; it is only a mitigation unless provider-side expiry/closure semantics are verified.

## Disposition
This review found no basis to weaken the current fail-closed behavior. The race and indefinite-hold case are documented for remediation. No payment, database, or production settings were changed.
