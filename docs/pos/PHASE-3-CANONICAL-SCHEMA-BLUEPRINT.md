# NMK Opticals POS — Phase 3 Canonical Schema Blueprint

**Status:** Logical/physical schema blueprint for review; not executable SQL.  
**Branch:** `master`  
**Prerequisites:** Phase 1 database foundation + live inspection; Phase 2 inventory architecture.  
**Safety:** This file does not change production MySQL, Java entities, APIs, stock, orders or payments.

## 1. Purpose and authority

This blueprint consolidates the POS database into one canonical table map and resolves the old product-level inventory proposal in Phase 1 in favour of Phase 2's variant/location model.

- Existing online tables remain authoritative for online orders and Razorpay.
- POS sales are separate transactions; online orders are not copied into POS sales.
- Inventory is shared between website and shop at variant + location granularity.
- Stock summary is a fast balance; append-only stock movements are its audit source.
- Invoice, tender, return, purchase, shift and audit records are immutable business history.
- The existing 7-product catalogue and 27 legacy stock units are not treated as verified variants or physical opening stock.
- NMK is not GST-registered currently. Tax/invoice fields remain configurable; do not apply GST by assumption.

## 2. Existing tables — preserve

| Existing table | POS compatibility rule |
|---|---|
| `products` | Retain as parent catalogue during transition. Product edits must not directly mutate new inventory balances. |
| `customer_orders` | Keep online order/payment/tracking flow; additive nullable customer/variant links only after reviewed migration. |
| `order_items` | Preserve price/name/prescription snapshots. Add variant mapping only after deterministic backfill. |
| `admins` | Existing login/bootstrap remains functional during staged staff migration. |
| `admin_action_authorizations` | Preserve one-time destructive-action authorization; do not weaken it. |
| `appointments` | Keep separate from POS sales/inventory. |
| `marketing_banners`, `offer_bars`, `website_images` | No POS schema coupling. |
| `razorpay_webhook_events` | Preserve provider-event idempotency and payload retention policy; never mix POS cash/UPI/card tenders into it. |

No existing table is dropped or renamed in the first additive migration.

## 3. Canonical table inventory

### Catalogue and inventory (core)

| Table | Purpose | Key uniqueness / relationships |
|---|---|---|
| `product_variants` | SKU/barcode/options for a sellable item under a parent product | FK `product_id → products.id`; unique SKU; normalized barcode unique when present |
| `inventory_locations` | Shop/branch/stockroom identity | unique location code; first active location = Avalurpet |
| `inventory_balances` | Current quantity summary per location/variant | unique `(location_id, variant_id)`; FK to location and variant |
| `stock_movements` | Append-only quantity/reservation ledger | FK location/variant; unique idempotency key; indexes variant+location+time and source reference |
| `online_order_item_variants` or additive `order_items.variant_id` | Exact variant mapping for online lines | one mapping per order item; no ambiguous legacy mapping |

### Customer and POS transactions

| Table | Purpose | Key uniqueness / relationships |
|---|---|---|
| `customers` | Optional reusable customer profile | normalized phone index; duplicates handled manually, not auto-merged |
| `customer_addresses` | Reusable address book | FK customer; customer+default policy enforced transactionally |
| `sales` | POS sale header | unique sale number; FK location/customer/staff; status and date indexes |
| `sale_items` | POS line and sale-time snapshots | FK sale/variant; positive quantity; snapshot name/SKU/price/discount |
| `sale_tenders` | One or more tender allocations per sale | FK sale/shift/staff; idempotency; provider reference uniqueness where applicable |
| `sale_fulfilment_events` | Collection/delivery state history | FK sale/staff; append-only event history |
| `returns` | Return/refund header linked to original sale | unique return number; FK sale/staff; status and reason |
| `return_items` | Return quantities/refund/restock disposition | FK return + original sale item; quantity and cumulative-return guards |
| `invoice_sequences` | Locked sequence allocator | unique approved scope (location + FY + prefix, unless adviser approves another scope) |
| `invoice_voids` (or invoice status/event record) | Auditable void/gap record | unique invoice number; actor/reason/time; never reuse issued number |

### Staff, registers and cash

| Table | Purpose | Key uniqueness / relationships |
|---|---|---|
| `staff_accounts` | POS actor identity staged from existing admins | unique normalized username; disabled/session revocation support |
| `staff_roles` | Controlled role catalogue | unique role code |
| `staff_permissions` | Controlled permission catalogue | unique permission code |
| `staff_role_permissions` | Role-permission mapping | unique role+permission pair |
| `staff_account_roles` | Staff-role assignment | unique staff+role pair; audited changes |
| `pos_registers` | Physical register/device identity | unique location+register code |
| `pos_shifts` | Cashier shift and opening/closing state | FK register/staff; one open/closing shift per register via locked guard |
| `cash_movements` | Cash in/out/refund/float changes | FK shift/staff/approver; idempotency; positive amount + controlled direction |
| `audit_events` | Security and business action audit | actor/time/action/target indexes; append-only |

### Purchasing and supplier stock

| Table | Purpose | Key uniqueness / relationships |
|---|---|---|
| `suppliers` | Supplier master | supplier code; active status |
| `purchase_orders` | Supplier order header | unique PO number; FK supplier/location/staff |
| `purchase_order_items` | Ordered variant quantities/cost snapshots | FK PO/variant; positive ordered quantity |
| `goods_receipts` | Physical delivery/inspection header | unique receipt number; FK PO/location/staff |
| `goods_receipt_items` | Delivered/accepted/rejected/quarantined quantities | FK receipt and PO line; disposition quantity equation |
| `purchase_returns` | Return-to-supplier dispatch header | unique return number; FK supplier/receipt/staff |
| `purchase_return_items` | Variant quantities dispatched | FK return/receipt line/variant; cannot exceed eligible received balance |
| `supplier_settlements` (defer if not needed) | Supplier payment/settlement records | separate from customer sale tenders and cash-shift movements |

### Prescription/private-file ownership (before POS prescription workflows)

| Table | Purpose | Key uniqueness / relationships |
|---|---|---|
| `prescriptions` | Versioned structured prescription and private object metadata | one unambiguous customer + transaction-item owner context; access audit |
| `prescription_access_events` | Access/change audit for private prescription records | actor/time/prescription/action; no values or URLs in logs |

Prescription images stay in private object storage. MySQL stores private object keys/metadata only. Existing online prescription JSON is not deleted or rewritten during initial migration.

## 4. Core column contract

The following is the canonical logical column contract. Exact SQL types, names, FK delete rules, indexes and MySQL constraints are to be finalized in the DDL review.

### 4.1 `product_variants`

- `id` BIGINT PK
- `product_id` BIGINT NOT NULL FK products
- `sku` VARCHAR(100) NOT NULL UNIQUE
- `barcode` VARCHAR(100) NULL (normalized; unique when non-empty)
- `variant_name` VARCHAR(255) NOT NULL
- `attributes` JSON NOT NULL (validated category-specific attributes)
- `selling_price_override` DECIMAL(12,2) NULL
- `active` BOOLEAN NOT NULL
- `created_at`, `updated_at`

Effective selling price = variant override when set, otherwise parent product price. Price must be resolved server-side and snapshotted into sale/order lines. SKU is stable and not derived dynamically from mutable product text.

### 4.2 `inventory_locations`

- `id` BIGINT PK
- `code` VARCHAR(40) NOT NULL UNIQUE
- `name` VARCHAR(150) NOT NULL
- `address_snapshot` VARCHAR(1000) NULL
- `active` BOOLEAN NOT NULL
- `created_at`, `updated_at`

One initial active shop location. Location is mandatory on every inventory balance/movement and POS sale.

### 4.3 `inventory_balances`

- `id` BIGINT PK
- `location_id` BIGINT NOT NULL FK inventory_locations
- `variant_id` BIGINT NOT NULL FK product_variants
- `on_hand_quantity` INT NOT NULL DEFAULT 0
- `reserved_quantity` INT NOT NULL DEFAULT 0
- `reorder_level` INT NOT NULL DEFAULT 0
- `version` BIGINT NOT NULL DEFAULT 0 (if optimistic locking is retained)
- `updated_at`
- UNIQUE `(location_id, variant_id)`

Invariant: `0 <= reserved_quantity <= on_hand_quantity`. Available is derived: on-hand minus reserved. Never store an independently editable available quantity.

### 4.4 `stock_movements`

- `id` BIGINT PK
- `location_id`, `variant_id` NOT NULL FK
- `movement_type` VARCHAR(40) NOT NULL (controlled enum/catalogue)
- `quantity_delta` INT NOT NULL (on-hand delta; signed)
- `reserved_delta` INT NOT NULL DEFAULT 0 (reserved delta; signed)
- `on_hand_after`, `reserved_after` INT NOT NULL
- `reference_type` VARCHAR(40) NULL
- `reference_id` BIGINT NULL
- `idempotency_key` VARCHAR(150) NOT NULL UNIQUE
- `actor_staff_id` BIGINT NULL only for explicitly system-generated events; FK staff after identity migration
- `reason` VARCHAR(500) NULL
- `created_at`
- Optional immutable SKU/name snapshots for readable history

Movement sign convention: positive delta increases the respective balance; negative delta decreases it. Ledger insert and balance mutation must be one transaction.

### 4.5 `customers` and `customer_addresses`

Customers:
- `id` BIGINT PK
- `name` VARCHAR(150) NOT NULL
- `phone` VARCHAR(20) NULL (normalized where present)
- `email` VARCHAR(254) NULL
- `active` BOOLEAN NOT NULL
- `created_at`, `updated_at`

Addresses:
- `id` BIGINT PK
- `customer_id` BIGINT NOT NULL FK customers
- `address_line` VARCHAR(1000) NULL
- `city`, `state` VARCHAR(120) NULL
- `pincode` VARCHAR(20) NULL
- `is_default` BOOLEAN NOT NULL
- `created_at`, `updated_at`

A walk-in sale may have no customer. Do not require a profile or auto-merge profiles by phone.

### 4.6 `sales`

- `id` BIGINT PK
- `sale_number` VARCHAR(50) NOT NULL UNIQUE
- `invoice_number` VARCHAR(50) NULL UNIQUE until invoice issuance policy is approved
- `location_id` BIGINT NOT NULL FK
- `customer_id` BIGINT NULL FK
- `sale_status` VARCHAR(30) NOT NULL (DRAFT/COMPLETED/VOIDED as approved)
- `subtotal`, `discount_amount`, `tax_amount`, `total_amount` DECIMAL(12,2) NOT NULL
- `sold_at` DATETIME NULL until completion
- `created_by_staff_id` BIGINT NOT NULL FK
- `completed_by_staff_id` BIGINT NULL FK
- `notes` VARCHAR(1000) NULL
- `created_at`, `updated_at`

Store customer/contact and invoice text snapshots when required by final invoice policy. No assumption of GST registration or GST tax application.

### 4.7 `sale_items`

- `id` BIGINT PK
- `sale_id` BIGINT NOT NULL FK sales
- `variant_id` BIGINT NOT NULL FK product_variants
- `product_name_snapshot` VARCHAR(255) NOT NULL
- `variant_name_snapshot` VARCHAR(255) NOT NULL
- `sku_snapshot` VARCHAR(100) NOT NULL
- `quantity` INT NOT NULL CHECK > 0
- `unit_price`, `discount_amount`, `tax_amount`, `line_total` DECIMAL(12,2) NOT NULL
- `created_at`

Never recalculate historical line price/name from current catalogue data.

### 4.8 `sale_tenders`

- `id` BIGINT PK
- `sale_id` BIGINT NOT NULL FK sales
- `shift_id` BIGINT NOT NULL FK pos_shifts
- `payment_method` VARCHAR(30) NOT NULL (CASH/UPI/CARD/other approved)
- `payment_status` VARCHAR(30) NOT NULL
- `amount` DECIMAL(12,2) NOT NULL > 0
- `reference_number` VARCHAR(150) NULL
- `idempotency_key` VARCHAR(150) NOT NULL UNIQUE
- `received_by_staff_id` BIGINT NOT NULL FK staff_accounts
- `paid_at` DATETIME NULL
- `created_at`

Split tender allowed. Completion requires sum of confirmed tender amounts = backend-calculated total due. Provider/terminal references must be unique in the appropriate provider scope. Cash drawer and electronic settlement reconciliation remain distinct.

### 4.9 `returns` and `return_items`

Return header:
- `id` BIGINT PK
- `return_number` VARCHAR(50) NOT NULL UNIQUE
- `sale_id` BIGINT NOT NULL FK sales
- `return_status` VARCHAR(30) NOT NULL
- `reason` VARCHAR(500) NOT NULL
- `refund_amount` DECIMAL(12,2) NOT NULL
- `requested_by_staff_id`, `approved_by_staff_id` FK staff
- `created_at`, `approved_at`, `completed_at`

Return line:
- `id` BIGINT PK
- `return_id` BIGINT NOT NULL FK returns
- `sale_item_id` BIGINT NOT NULL FK sale_items
- `quantity` INT NOT NULL > 0
- `restock_disposition` VARCHAR(30) NOT NULL (PENDING/RESTOCK/DAMAGED/QUARANTINE/NO_RESTOCK)
- `refund_amount` DECIMAL(12,2) NOT NULL
- `created_at`

Cumulative returned quantity cannot exceed original sold quantity. Refund and restock are separately approved/posted. Restock movement occurs only after physical inspection.

### 4.10 `invoice_sequences` and void history

Sequence:
- `id` BIGINT PK
- `location_id` BIGINT NOT NULL FK
- `financial_year` VARCHAR(9) NOT NULL
- `prefix` VARCHAR(20) NOT NULL
- `next_number` BIGINT NOT NULL
- UNIQUE `(location_id, financial_year, prefix)`

Allocate by locking the sequence row. Never use MAX(invoice_number)+1. Final issued invoice number has a unique constraint. Keep an invoice-void/event record for numbers that cannot be reused. Accountant/adviser must approve prefix, scope, issue timing and gap/void policy before implementation.

### 4.11 `staff_accounts`, roles and permissions

Staff:
- `id` BIGINT PK
- `username` VARCHAR(100) NOT NULL UNIQUE (normalized)
- `display_name` VARCHAR(150) NOT NULL
- `password_hash` VARCHAR(255) NOT NULL
- `active` BOOLEAN NOT NULL
- `session_version` BIGINT NOT NULL DEFAULT 0
- `created_at`, `updated_at`

Role/permission catalogue:
- `staff_roles(id, code UNIQUE, name, active)`
- `staff_permissions(id, code UNIQUE, description)`
- `staff_role_permissions(role_id FK, permission_id FK, UNIQUE(role_id, permission_id))`
- `staff_account_roles(staff_id FK, role_id FK, UNIQUE(staff_id, role_id))`

Existing admins migrate in a staged, tested identity mapping. Do not create unlinked parallel cashier/admin identities. Every privileged action is authorized in backend and audited.

### 4.12 `pos_registers`, `pos_shifts`, `cash_movements`

Registers:
- `id` BIGINT PK
- `location_id` BIGINT NOT NULL FK
- `register_code` VARCHAR(40) NOT NULL
- `name` VARCHAR(100) NOT NULL
- `active` BOOLEAN NOT NULL
- UNIQUE `(location_id, register_code)`

Shifts:
- `id` BIGINT PK
- `register_id` BIGINT NOT NULL FK
- `opened_by_staff_id` BIGINT NOT NULL FK
- `closed_by_staff_id` BIGINT NULL FK
- `shift_status` VARCHAR(20) NOT NULL (OPEN/CLOSING/CLOSED)
- `opening_float` DECIMAL(12,2) NOT NULL
- `expected_cash`, `counted_cash`, `cash_difference` DECIMAL(12,2) NULL
- `opened_at`, `closed_at` DATETIME NULL
- `close_notes` VARCHAR(1000) NULL
- `created_at`

Enforce one active OPEN/CLOSING shift per register through a locked register row and service guard (plus MySQL-compatible constraint strategy). Cash expected formula counts opening float exactly once.

Cash movements:
- `id` BIGINT PK
- `shift_id` BIGINT NOT NULL FK
- `direction` VARCHAR(10) NOT NULL (IN/OUT)
- `movement_type` VARCHAR(30) NOT NULL (FLOAT_IN/SALE_CASH/REFUND_CASH/CASH_IN/CASH_OUT/SUPPLIER_PAYMENT/etc.)
- `amount` DECIMAL(12,2) NOT NULL > 0
- `reason` VARCHAR(500) NOT NULL
- `idempotency_key` VARCHAR(150) NOT NULL UNIQUE
- `created_by_staff_id`, `approved_by_staff_id` FK staff
- `created_at`

Cash movements do not replace supplier payable/settlement records. Cash refunds and supplier cash-outs must be counted once.

### 4.13 Purchasing and goods receipt

Suppliers:
- `id` BIGINT PK
- `supplier_code` VARCHAR(50) NOT NULL UNIQUE
- `name` VARCHAR(200) NOT NULL
- `phone`, `email`, `address` nullable
- `active` BOOLEAN NOT NULL
- `created_at`, `updated_at`

Purchase order:
- `id` BIGINT PK
- `po_number` VARCHAR(50) NOT NULL UNIQUE
- `supplier_id`, `location_id` FK
- `status` VARCHAR(30) NOT NULL
- `ordered_at`, `created_at`
- `created_by_staff_id` FK staff
- `notes` VARCHAR(1000) NULL

Purchase lines:
- `id` BIGINT PK
- `purchase_order_id` BIGINT NOT NULL FK
- `variant_id` BIGINT NOT NULL FK
- `ordered_quantity` INT NOT NULL > 0
- `unit_cost`, `discount_amount`, `landed_cost` DECIMAL(12,2) (costing policy pending)
- `created_at`

Goods receipt:
- `id` BIGINT PK
- `receipt_number` VARCHAR(50) NOT NULL UNIQUE
- `purchase_order_id` BIGINT NOT NULL FK
- `location_id` BIGINT NOT NULL FK
- `status` VARCHAR(30) NOT NULL (DRAFT/PENDING_INSPECTION/ACCEPTED/PARTIAL/REJECTED)
- `received_at`, `accepted_at`
- `received_by_staff_id` FK staff
- `idempotency_key` VARCHAR(150) NOT NULL UNIQUE

Receipt lines:
- `id` BIGINT PK
- `goods_receipt_id` BIGINT NOT NULL FK
- `purchase_order_item_id` BIGINT NOT NULL FK
- `delivered_quantity`, `accepted_quantity`, `rejected_quantity`, `quarantined_quantity`, `short_quantity` INT NOT NULL
- `unit_cost_snapshot` DECIMAL(12,2) NOT NULL
- `disposition_notes` VARCHAR(500) NULL

For a fully inspected line: delivered = accepted + rejected + quarantined. Short quantity is ordered-but-not-delivered, not part of delivered. Only accepted quantity increases sellable on-hand; quarantine disposition later is its own authorized movement.

Purchase return:
- `purchase_returns(id, return_number UNIQUE, supplier_id, source_receipt_id, status, dispatched_at, created_by_staff_id, idempotency_key UNIQUE, created_at)`
- `purchase_return_items(id, purchase_return_id FK, receipt_item_id FK, variant_id FK, quantity > 0, unit_cost_snapshot, created_at)`

Stock reduces on physical dispatch to supplier, not on request. Supplier settlement remains a separate financial record.

### 4.14 `audit_events`

- `id` BIGINT PK
- `actor_staff_id` BIGINT NULL only for system actor
- `action_code` VARCHAR(80) NOT NULL
- `target_type` VARCHAR(80) NOT NULL
- `target_id` VARCHAR(100) NULL
- `outcome` VARCHAR(20) NOT NULL
- `reason` VARCHAR(1000) NULL
- `approval_id` BIGINT NULL
- `created_at`
- Optional safe metadata JSON (never passwords, tokens, prescription values, payment secrets or private object URLs)

Append-only by application policy; indexes actor+time, action+time, target and outcome.

## 5. Relationship map (text ERD)

```text
products 1 ── N product_variants
inventory_locations 1 ── N inventory_balances N ── 1 product_variants
inventory_locations 1 ── N stock_movements N ── 1 product_variants

customers 1 ── N customer_addresses
customers 0..1 ── N sales
inventory_locations 1 ── N sales
staff_accounts 1 ── N sales (creator/completer)

sales 1 ── N sale_items N ── 1 product_variants
sales 1 ── N sale_tenders N ── 1 pos_shifts
sales 1 ── N sale_fulfilment_events
sales 1 ── N returns 1 ── N return_items N ── 1 sale_items

inventory_locations 1 ── N pos_registers 1 ── N pos_shifts
pos_shifts 1 ── N cash_movements

suppliers 1 ── N purchase_orders 1 ── N purchase_order_items N ── 1 product_variants
purchase_orders 1 ── N goods_receipts 1 ── N goods_receipt_items
goods_receipt_items 1 ── N purchase_return_items N ── 1 purchase_returns

staff_accounts N ── N staff_roles (staff_account_roles)
staff_roles N ── N staff_permissions (staff_role_permissions)
staff_accounts 1 ── N audit_events
```

## 6. Transaction and integrity rules

1. Inventory balance update + stock movement + business source record commit atomically.
2. All inventory operations lock affected balance rows in stable location/variant order.
3. No sale may consume reserved quantity.
4. Online reservation/payment/release is idempotent and retains current Razorpay fail-closed behaviour.
5. POS completion requires confirmed tenders equal backend total due.
6. Return quantity cannot exceed original sale quantity; refund/restock are separate state transitions.
7. Purchase receipt accepted quantity is the only receipt quantity posted into sellable on-hand.
8. All stock-changing events use a unique idempotency key.
9. Every privileged mutation has a verified staff actor and audit event.
10. Summary-vs-ledger mismatch is detected and reconciled with an authorized movement, never silent direct edits.
11. Product/variant deactivation does not delete historical transactions or movement records.
12. No hard delete for sales, tenders, invoices, stock movements, returns, receipts or audit history.

## 7. Migration sequence (additive and gated)

1. Pin deployed MySQL version, SQL mode, collation and timezone; choose Flyway/Liquibase and baseline procedure.
2. Create new master/catalogue tables (locations, variants) without changing existing checkout.
3. Review seven existing products and determine exact variant mapping/SKUs; unresolved products remain blocked from stock cutover.
4. Create inventory balance/ledger structures; do not initialize stock yet.
5. Create POS customer, sale, tender, register/shift, return, purchase, receipt, staff/audit structures.
6. Stage staff identity mapping while preserving current admin login.
7. Backfill online order item variant mappings only after evidence review.
8. Prepare physical count, reservation/payment reconciliation, opening movements and restore-tested backup.
9. Validate all acceptance tests in staging.
10. Cut over online checkout and POS together to shared variant/location inventory in a controlled window.
11. Monitor ledger/balance and payment reservation reconciliation; retain rollback path.
12. Drop legacy stock behavior only after explicit approval and verified rollback window.

## 8. Decisions still open (do not guess in DDL)

- Category-specific variant attributes and actual SKU/barcode mapping for the seven current products.
- Exact invoice prefix/format, sequence scope, issue timing, void/gap policy and legal invoice wording (accountant/adviser review).
- Final tax handling while NMK is not GST registered; do not assume GST.
- Customer phone normalization and duplicate/merge policy.
- Discount ceilings and approval thresholds.
- POS UPI/card provider/terminal evidence and settlement reconciliation.
- Return/refund policy and approval thresholds.
- Cash discrepancy threshold and shift reopen authority.
- Supplier over-receipt tolerance, cost/landed-cost formula and supplier settlement design.
- Prescription data schema, retention, object storage and access roles.
- Deployed MySQL capabilities, migration tool, backup RPO/RTO and verified restore process.

## 9. Review checklist before SQL

- [ ] All table names and ownership are approved.
- [ ] Every FK and deletion policy is specified.
- [ ] Every unique/index/constraint is MySQL-version compatible.
- [ ] No duplicate source of truth for stock, price, invoice number or prescription.
- [ ] Online order/payment compatibility and variant backfill are proven.
- [ ] Opening stock is based on a physical count, not legacy `stockQuantity`.
- [ ] Invoice/accounting policy is confirmed.
- [ ] Migration and rollback are rehearsed on sanitized staging data.
- [ ] Automated integration tests cover reservation, payment, POS, return, receipt and reconciliation.

**Completion status:** Logical canonical schema blueprint drafted. Not executable SQL, not a migration, and not authorization to modify production.
