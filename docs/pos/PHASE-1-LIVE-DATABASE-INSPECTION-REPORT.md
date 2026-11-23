# Phase 1 — Live Database Inspection Report

**Project:** NMK Opticals  
**Environment inspected:** Aiven MySQL, database `defaultdb`  
**Inspection date:** 2026-11-16  
**Repository:** `Elavarasany/nmk-backend`  
**Branch:** `master`  
**Scope:** Read-only inspection and source review. No live database schema or records were modified.

---

## 1. Executive summary

The live database contains ten application tables and the initial POS inspection queries completed using a dedicated Aiven service user, `nmk_inspector`, granted `SELECT` and `SHOW VIEW`.

The current schema supports the existing online optical-store workflow: products, online orders, order items, prescription data, appointments, marketing content, admin authorization, and Razorpay webhook event storage.

**No POS migration has been applied.** The current product stock field is shared with the online checkout reservation flow. It must not be treated as a verified physical on-hand count until the shop's physical stock is counted and reconciled.

## 2. Read-only inspection access

A dedicated Aiven service user was created:

- Username: `nmk_inspector`
- Authentication: MySQL Native Password
- Granted privileges: `SELECT`, `SHOW VIEW`
- Observed grant: `GRANT SELECT, SHOW VIEW ON *.*`
- Additional restrictions shown for `metrics_user_telegraf`, `mysql`, and `sys`.

The connection was successfully opened in MySQL Workbench using the Aiven hostname and port with the Aiven CA/SSL configuration.

No passwords, connection secrets, customer records, prescription contents, or payment payloads are included in this report.

## 3. Live database inventory

The application database confirmed from the backend datasource configuration is `defaultdb`.

Ten base tables were listed:

| Table | Observed purpose |
|---|---|
| `admins` | Admin credentials and account identity |
| `admin_action_authorizations` | One-time authorization records for sensitive admin actions |
| `appointments` | Eye-checkup appointments |
| `customer_orders` | Customer checkout, payment, fulfilment, and tracking |
| `marketing_banners` | Marketing banner configuration |
| `offer_bars` | Promotional offer bars |
| `order_items` | Order line items, lens choices, and prescription JSON |
| `products` | Product catalogue and legacy stock quantity |
| `razorpay_webhook_events` | Webhook event processing records |
| `website_images` | Website image configuration |

The table listing contains **10 application tables**.

## 4. Observed record counts

Aggregate-only queries returned:

| Table | Row count |
|---|---:|
| `products` | 7 |
| `customer_orders` | 9 |
| `order_items` | 9 |
| `appointments` | 0 |
| `razorpay_webhook_events` | 0 |

No individual customer or order data was selected for this report.

## 5. Legacy stock snapshot

Aggregate query against `products.stock_quantity`:

| Metric | Result |
|---|---:|
| Total products | 7 |
| Products with negative stock | 0 |
| Products with zero stock | 1 |
| Products with positive stock | 6 |
| Sum of legacy stock quantities | 27 |

The sum of 27 is a database aggregate only. It is **not confirmed physical inventory**.

### Reservation state

The grouped `customer_orders` result contained:

| `stock_reserved` | `payment_status` | `order_status` | Count |
|---:|---|---|---:|
| 0 | PAID | CONFIRMED | 9 |

All nine observed orders were paid and confirmed, with `stock_reserved = 0`.

This is consistent with the inspected payment-confirmation service marking a reservation as consumed after successful payment. It does not independently prove the current physical stock count or reconcile inventory against physical sales.

## 6. Historical order-item integrity

A left-join aggregate check between `order_items.product_id` and `products.id` returned:

| Check | Result |
|---|---:|
| Total order items | 9 |
| Missing product IDs | 0 |
| Non-null product IDs with no matching product | 0 |
| Null or non-positive quantities | 0 |
| Items with non-empty prescription JSON | 6 |

The six prescription-bearing items must be preserved byte-for-byte or through a validated lossless transformation in any future migration. Prescription values and image URLs were not retrieved.

## 7. Indexes and foreign keys observed

### Unique indexes

| Table | Unique column |
|---|---|
| `admin_action_authorizations` | `token_hash` |
| `admins` | `username` |
| `customer_orders` | `tracking_token` |
| `razorpay_webhook_events` | `event_id` |
| `website_images` | `image_key` |

Primary keys were observed on all ten listed tables.

### Foreign keys

The only returned foreign-key relationship was:

`order_items.order_id` → `customer_orders.id`

No database-level foreign key was observed for `order_items.product_id` → `products.id`. The aggregate integrity check currently found no unresolved product references, but application-level validation and historical retention rules still matter.

## 8. Important existing columns

The inspected column metadata showed:

### `products`

- `id`
- `active`
- `category`
- `created_at`
- `description`
- `image_url`
- `name`
- `price`
- `stock_quantity`
- `try_on_image_url`
- `specifications` (JSON)
- `gender`

### `customer_orders`

Includes customer/contact and delivery fields, `payment_status`, Razorpay order/payment IDs, `total_amount`, `order_status`, courier/tracking fields, `tracking_token`, `reservation_expires_at`, and `stock_reserved`.

### `order_items`

Includes `order_id`, nullable `product_id`, `product_name`, `quantity`, `price`, `lens_type`, `photochromic`, `prescription_type`, and `prescription_json`.

The copied `SHOW CREATE TABLE` output for `products` and `order_items` was truncated in the inspection transcript. Their complete DDL, storage engine, and every constraint therefore remain to be captured before writing migrations.

## 9. Backend source review

The following source files were fetched from the repository's `master` branch:

- [OrderService.java](https://github.com/Elavarasany/nmk-backend/blob/master/nmk-backend/src/main/java/nmk_backend/service/OrderService.java)
- [PaymentVerificationService.java](https://github.com/Elavarasany/nmk-backend/blob/master/nmk-backend/src/main/java/nmk_backend/service/PaymentVerificationService.java)

### Observations from `OrderService`

- Pending checkout validates that the cart is non-empty and each product and quantity is valid.
- Product active status is checked.
- Repeated product IDs in a cart are aggregated before stock reservation.
- Stock reservation is delegated to `ProductRepository.reserveStock(...)`.
- The order is assigned a 15-minute reservation expiry and `stock_reserved = true`.
- Cancellation/release locks the order with `findByIdForUpdate`.
- Reservation release is guarded by `stock_reserved`, then delegates stock restoration to `ProductRepository.releaseStock(...)`.
- Prescription information is serialized into `prescription_json`; uploaded image mode requires an HTTPS URL.

**Not yet verified:** the exact SQL/locking semantics inside `ProductRepository.reserveStock` and `releaseStock`, scheduled expiry execution, and every caller of the release method.

### Observations from `PaymentVerificationService`

- The order is fetched using `findByRazorpayOrderIdForUpdate`.
- An already-PAID order returns without processing again.
- Payment confirmation requires `stock_reserved = true`.
- The service records the Razorpay payment ID and marks payment PAID.
- A PENDING order is changed to CONFIRMED.
- A tracking token is generated if absent.
- The already-deducted stock reservation is marked consumed by setting `stock_reserved = false`; stock is not deducted again in this service.

**Important boundary:** this service method accepts Razorpay order and payment IDs. The fetched method itself does not perform HMAC signature verification or independently retrieve the payment from Razorpay. Signature verification and payment amount/order validation, if implemented, must be verified in the calling controller/service and Razorpay integration code before being treated as confirmed safeguards.

**Additional item to verify:** the shown confirmation method does not clear `reservation_expires_at` when it marks `stock_reserved = false`. Check whether another layer clears it or whether stale expiry timestamps remain on paid orders.

### Webhook handling

The live `razorpay_webhook_events` table contained zero rows at inspection time. This alone does not prove webhook handling is broken or unused. The webhook endpoint, signature validation, event deduplication, transaction boundaries, retry behavior, and event persistence must be reviewed separately.

## 10. POS migration implications

The POS design must be additive and preserve the online workflow.

1. Do not repurpose or rename `products.stock_quantity` without a reviewed compatibility plan.
2. Establish a physical stock count before setting opening inventory balances.
3. Define how online checkout reservations, in-store POS sales, returns, damaged goods, and manual adjustments affect inventory.
4. Use a transaction-safe inventory movement ledger and concurrency controls for shared online/POS stock.
5. Preserve existing product IDs and historical order-item snapshots.
6. Preserve all existing order/payment/tracking records and prescription JSON.
7. Do not cascade-delete historical order data when products are archived.
8. Keep POS permissions separate from existing admin authorization flows and apply server-side authorization to every write operation.
9. Plan backups and a tested rollback procedure before any production DDL or data migration.
10. Do not enable GST-specific invoicing assumptions: the business has stated that it is not GST registered.

## 11. Required checks before migration approval

- [ ] Capture complete `SHOW CREATE TABLE` output for all existing tables, especially `products`, `customer_orders`, and `order_items`.
- [ ] Inspect all backend entity mappings and repository queries related to stock.
- [ ] Inspect Razorpay order creation, payment verification controller, webhook endpoint, signature verification, and event persistence.
- [ ] Inspect reservation expiry scheduler and all cancellation/failure paths.
- [ ] Reconcile the nine historical paid orders against trusted Razorpay records without exposing payment secrets.
- [ ] Perform a physical count of all saleable stock and distinguish frames, lenses, contact lenses, and variants.
- [ ] Approve the POS inventory model, opening-balance process, backup, and rollback plan.
- [ ] Test migrations against a database copy before production.

## 12. Change log and safety statement

- Read-only Aiven inspection performed using `nmk_inspector`.
- Aggregate queries and metadata queries only.
- Backend source files fetched for review.
- No production rows changed.
- No production schema changed.
- No credentials or personal customer data included.
- No POS migration or application-code change included in this report.

**Status: Phase 1 inspection documented; migration design is not yet approved.**
