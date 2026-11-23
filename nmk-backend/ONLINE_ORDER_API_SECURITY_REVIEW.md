# Online Order API Security and Input Review

Branch reviewed: `feature/pos-operations-v1`

## Scope
Reviewed the public online checkout endpoint, order request DTOs, order creation/reservation service, public tracking endpoint, admin order controller, order entity and repository.

## Controls observed
- Public checkout calculates prices from locked product/variant records rather than trusting client-submitted prices.
- Checkout verifies the selected variant belongs to the supplied product and rejects inactive products/variants.
- Stock reservation and order creation run transactionally; reservation uses the inventory ledger.
- Checkout requires a configured active inventory location and locks that location before inventory operations.
- Payment creation state is persisted before the external Razorpay call. Once provider creation may have begun, the controller deliberately retains the reservation for reconciliation instead of releasing stock on an ambiguous result.
- Pending-order cancellation checks payment-creation state and refuses cancellation while payment creation is in progress or a provider order has already been created.
- Public tracking uses a randomly generated UUID-derived 32-hex-character token, with a unique database column. The tracking response omits street address, phone and email.

## Confirmed gaps
1. **Public checkout request validation is incomplete.** `OrderController.createCheckout` binds `CreateOrderRequest` without `@Valid`; the request DTO and nested `OrderItemRequest` have no Bean Validation constraints. The service validates positive product/variant IDs and quantities, but the inspected path does not enforce a maximum number of cart lines, a maximum aggregate quantity, or field-length/format limits for customer name, email, phone, address, city, state and pincode.
2. **Prescription upload metadata needs tighter bounds.** Prescription URL length is capped at 2,048 characters and the prescription numeric/text fields are shortened, but the supplied image filename is copied into the serialized prescription without a length bound. The prescription JSON as a whole has no explicit serialized-size limit in this method. This can lead to oversized requests or database-column failures rather than a clean validation response.
3. **Public tracking is a bearer-link capability.** Anyone possessing the tracking token can view order status, total, order date, customer name, city, purchased item names/quantities, courier and tracking number. The endpoint does not expose address, email or phone in the reviewed DTO. Tokens are high entropy, but links can still leak through messaging, browser history, screenshots or referrer/logging practices. Treat tracking links as private credentials and avoid logging them.
4. **Admin order/customer reads are unpaginated.** `GET /api/admin/orders` and `GET /api/admin/orders/customers` load the complete order history (including order items for the first endpoint) into memory. This is acceptable only at small volume; it will create growing response sizes and memory/query costs. The reviewed security configuration leaves these endpoints behind the `/api/admin/**` administrator-only fallback.
5. **No application-level throttling was identified in the reviewed checkout/tracking controller path.** Public checkout can create pending orders and stock reservations, so production should apply rate limits and abuse monitoring at the application gateway or API layer. Confirm the deployed proxy/WAF configuration before launch.

## Recommended implementation work
- Add nested Bean Validation constraints and `@Valid` to the public checkout request; return consistent 400 responses for malformed input.
- Establish explicit, client-approved limits for cart line count, quantity, and customer/contact/address fields. Do not invent limits without checking frontend behavior and the business requirements.
- Bound prescription image filename and serialized prescription payload size; reject oversized values before persistence.
- Paginate admin order and customer endpoints, with bounded page sizes and stable ordering.
- Configure rate limiting/abuse monitoring for public checkout and tracking. Avoid logging tracking tokens or prescription contents.
- Consider whether the public tracking response needs customer name and exact purchased item names; minimize fields to the operational need. Keep the current token private even though its entropy is strong.

## Disposition
This review documents verified code-level gaps; it does not claim that they have been exploited. No database, payment-provider or production configuration was changed. The current one-shop setup does not require branch-level staff permissions, but public input hardening and operational protections remain production work.
