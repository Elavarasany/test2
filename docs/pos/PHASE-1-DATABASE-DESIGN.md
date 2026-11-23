# NMK Opticals POS — Phase 1 Database Foundation

**Status:** Design document; no schema or application code has been changed by this document.  
**Repository branch:** `master`  
**Scope:** Plan the database foundation for connected in-store POS while preserving the existing online checkout flow.

## 1. Goals and non-goals

### Goals

- Keep one Spring Boot backend and one MySQL database for the website, admin dashboard and physical-store POS.
- Reuse the existing product catalogue.
- Introduce shared customer profiles without rewriting historical order data.
- Introduce separate physical-store sales and sale items.
- Establish auditable inventory movements and a safe way to represent online reservations.
- Preserve payment, invoice, return and prescription history.
- Plan a controlled migration with a rollback and reconciliation strategy.

### Non-goals for this phase

- No POS UI or API implementation.
- No production database changes.
- No automatic conversion of existing orders into sales.
- No destructive changes to existing tables.
- NMK is currently **not GST-registered**. The initial POS design must not assume a GSTIN or apply GST as though NMK were registered. Keep the sales and invoice model extensible so applicable tax handling can be introduced if registration status changes. Confirm the legally appropriate invoice format and other applicable obligations before launch.

## 2. Current implementation inspected

The following existing backend files were inspected on the `master` branch:

- `entity/Product.java`
- `entity/Order.java`
- `entity/OrderItem.java`
- `entity/Customer.java`
- `entity/RazorpayWebhookEvent.java`
- `repository/ProductRepository.java`
- `repository/OrderRepository.java`
- `service/OrderService.java`
- `service/PaymentVerificationService.java`
- `service/RazorpayService.java`
- `controller/RazorpayController.java`
- `controller/OrderController.java`
- `controller/AdminOrderController.java`
- `controller/ProductController.java`
- `service/StockReservationScheduler.java`
- `src/main/resources/application.properties`

### Existing product and stock behaviour

- `products` currently contains a `stockQuantity` integer.
- `ProductRepository.reserveStock(...)` atomically subtracts quantity if the product is active and sufficient stock is available.
- `ProductRepository.releaseStock(...)` adds quantity back.
- `OrderService.createPendingOrder(...)` combines duplicate product lines, reserves stock, and marks the order as stock-reserved with a 15-minute expiry.
- `OrderService.releaseReservationForOrder(...)` restores the quantities and marks the order cancelled/failed.
- `PaymentVerificationService.confirmPayment(...)` locks the order, rejects confirmation without an active reservation, marks payment paid, and changes `stockReserved` to false without deducting stock a second time.
- `RazorpayController` verifies the Razorpay signature and checks captured payment, order ID and amount before confirming.
- `Customer.java` is currently an empty class.
- Online customer name, email, phone and address are stored as fields on `customer_orders`.
- `order_items` stores product ID, product-name snapshot, quantity, price and prescription-related fields.
- `application.properties` currently uses Hibernate `ddl-auto=update`; a controlled migration approach is required before production schema evolution.

### Important stock interpretation

The current reservation implementation deducts reserved units from `products.stockQuantity` at checkout. On payment confirmation, the deduction remains and the reservation flag is cleared. On cancellation/expiry, the deduction is reversed.

Therefore, the current `stockQuantity` should not be assumed to mean the shop's physical on-hand quantity. At a given moment it is closer to stock available after active online reservations, subject to successful cleanup and reconciliation.

**Do not initialize a new on-hand inventory quantity by blindly copying the current `stockQuantity`.** Before migration, reconcile:
1. Physical count in the shop.
2. Current `products.stockQuantity`.
3. All orders with `stockReserved = true`.
4. Paid/fulfilled order history and any pending cleanup work.
5. Any manual stock adjustments not represented in the database.

The scheduled cleanup entry point has now been located in `service/StockReservationScheduler.java`. It runs every 60 seconds, queries expired reservation IDs, checks the corresponding Razorpay order/payment attempts before releasing stock, and retains the reservation if the payment state is unsettled, successful or cannot be established. Before release it locks the order and rechecks `stockReserved`, pending payment status and expiry, then calls the existing order-service release path. Preserve this fail-closed behaviour during the inventory migration.

### Additional stock and transaction audit findings

- `controller/OrderController.java` creates a pending order/reserves stock before creating the Razorpay order. If payment-order creation fails, it attempts to release the reservation. The failure path should remain idempotent and be covered by integration tests.
- `controller/AdminOrderController.java` routes cancellation of eligible unpaid pending orders through `OrderService`, but paid-order cancellation is rejected because a refund workflow is not implemented. Preserve this restriction until returns/refunds are built.
- `controller/ProductController.java` exposes product creation that saves directly through `ProductRepository`, bypassing `ProductService` validation. Product creation should eventually be routed through one validated service path.
- Product administration currently allows stock quantity to be changed through product updates. This is not an adequate long-term inventory workflow: stock changes must become explicit, authorized adjustments with a reason and a stock-movement record.
- The inspected admin order controller includes a clear-all-orders operation. Historical financial/order records should not be routinely deleted; replace destructive history-clearing workflows with controlled retention/archival and auditable operations before production POS history is introduced.
- No dedicated stock movement ledger exists in the inspected entity set. The new ledger must be written atomically with every inventory change.

The scheduler checks Razorpay before releasing stock, but it is not a substitute for reconciliation. Monitoring and operational handling are still needed for reservations that remain held because Razorpay is unavailable or the payment state is ambiguous.

## 3. Target conceptual model

### Shared customer records

#### `customers`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `name VARCHAR(150)`
- `phone VARCHAR(20)`
- `email VARCHAR(254)`
- `created_at DATETIME NOT NULL`
- `updated_at DATETIME NOT NULL`
- `active BOOLEAN NOT NULL DEFAULT TRUE`

Phone is the primary lookup key for POS workflows, but the final uniqueness rule must account for missing/blank phone numbers and normalized formats. Do not assume every walk-in customer must be registered. A walk-in sale may have a null customer reference.

#### `customer_addresses`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `customer_id BIGINT NOT NULL`
- `address_line VARCHAR(1000)`
- `city VARCHAR(120)`
- `state VARCHAR(120)`
- `pincode VARCHAR(20)`
- `is_default BOOLEAN NOT NULL DEFAULT FALSE`
- `created_at DATETIME NOT NULL`

Relationship: one customer to many addresses.

### Online orders remain online orders

Keep the existing `customer_orders` and `order_items` tables and their current payment/tracking workflow.

Potential additive change after migration design is approved:

- Add nullable `customer_id` to `customer_orders`.
- Preserve the existing customer name, phone, email and address fields as historical order-time snapshots.
- Do not require every existing order to be linked to a customer.
- Do not convert online orders into POS sales.

### Physical-store sales

#### `sales`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `invoice_number VARCHAR(50) NOT NULL UNIQUE`
- `customer_id BIGINT NULL`
- `subtotal DECIMAL(12,2) NOT NULL`
- `discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `tax_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `total_amount DECIMAL(12,2) NOT NULL`
- `sale_status VARCHAR(30) NOT NULL`
- `sold_at DATETIME NOT NULL`
- `created_by BIGINT NULL`
- `notes VARCHAR(1000) NULL`
- `created_at DATETIME NOT NULL`

A sale can be a walk-in sale without a customer profile. Invoice numbering and tax fields must follow the business's confirmed invoice requirements.

#### `sale_items`

Proposed columns:

- `id BIGINT PRIMARY KEY`
- `sale_id BIGINT NOT NULL`
- `product_id BIGINT NULL`
- `product_name VARCHAR(255) NOT NULL`
- `sku VARCHAR(100) NULL`
- `quantity INT NOT NULL`
- `unit_price DECIMAL(12,2) NOT NULL`
- `discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `tax_amount DECIMAL(12,2) NOT NULL DEFAULT 0`
- `line_total DECIMAL(12,2) NOT NULL`

The product name, SKU and unit price are sale-time snapshots. Product deletion or later catalogue edits must not rewrite an old invoice. Decide the product foreign-key deletion policy before implementing it; historical sale items must remain readable.

### Payments

#### `payments`

Initial POS-oriented proposal:

- `id BIGINT PRIMARY KEY`
- `sale_id BIGINT NOT NULL`
- `payment_method VARCHAR(30) NOT NULL` — CASH, UPI, CARD, or another explicitly supported method
- `amount DECIMAL(12,2) NOT NULL`
- `payment_status VARCHAR(30) NOT NULL`
- `reference_number VARCHAR(150) NULL`
- `paid_at DATETIME NULL`
- `created_at DATETIME NOT NULL`

Multiple payment rows per sale support split payments. Payment rows need idempotency/reference constraints appropriate to the payment provider. Do not mix POS payment rows into the existing Razorpay online payment flow without a separate design review.

### Inventory

#### `inventory`

One summary row per product initially:

- `id BIGINT PRIMARY KEY`
- `product_id BIGINT NOT NULL UNIQUE`
- `on_hand_quantity INT NOT NULL`
- `reserved_quantity INT NOT NULL DEFAULT 0`
- `reorder_level INT NOT NULL DEFAULT 0`
- `updated_at DATETIME NOT NULL`

Available quantity is calculated as:

`available_quantity = on_hand_quantity - reserved_quantity`

The POS must not sell reserved online stock.

#### `stock_movements`

Append-only stock audit ledger:

- `id BIGINT PRIMARY KEY`
- `product_id BIGINT NOT NULL`
- `movement_type VARCHAR(40) NOT NULL`
- `quantity INT NOT NULL`
- `reference_type VARCHAR(40) NULL`
- `reference_id BIGINT NULL`
- `notes VARCHAR(1000) NULL`
- `created_by BIGINT NULL`
- `created_at DATETIME NOT NULL`

Movement types should be explicit, for example opening balance, purchase receipt, POS sale, online sale, return restock, damage/write-off and manual adjustment. Define whether quantity is stored signed or as a positive quantity plus direction before coding; use one convention consistently.

Every inventory-changing transaction must update the summary and write its movement record in the same database transaction.

### Prescriptions and private files

A future `prescriptions` table should link prescription data to a customer and to either an online order item or a POS sale item. Store structured eye values in typed columns or validated JSON according to the final prescription model.

Prescription images/documents belong in private Oracle Object Storage. Store an object key and metadata in MySQL, not Oracle credentials or image bytes. Access must be authorized by the backend; use short-lived access URLs where appropriate. Do not make prescription buckets public.

### Returns and refunds

Proposed future tables:

- `returns`: original sale, return number, reason, status, refund amount, timestamps and staff reference.
- `return_items`: original sale item, returned quantity, refund amount and quantity approved for restock.

Never delete or overwrite the original sale to process a return. Record the return/refund as a separate auditable transaction. Restock only items physically inspected and approved for resale.

### Suppliers and purchasing

Later additions:

- `suppliers`
- `purchase_orders`
- `purchase_order_items`
- Goods-receipt records

Receiving stock should increase on-hand inventory and write stock movements in the same transaction.

## 4. Relationships

- `customers 1 -> many customer_addresses`
- `customers 1 -> many customer_orders` (nullable link; preserve historical snapshots)
- `customers 1 -> many sales` (nullable for walk-ins)
- `sales 1 -> many sale_items`
- `sales 1 -> many payments`
- `products 1 -> 1 inventory`
- `products 1 -> many stock_movements`
- `products 1 -> many sale_items` (nullable or restricted deletion policy; preserve item snapshots)
- `sale_items 1 -> many return_items`
- Prescriptions may reference an online order item or a POS sale item, with a constraint ensuring the intended ownership model.

## 5. Required integrity and concurrency rules

1. Use MySQL InnoDB and foreign keys for relational integrity.
2. Use `DECIMAL(12,2)` for money; never use floating-point money fields.
3. Add unique constraints for invoice numbers and product SKU/barcode values where present.
4. Normalize phone numbers before customer matching; define a safe policy for duplicate or missing phone values.
5. Use a transaction for sale creation, payment recording, stock deduction and stock movement insertion.
6. Lock inventory rows or use guarded atomic updates so concurrent cashiers cannot sell the same remaining unit.
7. Check available stock, not just on-hand stock.
8. Make payment confirmation and sale submission idempotent; repeated requests must not duplicate payments, invoices or stock movements.
9. Keep stock movements and financial transaction history auditable; do not silently overwrite or delete history.
10. Define refund, cancellation and restock transitions explicitly.
11. Add indexes for customer phone, sale invoice number, sale date/status, payment references, product SKU/barcode, stock movement product/date and order/customer lookup.
12. Use database constraints for non-negative quantities and valid amounts where supported and practical.

## 6. Safe migration sequence — proposed, not executed

1. Confirm the invoice format and applicable legal requirements for a business that is currently not GST-registered. Do not calculate or represent GST as collected unless the business becomes registered and the rules are updated.
2. Finish auditing the online reservation cleanup/scheduler path and all stock-changing code paths.
3. Back up the current database and verify that a restore is possible.
4. Take a physical stock count and reconcile it against database stock and active online reservations.
5. Agree on the final schema and migration tool/versioning strategy (prefer Flyway or Liquibase over relying on Hibernate `ddl-auto=update` for production changes).
6. Create additive tables and nullable columns only; do not drop or rename existing production columns in the first migration.
7. Initialize inventory from a verified reconciliation snapshot, not by blindly copying `products.stockQuantity`.
8. Record opening balances and reservation balances with a documented reconciliation reference.
9. Link existing orders to customer profiles only where matching is reliable; retain all original customer snapshot fields.
10. Test online checkout, payment success, duplicate verification, cancellation, expiry, POS sale, split payment, concurrent sale attempts and rollback against a staging database.
11. Compare stock totals and transaction counts before and after migration.
12. Deploy only after backups, staging tests and restore verification pass.

### Rollback principles

- Never roll back by deleting transaction history.
- Prefer additive schema changes.
- If application deployment must be reverted, retain backward-compatible columns/tables until the old version no longer needs them.
- Restore production data only from a verified backup and with a documented recovery decision.
- Do not delete the old database or old image store until migration and recovery have been verified.

## 7. Open decisions before implementation

- NMK is currently not GST-registered. Confirm the required non-GST invoice wording/format with the business's accountant or appropriate adviser; revisit tax configuration if registration status changes.
- Should customer phone numbers be mandatory for creating a saved customer profile? Walk-in sales should still be possible without one.
- What is the desired invoice prefix and numbering policy?
- Which staff roles are required initially, and how do they map to the existing admin authentication model?
- What are the physical stock count and outstanding online reservations at cutover?
- The expired-reservation scheduler is `StockReservationScheduler`; verify its runtime scheduling configuration and add monitoring/test coverage before production cutover.
- Are multiple branches/locations required? Initial design assumes one shop location but should avoid blocking future expansion.

## 8. Immediate next audit task

Before creating entities or migrations, locate and inspect:
- The scheduled job that calls expired reservation cleanup.
- Every service/controller that changes `stockQuantity`.
- The order status transitions after successful payment, cancellation, refund and fulfilment.
- Existing admin/customer endpoints and authentication/role structure.
- Product SKU/barcode fields and product variant/size representation.

**No application code, schema, database records, credentials, deployment settings or other repository files are changed by this design document.**

## 9. Detailed proposed relational schema (review baseline)

This is a logical schema baseline for review, not an executable migration. Final DDL must be validated against the deployed MySQL version and existing data.

### 9.1 Common conventions

- Use InnoDB for transactional tables and BIGINT auto-increment primary keys.
- Use DATETIME(6) consistently for audit timestamps.
- Use DECIMAL(12,2) for INR money; never FLOAT or DOUBLE.
- Use integer quantities for discrete stock units.
- Validate statuses in the service layer and add database CHECK constraints where supported.
- Never store card numbers, CVV, UPI PINs, payment credentials or provider secrets.

### 9.2 customers

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK, auto-increment |
| name | VARCHAR(150) | Nullable for anonymous walk-in |
| phone | VARCHAR(20) | Nullable |
| phone_normalized | VARCHAR(20) | Nullable; normalized lookup value |
| email | VARCHAR(254) | Nullable |
| active | BOOLEAN | NOT NULL, default TRUE |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

Initially index phone_normalized without enforcing uniqueness. Review duplicate and missing phone handling before adding a unique constraint. Walk-in sales must not require a saved customer.

### 9.3 customer_addresses

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| customer_id | BIGINT | NOT NULL, FK to customers |
| address_line | VARCHAR(1000) | Nullable |
| city | VARCHAR(120) | Nullable |
| state | VARCHAR(120) | Nullable |
| pincode | VARCHAR(20) | Nullable; string preserves leading zeroes |
| is_default | BOOLEAN | NOT NULL, default FALSE |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

Index customer_id and is_default. Enforce at most one default address per customer in a service transaction.

### 9.4 inventory_locations

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| code | VARCHAR(30) | NOT NULL, UNIQUE |
| name | VARCHAR(120) | NOT NULL |
| active | BOOLEAN | NOT NULL, default TRUE |
| created_at | DATETIME(6) | NOT NULL |

Seed one initial location for the Avalurpet shop. This location dimension avoids a redesign if a second branch is added later; multi-branch UI is not part of the initial scope.

### 9.5 inventory

One row per product per location.

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| product_id | BIGINT | NOT NULL, FK to products |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| on_hand_quantity | INT | NOT NULL, >= 0 |
| reserved_quantity | INT | NOT NULL, default 0, >= 0 |
| reorder_level | INT | NOT NULL, default 0, >= 0 |
| version | BIGINT | NOT NULL, default 0; optional optimistic lock |
| updated_at | DATETIME(6) | NOT NULL |

Unique constraint: product_id + location_id. Available quantity is calculated as on_hand_quantity minus reserved_quantity; do not store it as an independently editable field. Enforce reserved_quantity <= on_hand_quantity in service logic and with a CHECK constraint where supported.

### 9.6 stock_movements

Append-only audit ledger. Correct mistakes with a new reversing movement, never by editing old movement rows.

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| product_id | BIGINT | NOT NULL, FK to products |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| movement_type | VARCHAR(40) | NOT NULL |
| quantity_delta | INT | NOT NULL, signed; positive adds on-hand, negative removes |
| on_hand_after | INT | NOT NULL |
| reserved_delta | INT | NOT NULL, default 0; positive reserves, negative releases |
| reserved_after | INT | NOT NULL |
| reference_type | VARCHAR(40) | Nullable, e.g. POS_SALE, ONLINE_ORDER, PURCHASE_RECEIPT, RETURN, ADJUSTMENT |
| reference_id | BIGINT | Nullable |
| idempotency_key | VARCHAR(100) | Nullable; unique when supplied |
| reason | VARCHAR(500) | Required for manual adjustments/write-offs |
| created_by | BIGINT | Nullable until staff model is finalized |
| created_at | DATETIME(6) | NOT NULL |

Indexes: product_id + location_id + created_at; reference_type + reference_id. Use a unique index for supplied idempotency keys after validating MySQL nullable-unique behaviour. Update inventory summary and insert its movement in the same transaction.

Movement types include OPENING_BALANCE, POS_SALE, ONLINE_RESERVATION, ONLINE_RESERVATION_RELEASE, ONLINE_ORDER_FULFILLED, PURCHASE_RECEIPT, RETURN_RESTOCK, DAMAGE_WRITE_OFF and MANUAL_ADJUSTMENT.

Important: current online checkout deducts products.stockQuantity when reserving. The new model must not reserve inventory and deduct it again at payment. Design the reservation-to-fulfilment transition against the actual online order lifecycle before switching code.

### 9.7 sales

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| invoice_number | VARCHAR(50) | NOT NULL, UNIQUE, immutable once issued |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| customer_id | BIGINT | Nullable FK to customers |
| sale_status | VARCHAR(30) | NOT NULL; DRAFT, COMPLETED, VOIDED, PARTIALLY_RETURNED, RETURNED |
| subtotal | DECIMAL(12,2) | NOT NULL, >= 0 |
| discount_amount | DECIMAL(12,2) | NOT NULL, default 0, >= 0 |
| tax_amount | DECIMAL(12,2) | NOT NULL, default 0 |
| total_amount | DECIMAL(12,2) | NOT NULL, >= 0 |
| customer_name_snapshot | VARCHAR(150) | Nullable |
| customer_phone_snapshot | VARCHAR(20) | Nullable |
| notes | VARCHAR(1000) | Nullable |
| sold_at | DATETIME(6) | Nullable until completion |
| created_by | BIGINT | Nullable until staff identity model is finalized |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

Indexes: location_id + sold_at; customer_id + sold_at; sale_status + sold_at. Allocate an invoice number only on completion, unless an accountant-approved numbering policy requires otherwise.

NMK is currently not GST-registered. Do not require GSTIN, calculate or represent GST as collected, or hardcode a GST rate. Keep tax fields for future extensibility; initial tax behaviour should be zero unless qualified review identifies another applicable tax treatment. Confirm invoice wording and applicable obligations with the business's accountant/adviser before launch.

### 9.8 sale_items

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| sale_id | BIGINT | NOT NULL, FK to sales |
| product_id | BIGINT | Nullable FK to products |
| product_name_snapshot | VARCHAR(255) | NOT NULL |
| sku_snapshot | VARCHAR(100) | Nullable |
| barcode_snapshot | VARCHAR(100) | Nullable |
| quantity | INT | NOT NULL, > 0 |
| unit_price | DECIMAL(12,2) | NOT NULL, >= 0 |
| discount_amount | DECIMAL(12,2) | NOT NULL, default 0, >= 0 |
| tax_amount | DECIMAL(12,2) | NOT NULL, default 0 |
| line_total | DECIMAL(12,2) | NOT NULL, >= 0 |
| prescription_required | BOOLEAN | NOT NULL, default FALSE |
| prescription_data | JSON | Nullable; structured values only |
| created_at | DATETIME(6) | NOT NULL |

Index sale_id. Product name, SKU/barcode and price snapshots are authoritative for historic invoices. Prefer archiving products and restricting deletion once referenced.

### 9.9 sale_payments

This is the POS tender table and must remain separate from the existing Razorpay online payment flow.

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| sale_id | BIGINT | NOT NULL, FK to sales |
| payment_method | VARCHAR(30) | NOT NULL: CASH, UPI, CARD, OTHER |
| amount | DECIMAL(12,2) | NOT NULL, > 0 |
| payment_status | VARCHAR(30) | NOT NULL: PENDING, COMPLETED, FAILED, REFUNDED, PARTIALLY_REFUNDED |
| reference_number | VARCHAR(150) | Nullable provider/bank reference; no secrets |
| idempotency_key | VARCHAR(100) | Nullable, unique when supplied |
| paid_at | DATETIME(6) | Nullable until successful |
| created_at | DATETIME(6) | NOT NULL |

Index sale_id + payment_status and reference_number. Multiple completed rows support split tender. The sum of completed payments must equal the amount due before a sale is fully paid. A pending UPI/card initiation is not collected money. Record refunds separately; do not delete the original payment.

### 9.10 invoice_sequences

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| financial_year | VARCHAR(9) | NOT NULL; India financial-year format to be finalized |
| prefix | VARCHAR(20) | NOT NULL |
| next_number | BIGINT | NOT NULL, > 0 |
| updated_at | DATETIME(6) | NOT NULL |

Unique constraint: location_id + financial_year + prefix. Allocate numbers by locking the sequence row inside the sale transaction. Never use MAX(invoice_number) + 1 or an unlocked counter. Confirm numbering and cancellation treatment with the accountant/adviser.

### 9.11 returns and return_items

returns:
- id BIGINT PK; return_number VARCHAR(50) NOT NULL UNIQUE; sale_id BIGINT NOT NULL FK to sales.
- return_status VARCHAR(30) NOT NULL: REQUESTED, APPROVED, REJECTED, COMPLETED.
- reason VARCHAR(500) NOT NULL; refund_amount DECIMAL(12,2) NOT NULL default 0.
- created_by BIGINT and approved_by BIGINT nullable until staff model is finalized.
- created_at DATETIME(6) NOT NULL; completed_at DATETIME(6) nullable.

return_items:
- id BIGINT PK; return_id BIGINT NOT NULL FK to returns; sale_item_id BIGINT NOT NULL FK to sale_items.
- quantity INT NOT NULL and > 0; restock_quantity INT NOT NULL default 0 and <= quantity.
- refund_amount DECIMAL(12,2) NOT NULL and >= 0; inspection_notes VARCHAR(500) nullable.

Completed returns across all return records must not exceed the originally sold quantity. Restock only after physical inspection and approval, using a separate inventory movement. Refund execution must be recorded and idempotent. A refund request is not a completed refund.

### 9.12 Product identifiers and deletion policy

The current Product entity has no dedicated SKU or barcode fields. Add nullable SKU and barcode only after reviewing catalogue data and defining legacy assignment. Enforce uniqueness for non-empty normalized values; do not treat repeated empty strings as valid identifiers. Barcode scanners can initially work as keyboard input.

Do not physically delete products referenced by sales, stock movements, purchase receipts or returns. Prefer deactivation/archive. Financial and inventory foreign-key deletion behaviour should be RESTRICT/NO ACTION; never cascade-delete transaction history.

### 9.13 Transaction boundaries and invariants

1. POS completion: lock inventory rows in stable product/location order; validate available quantities; write sale and items; record completed tender(s); decrement on-hand; write stock movements; allocate invoice number; commit atomically.
2. Split tender: payment rows and sale completion are atomic. A partially paid sale requires an explicitly designed receivable/credit policy.
3. Duplicate requests: require an idempotency key for sale submission and payment callbacks; retries return the original result rather than duplicate invoices, payments or movements.
4. Online reservation: reserve from the same shared inventory available balance. Preserve the existing Razorpay fail-closed expiry behaviour until a tested replacement is deployed.
5. Locking: use row-level locks or guarded atomic updates. For multi-product transactions, lock in deterministic order; retry known transient deadlocks only with idempotency.
6. Stock ledger: inventory summary and movement ledger update in the same transaction. Normal product-edit APIs must not overwrite inventory balances.
7. Returns: preserve original sale/payment; create separate return/refund records and stock movements.
8. Money: compute totals server-side using BigDecimal; validate discounts, line totals, payment sums and refund caps.
9. Authorization: enforce cashier/manager/admin permissions in backend services/controllers, not only the UI. Staff identity and role references need a separate authentication design review.
10. Audit: price overrides, discounts, manual stock adjustments, voids, returns and refunds must record actor, timestamp and reason.

### 9.14 Sign-off gates before implementation

- Confirm SKU/barcode assignment and whether product variants are separately stocked.
- Confirm staff/cashier roles and actor references.
- Confirm invoice numbering and invoice wording with the accountant/adviser.
- Confirm whether POS is cash-and-carry only or supports later collection/delivery.
- Define online stock transitions from reservation through payment, packing, dispatch, cancellation and refund.
- Perform physical stock count and reconcile every active online reservation at cutover.
- Choose Flyway or Liquibase and write explicit additive migrations; do not rely on Hibernate schema auto-update for production.
- Test rollback, concurrent checkout, duplicate submissions, payment retries, reservation expiry, partial returns and database restore in staging.

**Phase 1 schema status:** proposed for review only. No Java entities, repositories, controllers, SQL migration files, database records or production settings are changed by this section.


## 10. Confirmed business decisions and schema amendments

The following decisions were confirmed during Phase 1 review.

### 10.1 Variant-level stock

Each sellable product variant must have its own stock quantity. For example, different frame colours or sizes are distinct stock-keeping variants and must not share one quantity.

Add a `product_variants` table:

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK |
| product_id | BIGINT | NOT NULL, FK to the existing products table |
| variant_code | VARCHAR(100) | NOT NULL; unique within a product |
| attributes | JSON | NOT NULL; structured values such as colour and size |
| barcode | VARCHAR(100) | Nullable; unique when supplied after normalization |
| is_active | BOOLEAN | NOT NULL, default true |
| created_at | DATETIME(6) | NOT NULL |
| updated_at | DATETIME(6) | NOT NULL |

A product with no selectable options still gets one default variant. This keeps inventory and POS sale lines consistent: every stock-tracked sale refers to a specific variant, never to an ambiguous parent product.

**Required amendment to Sections 9.5, 9.6 and 9.8:** inventory balances, stock movements and sale items must reference `variant_id` (FK to `product_variants`) rather than using only `product_id` as the stock identity. Keep `product_id` on sale-item snapshots if useful for reporting, but variant is the authoritative stock identity. Inventory uniqueness becomes location_id + variant_id. Stock movement records should retain both variant_id and a product/variant description snapshot where audit readability requires it.

Existing online order lines and the current `products.stockQuantity` model must be mapped to default or newly created variants during a controlled migration. Do not create variant rows or migrate balances blindly: first reconcile current product stock, physical count, and active online reservations. Online checkout and POS must then reserve/decrement the same variant-level inventory without double deduction.

### 10.2 POS payment policy

- A POS sale can be completed only when fully paid.
- Multiple tender types are allowed in one sale (split payment), such as cash plus UPI.
- Phase 1 does not support partial payment, customer credit, or an outstanding customer balance.
- Payment rows must be recorded individually, and the sum of successful/collected payments must equal the amount due before the sale is marked paid/completed.
- Customer credit/receivables are explicitly deferred to a later phase and must not be simulated by leaving a Phase 1 sale partially paid.

### 10.3 Invoice numbering

Invoice numbering restarts with a new sequence for each Indian financial year (April to March), and the sequence is scoped by inventory location and configured prefix. Example format for discussion: `NMK/26-27/0001`.

The `invoice_sequences` design in Section 9.10 applies: allocate the next number by locking the matching location + financial_year + prefix row inside the sale transaction. Never calculate the next invoice number using MAX + 1. Final invoice format, prefix, treatment of void/cancelled invoices, and applicable invoice wording must be checked with the accountant/adviser before production use.

### 10.4 Updated sign-off status

Confirmed: variant-level stock, full settlement with split tender, and financial-year invoice sequence reset.

Still requiring review before implementation: exact product/variant mapping from the current catalogue, staff roles and permissions, online reservation-to-fulfilment stock lifecycle, physical stock reconciliation, migration strategy, and accountant review of invoice format and wording.

**Phase 1 remains a design document only.** No application code, SQL migration, live database or production configuration is changed by these decisions.


### 10.5 Customer catalogue and variant selection

The confirmed storefront behaviour is **one product listing per parent product, with selectable variants** (colour/size) on the product detail or selection flow. Do not create a separate customer-facing product card for every colour/size combination by default.

- The parent product remains the catalogue and marketing identity (name, description, brand, category and shared imagery).
- Each sellable combination of attributes is a distinct `product_variants` row. Its attributes identify the exact option combination (for example, colour = Black and size = Medium).
- Each variant owns its own SKU/barcode and location-specific inventory balance. A customer must select an available variant before adding the item to the cart.
- The cart line, checkout request, online order item and POS sale item must carry the selected `variant_id`. Stock validation, reservation, release, fulfilment and deduction must all use that same variant ID.
- The product page should show only valid combinations that actually exist. Do not let the UI construct an unconfigured colour/size combination from independent option lists.
- Availability shown to customers must reflect sellable available quantity (on-hand minus active reservations), not raw on-hand quantity. The backend remains authoritative and must revalidate stock during reservation and sale completion.
- Products without selectable options receive one default variant, so every stock-controlled cart/order/sale line still resolves to a variant.
- The admin catalogue workflow must allow staff with appropriate permissions to create, edit, deactivate and identify variants, and to maintain stock per variant through auditable inventory operations. Ordinary product edits must not overwrite variant inventory.
- Barcode/SKU lookup in POS resolves directly to the exact variant. Searching by parent product may show its available variants for cashier selection.

#### Existing catalogue and online-order migration

The current implementation treats each product row as one stock identity: `products.stockQuantity` is stored on the parent product, size/colour are ordinary specification attributes, and existing `order_items` refer to `product_id` only. The migration must therefore be explicit and staged:

1. Create a default variant for each existing product as the safe initial mapping. Do not split a legacy product into multiple variants merely because its JSON specifications mention colour or size; first review catalogue data and determine whether those combinations are truly separately stocked.
2. For products confirmed to have multiple separately stocked combinations, create the required variant rows and map each physical quantity only after a physical count and reservation reconciliation.
3. Add a nullable variant reference to existing order items first. Backfill historical/current order lines to the appropriate default or reviewed variant while preserving their original product IDs and sale-time snapshots.
4. Update cart, checkout, order creation, stock reservation, reservation expiry/release, payment confirmation and fulfilment paths to use variant-level inventory consistently. Preserve the current Razorpay fail-closed reservation cleanup behaviour and ensure payment confirmation does not deduct reserved stock a second time.
5. Keep compatibility during rollout: do not remove or stop maintaining the legacy stock field until all application paths are migrated, reconciled and verified. Define a rollback point before switching the source of truth.
6. Verify existing online orders, pending reservations, duplicate payment verification, cancellation/expiry and POS sales against staging data before enabling variant-level stock in production.

**Important:** this decision changes both customer catalogue behaviour and the stock identity used by checkout/POS. It is not merely a visual selector. No frontend or backend implementation is included in this Phase 1 document update.


### 10.6 Initial POS staff roles and permissions

The initial POS permission model uses three roles. These roles are a starting authorization policy for the first shop location; they do not imply that the existing admin authentication implementation already supports staff accounts or these permissions. Inspect and design the authentication/authorization integration before implementation.

| Capability | OWNER_ADMIN | MANAGER | CASHIER |
|---|---|---|---|
| Access POS and create sales | Yes | Yes | Yes |
| Accept and record POS tender | Yes | Yes | Yes |
| View and reprint permitted invoices | Yes | Yes | Yes |
| View customer information | Full access | Operational access | Minimum details needed for the sale |
| View operational reports | Yes | Yes | No, unless explicitly permitted |
| Manage staff accounts and roles | Yes | No | No |
| Change business/POS settings | Yes | No | No |
| Create/edit/deactivate catalogue products and variants | Yes | As explicitly granted | No |
| Perform manual stock adjustments | Yes | Yes, with reason and audit record | No |
| Apply discounts | Yes | Within configured policy; approve exceptions | Only within a configured cashier limit |
| Approve discounts above cashier limit | Yes | Yes, within manager authority | No |
| Void a completed sale | Yes, with reason and audit record | Yes, with reason and audit record | No |
| Approve/perform returns and refunds | Yes, with reason and audit record | Yes, with reason and audit record | No |
| Delete or rewrite completed sales/payment history | No routine deletion; preserve audit history | No | No |

#### Authorization and audit requirements

- Enforce every permission in the Spring Boot backend service/controller layer. Hiding or disabling a frontend button is not authorization.
- Give every staff member an individual account. Do not use shared cashier or manager credentials. Staff identity must be attributable to every sale and sensitive action.
- A staff account must have an assigned role, an active/disabled state and a controlled account lifecycle. Owner/Admin alone can create staff accounts, assign roles and disable accounts.
- Cashiers may create and fully settle sales, record permitted tender types, and view/reprint invoices. They must not edit or delete completed sales, alter recorded payments, adjust stock, approve their own restricted actions, or issue refunds independently.
- Managers may perform operational approvals within their assigned authority. Actions such as stock adjustments, voids, returns and refunds require a reason and an audit record. A manager must not grant themselves Owner/Admin privileges.
- Discount limits must be configurable by the business; do not hardcode a percentage or amount in the schema. A discount exceeding the cashier's configured limit requires manager or Owner/Admin approval. Record the applied discount, approving staff member and reason when approval is required.
- Record actor, timestamp, action, affected sale/payment/variant, before-and-after values where relevant, and reason for sensitive operations including price overrides, discounts, stock adjustments, voids, returns and refunds.
- Never implement a permission path that silently deletes or rewrites completed financial history. Correct mistakes through explicit void, return, refund or reversing inventory transactions.
- The exact mapping to existing admin authentication, JWT claims, staff identity tables and endpoint permissions must be reviewed before coding. Do not assume the current single-admin login can safely represent multiple staff roles.

**Confirmed initial policy:** three roles — OWNER_ADMIN, MANAGER and CASHIER — with backend-enforced permissions, individual staff accounts and auditable sensitive actions. Exact discount thresholds and detailed endpoint-to-permission mapping remain implementation decisions.

**Phase 1 remains a design document only.** This section does not implement staff accounts, authentication changes, authorization code, database tables or UI.


### 10.7 POS fulfilment and collection workflow

The initial POS release supports both immediate take-away and paid sales collected later. Payment settlement and physical fulfilment are separate concepts: a sale may be fully paid while still awaiting preparation or customer collection.

#### Fulfilment data and statuses

Add a distinct `fulfilment_status` to the proposed `sales` record; do not overload `sale_status` or `payment_status`.

Initial fulfilment statuses:

- `PENDING_PREPARATION`: the sale is fully paid and recorded, but the items are not yet ready for handover.
- `READY_FOR_COLLECTION`: preparation is complete and the customer can collect the items.
- `COLLECTED`: the items have been handed over to the customer.
- `CANCELLED`: fulfilment has been cancelled. This status does not itself mean that payment has been refunded; any eligible refund must be a separate, recorded and idempotent refund transaction.

Add or provide equivalent auditable fields on the sale:

- `expected_collection_at DATETIME(6) NULL`
- `ready_for_collection_at DATETIME(6) NULL`
- `collected_at DATETIME(6) NULL`
- `collected_by BIGINT NULL` — staff actor who confirms handover
- `fulfilment_updated_at DATETIME(6) NOT NULL`

The sale's existing `created_by` identifies the staff member who records the sale. Record the staff actor and timestamp for every fulfilment status transition in an audit history; do not rely only on the latest status field.

#### Workflow rules

1. **Immediate collection:** after full payment is successfully recorded and the sale is committed, set fulfilment status to `COLLECTED` and record the handover timestamp and staff actor.
2. **Collect later:** after full payment and sale commit, set fulfilment status to `PENDING_PREPARATION`. An expected collection date/time may be recorded. When preparation is complete, an authorized staff member changes the status to `READY_FOR_COLLECTION`. At handover, staff confirm `COLLECTED` and the system records who handed over the items and when.
3. **Payment is independent:** every POS sale must still be fully settled before it is committed as a sale. Phase 1 continues to exclude partial payment, customer credit and outstanding balances. A paid sale awaiting collection is not an unpaid sale.
4. **Stock is deducted once:** decrement the relevant variant's on-hand inventory and write the corresponding stock movement atomically when the fully paid POS sale is recorded. Changing fulfilment status to ready or collected must never deduct stock again.
5. **Cancellation and refunds:** do not erase the sale, payment or stock movement. A cancellation after sale completion requires an explicit authorized cancellation/return decision and a separately recorded refund where applicable. Restock only after physical inspection and approval, using a reversing/return stock movement. Never infer that a cancelled fulfilment automatically restores stock or refunds money.
6. **Authorization and audit:** only authorized staff may change fulfilment status. Record actor, timestamp and reason for cancellation or exceptional status changes. Cashiers may confirm ordinary handover if permitted by the role policy; exceptions require manager or Owner/Admin authority.

Home delivery is deferred from the initial POS release. The initial workflow covers immediate handover at the shop and later customer collection at the shop. Partial fulfilment of individual lines is also deferred unless a later workflow decision explicitly requires it.

**Confirmed initial policy:** support immediate collection and fully paid collect-later sales; keep payment and fulfilment statuses separate; deduct stock once at sale recording; defer home delivery and partial payment/credit. No Java implementation, SQL migration or UI is included in this document update.

### 10.8 Shared inventory lifecycle for online orders and POS

Online checkout and physical POS sales must use one shared, variant-level inventory source of truth. The inventory balance is scoped by inventory location and product variant, as defined in Sections 10.1 and 9.5–9.6.

#### Inventory quantity meanings

- `on_hand_quantity` is the physical quantity currently held in sellable shop inventory, including units temporarily reserved for unpaid online checkout.
- `reserved_quantity` is the subset of on-hand units held for active online checkout/payment attempts.
- `available_quantity = on_hand_quantity - reserved_quantity`.
- All quantities must remain non-negative, and reserved quantity must never exceed on-hand quantity.

#### Required stock transitions

| Event | on_hand_quantity | reserved_quantity | available quantity |
|---|---:|---:|---:|
| Create a valid online checkout reservation | No change | Increase | Decrease |
| Confirm successful online payment | Decrease | Decrease by the same quantity | No further change |
| Safely release failed, cancelled or expired online reservation | No change | Decrease | Increase |
| Complete a fully paid POS sale | Decrease | No change | Decrease |
| Pack, mark ready, dispatch or hand over an already-paid online order | No change | No change | No change |
| Approve a physically inspected return for resale | Increase by approved resale quantity | No change | Increase |

1. **Online reservation:** lock or atomically update the relevant location/variant inventory row. Reserve only when available quantity is sufficient. Increase reserved quantity and write an idempotent reservation movement in the same database transaction. Do not reduce on-hand quantity at reservation time in this target model.
2. **Online payment success:** after verified provider payment, atomically decrease both on-hand and reserved quantities by the reserved amount, and write the corresponding fulfilment/sale movement. This converts the held units into sold units; it must not deduct the stock a second time while leaving the reservation in place. Payment confirmation and this conversion must be idempotent.
3. **Payment failure, cancellation or expiry:** release only the reserved quantity after the payment provider state is safely established as not paid. Do not increase on-hand quantity, because it was not reduced when the reservation was created. Write one idempotent reservation-release movement.
4. **Uncertain payment state:** preserve the existing Razorpay fail-closed behaviour. If Razorpay is unavailable, the attempt is unsettled, or payment state cannot be established, keep the reservation held for reconciliation. Never release stock merely because a timeout elapsed. Add operational monitoring and a safe reconciliation path for reservations that remain held.
5. **POS sale:** lock inventory rows in a deterministic order and permit sale only when on-hand minus reserved is sufficient. On successful, fully paid sale completion, decrease on-hand quantity and write the POS sale movement atomically with the sale and tender records. POS must never consume units reserved for online checkout.
6. **Fulfilment:** packing, preparation, ready-for-collection, dispatch, delivery and customer handover are fulfilment events, not additional stock deductions. The inventory deduction/conversion occurs once at the appropriate sale/payment commitment transition.
7. **Returns:** refund processing and inventory restocking are separate decisions. Increase on-hand quantity only for the quantity physically received, inspected and explicitly approved for resale. Record the return movement and approval actor; damaged or non-resalable units must not be returned to sellable stock.

#### Concurrency, ledger and integrity

- Every inventory transition must update the inventory summary and append its stock movement in the same database transaction.
- Use row locks or guarded atomic updates, stable lock ordering for multi-variant operations, and idempotency keys/reference constraints to prevent duplicate reservation, release, payment conversion, POS sale or return movements.
- Validate available quantity on the backend at the moment of reservation or POS completion; storefront/cart quantities are informational and are never authoritative.
- Keep an auditable reference to the originating online order/order item or POS sale/sale item, the location and variant, quantity deltas, resulting balances, actor/system source, timestamp and reason.
- Do not allow ordinary product or variant edit endpoints to overwrite inventory balances. Corrections must use authorized, reasoned stock-adjustment movements.
- If a payment callback or retry is received more than once, return the prior result and do not repeat inventory or ledger changes.

#### Migration and cutover from the current implementation

The existing online checkout currently subtracts reserved quantities directly from `products.stockQuantity` and restores them on safe reservation release. That legacy field therefore behaves like available stock while reservations are active; it is not equivalent to the target model's physical on-hand quantity.

Do not switch the source of truth by copying `products.stockQuantity` directly into `on_hand_quantity`. Before cutover:

1. Back up the database and verify a restore in a non-production environment.
2. Freeze or tightly control stock-changing operations for the cutover window.
3. Count physical stock by the final product variant and inventory location.
4. Identify and reconcile every active online reservation, pending payment, paid order and unresolved Razorpay attempt.
5. Derive and validate opening on-hand and reserved balances from the physical count and verified reservation/order state. Record the reconciliation snapshot and opening movements with a traceable reference.
6. Implement and test the new reservation, payment-conversion, safe-release and POS deduction transitions together. Preserve the fail-closed Razorpay scheduler behaviour throughout rollout.
7. Run staging tests for concurrent online checkout and POS sales, duplicate payment callbacks, provider outages, reservation expiry, cancellation, retries, returns and rollback.
8. Reconcile inventory totals, reservation totals, stock movement ledger and order/payment records before enabling the new inventory source of truth.
9. Retain a documented rollback point and compatibility plan. Do not remove or stop maintaining the legacy stock field until every application path has been migrated and verified.

**Confirmed Phase 1 policy:** website orders and POS sales share one variant/location inventory system; online checkout reserves available stock; verified payment converts the reservation into a stock deduction; safe failure/expiry releases only the reservation; POS deducts unreserved stock immediately on fully paid sale completion; and approved returns restock only after inspection. This is a target design, not a claim that the current implementation already behaves this way. No application code, SQL migration, database record or production configuration is changed by this section.

### 10.9 SKU and barcode strategy

Every sellable product variant must have an internal NMK SKU. The SKU is the primary human-readable stock identifier used by POS, inventory operations, stock movements and reporting.

1. **SKU generation:** Generate SKUs within NMK using a controlled, collision-safe sequence (for example, `NMK-000001`). Enforce global uniqueness across the catalogue. Do not derive the canonical SKU solely from product name, colour, size or other mutable attributes; changing a product label or variant attribute must not change its SKU.
2. **Required per variant:** Every active sellable variant, including a product with no selectable options (its default variant), must have one SKU before it can be sold through POS or online checkout.
3. **Barcode is optional:** Store a manufacturer barcode when one is available and verified. A variant can be sold without a barcode. Do not assume every supplier barcode is present, valid or unique.
4. **Barcode uniqueness and normalization:** Normalize barcode values consistently before comparison and storage. Enforce uniqueness for non-empty normalized barcode values across the catalogue. Keep missing barcodes as NULL rather than empty strings. Reject duplicate or invalid barcode assignments instead of silently assigning them to another variant.
5. **POS product lookup:** Support scanning a barcode as keyboard input. Also allow staff to find a variant by exact SKU and search by product name or variant attributes (such as colour and size). Barcode scanning must not be the only way to find or sell an item.
6. **NMK-generated barcodes:** The data model may support an NMK-generated barcode later if operationally required, but do not assume that one is required for the initial release. The internal SKU remains mandatory regardless.
7. **Controlled legacy assignment:** During migration, review the existing product catalogue and map each sellable variant to a stable SKU. Identify and resolve duplicate or malformed existing barcode values before enforcing uniqueness. Do not blindly generate variants or identifiers without reconciling the catalogue and existing online order references.
8. **Audit and immutability:** Do not silently change or reuse an SKU after sales or stock movements reference it. Any exceptional correction or barcode reassignment must be authorized and auditable, preserving historical transaction snapshots.

This is a logical design rule only. It does not authorize immediate schema migration, automatic SKU assignment, barcode printing, or application-code changes. Those require a separately reviewed implementation and migration plan.

### 10.10 Product and variant pricing

Use parent-product pricing as the default and allow an explicit selling-price override on an individual variant. This supports a simple catalogue while allowing variants with genuinely different prices.

1. **Parent default:** The parent product stores the default selling price used by its variants unless a variant has its own override.
2. **Variant override:** A variant may define an explicit selling-price override. When present, that override is the effective selling price for that variant; otherwise, use the current parent-product default.
3. **Single effective price:** POS and online product flows must resolve and display the effective price for the selected variant. The customer must see the selected variant's price before adding it to the cart or completing checkout.
4. **Server-side authority:** The backend must resolve and validate the applicable price when creating a sale or order. Never trust a price supplied by the browser or POS client as authoritative. Any authorized discount must be represented separately and validated against the staff member's permissions and configured limits.
5. **Historical sale snapshot:** Each completed sale item must preserve the actual unit price, discount and applicable price/tax calculation inputs or snapshots used at the time of sale. Later parent or variant price changes must never alter existing invoices, reports or return calculations.
6. **Price changes:** Updating a parent default may affect variants that inherit the parent price, but must not overwrite explicit variant overrides. Updating a variant override must affect only that variant. Record who made a price change and when; apply the appropriate authorization and audit rules.
7. **Money representation:** Store monetary values using fixed-precision decimal types, not floating-point types. Define currency consistently as INR for the initial NMK deployment. GST is not to be represented as collected while NMK is not GST-registered; invoice/tax wording remains subject to professional confirmation.
8. **Migration and compatibility:** Before implementation, audit existing product price fields and frontend/backend payloads. Preserve current customer-facing prices during migration, resolve invalid or inconsistent values, and ensure online orders and POS use the same effective-price rule. Do not silently reprice existing carts or confirmed orders.

This is a logical design decision only. It does not authorize immediate schema migration, price updates, or application-code changes. Implementation requires a separately reviewed migration and compatibility plan.

### 10.11 POS discounts and approval controls

The POS must support controlled discounts at both sale-item and whole-bill level. Discounts are separate from the catalogue selling price and must never rewrite the parent product price, variant override or historical sale price.

1. **Discount scope:** Support an item-level discount on an individual sale item and a bill-level discount on the eligible sale subtotal. Record the scope and the sale item reference where applicable.
2. **Discount types:** Support a fixed INR amount and a percentage discount. Validate that the entered value is positive and that the resulting discount does not exceed the eligible amount. The final payable amount must never become negative.
3. **Discount calculation:** Calculate discounts on the backend using the effective variant selling price and quantity. Define and consistently apply currency rounding. Prevent unintended stacking or duplicate application of discounts; the configured policy must determine whether bill-level discounts apply before or after item-level discounts.
4. **Owner-configured limits:** Store discount permissions and limits as configurable business settings, with scope for role, discount type and applicable amount or percentage. Do not hardcode a cashier percentage or rupee threshold in application code.
5. **Cashier permissions:** A cashier may apply discounts only within the currently configured cashier limit. The backend must enforce the limit for every sale; hiding or disabling a frontend control is not sufficient authorization.
6. **Manager approval:** A discount above the cashier's permitted limit requires approval by an authorized manager, within the manager's own configured authority. Capture the approving staff member and approval time. An approval must be explicitly recorded before the discounted sale can be completed.
7. **Owner authority:** The owner administrator can configure discount limits and perform authorized overrides. Owner overrides must also be recorded and auditable; they must not bypass calculation, amount validation or transaction integrity rules.
8. **Audit trail:** For every applied, changed, rejected or approved discount, retain the actor, approver where applicable, timestamp, discount scope and type, entered value, original eligible amount, calculated discount and resulting amount. Preserve the reason or note when required by the configured approval policy.
9. **Completed-sale integrity:** Once a sale is completed, its item prices, discounts, approvals and final totals are immutable historical transaction data. Corrections must use the authorized void, return, refund or adjustment workflow; do not edit the original completed sale or change catalogue prices to represent a discount.
10. **Returns and reporting:** Preserve enough item-level and bill-level discount allocation detail to calculate a fair return value and produce accurate net-sales and discount reports. A bill-level discount must be allocated consistently across sale items for return calculations, without changing the original invoice snapshot.
11. **Server-side totals:** The backend must calculate and persist line totals, discount totals and final payable amount from trusted catalogue prices and authorized discount inputs. Recalculate and validate the total atomically when completing the sale; do not accept client-calculated totals as authoritative.

This is a logical design decision only. It does not authorize immediate schema migration, discount-setting changes, or application-code changes. Implementation requires a separately reviewed database and API design, including the exact approval workflow and rounding/allocation rules.

### 10.12 Returns, refunds and exchanges

Returns, refunds and exchanges must be recorded as controlled transactions linked to the original completed POS sale. Never delete or rewrite the original sale, sale items, invoice or payment records to represent a return.

1. **Original sale reference:** Every POS return must reference the original completed sale and the specific sale item(s) being returned. Validate that the returned quantity does not exceed the quantity sold less quantities already returned. Prevent duplicate or concurrent returns from exceeding the remaining returnable quantity.
2. **Return reasons and condition:** Record a mandatory return reason, returned quantity, inspection result and receiving staff member. Capture supporting notes where required by the configured policy.
3. **Approval controls:** Require manager authorization for ordinary returns under the initial policy. Permit owner-admin override with a recorded reason. Enforce permissions on the backend and preserve the approver identity and timestamp.
4. **Separate return and refund states:** A return decision, physical receipt/inspection, stock disposition and money refund are distinct facts. Track them separately so that receiving an item does not falsely imply that money has been refunded, and initiating a refund does not falsely imply that it has settled.
5. **Refund processing:** Where supported, refund through the original tender/payment method. Record the refund amount, method, provider or transaction reference, initiating staff member, approval and timestamps. Cash refunds require authorized approval and a recorded cash-out reference. Do not mark a refund as successful until the actual cash disbursement or payment-provider confirmation is verified. Support pending, failed and completed refund outcomes without losing retry history.
6. **Refund limits:** The cumulative amount refunded for a sale must not exceed the eligible amount paid, adjusted for prior completed refunds and the applicable item/bill discount allocation. Enforce this atomically on the backend to prevent duplicate or concurrent over-refunds.
7. **Exchange representation:** Model an exchange as a return of the original item plus a separate new sale for the replacement item(s), linked by a common exchange reference. Collect any additional amount due through the normal POS tender flow. If the replacement is cheaper, record any eligible difference refund through the normal refund workflow. Do not overwrite the original sale or silently net transactions without an auditable record.
8. **Stock inspection and disposition:** Returning an item must not automatically increase sellable inventory. After physical inspection, an authorized staff member must classify it as resaleable, damaged/defective, or otherwise non-sellable. Only approved resaleable quantities may be restocked into the appropriate location. Damaged or non-sellable items must be recorded separately and excluded from available stock.
9. **Stock ledger:** Every approved restock or non-sellable disposition must create an auditable inventory movement linked to the return and return item. Record location, variant, quantity, disposition, actor, reason and resulting balance. Do not create a stock movement merely because a refund was requested or completed.
10. **Audit and immutable history:** Preserve original sale/payment records and append return, inspection, approval, refund and inventory events. Record who performed each action and when. Corrections must use authorized compensating records rather than deleting historical transactions.
11. **Eligibility policy:** Keep return window, eligible product categories, condition requirements and any exceptions configurable by the owner rather than hardcoding a universal period into the initial schema. Configure and operate the policy in accordance with applicable consumer-law obligations and professional advice.
12. **Online order boundary:** This section defines the initial physical POS return/exchange workflow. Online order cancellation, return pickup, shipping and Razorpay refund integration must be separately mapped to the existing online order/payment lifecycle before implementation; do not assume POS refund logic automatically covers online orders.

This is a logical design decision only. It does not authorize immediate schema migration, refund execution, inventory changes or application-code changes. Implementation requires separately reviewed return/refund state transitions, payment-provider capabilities, authorization rules and concurrency/idempotency tests.

### 10.13 Cash register, cashier shifts and daily closing

The POS must record cashier shifts and reconcile physical cash independently from non-cash tenders. A shift belongs to one cash register and the staff member responsible for it. The initial deployment may use one register, but the data model must support multiple registers at a location.

1. **Register and location:** Each register belongs to an inventory/business location and has a stable identifier. A register may have only one open shift at a time. Enforce this invariant transactionally in the database/backend, not only in the user interface.
2. **Shift opening:** An authorized cashier or manager opens a shift by recording the opening timestamp, register, responsible staff member and counted opening cash float. Preserve who opened it and any handover/approval details.
3. **Shift ownership:** Attribute every POS sale, tender/payment, refund cash-out and cash movement to the relevant active shift and register. Define an authorized manager handover procedure; do not allow two active shifts to operate the same register concurrently.
4. **Cash sales and expected drawer balance:** Include only cash actually received through the shift's cash tenders in the expected physical drawer balance. UPI, card and other non-cash tenders must be recorded separately and must never be added to expected drawer cash.
5. **Cash movements:** Record each non-sale cash-in or cash-out (for example, adding float or an approved petty-cash withdrawal) as a separate movement with amount, direction, reason, actor, timestamp and approval where required. Do not edit the opening float or completed movement records to force a reconciliation.
6. **Expected cash calculation:** Calculate expected closing cash from the opening float plus cash received, plus authorized cash-in, less authorized cash-out and cash refunds paid from the drawer. Derive the result from recorded transactions; do not trust a manually entered expected amount.
7. **Shift closing:** The responsible cashier enters the actual counted closing cash and submits the shift for closing/review. Persist the count, expected amount, difference, submitter and timestamps. Keep the calculation reproducible from the underlying tender and cash-movement records.
8. **Discrepancies:** Record shortage or excess as a discrepancy with its amount and, where available, a reason or explanatory note. Never silently change the expected amount, erase a discrepancy or create an unexplained balancing movement. A correction must be a separately authorized and auditable transaction.
9. **Manager review:** Use an owner-configured discrepancy threshold and approval policy. Require manager review when the configured threshold or other policy condition is met. Record reviewer, decision, reason and time. The owner may configure the policy and perform authorized overrides, with an audit record.
10. **Non-cash reconciliation:** Track UPI and card tender totals separately by method, provider/reference and shift. Provide the information needed to compare POS-recorded collections with provider/bank settlement records. A POS tender marked accepted is not proof that the provider has settled the funds; preserve pending, failed, reversed and confirmed states where applicable.
11. **Shift finalization and audit:** Keep shift lifecycle states and prevent a finalized shift from being silently reopened or edited. Any authorized reopening or post-close correction must record the actor, reason, approval and before/after references. Preserve opening, sales, tender, movement, closing, discrepancy and review history.
12. **Failure and concurrency safety:** Make shift opening, sale/tender association, cash movement and closing operations transactional and idempotent where retries are possible. Prevent sales from being assigned to a closed shift and prevent duplicate cash movements or duplicate closing submissions.
13. **Reports:** Provide shift-level and daily summaries showing opening float, cash sales, non-cash sales by method, cash-in/out, cash refunds, expected cash, counted cash, discrepancy and review status. Daily totals must be derived from completed transactions and clearly distinguish sales from payment-provider settlement.

This is a logical design decision only. It does not authorize immediate schema migration, cash drawer hardware integration, provider settlement automation or application-code changes. Implementation requires separately reviewed register/shift tables, tender states, cash movement types, permissions and reconciliation tests.

### 10.14 Supplier and purchase management

The POS back office must maintain supplier records and a traceable purchase/receiving workflow for frames, lenses and accessories. Purchase documents, physical receipt, supplier settlement and inventory movements are separate events.

1. **Supplier records:** Maintain a supplier record with supplier name, contact person, phone, address and internal notes. Supplier GSTIN and other tax identifiers are optional fields where applicable; NMK is currently not GST-registered, so the initial workflow must not require NMK GSTIN or assume GST is collected. Tax treatment and supplier-document handling must be confirmed with an accountant/adviser.
2. **Purchase document:** Record a unique purchase reference/number, supplier, document date, created-by staff member, line items, quantities, unit purchase costs and applicable additional charges such as freight. Preserve supplier invoice/reference details and document totals as historical snapshots.
3. **Variant-level lines:** Each purchase line must reference the exact product variant/SKU and record ordered quantity, unit cost and any line-level adjustment. Preserve product/SKU descriptions as snapshots so later catalogue edits do not rewrite purchase history.
4. **Purchase lifecycle:** Distinguish draft, ordered/confirmed, partially received, fully received and cancelled states as appropriate. A purchase order or draft purchase entry must not increase on-hand or available inventory.
5. **Physical goods receipt:** Record one or more receipt events against the purchase. Only quantities physically received, checked and accepted by an authorized staff member may increase sellable on-hand inventory. Support partial receipts and prevent accepted quantities from exceeding the outstanding ordered quantity unless an authorized, documented over-receipt exception is allowed.
6. **Damaged, missing and rejected goods:** Record received, accepted, damaged/rejected and missing quantities separately, with reason and notes. Damaged or rejected items must not increase sellable inventory. If the business later chooses to track them physically, use a separate non-sellable/quarantine disposition rather than treating them as available stock.
7. **Inventory ledger:** Every accepted receipt creates an idempotent stock movement linked to the purchase and receipt line, location, variant, quantity, actor and timestamp. Prevent duplicate receipt submissions from increasing stock twice. Do not directly overwrite inventory balances from purchase screens.
8. **Purchase cost and expenses:** Preserve actual purchase unit cost and additional attributable costs as purchase history. Keep purchase cost separate from selling price. Define the allocation of freight or other landed costs consistently before implementation; do not silently distribute costs differently across reports.
9. **Inventory valuation:** Support weighted-average cost reporting as the initial valuation approach, calculated from accepted inventory receipts and the existing inventory value/balance. Keep the underlying receipt and adjustment history so the valuation can be audited and recalculated. Returns, write-offs, opening balances and corrections must follow explicit valuation rules approved before implementation; do not infer cost from current selling price.
10. **Supplier settlement:** Track supplier payment/settlement separately from purchase creation and goods receipt. The initial design may record payment date, amount, method and supplier reference, but must not imply that a purchase is paid merely because goods were received. Any future payable/credit workflow requires separately defined approval and reconciliation rules.
11. **Purchase returns and supplier credits:** Link each supplier return to the original purchase and accepted receipt quantities. Reduce inventory only when goods are actually dispatched/handed back and the return movement is recorded. Track supplier refund, credit note or replacement separately from physical stock movement; do not assume a supplier credit automatically means money was received.
12. **Permissions and audit:** Cashiers cannot create, edit, receive or return purchases. Managers may create purchase records and record physical receipts within assigned permissions. Owner-admin controls supplier settings and sensitive adjustments/overrides. Preserve actor, approver, timestamps, reasons and before/after references for changes; do not delete purchase or receipt history referenced by stock movements.
13. **Reports:** Provide supplier purchase history, ordered versus received quantities, damaged/rejected/missing quantities, accepted receipt value, purchase-cost trends, supplier settlement records and stock valuation summaries. Clearly distinguish ordered value, received value and paid/settled amount.

This is a logical design decision only. It does not authorize immediate schema migration, supplier onboarding, purchase payment execution, inventory adjustment or application-code changes. Implementation requires separately reviewed purchase/receipt/return tables, cost allocation and weighted-average valuation rules, permissions, and idempotent inventory transaction tests.

### 10.15 Stock adjustments and physical stock counting

Inventory balances must not be directly edited by ordinary product or POS screens. Stock corrections must use an auditable adjustment workflow, and physical stock counts must be reconciled against the shared variant/location inventory model without disturbing active online reservations.

1. **Adjustment reasons:** Support controlled reason codes including damaged, lost/missing, found/excess, supplier correction, data-entry correction and other. Require explanatory notes for "other" and for configured exceptional cases. Record the affected variant, location, quantity and direction.
2. **Physical count sessions:** Allow authorized staff to open a count session for the full location, selected categories or selected variants. Record session scope, start time, counter(s), location and status. A count session is a snapshot/work process; opening or entering counts must not change live inventory.
3. **Count quantities:** Record the physically counted quantity per variant and location, count time, counter and any notes. Support recounts and independent verification for discrepancies. Preserve the original count entries and revisions rather than silently replacing them.
4. **System comparison:** At reconciliation, compare the physical count with the appropriate system quantity using a documented cutoff/snapshot. The comparison must account for stock movements occurring during the count window, such as sales, receipts, returns and transfers if later supported. Do not treat a stale opening quantity as current without reconciling intervening movements.
5. **Online reservations:** Distinguish physical on-hand quantity from reserved quantity and available quantity. A physical count must not accidentally clear or consume active online reservations. Reconcile pending orders, payment attempts and reservations before applying a count-based correction. Preserve the fail-closed rule for uncertain payment state.
6. **Proposed adjustment:** Generate a proposed adjustment for the verified difference, with expected system balance, counted physical balance, movement cutoff, calculated delta and reason. A count result alone must not automatically post an inventory movement.
7. **Approval:** Require manager review and approval for ordinary adjustments. Apply owner-configured thresholds and exceptional-case rules for owner-admin approval; do not hardcode a universal rupee or quantity threshold. Cashiers may participate in counting if authorized but cannot independently approve or post stock adjustments.
8. **Posting and concurrency:** Post an approved adjustment as an atomic stock movement against the current inventory balance, validating that the count basis is still valid and that intervening movements have been reconciled. If the balance changed after counting, require recalculation/recount or an explicit approved reconciliation; never blindly overwrite the latest balance with the counted number.
9. **Ledger and audit:** Every posted adjustment must create an append-only stock movement linked to the count session or adjustment request. Record previous/resulting balances, on-hand and reserved deltas as applicable, variant, location, reason, notes, actor, approver, timestamps and idempotency reference. Do not delete or rewrite historical stock movements.
10. **Damaged and non-sellable stock:** A count may identify goods that physically exist but are not sellable. Record their disposition separately from available stock. Moving items into or out of quarantine/non-sellable status must be an authorized, traceable inventory movement and must not increase sellable availability unless inspection approves it.
11. **Reports and closure:** Provide count-session reports showing system basis, physical count, intervening movements, variance, proposed adjustment, approval status and posted result. Close a session only after every discrepancy is resolved, explicitly accepted as unresolved under owner policy, or assigned a documented follow-up.
12. **Direct-edit prohibition:** Do not expose a general-purpose stock quantity field that silently overwrites inventory. All opening balances, corrections, write-offs and count variances must use controlled stock movements with reason, permissions and audit history.

This is a logical design decision only. It does not authorize immediate stock changes, count execution, schema migration or application-code changes. Implementation requires separately reviewed count-session/adjustment tables, cutoff and movement-reconciliation rules, authorization limits and concurrent online/POS test coverage.

### 10.16 Product catalogue lifecycle

The product catalogue must support a controlled lifecycle that separates whether a product is sellable, visible online, or retained only for historical and operational reference. Removing a product from the active catalogue must never erase transaction or inventory history.

1. **Active:** The product and its eligible variants may be sold through the channels enabled for them. Product-level and variant-level availability must be validated by the backend.
2. **Inactive:** Temporarily prevent new sales through the configured channels while retaining the product, variants, stock, reservations and historical records. Reactivation requires an authorized user and must revalidate stock and other operational constraints.
3. **Discontinued:** Mark a product as no longer offered for new sales. Preserve its records for existing orders, returns, warranty/service obligations, purchase history, stock reconciliation and reporting. Resolve or explicitly handle active reservations and outstanding operational commitments before discontinuation.
4. **Archived:** Hide the product from normal active catalogue and operational selection screens while retaining it in historical views and authorized administrative searches. Archiving is not deletion and must not bypass outstanding stock, reservation, return, purchase or service obligations.
5. **Channel availability:** Store online visibility separately from physical POS sellability. A shop-only product may be unavailable on the customer website but remain sellable through POS. Channel visibility must not override lifecycle status, variant availability, stock checks or authorization rules.
6. **Stock and reservation safeguards:** Do not archive or fully discontinue a product while it has unresolved sellable stock, active online reservations, pending orders, open purchase receipts, returns or other unresolved operational references. Require the relevant stock to be sold, transferred, returned, written off or otherwise dispositioned through the approved workflow. Never clear reservations merely to permit a status change.
7. **Historical snapshots and references:** Completed sales, invoices, online orders, purchase records, returns, stock movements and reports must remain readable with their original product name, SKU/barcode, variant attributes and transaction-time price/cost snapshots. Catalogue edits must not rewrite historical snapshots.
8. **Deletion policy:** Permanent deletion is exceptional and may be considered only when a product and all its variants have never been referenced by sales/orders, payments, purchases/receipts, inventory movements, reservations, returns, prescriptions, warranty/service records or other retained business history, and have no stock balance. Enforce this on the backend and database relationships; do not rely on a hidden or disabled UI button. Require owner-admin authorization and an audit record. Where any historical reference exists, retain and archive instead of deleting.
9. **Status transition controls:** Define allowed transitions and validate them server-side. Record previous and new status, channel visibility changes, actor, timestamp, reason and any approval. Prevent unauthorized staff from reactivating, discontinuing, archiving or deleting catalogue records.
10. **Search and reporting:** Normal product selection should default to active, channel-eligible products. Authorized staff must still be able to search inactive, discontinued and archived records when handling returns, warranty/service work, historical sales, stock counts and reports. Reports must not silently exclude historical transactions because a product is no longer active.
11. **Variant consistency:** Apply lifecycle and channel rules consistently to parent products and individual sellable variants. A parent product must not be offered through a channel if it has no eligible active variants; variant-level disablement must not delete the variant or its historical references.

This is a logical design decision only. It does not authorize catalogue status changes, permanent deletion, schema migration or application-code changes. Implementation requires separately reviewed product/variant status fields, channel-availability rules, transition permissions, historical foreign-key policies and tests for outstanding reservations and transaction references.

### 10.17 Barcode labels and scanning

The POS must support fast, reliable variant identification through barcode scanning while retaining NMK's own SKU as the stable internal identifier. A barcode is a lookup mechanism only; it does not establish stock availability or authorize a sale.

1. **Permanent NMK SKU:** Assign every sellable product variant a unique, stable NMK-generated SKU. Do not derive the SKU from editable product names, colour, size or other mutable attributes. A SKU must not be reassigned to another variant, including after discontinuation or archival.
2. **Barcode sources:** Support a supplier/manufacturer barcode where one is present and verified. If a variant has no usable supplier barcode, generate an NMK barcode value and encode it in a standard printable barcode format selected during implementation. Keep the barcode value distinct from the internal SKU, even if the printed label displays both.
3. **Variant-level mapping:** Resolve each accepted barcode to exactly one sellable variant. A parent product with multiple colours or sizes must not resolve ambiguously to the parent alone. Store the variant and product relationship and preserve historical barcode/SKU snapshots on transaction records where required.
4. **Uniqueness and normalization:** Normalize barcode input consistently, preserving meaningful leading zeroes and avoiding numeric conversion that can alter the value. Enforce uniqueness for active barcode mappings across the catalogue. Reject duplicate assignments and ambiguous matches. Define accepted barcode symbologies and check-digit validation before implementation.
5. **Multiple barcode aliases:** Allow more than one barcode to map to a variant only where there is a documented need, such as supplier and NMK labels. Each alias must remain unique, have a source/type and lifecycle state, and be auditable. Retired aliases must not be silently reassigned to a different variant if historical transactions may reference them.
6. **POS scan workflow:** A scan should look up the exact variant and return its product name, variant attributes, SKU, applicable price and channel eligibility. Before adding or completing a sale, the backend must revalidate that the product/variant is active for POS and that sufficient available inventory exists. Do not trust cached stock or a barcode match as stock authorization.
7. **Unknown or ambiguous scans:** If a barcode is unknown, duplicated, malformed or mapped to an unavailable/disabled variant, do not guess or automatically add a similar product. Show a clear error and allow an authorized staff member to search the catalogue manually or resolve the mapping through the controlled administration workflow.
8. **Label contents and printing:** Provide printable labels containing the barcode, human-readable SKU and enough product/variant description to distinguish similar items (for example, model, colour and size). Label layout, paper size, printer selection and optional price display should be configurable. Do not encode customer data, prescription information or other personal information in product barcodes.
9. **Scanner compatibility:** The initial POS should support common USB and Bluetooth scanners that operate as keyboard input and submit a scan using a configured suffix such as Enter. Avoid dependence on a particular scanner brand or proprietary SDK. The POS input workflow must handle rapid input, focus management and accidental duplicate scans safely.
10. **Printing and reprinting permissions:** Permit authorized staff to print or reprint labels. Record the actor, variant, barcode, quantity and timestamp where label printing history is operationally significant. Reprinting must not generate a new barcode or SKU for an existing variant.
11. **Barcode lifecycle:** When a variant is inactive, discontinued or archived, retain its barcode mapping for historical lookup and controlled returns/service workflows, but prevent ordinary new POS sales according to the product lifecycle rules. Do not reuse its barcode for a different variant.
12. **Fallback and resilience:** Permit manual SKU or product search when a scanner or barcode label is unavailable. Barcode lookup failure must not bypass server-side price, status, permission or inventory checks. Scanner input should be treated as untrusted input and validated like any other request.

This is a logical design decision only. It does not authorize purchase of scanner/printer hardware, barcode assignment to live products, label printing, schema migration or application-code changes. Implementation requires a separately reviewed barcode/alias data model, accepted symbologies and check-digit rules, label dimensions/printer tests, and end-to-end variant lookup and stock-validation tests.

### 10.18 Customer records and prescription-data privacy

NMK must maintain a shared customer identity across online orders and physical POS sales while minimizing the personal and prescription information exposed to each staff role. Prescription records and images require stronger access controls than ordinary customer contact details.

1. **Optional customer registration:** Permit walk-in POS sales without creating or selecting a saved customer profile. Name, phone and email should be optional unless a specific operational or legal requirement makes a field necessary for a particular transaction. Do not invent customer details to satisfy validation.
2. **Customer lookup and duplicate handling:** Normalize phone numbers consistently for lookup and show possible matching profiles to authorized staff. Do not automatically merge profiles based only on a matching phone number. Require an authorized, auditable merge/correction workflow with safeguards for linked orders, sales, prescriptions and addresses.
3. **Shared transaction history:** Where a reliable customer link exists, authorized staff may view relevant online orders and POS purchase history from the shared customer profile. Preserve transaction-time customer and address snapshots on invoices/orders; changing a customer profile must not rewrite historical documents.
4. **Separate prescription records:** Store prescription information as a distinct record linked to the relevant customer and to the applicable online order item or POS sale item. Support the optical measurements required by NMK, including SPH, CYL, AXIS, ADD and PD where applicable, with eye/side, units, validation and an explicit representation for values that are not supplied. Do not assume every prescription has every measurement.
5. **Prescription versioning and correction:** Preserve the original submitted prescription and its source, creation time and associated transaction. Corrections or updated prescriptions must create a traceable revision with actor, timestamp and reason; do not silently overwrite the original clinical/order-time values. Clearly distinguish customer-provided values from staff-entered or professionally verified values.
6. **Private image/document storage:** Store prescription images and documents in a private Oracle Object Storage bucket/container. Store only object keys, metadata, content type, size, checksum where useful, upload time and related record references in MySQL; do not store Oracle credentials or permanent public URLs in records. Never make prescription storage public.
7. **Authorized access:** Enforce prescription permissions on backend endpoints and object access, not only by hiding frontend controls. OWNER_ADMIN may have full access subject to audit. MANAGER access should be limited to operational needs and explicit permissions. CASHIER access should be limited to the minimum customer details needed for billing and must not include unrestricted prescription image/document access. Grant prescription access only to staff roles and individuals with a legitimate business need.
8. **Short-lived access and secure transfer:** Serve prescription files through an authenticated backend authorization check and short-lived, scoped access URLs or an equivalent secure download mechanism. Avoid exposing object keys or reusable public links unnecessarily. Use HTTPS for transfer and encryption-at-rest capabilities provided by the storage service; protect credentials through server-side configuration/secrets.
9. **Access and change audit:** Record prescription creation, upload, revision, replacement, download/view authorization and deletion/retention actions where technically feasible. Include the staff identity, action, record reference, timestamp, outcome and reason for sensitive overrides. Do not log prescription image contents, access tokens or unnecessary prescription details in application logs.
10. **Customer-data minimization:** Collect only information needed for billing, order fulfilment, customer communication, prescription handling and applicable legal obligations. Restrict address and contact-detail access by role and workflow. Do not expose customer or prescription information in barcode labels, public product pages, analytics or general-purpose logs.
11. **Retention and disposal:** Define retention periods for customer records, prescriptions, images, invoices and related records before production launch, taking account of applicable legal obligations, accounting requirements, warranty/service needs and legitimate business purposes. Do not automatically delete prescription records merely because an order is old. When disposal is authorized, delete or render inaccessible both the database references and corresponding private objects, subject to legal holds and retained transaction requirements, and record the disposal action.
12. **Customer corrections and requests:** Provide an authorized workflow for correcting contact details and handling applicable customer access/correction/deletion requests. Preserve immutable transaction snapshots and legally required records; do not promise deletion where retention obligations or other lawful grounds require retention. Record the request, decision, action and reason.
13. **Backup and recovery:** Include prescription metadata and private objects in the backup/recovery plan. Protect backups with access controls and encryption, define retention and restore procedures, and test that restored database references still map to the correct private objects. A database-only backup is not sufficient to recover prescription files.
14. **Failure handling:** If storage upload, authorization or retrieval fails, do not silently save a broken public link or claim that the prescription was securely stored. Surface the failure to authorized staff, retain a clear retry/reconciliation state and avoid completing workflows that explicitly require a prescription until the required data is safely available.

This is a logical design decision only. It does not authorize creation of storage buckets, upload of live customer prescriptions, retention/deletion of existing records, schema migration or application-code changes. Before implementation, separately review the prescription schema and revision model, role/permission matrix, applicable privacy and retention obligations, Oracle Object Storage configuration, secure access mechanism, backup/recovery process and end-to-end authorization tests.

### 10.19 POS connectivity and internet failure

The initial NMK POS release will be online-first and depend on the shared Spring Boot backend and cloud MySQL database for authoritative business operations. It will not support completing sales while disconnected. This avoids conflicting stock, invoice, payment and transaction state between the physical shop and online store.

1. **Normal operation:** Require a healthy authenticated backend connection for authoritative catalogue/price refresh, stock validation, sale creation, payment recording, invoice allocation, returns/refunds, stock adjustments, purchase receiving and shift/register operations that change business records.
2. **Connection detection:** Detect backend/network failures and distinguish a confirmed server rejection from a timeout or unknown outcome. Show a clear, persistent connection status and a useful message; do not interpret a timeout as proof that a request failed to reach the server.
3. **New sales while disconnected:** Block sale completion and all inventory- or financial-state-changing operations when the backend cannot be reached. Do not issue an invoice number, deduct stock, record a completed sale or display a success state based only on local browser state.
4. **Existing cart preservation:** Preserve the in-progress cart locally across temporary network loss and page refresh where practical. Clearly mark it as an unsubmitted draft, protect it from accidental loss, and revalidate every item, variant, price, discount permission and available quantity with the backend before submission after reconnection. Local cart data is not a sale record or proof of stock reservation.
5. **Cached catalogue:** Previously loaded product descriptions and images may remain viewable as a convenience, but mark cached prices, availability and other changing information as potentially stale. Do not allow cached stock quantities or prices to authorize or finalize a sale. Refresh authoritative values before enabling completion.
6. **Payment safety and uncertain outcomes:** If a sale or payment request times out after submission, treat its outcome as unknown until the backend/provider state is checked. Use stable idempotency keys and retrieve the existing transaction state before retrying. Never create a second invoice, duplicate tender, duplicate payment request or second stock deduction merely because the client did not receive the first response.
7. **UPI/card and external payment:** Do not treat a local success screen, screenshot, verbal confirmation or unverified external payment status as collected funds. Require the supported payment provider or authorized server-side verification path to confirm the payment before completing the sale. Define cash acceptance during a total backend outage as unavailable in the initial release as well, because the sale and inventory record cannot be safely committed.
8. **Reconnection workflow:** When connectivity returns, reauthenticate if necessary, query the backend for the status of any request with an unknown outcome, refresh catalogue and prices, revalidate stock and permissions, and allow the cashier to explicitly resume or discard the preserved draft. Never automatically submit a stale cart without cashier confirmation.
9. **Authentication and sessions:** Apply normal server-side authentication and authorization to every operation. If a staff session expires during an outage, require successful reauthentication before any sensitive operation resumes. Do not treat cached role information as sufficient authorization to complete a transaction.
10. **Audit and diagnostics:** Record authoritative business events on the server when reachable, including failed validation, interrupted requests, retries and reconciliation outcomes where appropriate. Client-side diagnostics may help troubleshoot connectivity but must not be treated as the authoritative financial or inventory audit ledger. Avoid logging credentials, payment secrets or prescription/customer data unnecessarily.
11. **Operational messaging:** Clearly distinguish offline/disconnected, reconnecting, request pending, outcome unknown, rejected and completed states. Give staff a safe next action for each state; never show a completed invoice or success confirmation until the backend confirms the committed transaction.
12. **Initial-release boundary:** No local offline sales database, offline invoice allocation, queued financial transactions, deferred stock deduction or automatic synchronization is included in the first release. Do not describe the initial POS as capable of offline billing.
13. **Future offline phase:** If offline billing becomes a business requirement, design it as a separate phase with a trusted local data store, device identity, conflict-safe invoice allocation, transaction signing/idempotency, payment verification rules, stock-risk controls, queued synchronization, reconciliation and explicit handling of online orders created during the outage. Reassess the shared-inventory model before enabling it.

This is a logical design decision only. It does not authorize a local offline database, service-worker caching of sensitive data, queue implementation, deployment/network changes, schema migration or application-code changes. Implementation requires separately reviewed request idempotency and unknown-outcome recovery, secure cart persistence, session handling, payment-provider behaviour and tests for disconnection at every transaction boundary.

### 10.20 POS reports and financial reconciliation

The POS reporting model must derive totals from authoritative, auditable transaction records. Dashboard summaries are read-only projections; staff must correct underlying records only through authorized business workflows, never by editing report totals.

1. **Daily sales report:** Show transaction count, gross sales, item and bill discounts, returns, net sales and date/time range. Define each metric consistently and distinguish completed, cancelled and voided transactions.
2. **Channel reporting:** Report physical POS sales and online orders separately, with an optional combined business overview. Preserve each channel's distinct payment, fulfilment, cancellation and refund lifecycle; do not double-count online payment events or POS tenders.
3. **Payment collection report:** Break down accepted and confirmed POS tenders by cash, UPI, card and split tender, with transaction references and statuses. Report online payment collections separately by provider/method. Distinguish initiated, pending, failed, uncertain, confirmed, refunded and reversed states as applicable.
4. **Sales versus collections:** Do not equate completed sale value with funds collected or settled. Show sales, tender/payment acceptance, refunds and external bank/provider settlement as distinct measures. A POS payment recorded as accepted does not prove that UPI/card funds have reached NMK's bank account.
5. **Returns and refunds report:** Show original sale/invoice, returned items and quantities, reason, inspection/disposition, approved refund, refund method, refund status and responsible staff. Separate requested, approved, processed and confirmed refunds. Never erase or silently rewrite the original sale.
6. **Cash register and shift report:** For each register/shift, show opening float, cash sales received, cash-in, cash-out, cash refunds, expected closing cash, counted cash and variance. Preserve the staff-entered count and record shortage/excess for review; never silently adjust the count or opening float to force a match. UPI/card reconciliation remains separate from physical cash counting.
7. **Staff activity report:** Show permitted measures such as completed sales, discounts, voids, returns/refunds, stock adjustments and approvals by staff member and shift. Enforce role-based access; do not expose unnecessary customer or prescription information.
8. **Product and variant sales report:** Show quantities sold, revenue, discounts, returns and ranking by product/variant for a selected period. Use transaction-time product, SKU, variant and price snapshots so later catalogue edits do not rewrite historical results.
9. **Inventory report:** Show on-hand, reserved and available quantities, configured reorder levels, low-stock items and stock movement history. Reconcile summary balances against the append-only stock ledger; never infer stock solely from sales totals.
10. **Purchases report:** Show supplier, purchase records, ordered and received quantities, accepted/rejected/damaged quantities, purchase cost and outstanding receipts. Keep supplier settlement status separate from goods receipt and inventory recognition.
11. **Outstanding and exception report:** Surface pending or uncertain payments, unresolved payment outcomes, failed/partial operational workflows, unreconciled tenders, open shifts, refund exceptions and inventory reconciliation discrepancies. Provide references and safe next actions; do not auto-resolve uncertain financial states by assumption.
12. **Financial terminology and profit boundary:** Do not label sales less purchases as profit. A reliable gross-profit or net-profit report requires a confirmed cost-of-goods-sold method, inventory valuation, treatment of discounts/returns and relevant operating expenses. Keep profit reporting out of the initial release until those inputs and accounting rules are reliable.
13. **Tax and invoice reporting:** NMK is currently not GST-registered. Do not represent GST as collected or produce GST-registered business reports on that assumption. Keep the model extensible and confirm applicable invoice and reporting obligations with the business's accountant/adviser before launch or any registration-status change.
14. **Role-based visibility and exports:** CASHIER may view their own shift summary and permitted basic sales information. MANAGER may access operational reports and review reconciliations within assigned permissions. OWNER_ADMIN may access all reports, configuration and exports. Enforce authorization on backend endpoints, not only in the UI. Audit sensitive exports with actor, report type, filters/time range and timestamp; minimize personal data in exported reports.
15. **Report integrity and reproducibility:** Define timezone, date boundaries, status inclusion, rounding and refund attribution consistently. Reports must be derived from persisted transaction, tender, refund, shift, purchase and stock-ledger records. Store or expose enough filter and generation metadata to explain a reported total. Corrections must use audited transactions rather than direct edits to historical records.

This is a logical reporting and reconciliation decision only. It does not authorize report screens, exports, accounting integrations, schema migrations or application-code changes. Before implementation, separately review report definitions with the accountant/business owner, define settlement import/reconciliation sources for UPI and card providers, validate role-level access, and test report totals against source transactions including split tenders, cancellations, returns, refunds, voids, open shifts and date-boundary cases.

### 10.21 Audit logs, staff authentication and security controls

The POS and related administrative workflows must identify the individual staff member performing each action and enforce authorization in the backend. Frontend visibility controls are usability features only and are never a substitute for server-side authorization.

1. **Individual staff identities:** Every person using POS or administrative functions must have an individual account. Shared cashier/manager accounts are prohibited. Each account has a stable identity, username or login identifier, assigned role, active/disabled status, creation and update metadata, and appropriate last-login/security metadata. Staff accounts are created, disabled and managed by authorized OWNER_ADMIN users. Disabled accounts cannot authenticate or continue using valid sessions.
2. **Initial role model:** Support OWNER_ADMIN, MANAGER and CASHIER roles as defined in the staff-role section. Apply least privilege. A staff member may receive only explicitly approved permissions; role and permission changes are owner-authorized and audited. Do not rely on role names or permissions supplied by the client.
3. **Backend authorization:** Enforce authentication and authorization on every protected API and sensitive service operation. Check the authenticated account's current status and effective permissions server-side. Protect direct API calls, alternate routes and bulk operations. Deny by default when identity, role, permission or approval state is missing or cannot be verified. Review existing endpoints and controllers before rollout because the current JWT filter authenticates a username with empty authorities and the current Admin entity does not contain a staff role.
4. **Authentication and password storage:** Continue using a vetted password-hashing mechanism such as the existing BCrypt PasswordEncoder, with appropriate work factor and password policy. Never store or log plaintext passwords. Production accounts must not use default or hardcoded credentials. Bootstrap credentials must be supplied securely, restricted to initial setup, and not remain a reusable backdoor after initialization. Password reset and account recovery must verify authority and be audited.
5. **Session and token lifecycle:** Use configurable staff session lifetimes appropriate to POS risk; do not assume the existing ten-hour JWT lifetime is suitable. Provide secure logout and server-enforced revocation or equivalent invalidation when an account is disabled, credentials are reset, permissions materially change or a security incident requires termination. Validate current account status and permissions for protected operations rather than trusting stale token claims indefinitely. Protect signing keys and rotate them through a controlled procedure.
6. **POS inactivity and reauthentication:** Provide a configurable inactivity lock for POS screens. Locking must protect customer and transaction information and require the authenticated staff member to reauthenticate or an authorized handover procedure before resuming. Require fresh authentication or a narrowly scoped, short-lived approval for sensitive actions such as refunds, voids, high discounts, stock adjustments, cash movements, staff/permission changes and other owner-configured operations. Never treat possession of an old browser session alone as approval.
7. **Login abuse protection:** Apply rate limiting or progressive delays to repeated failed login attempts, safe temporary lockout controls, generic authentication failure messages and monitoring of suspicious activity. Avoid permanent lockouts that create an easy denial-of-service path; provide a controlled owner recovery process. Record relevant security events without storing credentials or tokens.
8. **Append-only audit records:** Maintain durable audit records for important business and security events. Each record should include event identifier, timestamp, actor account and role (or explicit system actor), action/event type, affected entity and identifier, outcome, relevant reason, correlation/request reference and approval reference where applicable. For changes, capture appropriate before/after values or a safe structured change summary. Exclude passwords, access tokens, payment secrets and prescription contents; minimize personal data.
9. **Events requiring audit:** Include login success/failure and logout; account creation, disablement and recovery; role/permission changes; sales creation and completion; invoice void/cancellation and reprint where relevant; discounts and approvals; returns, inspections and refunds; stock adjustments and counts; purchases and goods receipts; cash register opening, movements and closing; payment reconciliation; sensitive customer or prescription record access/change/download; report exports; and security configuration changes. Record both successful and rejected sensitive attempts where practical.
10. **Audit integrity and access:** Audit records are append-only for normal application users. No cashier or manager may edit or delete audit history. Restrict audit viewing and exports to authorized roles; protect against unauthorized database/application access, and define retention, backup and review procedures. Corrections to business data must be new authorized transactions with references to the original event, not edits to historical audit entries. Audit log tamper-evidence and privileged database access controls must be considered in deployment.
11. **Sensitive action approval:** Reuse or adapt the existing short-lived AdminActionAuthorization mechanism only after reviewing its current security properties. Approval must be bound to the authenticated approver, specific action, target record and relevant amount/scope, expire quickly, be single-use and be consumed atomically. A generic reusable approval token is not acceptable. Record requester, approver, decision, reason and outcome.
12. **Existing administrator migration:** Preserve the current administrator's ability to log in during migration. Plan a controlled transition from the existing Admin account and username-based JWT authentication to individual staff identities and roles. Establish and verify the OWNER_ADMIN account and recovery path before enforcing new restrictions; do not silently assign broad permissions to every legacy account. Test existing website administration, password verification and sensitive-action flows for compatibility before cutover.
13. **Transport, browser and configuration security:** Production APIs must use HTTPS. Configure exact trusted production CORS origins; do not use wildcard origins with credentials. Protect cookies/tokens against theft and cross-site misuse according to the chosen browser authentication architecture, and review CSRF implications if cookies are introduced. Keep JWT signing keys, database credentials and payment secrets outside source control. Avoid exposing sensitive data in logs, error messages or browser storage.
14. **Privacy-aware audit:** Prescription and customer data access must be logged at an appropriate level without copying prescription images, prescription values or unnecessary personal details into general audit records. Restrict prescription access by role and business need; use short-lived authorized access to private object storage and audit sensitive downloads.
15. **Operational monitoring and recovery:** Monitor repeated authentication failures, privilege changes, unusual refunds/voids, abnormal stock adjustments and audit-write failures. Define alert ownership, incident response, account suspension, credential rotation and recovery procedures. Critical business operations must fail safely if required authorization or audit persistence is unavailable; do not complete a sensitive transaction while silently losing its mandatory audit event.

This section defines target design and security requirements only. It does not authorize immediate changes to the existing authentication system, Admin entity, JWT format, API permissions, database schema or frontend login. Before implementation, inventory every protected endpoint and current frontend authentication dependency, design a backward-compatible staff migration, define permission matrices and session policy, and test account disablement, token revocation, role changes, sensitive approvals, audit completeness and recovery paths. Existing authentication and AdminActionAuthorization code must be reviewed and tested before reuse or modification.

### 10.22 Invoice design, printing and thermal printer support

The system must provide consistent, durable invoice records for physical POS sales and online sales, while clearly identifying the sales channel and preserving the original transaction values. Invoice rendering and printing are presentation operations; they must never create, settle, modify or duplicate a sale or payment.

1. **Invoice formats:** Support (a) a compact POS receipt layout for thermal printers, with 80 mm as the primary target and optional 58 mm support after layout validation; (b) a full A4 invoice suitable for standard printers and PDF download; and (c) an online-order invoice using the same business identity, numbering policy and core transaction information, with relevant online payment and fulfilment references. Layouts may differ, but transaction facts and totals must agree.
2. **Required invoice information:** Include NMK OPTICALS business name, configured address and contact details; unique invoice number; invoice date/time; sales channel; customer name and phone only where available and appropriate; line-item description, SKU/variant identifier, quantity, unit selling price, applicable discount and line total; bill-level discount where applicable; final payable/paid amount; tender breakdown and payment reference where relevant; and applicable return/exchange policy reference. Include staff identity or system attribution where appropriate for the document and audit trail.
3. **GST and tax representation:** NMK is currently not GST-registered. Do not require a GSTIN, represent GST as collected, or display tax labels/calculations that imply GST registration. Store and render tax fields only in a way supported by the approved legal/accounting configuration. Have an accountant or qualified adviser confirm invoice wording, mandatory particulars and applicable obligations before production release. The design must permit lawful future configuration if registration status changes, without rewriting historical invoices.
4. **Optical and prescription privacy:** Include product/lens specifications on the invoice only when useful and approved for the transaction. Do not print prescription images or detailed prescription values by default. Provide no prescription data on public/shared receipt views. Any exceptional prescription-related document must use an explicitly authorized workflow and appropriate privacy controls.
5. **Immutable invoice snapshot:** At sale completion, persist or otherwise durably preserve the invoice's transaction snapshot: invoice number/date, seller details as applicable at issue time, item descriptions and SKU/variant, quantities, unit prices, discounts, totals, tender allocation and relevant order references. Later changes to product names, catalogue prices, customer profiles or shop settings must not silently rewrite a historical invoice. Corrections, cancellations, credit/refund documents and reissues must follow explicit audited workflows and applicable accounting advice.
6. **Numbering and uniqueness:** Use the approved shared numbering policy across POS and online sales, with a unique number scoped by the configured business/location and Indian financial year, for example NMK/26-27/0001. Allocate numbers safely under concurrent sales. Failed attempts must not cause duplicate numbers; any gaps, cancelled numbers or voided invoices must remain traceable and never be silently reused. Final format, sequence rules and void treatment require accountant/adviser confirmation.
7. **Printing and PDF:** Provide browser-based print layouts and downloadable PDF invoices for A4 and thermal widths. Ensure readable typography, appropriate margins, line wrapping, totals and page breaks. Thermal output must fit the selected paper width without clipping. Support ordinary operating-system printer selection so the first release does not depend on a particular printer brand or proprietary driver. Validate 80 mm and, if enabled, 58 mm layouts on representative devices and printers before release.
8. **Printer compatibility:** Target standard USB and Bluetooth printers that are exposed to the device through its operating system/browser print workflow. Direct raw ESC/POS or vendor-specific integration is not a Phase 1 dependency. If later required, introduce it as a separately tested integration with explicit supported device models, connection recovery and failure handling; do not claim universal compatibility.
9. **Reprints and duplicate marking:** Allow authorized staff to reprint or redownload an existing invoice from its stored snapshot. Reprinting must not create a new sale, payment, stock movement or invoice number. Where operationally appropriate, mark the output as a copy/reprint with the original invoice number and issue details; record reprint actor, time and reason where required by policy. Reprint permissions must be role-based and sensitive reprints audited.
10. **Online invoice delivery:** Allow the existing online-order workflow to provide the appropriate invoice view or PDF after the sale reaches the defined invoice-issuance state. Do not issue a paid invoice solely because an order was created or a payment attempt started. Use verified payment and order state. Customer access must be authenticated or use a secure, limited link; never expose another customer's invoice by predictable identifiers.
11. **Voids, returns and refunds:** Never silently delete or overwrite a completed invoice. A void/cancellation, return, refund or exchange must be represented by the appropriate linked transaction/document, preserve the original invoice, record reason and authorization, and follow accountant-approved numbering and wording. A refund confirmation must not be inferred from merely initiating a payment-provider request.
12. **Failure handling:** If printing or PDF generation fails, preserve the completed sale and invoice record. Clearly report that printing failed and allow a safe retry using the same invoice. Do not roll back or duplicate a completed transaction because a printer is disconnected, paper is out or a browser print dialog is cancelled. Do not claim that a physical print succeeded unless the printer workflow can actually confirm it.
13. **Audit and access:** Apply backend authorization to invoice retrieval, download, reprint, void and correction actions. Audit sensitive retrieval/export/reprint and all void/correction actions with actor, time, invoice reference, reason and outcome. Minimize customer data in logs and prevent unauthorized access to invoices containing personal information.
14. **Configuration:** Store business identity/contact details and print-layout preferences in controlled settings. Support paper width, margins, logo and footer/policy text without allowing ordinary staff to change legal business identity or historical invoice snapshots. Owner-authorized changes must be audited and apply prospectively unless a lawful correction workflow explicitly states otherwise.
15. **Acceptance testing:** Test concurrent invoice allocation across POS and online channels; financial-year rollover; failed and retried printing; A4 pagination; 80 mm and optional 58 mm clipping; long product descriptions; discounts and split tenders; reprint idempotency; void/refund references; historical snapshot stability after catalogue/settings edits; and authorization for invoice access and exports.

This section defines target design only. It does not authorize changes to current checkout, payment, invoice numbering, database schema, printer integration or application code. Before implementation, confirm legal invoice wording and numbering treatment with the accountant/adviser, identify the actual shop details and preferred receipt content, validate the printer models and operating systems available at the shop, and agree when POS and online invoices are issued. Preserve existing online-order and payment behaviour during migration.

### 10.23 Low-stock alerts and reorder management

The inventory system should help staff identify variants that need replenishment without changing stock automatically or creating unnecessary repeated notifications. Stock alerts are operational signals only; they are not stock movements, purchase orders or proof that a supplier order has been placed.

1. **Variant- and location-level thresholds:** Configure a minimum/reorder threshold for each sellable product variant at each inventory location. Do not apply one parent-product threshold to all colours or sizes. Support an optional target stock quantity (the desired level after replenishment). Thresholds must be non-negative integers; a disabled or unset threshold means no automatic low-stock alert for that variant/location. The initial deployment may configure the single Avalurpet shop while preserving the location dimension for future branches.
2. **Alert against available quantity:** Evaluate `available_quantity = on_hand_quantity - reserved_quantity`, using the shared inventory definition. Online-reserved units are not available for a new POS sale and must not hide a low-stock condition. Display on-hand, reserved and available quantities separately so staff can understand why an alert was raised. Never treat a reservation as a physical stock movement.
3. **Alert conditions:** Define at least LOW_STOCK when available quantity is greater than zero and at or below the configured minimum, and OUT_OF_STOCK when available quantity is zero. Treat replenishment as a resolution/transition when available quantity rises above the applicable threshold; retain the event history rather than deleting the previous alert. Define a clear precedence so one variant/location is not simultaneously shown as both current LOW_STOCK and OUT_OF_STOCK.
4. **Reorder quantity suggestion:** Where a target quantity is configured, suggest `max(0, target_quantity - available_quantity)`. If no target is configured, show the shortage relative to the minimum and require staff to enter a proposed quantity. Suggestions are advisory and must account for outstanding purchase quantities only if those quantities are reliable and explicitly shown; do not silently assume that an unreceived purchase order is available stock.
5. **Dashboard and operational views:** Provide an in-app dashboard summary and searchable list for low-stock and out-of-stock variants. Show product and variant description, SKU/barcode where available, location, on-hand, reserved, available, threshold, target, suggested reorder quantity and last relevant stock movement. Support filters for condition, category, supplier where known, and location, plus sorting by urgency and available quantity. Do not expose customer or prescription data in these views.
6. **Alert lifecycle and duplicate prevention:** Maintain at most one current unresolved alert per variant, location and condition/state. Repeated dashboard refreshes or repeated evaluation jobs must not create duplicate active alerts. Record state transitions (raised, updated, resolved, acknowledged where acknowledgement is supported), timestamps and responsible actor/system process. Acknowledgement means staff have seen the alert; it does not resolve the shortage. Reopen or create a new alert only after a genuine subsequent transition according to the defined lifecycle.
7. **Evaluation and consistency:** Re-evaluate alerts after committed inventory changes that can affect available quantity, including accepted goods receipts, POS/online sale conversion, reservation creation/release, approved returns/restocks and authorized adjustments/count corrections. Reservation changes can alter available quantity even when on-hand is unchanged. Use a reliable transactional/outbox or idempotent post-commit evaluation approach so alert state cannot falsely claim a stock change that rolled back. A periodic reconciliation job may repair missed evaluations, but it must be idempotent and must not modify inventory.
8. **Notifications:** The first release should use in-app Admin dashboard alerts. Do not make email or SMS a launch dependency. Future external notifications must be configurable by event, recipient/role, channel and frequency, with delivery status, retry limits and duplicate suppression; evaluate provider cost and consent requirements before enabling them. Avoid sending a message on every quantity fluctuation or dashboard refresh.
9. **Permissions and responsibilities:** OWNER_ADMIN can configure thresholds and notification settings. MANAGER can view/acknowledge alerts and prepare or manage reorder proposals within assigned purchasing permissions. CASHIER may see only the limited stock availability needed for POS operations and cannot change thresholds, dismiss audit history or create supplier orders unless explicitly granted a separate permission. Enforce all permissions in backend APIs and services.
10. **Purchasing separation:** An alert or reorder suggestion must not automatically create, approve, send or receive a purchase order. Staff must use the separately authorized supplier/purchase workflow, where ordered quantity, accepted goods receipt and supplier settlement remain distinct. Inventory increases only when goods are physically received, inspected and accepted under the purchase-receipt workflow.
11. **Audit and history:** Audit threshold creation/change/disablement, target quantity changes, manual acknowledgement, reorder proposal creation or modification, and alert resolution where staff action is involved. Capture actor/system source, variant, location, previous and new values, timestamp and reason where applicable. Keep alert history available for operational review; do not allow normal users to rewrite or erase historical events.
12. **Data integrity and migration:** Keep thresholds separate from on-hand/reserved quantities and from the append-only stock movement ledger. Use foreign keys and uniqueness for the variant/location threshold and active-alert identity as appropriate. Validate non-negative values and avoid stale cached availability. During migration, do not infer thresholds from legacy `Product.stockQuantity`; initialize thresholds only from owner-approved values or leave them unset until configured. Existing stock and active online reservations must follow the separately defined reconciliation process.
13. **Acceptance testing:** Verify zero and boundary quantities, reservation creation and release, online payment conversion, concurrent POS sales, accepted and rejected purchase receipts, returns/restocks, stock adjustments, threshold changes, target-based reorder suggestions, alert resolution/reopening, duplicate evaluation jobs, transaction rollback, role restrictions and dashboard filtering. Confirm that no alert action itself changes inventory or creates a purchase order.

This section defines target design only. It does not authorize alert screens, notification integrations, purchase-order automation, schema migrations or application-code changes. Before implementation, agree initial thresholds and target quantities with the shop owner, confirm which managers may prepare purchase orders, and define whether outstanding purchase orders should be included in reorder suggestions.

### 10.24 Backup, disaster recovery and database migration strategy

NMK must be able to recover business records and private uploaded files after accidental deletion, application failure, database corruption, credential compromise, provider outage or a future cloud-provider change. Backups are not considered reliable until restoration has been tested. The design should use Oracle Cloud as the planned primary hosting environment where suitable, while keeping the application data portable and avoiding unnecessary provider-specific coupling.

1. **Backup scope and ownership:** Define a documented backup inventory covering the MySQL database (including schema, customer/order/sale/payment records, inventory balances and ledger, staff/audit records, configuration and migration history), private Oracle Object Storage objects (prescription images and other uploaded files), and the configuration/secrets needed to rebuild services. Do not place plaintext secrets, passwords, JWT signing keys or payment credentials inside ordinary database dumps or unprotected backup archives. Identify an owner responsible for monitoring backup success and responding to failures.
2. **Database backup schedule:** Configure automated, encrypted backups of the production MySQL database. A daily full backup is the minimum baseline, with more frequent incremental/binlog-based recovery or a managed point-in-time recovery feature where supported by the selected MySQL service and plan. Confirm actual service capabilities, retention, export format, restore limitations and charges before selecting the final Oracle service. Do not assume a feature is always-free or available on every database offering.
3. **Recovery point objective (RPO):** Set an explicit maximum acceptable data-loss window with the business owner. Evaluate a target of no more than 15 minutes for committed transactional data if the selected database service and budget can support it; this is a design target, not a guarantee. If the chosen service only supports daily recovery points, clearly document the larger possible loss window and obtain explicit business acceptance before production use. Payment-provider records and webhook/event references should support reconciliation of transactions that occurred after the latest database backup.
4. **Recovery time objective (RTO):** Define the maximum acceptable time to restore the website, admin and POS after an outage. Establish a realistic initial target with the owner after measuring database size, object volume, service provisioning time, DNS/configuration changes and restore duration. Do not advertise an RTO until it has been demonstrated in a recovery exercise. Prioritize safe recovery of transaction integrity over bringing the POS online with uncertain stock or payment state.
5. **Independent backup copies:** Keep at least one protected backup copy logically and, where feasible, administratively separate from the primary production database and its credentials/account. A backup stored only in the same database instance, bucket, region or compromised account is not sufficient disaster recovery. Evaluate a separate account/compartment or independent provider/location for critical copies, subject to cost and security review. Apply least-privilege write/read permissions and protect backups from accidental deletion or ransomware-style credential compromise.
6. **Object Storage and image recovery:** Back up prescription images and other required private objects independently from MySQL. Preserve object keys, bucket/container mapping, metadata, checksums, content type and the relationship to database records. Use private buckets, encryption and restricted service identities. Enable versioning, retention protection or equivalent recovery features where supported and justified. A database backup without its referenced objects is incomplete; an object backup without the matching database metadata is also incomplete. Do not expose prescription images through public buckets or unrestricted permanent URLs.
7. **Consistency across database and objects:** Define a coordinated backup/recovery point for database metadata and object files. Because uploads and database transactions may not be atomic across services, use a controlled upload lifecycle (for example, temporary/pending object state followed by verified persistence and database linkage), reconciliation of orphaned objects and missing references, and a documented recovery procedure. Do not delete source objects merely because a database row is temporarily absent during an incomplete workflow.
8. **Encryption and access controls:** Encrypt backups in transit and at rest using supported key-management controls. Restrict backup creation, listing, download, deletion and restoration to specifically authorized identities. Separate application runtime permissions from backup-administrator permissions. Protect encryption keys and recovery credentials independently from the backup data, document key rotation and emergency access, and audit backup/restore actions. Never include customer prescription content in general logs or unencrypted diagnostic exports.
9. **Retention policy:** Agree retention periods for daily, weekly and monthly recovery points based on business, legal, privacy and storage-cost requirements. Retention must cover routine mistakes and delayed discovery, not just the most recent backup. Apply a controlled disposal policy to expired copies and ensure legal/operational holds are respected. Do not promise a retention duration until service limits and recurring charges have been checked.
10. **Restore testing:** Test restoration on a separate non-production environment at a defined cadence and after material changes to backup configuration. Verify database connectivity, schema/migration version, record counts and integrity, representative customer/order/payment/sale records, invoice snapshots, inventory summary-to-ledger reconciliation, staff/audit history, and access to corresponding private object files. Record test date, backup point, duration, failures, corrective actions and reviewer. A successful backup job without a successful restore test is not proof of recoverability.
11. **Disaster recovery runbook:** Document who declares an incident, how to obtain protected recovery credentials, choose a known-good restore point, provision a clean database and object store, restore data, apply compatible application configuration, rotate compromised credentials, validate payment and stock state, update service endpoints/DNS and communicate service status. Keep the runbook accessible when the primary cloud account is unavailable. Never reconnect production traffic before integrity checks and authorization are complete.
12. **Payment and inventory reconciliation after recovery:** Treat payment-provider state as an independent source for investigating transactions around the recovery point. Reconcile Razorpay orders, payment captures, refunds and webhook/event identifiers before retrying or recreating any financial operation. Preserve idempotency keys and references. Reconcile active online reservations, POS sales, refunds, accepted goods receipts and stock movements; do not blindly release reservations, replay stock changes or assume an uncertain payment failed. Keep POS inventory-changing operations disabled until critical payment and inventory state is understood.
13. **Local MySQL recovery and portability:** Maintain a documented, tested process to export a consistent logical backup and restore it into a supported local MySQL installation. Use portable SQL/data formats and documented version/collation/charset requirements. Validate schema, indexes, constraints, timestamps, decimal precision, migration history and representative application queries after restore. Local restoration is a recovery/development capability, not permission to run two writable production databases or to merge divergent datasets without a controlled reconciliation plan.
14. **Cloud-provider migration:** Keep business logic and persistence behind standard application/database interfaces where practical. Document MySQL version, extensions, collations, timezone, SQL modes, object-storage API assumptions, object-key mapping, access policies and deployment dependencies. For a future provider switch, take a consistent database and object snapshot, restore into the target, validate counts and checksums, test application/payment integrations, perform a controlled write freeze or a defined replication/cutover procedure, reconcile final changes, then switch traffic. Retain the original environment and backup until the new environment is verified and the rollback decision window has passed.
15. **Migration and schema-change safeguards:** Use version-controlled Flyway or Liquibase migrations (or another explicitly approved versioned migration system) for production schema changes rather than relying on Hibernate `ddl-auto=update` as the production migration strategy. Back up before high-risk migrations, test against a production-like copy, review execution time and locks, use additive/backward-compatible changes where possible, and define rollback or forward-fix procedures. Do not assume that restoring an old database snapshot is safe after new application writes have occurred.
16. **Monitoring and failure alerts:** Monitor backup completion, age of latest successful recovery point, backup size anomalies, object-copy failures, storage capacity, retention enforcement and restore-test outcomes. Alert the responsible owner when a scheduled backup fails or the recovery-point age exceeds the agreed limit. Avoid silent failures and do not mark a backup complete until integrity/checksum validation required by the procedure succeeds.
17. **Cost and service verification:** Before choosing Oracle database, Object Storage, replication, cross-region copy or point-in-time recovery features, verify current service availability, free-tier/Always Free eligibility, quotas, retention limits, egress and storage charges, support lifecycle and restore costs. Keep the design provider-portable, but do not assume all desired redundancy or recovery features are free. Record the selected service and cost assumptions before production provisioning.
18. **Acceptance criteria:** Demonstrate restoration of a database backup into local MySQL and a separate cloud/test environment; retrieval and checksum verification of associated private objects; recovery of a representative invoice and prescription reference; payment and stock reconciliation; successful application startup against the restored database; and documented measured RPO/RTO. Test at least one scenario involving accidental deletion or corruption and one scenario involving unavailable primary cloud resources.

This section defines target requirements only. It does not authorize creation of cloud resources, backup jobs, object replication, database migrations, production configuration changes or application code. Before production launch, choose the exact Oracle database and Object Storage services, verify current service/Always Free limits and costs, agree retention/RPO/RTO with the owner, assign operational responsibility, and complete a witnessed restore and recovery exercise.

## 10.25 Foundational schema reconciliation — variant-level inventory

This section is the authoritative reconciliation of the earlier conceptual model in Sections 3–4 and the detailed baseline in Sections 9.5, 9.6 and 9.8. Where those earlier sections describe product-level stock identity, they are superseded by this section and the confirmed variant requirements in Sections 10.1 and 10.8. The existing `products` table remains the parent catalogue record; it is not the stock identity.

#### Authoritative identity and relationships

1. Every stock-tracked sellable item must resolve to exactly one `product_variants` row. A product without selectable options still has one default variant, as specified in Section 10.1.
2. `product_variants.product_id` references the parent `products.id`. A variant belongs to one parent product; a parent product may have multiple variants.
3. Inventory is scoped by `(location_id, variant_id)`. The `inventory` table must have a foreign key to `product_variants.id` and a unique constraint on `(location_id, variant_id)`. It must not use a unique `product_id + location_id` stock row.
4. Every stock movement references the exact `variant_id` and `location_id`. A parent `product_id` may be retained as a redundant, validated reporting/reference field or obtained through the variant relationship; it must never replace the variant stock identity.
5. Every stock-tracked POS `sale_items` row references `variant_id`. Retain `product_id` only where useful for reporting or historical linkage, and validate that it is the parent of the referenced variant. Preserve product name, variant description, SKU/barcode and price as immutable transaction-time snapshots.
6. Online order items must also resolve to the purchased `variant_id`. Existing online order rows that currently contain only `product_id` require an explicit, reviewed mapping to a default or newly created variant before the new inventory model is activated.
7. Supplier purchase lines, accepted goods receipts, returns/restock records, stock counts, adjustments and low-stock thresholds must all reference the same variant and location identity. No workflow may create or adjust stock against a parent product alone.
8. SKU is the stable internal identifier for a variant. Barcode is an optional lookup identifier. Neither barcode nor SKU substitutes for the database foreign key `variant_id`.

#### Corrected inventory schema

The authoritative inventory summary is:

| Column | Type | Rules |
|---|---|---|
| id | BIGINT | PK, auto-increment |
| location_id | BIGINT | NOT NULL, FK to inventory_locations |
| variant_id | BIGINT | NOT NULL, FK to product_variants |
| on_hand_quantity | INT | NOT NULL, >= 0 |
| reserved_quantity | INT | NOT NULL, >= 0 and <= on_hand_quantity |
| version | BIGINT | NOT NULL, default 0; use where optimistic locking is selected |
| updated_at | DATETIME(6) | NOT NULL |

Unique constraint: `location_id + variant_id`. Compute `available_quantity = on_hand_quantity - reserved_quantity`; do not store a separately editable available balance. Reorder thresholds belong to a separate variant/location threshold record as specified by Section 10.23, not to a product-level inventory row.

#### Corrected stock movement identity

The append-only `stock_movements` ledger must include:

- `variant_id` and `location_id` as mandatory foreign keys.
- `quantity_delta`, `on_hand_after`, `reserved_delta` and `reserved_after` to describe the exact transition.
- A controlled movement type, reference type/reference ID, idempotency key, actor, timestamp and reason where applicable.
- Optional immutable product/variant/SKU description snapshots for readable audit history.

The inventory summary update and corresponding movement insert must commit in the same transaction. Correct errors with compensating movements; never edit or delete historical movements.

#### Corrected sale-item identity

Each stock-tracked sale item must include:

- `sale_id` FK to the POS sale.
- `variant_id` FK to the exact sold variant.
- Optional `product_id` FK for convenient parent reporting, validated against the variant.
- Immutable product-name, variant-attribute, SKU/barcode, quantity, unit-price, discount, applicable tax-treatment and line-total snapshots.
- Prescription linkage only through the separately authorized prescription-record model in Section 10.18; do not make unrestricted prescription contents part of the ordinary sale-item record.

A historical sale item must remain understandable even if its product or variant is later renamed, deactivated or archived. Foreign-key deletion rules must preserve transaction history.

#### Superseded baseline text and implementation rule

- Section 3's product-level inventory description and Section 4's corresponding product-to-inventory relationship are conceptual history only; use the variant/location relationships above.
- Section 9.5's one-row-per-product inventory definition and its `product_id + location_id` uniqueness are superseded.
- Section 9.6's product-only stock-movement identity is superseded; movements are variant/location scoped.
- Section 9.8's product-only sale-item identity is superseded; stock-tracked sale items reference a variant.
- Section 10.1's variant model and Section 10.8's shared reservation lifecycle remain in force and are made explicit by this reconciliation.

#### Migration safety requirements

1. Create and validate variant mappings before migrating inventory balances. Do not infer that every existing product has only one physical variant if its current specifications or stock history indicate otherwise.
2. Reconcile physical counts, current `products.stockQuantity`, active online reservations and pending/paid order state. The existing stock field currently behaves like available stock because reservation subtracts from it; it must not be copied directly into the new `on_hand_quantity` without calculating the corresponding reserved quantity.
3. Backfill `variant_id` on online order items and all other existing stock references, preserving original order/payment identifiers and historical snapshots.
4. Detect and resolve missing, ambiguous or duplicate mappings before cutover. Do not silently assign an order line to an arbitrary variant.
5. Introduce additive, versioned database migrations and a controlled cutover. Keep the old checkout/reservation path operating until the new variant-aware path is deployed and tested end-to-end.
6. Verify concurrent online reservations, payment success, uncertain Razorpay attempts, cancellation/expiry release, POS sale contention and idempotent retries against the same variant/location row.
7. Do not remove or stop maintaining the legacy stock field until reconciliation, rollback criteria and production cutover have been approved and tested.

This reconciliation is a design-document correction only. It does not create entities, change APIs, execute SQL, alter existing stock, or authorize a production migration.

## 10.26 Transaction schema reconciliation — sales, payments, fulfilment, invoices, returns and refunds

This section consolidates the transaction model for the initial POS release. It is authoritative where earlier conceptual descriptions in Sections 3, 4 and 9 leave transaction ownership, status transitions or relationships incomplete. It must be implemented additively and must preserve the existing online order and Razorpay workflow. The POS sale model is separate from `customer_orders` and `order_items`; online orders must not be copied into POS sales merely to share reporting or invoice numbering.

### Transaction ownership and core relationships

1. A completed physical-store transaction is represented by one `sales` record. It has one or more `sale_items`, one or more successful tender allocations, one invoice identity/snapshot, and a fulfilment record when collection is not immediate.
2. Every stock-tracked `sale_items` row references the exact `variant_id` and `location_id` as required by Section 10.25. It also retains immutable product name, variant description, SKU, quantity, unit price, discount allocation, applicable tax treatment and line-total snapshots. Historical rendering must not depend on the current catalogue.
3. A POS sale may have a nullable `customer_id` for walk-in transactions. Customer name/contact details required on the invoice, where collected, are transaction-time snapshots and must not change when the customer profile is edited.
4. Online orders remain in `customer_orders` and `order_items`, with their existing provider/payment identifiers and lifecycle. They share the customer, variant inventory, invoice-numbering policy and reporting dimensions where appropriate, but are not POS `sales`.
5. Use a common invoice-number allocation mechanism scoped by location and Indian financial year for both online and POS documents if both channels issue invoices under the same business sequence. The allocator must be concurrency-safe and must not rely on `MAX(invoice_number) + 1`. A separate channel/document-type sequence is permissible only after the accountant/adviser confirms that policy. Financial-year boundaries and timezone are based on India Standard Time. Preserve issued numbers; void/cancelled documents remain auditable and numbers are never reused.
6. The sale, sale items, inventory deduction, stock movement, tender records, invoice snapshot and initial fulfilment state must be committed atomically where they are part of one POS completion operation. A failed transaction must not leave a paid sale without its corresponding stock and audit records, or stock deducted without a sale.

### Authoritative POS sales schema

The logical `sales` record should include, at minimum:

| Field | Purpose and rule |
|---|---|
| `id` | Primary key |
| `sale_number` | Immutable internal sale reference, unique |
| `invoice_number` | Issued invoice identifier; unique within the approved numbering scope |
| `location_id` | Shop/location foreign key |
| `customer_id` | Nullable customer foreign key |
| `channel` | POS; online orders remain in their existing online tables |
| `sale_status` | Controlled lifecycle status |
| `currency_code` | INR for the initial release |
| `subtotal_amount` | Sum of pre-discount item amounts |
| `item_discount_amount` | Item-level discount total |
| `bill_discount_amount` | Bill-level discount total |
| `tax_amount` | Zero unless a legally applicable and approved tax treatment is introduced; NMK is currently not GST-registered |
| `rounding_amount` | Explicit rounding adjustment, if used |
| `total_amount` | Backend-calculated amount due |
| `completed_at` | Set only when sale completion conditions are satisfied |
| `created_by`, `created_at`, `updated_at` | Actor and audit timestamps |

Initial POS status semantics:

- `DRAFT`: mutable cart/checkout state; not an issued sale, invoice or stock deduction.
- `COMPLETED`: fully paid sale, stock deduction and invoice issuance have committed.
- `VOIDED`: only through a controlled, authorized void process with a reason and linked reversal/compensating records. Do not erase the original sale or pretend a completed transaction never occurred.
- Returns and refunds are separate records and do not change a completed sale back into a draft or delete it.

If the implementation needs an intermediate payment-attempt state, model it explicitly (or in a checkout/payment-attempt table); do not overload `COMPLETED`. A sale must not be marked completed merely because the browser submitted a payment form.

### Sale items and calculation snapshots

Each `sale_items` row must reference `sale_id`, `variant_id`, `location_id` (directly or through an unambiguous sale/location relationship), and retain:

- Product name, variant attributes/description, SKU and optional barcode snapshots.
- Quantity as a positive integer.
- Unit price and gross line amount.
- Item discount amount and discount type/value snapshots where needed for audit.
- Allocated bill-level discount, so the historical net line value can be reconstructed.
- Applicable tax rate/category and tax amount only if a lawful tax treatment is later configured; do not label ordinary amounts as GST collected.
- Net line total and currency.
- Prescription linkage only to the separately permissioned/versioned prescription record model in Section 10.18; do not copy prescription contents into ordinary sale rows.

The backend is authoritative for price, discount allocation, rounding and totals. Recalculate from trusted catalogue/pricing data and approved discount rules; never trust client-supplied totals. Use DECIMAL precision consistently and define a single rounding policy before implementation. Enforce positive quantity, non-negative monetary components, and that line/bill totals reconcile to the sale total.

### Payment and tender model

A POS sale supports one or more tender allocations to support split tender (for example cash plus UPI). The logical payment/tender record should include:

| Field | Purpose and rule |
|---|---|
| `id` | Primary key |
| `sale_id` | POS sale foreign key |
| `method` | CASH, UPI, CARD, or another explicitly enabled method |
| `amount` | Positive amount allocated to this tender |
| `status` | PENDING, CONFIRMED, FAILED, CANCELLED or REFUNDED/partially refunded as derived from refund records; define exact enum/check policy |
| `provider` | Nullable provider identifier for electronic tender |
| `provider_reference` | Nullable transaction/reference identifier |
| `idempotency_key` | Unique operation key to prevent duplicate capture/recording |
| `accepted_by`, `accepted_at` | Staff and timestamp |
| `created_at`, `updated_at` | Audit timestamps |

Rules:

1. A sale is eligible for `COMPLETED` only when the sum of confirmed tender allocations equals the backend-calculated amount due, subject to the explicitly defined rounding rule. Do not allow underpayment or an unexplained overpayment.
2. Split tender rows must be created and reconciled atomically with sale completion. Repeated submission with the same idempotency key must return the original result rather than create another tender, invoice or stock movement.
3. For cash, confirmation means the cashier has accepted the cash and the operation has been committed. For UPI/card, confirmation must follow the configured provider/terminal verification process; a screenshot, client assertion or unverified reference is not proof of settlement.
4. Distinguish payment acceptance at the shop from subsequent bank settlement. Reconciliation reports must not claim that UPI/card money has reached the bank solely because POS accepted the payment.
5. Store no card number, CVV, UPI PIN, payment credentials or provider secrets. Preserve provider references and verified event/callback identifiers only.
6. Refunds are separate linked records. Do not overwrite the original tender amount or mark a tender fully refunded until confirmed refund totals support that state.
7. Existing Razorpay online payments remain under the current online payment/order model. Any future shared payment abstraction must preserve Razorpay order IDs, payment IDs, webhook event IDs, signature verification, amount/currency checks and current idempotency protections. Do not route online payment through POS tender tables as an unreviewed shortcut.

### Fulfilment is separate from payment and sale completion

The POS initial release supports immediate collection and fully paid collect-later only. Fulfilment must not be represented by payment status.

A `sale_fulfilments` record (or equivalent one-to-one state record) should include:

- `sale_id` unique foreign key.
- `fulfilment_status`: `PENDING_PREPARATION`, `READY_FOR_COLLECTION`, `COLLECTED` or `CANCELLED`.
- Expected collection time, ready time and collected time.
- Staff identifiers for preparation/ready/collection actions.
- Last update timestamp and optional customer-facing note that excludes sensitive prescription data.

Record every status transition in an append-only `fulfilment_events` history with prior/new status, actor, timestamp and reason. Validate transitions on the backend. Immediate collection may transition to `COLLECTED` as part of the sale completion transaction; collect-later begins at `PENDING_PREPARATION`. A collect-later sale is still fully paid and its stock is deducted once at sale completion. Marking ready or collected must not deduct stock again. No home delivery or partial fulfilment is included in the initial release.

### Invoice allocation and immutable invoice snapshots

Use an invoice sequence/allocation table keyed by the approved combination of location, financial year and document sequence (and document type/channel only if the accountant approves separate sequences). Lock/update the sequence row transactionally to allocate the next number. Add a unique database constraint to the final invoice number and its scope. Do not derive the next number from existing invoice rows.

Persist an immutable invoice snapshot at issuance, including:

- Invoice number, issue timestamp and financial-year scope.
- Seller legal/trading name and address details as approved for the invoice.
- Customer details actually supplied for that transaction, if any.
- Item descriptions, SKU, quantity, unit prices, discounts and totals.
- Payment/tender summary and sale reference.
- Applicable tax wording/fields only after professional confirmation. NMK is currently not GST-registered; the initial document must not state or imply GST was collected, and must not invent a GSTIN.
- Any legally required terms or return information after adviser review.

The snapshot is the source for historical reprints; never rebuild an old invoice from the current product/customer profile. Reprinting must not allocate a new invoice number, create a payment, change stock or alter the sale. Record reprint actor/time/reason in an invoice audit event. A voided invoice remains retained with its status and reason; never recycle its number. Support 80 mm thermal output as the primary layout, optional 58 mm and A4/PDF rendering without making printer model a database dependency. Prescription values/images must not appear on the ordinary invoice by default.

Online invoice issuance must be integrated with the existing online order lifecycle without creating a duplicate POS sale. The exact event at which an online invoice is legally issued (for example, confirmed payment/dispatch as advised by the accountant) must be explicitly selected and consistently enforced.

### Returns, inspection, stock disposition and refunds

Returns must be independent transactions linked to the original completed sale and original sale item. Define at least:

- `returns`: unique return reference, original sale, customer if applicable, reason, requested/approved/rejected status, requesting/approving staff, timestamps and notes.
- `return_items`: original sale-item reference, requested quantity, approved quantity, unit refund basis and calculated amount. Enforce cumulative approved/returned quantity not exceeding the quantity sold less prior accepted returns.
- `return_inspections`: inspection outcome, inspected quantity, inspector, time, condition and reason.
- `return_stock_dispositions`: quantity and disposition such as RESTOCK_SELLABLE, DAMAGED, NON_SELLABLE or SUPPLIER_RETURN_PENDING. Only physically inspected and approved resale quantity may increase on-hand inventory; each restock writes a variant/location stock movement exactly once.
- `refunds`: return reference, amount, method/provider, status, idempotency key, provider reference, initiator/approver, reason and timestamps.
- `refund_allocations` (or equivalent): links refund amounts to the original tender/payment rows so the total refunded against a tender cannot exceed its confirmed captured/accepted amount, subject to approved policy.

Keep return approval, inspection, stock disposition and refund confirmation as distinct states. A return request does not itself restock inventory or prove money was refunded. A refund request does not become successful until the provider confirms it or an authorized cash refund is recorded under policy. Preserve failed/pending refund attempts for reconciliation; never silently retry a non-idempotent provider operation.

Initial permissions follow Section 10.12: manager approval within configured authority and owner override; cashier cannot independently approve refunds. Exchanges are represented as a linked return plus a new sale, with the difference collected or refunded through the corresponding tender/refund workflow. Do not mutate the original sale item or invoice to represent an exchange. Online returns remain linked to the original online order/payment and must not be treated as POS returns without a reviewed cross-channel workflow.

### Voids, cancellations and compensating records

1. A draft checkout may be abandoned without a financial transaction, invoice or stock movement.
2. A completed POS sale cannot be physically deleted. A valid void requires authorization, reason, audit event and explicit compensating inventory/payment/invoice treatment. If money was captured, use the refund process; do not merely set a status to VOIDED.
3. Do not release stock or increase inventory twice when a POS sale is voided or returned. Use idempotent compensating stock movements linked to the original movement and approved business event.
4. A cancellation before completion must release any reservation/hold exactly once. The initial POS design does not require a persistent POS stock reservation unless a later approved workflow introduces one.
5. Preserve immutable original sale, tender, invoice and stock-movement history. Corrections are new linked records.

### Integrity, concurrency and acceptance criteria

- Use InnoDB foreign keys and unique constraints for sale number, invoice number/scope, provider references where appropriate, idempotency keys and one fulfilment record per sale.
- Lock the variant/location inventory row and verify available quantity immediately before sale completion. Commit stock decrement, stock movement, sale items, confirmed tenders, invoice snapshot and fulfilment state atomically.
- Lock invoice sequence allocation within the same transaction as issuance. A failed transaction must not consume an invoice number unless the approved legal numbering policy requires retaining a voided number; in either case, never reuse an issued number.
- Enforce payment total equals amount due before completion. Enforce refund totals do not exceed the refundable captured/accepted amount and cumulative returned quantities do not exceed sold quantities.
- Use idempotency for sale submission, tender acceptance, online payment confirmation, invoice issuance, return restock and refund initiation/confirmation.
- Apply backend authorization and audit logging to discounts, voids, returns, refunds, invoice reprints and fulfilment changes. Keep the existing admin login working during role migration, as required by Section 10.21.
- Use IST consistently for financial-year and shop operational dates; store timestamps in the agreed database convention and convert at boundaries.
- Define all status transitions and invalid-transition responses before API implementation. Never accept arbitrary status strings from the client.
- Acceptance tests must cover split tender, under/overpayment rejection, duplicate submission, concurrent final-unit sales, rollback after tender/stock/invoice failure, immediate and collect-later fulfilment, reprint without side effects, partial and repeated returns, damaged versus sellable disposition, failed/pending/confirmed refunds, exchange difference collection/refund, online Razorpay success/duplicate webhook/uncertain state, financial-year invoice rollover and void-number handling.

This section is a target design correction only. It does not create tables, alter existing online checkout/payment code, issue invoices, process refunds, change inventory, or authorize API/schema/application changes. Before implementation, confirm invoice legal wording and issuance timing with the accountant/adviser, select the final invoice sequence scope, define supported electronic POS payment verification, and approve the exact status/permission matrix.

## 10.27 Operational schema reconciliation — staff permissions, cash registers/shifts, suppliers and purchasing

This section defines the operational records required to run the physical shop safely. It supplements Sections 10.6, 10.13, 10.14 and 10.21 and the transaction model in Section 10.26. All stock references remain variant/location scoped under Section 10.25. These are target relational requirements, not permission to change the existing authentication system or production records.

### Staff accounts, roles and permission enforcement

The initial role set is exactly:

- `OWNER_ADMIN`: full business access, staff and role administration, settings, reports, stock, purchase operations, refunds and sensitive actions.
- `MANAGER`: operational POS, permitted returns/refunds/voids, stock adjustments, purchasing and reports; approvals are subject to owner-configured limits and assigned permissions.
- `CASHIER`: create fully paid POS sales, accept supported tenders, print/reprint invoices and access only the minimum customer information needed for the sale. A cashier cannot independently adjust stock, edit completed sales/payment records, delete transactions, approve refunds or perform restricted purchase operations.

The logical schema should include:

#### `staff_accounts`

| Field | Purpose and rule |
|---|---|
| `id` | Primary key |
| `username` | NOT NULL, normalized and unique |
| `password_hash` | BCrypt or an approved adaptive password hash; never plaintext |
| `display_name` | Staff display name |
| `role_id` | FK to staff_roles |
| `active` | Disabled accounts cannot authenticate or perform new actions |
| `created_at`, `updated_at` | Audit timestamps |
| `last_login_at` | Nullable operational timestamp |

#### `staff_roles` and `permissions`

- `staff_roles`: stable role code, display name, description and active flag. Seed only the three approved initial roles; do not rely on display names for authorization.
- `permissions`: stable permission code and description, such as `POS_SALE_CREATE`, `SALE_VOID`, `REFUND_APPROVE`, `STOCK_ADJUST`, `PURCHASE_RECEIVE`, `STAFF_MANAGE`, `PRESCRIPTION_VIEW` and `REPORT_EXPORT`.
- `role_permissions`: unique role/permission mapping with foreign keys.
- If individual exceptional grants are later required, model them explicitly with grantor, reason, expiry and audit; do not silently widen a role.

Backend authorization is mandatory on every protected endpoint and service operation. Hiding a button in React is not authorization. The authenticated principal must carry verified staff identity and effective authorities. Deny by default when role/permission data is missing, stale or invalid.

#### Approval and authorization records

Use an explicit `approval_requests` (or equivalent) record for controlled actions requiring another staff member's approval. Store action type, target type/ID, requested amount or quantity where relevant, requester, approver, decision, reason, timestamps, expiry and one-time consumption state. The approver must be authorized for that action and must not be the requester where separation of duties is required. Approval must be bound to the exact action details and cannot be reused for a different sale, refund, adjustment or amount.

Sensitive actions must require reauthentication where policy specifies it. Existing `AdminActionAuthorization` may be evaluated for reuse only after its token binding, expiry, one-time consumption and actor checks are reviewed. Do not assume it replaces role authorization.

#### Authentication migration and security controls

1. Preserve the current admin login during migration. Inspect the current `Admin`, JWT filter and `JwtService` implementation before choosing whether to extend or migrate them.
2. Introduce staff identity/role support through an additive, staged migration. Existing administrator access must not be accidentally locked out; the bootstrap owner account must be explicitly mapped and verified before enabling role enforcement.
3. Use individual staff accounts; prohibit shared cashier logins. Disable departed staff promptly and revoke or invalidate their active sessions/tokens.
4. JWT/session authorization must include a safe strategy for role changes and account disablement. A token issued before a staff role is revoked must not retain privileged access for its full lifetime without a revocation/version check.
5. Use BCrypt or another approved adaptive password hash, secure password reset, rate limiting, HTTPS and exact production CORS origins. Never log passwords, bearer tokens, authorization codes or secrets.
6. Configure POS inactivity lock and reauthentication for sensitive actions. Do not create a universal production default password.
7. Record actor identity for every transaction and operational change. Do not allow nullable/anonymous actors for privileged operations.
8. Cashier customer access is limited. Prescription records and images require separate permissions and audit as specified in Section 10.18.
9. Audit permission changes, staff creation/disablement, login/security events, approval decisions and denied sensitive actions without logging prescription contents or credentials.

### Cash registers, shifts and cash reconciliation

The initial system supports one or more physical registers per shop location, with no more than one active shift per register. A shift belongs to one register and one opening staff member; additional authorized staff activity must still be attributable to the actual actor.

#### `pos_registers`

- `id` primary key.
- `location_id` FK to inventory_locations.
- Stable register code unique within a location.
- Display name, active flag and created/updated timestamps.
- A disabled register cannot open new shifts but its historical shifts remain readable.

#### `pos_shifts`

- `id` primary key; `register_id` FK; `opened_by` staff FK.
- `opened_at`, `opening_cash_amount`, `opening_note`.
- `shift_status`: OPEN, CLOSING or CLOSED.
- `closed_by`, `closed_at`, `closing_note` nullable until close.
- `expected_cash_amount`, `counted_cash_amount`, `cash_difference_amount`.
- Manager review state, reviewer, review time and explanation where a discrepancy exceeds configured policy.
- Unique/transactional enforcement allowing only one OPEN/CLOSING shift per register. A simple application-side pre-check is insufficient under concurrent requests.

#### `cash_movements`

Each non-sale cash movement records:

- Shift and register/location references.
- Movement type: OPENING_FLOAT, CASH_IN, CASH_OUT, CASH_REFUND or another explicitly approved category.
- Positive amount, direction/type, reason and supporting reference.
- Actor, approver where required, timestamp and idempotency key.
- Immutable record after posting; corrections use linked compensating movements.

POS cash sales and cash refunds must link to their originating tender/refund records and shift. Do not duplicate them as unexplained manual cash movements. Non-sale cash-in/out requires a reason and permission/approval according to owner-configured limits.

#### Cash closing calculation and rules

Expected drawer cash is calculated from the shift's opening float plus confirmed cash tender received, plus approved cash-in, less cash-out and confirmed cash refunds. UPI and card tender are excluded from drawer cash and reconciled separately.

At close:

1. Stop accepting new sales on the shift and serialize the close against concurrent tender recording.
2. Calculate expected cash from immutable linked transactions; do not trust a client-submitted expected amount.
3. Record the actual counted amount and calculate the difference.
4. Preserve both expected and counted values. Never silently create a cash adjustment to force them to match.
5. Require manager review when the absolute difference exceeds the owner-configured threshold; record the reason and reviewer.
6. Close only after required checks and approvals. Reopening a closed shift requires owner-authorized action, reason and audit event.
7. Retain a complete per-shift reconciliation of cash tenders, refunds, cash movements, electronic tenders and relevant transaction references.

UPI/card acceptance and bank settlement are separate. Electronic reconciliation records should capture provider/bank reference, expected amount, reconciled amount, discrepancy, reviewer and timestamps without treating POS acceptance as bank settlement. The initial scope does not require direct bank API integration.

### Suppliers and purchasing lifecycle

Purchasing is separate from sales. A purchase order is a request/commitment to a supplier; it does not increase inventory. Inventory increases only when goods are physically received, inspected and accepted.

#### `suppliers`

Include supplier ID, legal/trading name, contact person, phone, email, address, active status, notes and timestamps. Supplier GSTIN may be stored as optional supplier-provided information, subject to validation and applicable business need. NMK itself is currently not GST-registered; storing a supplier GSTIN does not mean NMK is GST-registered or entitled to claim input tax credit. Tax/accounting treatment must be confirmed with the accountant.

Do not store supplier credentials or unnecessary personal information. Deactivation preserves historical purchases.

#### `purchase_orders` and `purchase_order_items`

A purchase order should record:

- Unique purchase-order number, supplier, location, status, created_by, approved_by, ordered_at and expected delivery date.
- Currency, item subtotal, discounts, freight/other landed-cost components and total cost snapshots as applicable.
- Notes, cancellation reason and audit timestamps.
- Lines reference `variant_id`, ordered quantity, expected unit purchase cost and line-cost snapshot.
- Statuses such as DRAFT, APPROVED, PARTIALLY_RECEIVED, FULLY_RECEIVED, CANCELLED and CLOSED, with backend-validated transitions.

Purchase costs are distinct from retail selling prices. Store supplier quote/order values as snapshots. Do not change the sale price when a purchase cost changes.

Managers may create/prepare and approve purchasing actions only within assigned permissions and limits; owner controls policy and overrides. Cashiers cannot create, approve, receive or return purchases. Approval thresholds must be configurable, not hardcoded.

#### `goods_receipts` and `goods_receipt_items`

A receipt references its purchase order and supplier/location, with received_by, received_at, delivery note/reference, status and inspection notes. Receipt lines reference the exact purchase-order line and `variant_id`, and record:

- Quantity physically delivered.
- Quantity accepted into sellable stock.
- Quantity rejected/damaged/missing or held for inspection.
- Accepted purchase cost and any documented variance from the ordered cost.
- Reason, inspector and timestamps.

Support multiple partial receipts against one purchase order. Enforce that cumulative accepted/rejected quantities do not exceed delivered/ordered quantities under the approved tolerance policy. Do not mark the entire purchase order received merely because one partial receipt was entered.

Only accepted sellable quantity increases `inventory.on_hand_quantity`. The inventory update and corresponding `PURCHASE_RECEIPT` stock movement must commit atomically and be idempotent by receipt line/event. Rejected, damaged or quarantined goods do not become available POS stock. A later decision to move inspected goods into sellable stock must be a separately authorized disposition with its own movement.

#### Purchase cost and inventory valuation

Maintain purchase cost independently from sale price. The recommended initial valuation method is weighted-average cost, but its exact calculation, landed-cost allocation, rounding and treatment of returns/discounts/freight must be approved before implementation.

If weighted-average valuation is selected, retain sufficient receipt and adjustment history to reproduce valuation changes. Do not derive reliable gross profit from the latest purchase price or mutable product fields. A purchase receipt's inventory quantity and cost valuation update must be transactionally consistent; valuation adjustments must be auditable. Profit reporting remains disabled until cost-of-goods-sold, discounts, returns and expenses are reliably defined as stated in Section 10.20.

#### `purchase_returns` and supplier settlement

A purchase return references the original supplier, purchase order and receipt/receipt line. Store requested, approved and dispatched quantities, reason, authorization, handover/dispatched timestamp and supplier credit/refund reference.

Reduce on-hand stock only when the goods are physically dispatched/handed back under the approved return process, not merely when a return is requested. Record a variant/location stock movement exactly once. Supplier refund, credit note or settlement is a separate financial record; stock leaving the shop does not prove that supplier money/credit has been received.

Supplier payments/settlements should be recorded separately from goods receipts, with amount, method/reference, date, actor, approval and allocation to supplier invoices/purchases. Do not infer that an order is paid because goods were received, or that a supplier balance is settled because a payment record was attempted. Cash purchase payments must reconcile to the relevant register/shift cash movement where they are paid from the shop drawer.

### Cross-module integrity, permissions and audit

1. Every staff, register, shift, cash movement, purchase order, receipt, purchase return and supplier settlement must have stable foreign-key relationships and actor/timestamp history.
2. Apply unique constraints to staff username, role/permission codes, register code within location, active shift per register, purchase-order number, receipt references and idempotency keys.
3. Use InnoDB transactions and row locking for shift opening/closing, cash tender posting, purchase receipt acceptance, stock updates and supplier return dispatch. Prevent concurrent requests from opening two active shifts or receiving the same quantity twice.
4. Stock receipt/return operations must use the authoritative `variant_id + location_id` inventory row and append-only stock ledger from Section 10.25.
5. A shift close must not race with a new sale or refund. Define a lock/order strategy for shift, sale/tender and inventory rows and use a stable lock order to reduce deadlocks.
6. No role may bypass server-side validation by calling a lower-level controller or repository path. Route stock/purchase operations through validated service methods.
7. Audit approvals, cash discrepancies, shift reopenings, supplier edits, purchase approval/receipt/return, valuation changes, permission changes and exports. Store reason and before/after metadata where appropriate; exclude secrets and prescription contents.
8. Enforce least privilege on reports and exports. Cashiers should not gain unrestricted purchase cost, profit, prescription or staff-security information.
9. Use idempotency and duplicate-event handling for receipt posting, supplier settlement and cash movement submissions. A retry must not duplicate inventory or cash.
10. Acceptance tests must cover concurrent shift opening, sale during close, cash-in/out approval, cash refund reconciliation, cash shortage/excess review, electronic tender reconciliation, role-denied operations, disabled staff sessions, approval expiry/reuse, partial purchase receipts, over-receipt rejection, damaged/rejected stock, duplicate receipt retries, purchase returns, supplier credits and full rollback when ledger insertion fails.

This section defines the target operational schema only. It does not create staff accounts, alter JWT/authentication behaviour, configure register hardware, post cash movements, create purchase orders, receive goods, change inventory or execute migrations. Before implementation, approve the exact permission matrix and approval limits, opening/closing policy, cash discrepancy threshold, supplier payment workflow, landed-cost allocation and final inventory valuation method.

## 10.28 Final Phase 1 database consistency review — authoritative rules, gaps and implementation gates

This review checks Sections 1–10.27 as one proposed design against the inspected current backend and the confirmed NMK business decisions. It does not certify executable DDL, replace a database-engine review, or authorize implementation. Where earlier sections conflict with this section, apply the precedence rules below. The most recent detailed reconciliations in Sections 10.25–10.28 take precedence over the earlier conceptual and baseline material.

### A. Document precedence and canonical ownership

Use this authority order when translating the document into a physical schema:

1. Confirmed business decisions in Sections 10.1–10.24 remain the business-policy source unless explicitly revised by a later confirmed decision.
2. Section 10.25 is authoritative for product variant identity, inventory identity and stock-movement identity.
3. Section 10.26 is authoritative for POS sales, sale items, tender allocation, fulfilment, invoice snapshots, returns and refunds.
4. Section 10.27 is authoritative for staff/permissions, register shifts, cash movements, suppliers, purchasing and goods receipts.
5. This Section 10.28 resolves cross-section contradictions and lists remaining implementation blockers.
6. Earlier Sections 3–4 and 9 are historical design baselines wherever later sections explicitly supersede them. Do not copy their superseded product-level inventory or payment definitions into migrations.

The canonical transaction ownership is:

- Existing `customer_orders` and `order_items` remain the online order/payment records and retain Razorpay identifiers and lifecycle.
- `sales`, `sale_items`, POS tenders and POS fulfilment records represent physical-store sales only.
- Both channels use the same authoritative variant/location inventory and append-only stock ledger.
- Customer identity may be shared, but each transaction preserves its own customer and item snapshots.
- Supplier purchases and receipts are procurement records, never POS sales or customer orders.

### B. Cross-section corrections that must be applied before DDL

#### 1. Inventory and variant foreign-key consistency

The only valid stock identity is `(location_id, variant_id)`. The product-level inventory model in Sections 3, 4 and 9.5–9.6 is superseded.

- `inventory` has a foreign key to `product_variants.id`, a foreign key to `inventory_locations.id`, and a unique constraint on `(location_id, variant_id)`.
- `stock_movements`, reorder thresholds, POS sale items, online order-item mappings, purchase-order lines, goods-receipt lines, purchase returns, stock counts and adjustments all resolve to that same variant/location identity.
- Parent `product_id` may be retained as a reporting snapshot or validated convenience reference, but never as the stock key.
- Any table storing both `sale_id` and `location_id`, or both `variant_id` and parent `product_id`, must enforce that the redundant values agree with their parent records. Prefer deriving location from the sale where a duplicate column is not operationally required.
- Enforce `on_hand_quantity >= 0`, `reserved_quantity >= 0` and `reserved_quantity <= on_hand_quantity`. `available_quantity` is derived only.
- Inventory summary changes and their stock-ledger movements commit in one transaction. Ledger rows are append-only and corrections use compensating movements.

The existing `products.stockQuantity` is not a safe migration source by itself. The cutover must reconcile physical count, active online reservations, paid/pending orders, manual changes and the current available-stock-like meaning of the legacy field. Do not enable variant-aware POS and online stock paths until every active order line has an unambiguous variant mapping and both paths use the same inventory row.

#### 2. Reservation and sale movement semantics

Use one signed movement convention throughout. For the canonical ledger, `quantity_delta` changes on-hand quantity and `reserved_delta` changes reserved quantity; each row records resulting balances. The following semantics are mandatory:

| Event | on-hand delta | reserved delta | Result |
|---|---:|---:|---|
| Online reservation created | 0 | +Q | Available decreases; physical on-hand is unchanged |
| Online reservation safely released | 0 | -Q | Available increases; physical on-hand is unchanged |
| Online payment/order confirmed against reservation | -Q | -Q | Held units become sold; no second on-hand deduction |
| POS sale completed | -Q | 0 | Only unreserved available units can be sold |
| Approved return restock | +Q | 0 | Only inspected resale quantity is restored |
| Accepted purchase receipt | +Q | 0 | Only physically accepted sellable units enter stock |
| Purchase return physically dispatched | -Q | 0 | Stock leaves when handed back, not when requested |
| Damage/write-off | -Q | 0 | Authorized physical loss from sellable on-hand |

Every event must be idempotent and linked to its source record. A payment callback, scheduler retry, receipt retry or return retry must not apply the same quantity transition twice. If Razorpay status is unsettled or cannot be established, preserve the reservation as required by Sections 2 and 10.8; do not release it speculatively.

#### 3. Tender, refund and cash-drawer accounting

Section 10.26's tender records and refund records are authoritative. Section 10.27's cash movements are only for independent drawer movements and must not duplicate tender/refund effects.

- A completed POS sale has confirmed tender allocations totaling exactly the backend-calculated amount due. Initial policy remains fully paid only; split tender is allowed. No customer credit or partially paid completed sale is introduced.
- Cash tender affects drawer cash once through the original confirmed tender.
- A confirmed cash refund reduces drawer cash once through its refund allocation/record.
- `cash_movements` must represent non-sale CASH_IN/CASH_OUT and any separately approved opening-float correction only. Do not create a second `CASH_REFUND` cash movement for a refund already included from the refund table when calculating expected cash.
- Opening float must have one authoritative source. Prefer `pos_shifts.opening_cash_amount` as the opening balance and exclude a duplicate OPENING_FLOAT movement from the expected-cash formula. If implementation instead chooses an opening-float movement, do not also add the shift opening amount. Never count both.
- Expected cash = opening float + confirmed cash tenders + approved cash-in - approved cash-out - confirmed cash refunds, each counted exactly once.
- UPI/card accepted at POS is not proof of bank settlement. Electronic tender reconciliation is separate from cash drawer closing.
- Supplier cash payments from the drawer must be linked to the relevant shift and counted as cash-out once. Supplier payable/settlement records remain distinct from customer POS tenders.

#### 4. Purchase receipt quantity semantics

The phrase “missing quantity” in Section 10.27 must not be treated as goods physically received. Each receipt line must distinguish:

- `delivered_quantity`: units physically present at the shop.
- `accepted_quantity`: delivered units inspected and accepted into sellable inventory.
- `rejected_quantity`: delivered units physically present but rejected/damaged and not added to sellable inventory.
- `quarantined_quantity`: delivered units held pending inspection/disposition and not sellable.
- `short_quantity`: ordered remaining units not delivered, calculated against the remaining order quantity and not part of delivered quantity.

Require `delivered_quantity = accepted_quantity + rejected_quantity + quarantined_quantity` for a fully inspected receipt line. If inspection is incomplete, keep the line/receipt in a pending-inspection state and do not post uninspected units as sellable. Prevent cumulative delivered quantity from exceeding the approved outstanding ordered quantity unless an owner-authorized over-receipt exception is recorded. A later quarantine-to-sellable decision is a separately authorized stock movement.

#### 5. Invoice numbering and channel scope

Section 9.10's initial sequence key and Section 10.26's shared online/POS sequence requirement must be implemented as one approved policy, not two competing allocators.

- Default design: one shared sequence per `location_id + financial_year + prefix` across online and POS invoices, with a unique constraint on the final invoice number.
- If the accountant/adviser requires separate document-type or channel sequences, add the approved document-type/channel dimension to the sequence key before implementation and document the legal reason. Do not silently allocate independent online and POS counters.
- Allocate under a row lock; never use `MAX(invoice_number) + 1`.
- Financial year is India financial year and calculated in IST. Store the issuance timestamp consistently in the agreed database timezone convention.
- Issued numbers are never reused. The policy for a failed transaction after number reservation, voided invoices and invoice issue timing must be finalized with the accountant/adviser. The implementation must preserve an auditable void/gap record if the approved numbering policy requires it.
- An online invoice must remain attached to its online order and must not create a duplicate POS `sales` row.

#### 6. Prescription ownership and private-file separation

Prescription data must not be duplicated into sale-item JSON and a separate prescription record as two competing sources of truth.

- Use a dedicated versioned prescription record with private object metadata and controlled access as required by Section 10.18.
- A prescription record must have one unambiguous owner context: customer/prescription history and, where applicable, a specific online order item or POS sale item. If a single table supports both channels, enforce exactly one transaction-item owner (XOR rule) or use explicit linking tables with constraints that prevent ambiguous ownership.
- Preserve the prescription snapshot/version used for a particular order or sale; later edits to a customer's reusable prescription must not rewrite historical transaction context.
- MySQL stores structured values and private object keys/metadata only. Object storage remains private; access is backend-authorized and audited. Do not put prescription values or object URLs into ordinary invoices, barcode labels, logs or broad cashier responses.
- Do not complete a transaction that requires a prescription if required data/file persistence has failed.

#### 7. Staff, approval and audit consistency

- Section 10.27's `staff_accounts` and Section 10.21's existing-admin migration must be treated as one staged identity migration. Do not create parallel, unlinked admin and staff identities that produce ambiguous actors.
- Every new POS/purchasing/stock/cash/audit record must resolve to a staff actor. Nullable legacy actor columns may remain for historical backfill, but new privileged operations require a verified actor.
- Role and permission checks occur in backend services/controllers. A role change or disabled account must invalidate/re-evaluate existing sessions promptly.
- Approval requests are bound to exact target, amount/quantity, requested operation, requester, approver, expiry and one-time consumption. They cannot be replayed or altered after approval.
- Audit events are append-only and must capture actor, action, target, timestamp, outcome, reason and approval linkage as applicable. Do not log credentials, tokens, prescription contents or private object URLs.
- Reopening a closed shift, approving a refund, changing stock, receiving/returning goods or changing permissions must create explicit audit history; never rewrite the original business event.

### C. Required logical constraints and indexes before implementation

The physical DDL review must translate the following into actual MySQL foreign keys, unique keys, indexes, CHECK constraints where enforced by the selected MySQL version, and service-level transactional guards:

| Area | Required constraint/index |
|---|---|
| Product variants | FK to parent product; stable unique SKU; normalized non-empty barcode uniqueness; active/default variant rules |
| Inventory | Unique `(location_id, variant_id)`; non-negative on-hand/reserved; reserved not greater than on-hand |
| Stock ledger | FK variant/location; index by variant/location/time and reference; unique idempotency key when supplied |
| Online order items | Explicit variant mapping before cutover; index/FK to variant; preserve legacy product and order references |
| POS sales | Unique sale number; unique final invoice number; FK location/customer/staff; indexes for location/date/status and customer/date |
| Sale items | FK sale/variant; positive quantity; immutable snapshots; index by sale and variant |
| POS tenders | FK sale/shift/staff; positive amount; idempotency uniqueness; provider reference uniqueness scoped to provider where appropriate |
| Refunds | FK original sale/return/tender; idempotency; cumulative refund cap enforced transactionally |
| Fulfilment | One state record per sale; append-only event history; actor/time/status indexes |
| Invoice sequence | Unique approved sequence scope; transactional row lock; final invoice unique |
| Registers/shifts | Unique register code per location; at most one OPEN/CLOSING shift per register enforced using a DB-compatible strategy plus row locking |
| Cash movements | FK shift/staff/approver; idempotency; positive amount; controlled direction/type |
| Suppliers/purchases | Supplier and location FKs; unique purchase/receipt numbers in scope; variant-level lines; receipt idempotency |
| Goods receipts | Delivered disposition math; cumulative ordered quantity guard; accepted stock update and ledger insert atomic |
| Purchase returns/settlements | Link to original receipt/order; idempotency; dispatch and financial settlement remain separate |
| Customers | Non-unique normalized phone index initially; no automatic merge; optional unique policy only after duplicate analysis |
| Prescriptions/files | Explicit owner relationship; private object key; access/audit indexes; no public URL |
| Staff/permissions | Unique normalized username and stable codes; unique role-permission pair; session revocation/version mechanism |
| Audit events | Actor/time/action/target indexes; append-only access policy; avoid sensitive payloads |

MySQL partial unique indexes are not generally available in the same way as some other databases. For “unique only while active” requirements, use a reviewed MySQL-compatible design (for example, a generated nullable key or a locked parent-row guard) rather than assuming a filtered unique index exists. The selected MySQL version must be confirmed before relying on CHECK constraint enforcement or generated-column behaviour.

### D. Current-backend compatibility and safe cutover gates

The existing backend audit identified these compatibility risks:

1. `Product` currently has one `stockQuantity` and no variant entity/SKU/barcode model. `ProductService` writes stock through product add/update; `ProductController` also has a direct repository-save creation path. Both must be reviewed and consolidated so product edits cannot bypass the inventory ledger.
2. `OrderItem` and `OrderItemRequest` currently store product identity without a variant reference. Existing online cart/order requests, historical rows and Razorpay payment lifecycle need a deterministic variant mapping before variant stock is authoritative.
3. `ProductRepository.reserveStock/releaseStock` and the existing 60-second reservation scheduler currently operate on product stock. Preserve the current fail-closed Razorpay checks while designing and testing a variant/location replacement. Do not run old and new reservation deductions simultaneously.
4. Existing admin authentication has an `Admin` entity and JWT path without role authorities. Additive staff/role migration must be tested with the current login and bootstrap initializer before enforcing the new permission matrix.
5. Existing product deletion and product-update stock editing are incompatible with an append-only inventory ledger and historical sale/purchase references. Replace with controlled lifecycle/adjustment operations before production POS history is created.
6. Existing Hibernate `ddl-auto=update` is not the production migration plan. Select Flyway or Liquibase, pin the deployed MySQL version, review generated DDL and run migrations against a sanitized production-like copy.
7. Prescription image storage, access authorization, object retention and backup integration must be implemented before POS/online prescription workflows depend on the new records.
8. The POS is online-first. No offline sale or offline inventory mutation may be introduced by the database migration.

Cutover must be staged: verified backup and restore; schema-only additive migration; variant/catalogue mapping; inventory reconciliation; online-order variant backfill; dual-read/controlled validation where safe; staging tests; a controlled write-path switch; reconciliation monitoring; and a documented rollback that does not lose orders, payments or stock movements. Do not dual-write both legacy and new stock balances without a specifically designed reconciliation protocol.

### E. Decisions still requiring explicit approval before executable DDL

The database model is now sufficiently reconciled for a physical-schema design pass, but the following are not silently decided by this document:

1. Final customer phone policy, duplicate matching and profile merge rules.
2. Final variant option model and migration mapping for existing products/specifications; ambiguous legacy products require manual resolution.
3. Exact POS electronic payment verification method/provider/terminal process for UPI and card.
4. Accountant-approved invoice wording, online invoice issuance timing, sequence scope, prefix and void/gap treatment.
5. Final discount limits and manager/owner approval thresholds.
6. Cash opening/closing procedure, cash discrepancy review threshold and permitted shift-reopen authority.
7. Supplier payment terms, supplier invoice matching and whether supplier payments are recorded in POS cash shifts or through a separate bank/cash workflow.
8. Final purchase over-receipt tolerance and handling of quarantined/damaged goods.
9. Weighted-average cost formula, landed-cost allocation and valuation treatment for purchase returns, discounts and freight.
10. Exact prescription structured fields, versioning/retention period, object-storage configuration and access roles.
11. Final stock-count cutoff and reconciliation process, including the online orders/reservations active at cutover.
12. Deployed MySQL version, collation, SQL mode, timezone convention, CHECK constraint behaviour and Flyway/Liquibase choice.
13. Backup RPO/RTO targets and verified database/object restore procedure.
14. Whether the first release remains one active location in the UI while retaining location-scoped schema support.

These decisions should be resolved in the relevant implementation-specific design task before writing migration SQL. If an item is not relevant to the first release, record it as explicitly deferred with a safe default; do not leave an ambiguous schema branch.

### F. Phase 1 review conclusion and acceptance gate

**Review conclusion:** The conceptual design and later reconciliations are aligned around separate online/POS transactions, shared variant-level inventory, fully paid POS sales with split tender, immutable transaction snapshots, auditable returns/refunds, controlled staff permissions and receipt-driven purchasing. The older product-level inventory and early payment baselines are explicitly superseded.

**Not yet approved for executable DDL:** The unresolved decisions in Section E, the exact deployed MySQL capabilities, and the migration/backfill plan must be confirmed. This document alone is not sufficient authorization to modify the database or application.

Before Phase 1 can be marked implementation-ready, produce and review:

- A canonical table/column/FK/index dictionary with no duplicate sources of truth.
- An entity-relationship diagram matching that dictionary.
- A migration/backfill and rollback plan mapped to the actual current entities and rows.
- A MySQL-version-specific DDL review.
- A transaction/invariant test matrix for online reservations, POS sale, tender/refund, shift closing, goods receipt and return.
- Written sign-off for the remaining business/accounting decisions that affect schema behaviour.

This section is a documentation review only. It does not create or alter Java code, SQL migrations, database tables/data, authentication, inventory, payments or production configuration.
