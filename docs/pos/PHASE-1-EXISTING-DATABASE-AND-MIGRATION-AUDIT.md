# NMK Opticals — Phase 1 Existing Database and Data Migration Audit

**Status:** Source/entity mapping audit; live database inspection is pending.  
**Repository:** `Elavarasany/nmk-backend`, branch `master`  
**Scope:** Existing JPA entities, repository relationships, persistence configuration, migration tooling and safe transition to the approved POS schema.  
**Companion documents:** `PHASE-1-DATABASE-DESIGN.md`, `PHASE-1-CANONICAL-SCHEMA.md`, `PHASE-1-BACKEND-COMPATIBILITY-AUDIT.md`, `PHASE-1-PAYMENT-AND-PRESCRIPTION-AUDIT.md`

> This document describes the source files inspected on GitHub. It does not claim that the live MySQL schema exactly matches the annotations. No production database connection, credentials, row counts, SQL metadata, or customer records were accessed. Physical table definitions and existing data must be verified against a sanitized database snapshot before executable migrations are written.

## 1. Executive findings

The existing backend is a working website/admin persistence model, not yet a POS-ready inventory/accounting schema.

Key findings:

1. **Product stock is product-level.** `Product` has one `stockQuantity`; no variant or location inventory entity was found among the inspected entity paths.
2. **Online order items point to products by scalar ID.** `OrderItem.productId` is a `Long`, not a JPA relationship or variant foreign key.
3. **Online stock reservation changes the product quantity directly.** Checkout subtracts from `products.stockQuantity`; safe reservation release adds it back. The current field therefore behaves like available stock during pending checkout, not the canonical physical on-hand quantity.
4. **Order/item relationship is the clearest current transactional FK relationship.** `Order.items` maps to `OrderItem.order`; cascade ALL and orphan removal are enabled.
5. **Payment IDs and most cross-domain IDs are scalar values.** The inspected entities do not declare database relationships for product IDs on order items, Razorpay identifiers, or the admin username stored on action-authorization records.
6. **Existing data has historical value.** Product names/prices and order-item names/prices are stored; prescription data is stored as JSON on order items; order/customer contact and delivery details are snapshots.
7. **The persistence configuration is not migration-controlled.** `spring.jpa.hibernate.ddl-auto=update` is configured. No Flyway or Liquibase dependency was found in the inspected `pom.xml`.
8. **The live database has not been audited.** Actual tables, column types, indexes, foreign keys, MySQL version, SQL mode, row counts, duplicate data and orphan rows remain unknown.

**Recommendation:** Do not write production DDL or switch inventory writes yet. First obtain a verified backup and sanitized schema/data report, then design an additive, versioned migration with explicit reconciliation and rollback gates.

## 2. Source files inspected

The following files were fetched from GitHub `master` and reviewed for this audit:

### Entities
- `nmk-backend/src/main/java/nmk_backend/entity/Product.java`
- `.../entity/Order.java`
- `.../entity/OrderItem.java`
- `.../entity/Admin.java`
- `.../entity/AdminActionAuthorization.java`
- `.../entity/Appointment.java`
- `.../entity/MarketingBanner.java`
- `.../entity/WebsiteImage.java`

### Repositories
- `.../repository/ProductRepository.java`
- `.../repository/OrderRepository.java`
- `.../repository/OrderItemRepository.java`
- `.../repository/AdminRepository.java`
- `.../repository/AdminActionAuthorizationRepository.java`
- `.../repository/AppointmentRepository.java`
- `.../repository/MarketingBannerRepository.java`
- `.../repository/WebsiteImageRepository.java`

### Services, controllers and configuration
- `.../service/OrderService.java`
- `.../service/PaymentVerificationService.java`
- `.../service/StockReservationScheduler.java`
- `.../controller/OrderController.java`
- `.../controller/RazorpayController.java`
- `.../controller/AdminOrderController.java`
- `.../controller/AppointmentController.java`
- `.../controller/MarketingBannerController.java`
- `.../config/SecurityConfig.java`
- `.../config/AdminDataInitializer.java`
- `nmk-backend/src/main/resources/application.properties`
- `nmk-backend/pom.xml`

This is a targeted source review, not a complete repository file-tree enumeration. Entity names/paths not listed above are not asserted to be absent from the entire repository.

## 3. Current entity-to-table map

Table names below are taken from explicit `@Table` annotations where present. Where no explicit table name is shown, the name is a likely Hibernate naming-strategy result and must be confirmed from the live database.

| Entity | Explicit/current table name | Important mapped fields | Relationship notes |
|---|---|---|---|
| `Product` | `products` | id, name, category, gender, description, price, stockQuantity, imageUrl, tryOnImageUrl, active, createdAt, specifications JSON | No variant/location inventory relationship |
| `Order` | `customer_orders` | customer/contact/address snapshots, totalAmount, paymentStatus, orderStatus, reservation fields, Razorpay IDs, delivery fields, tracking token/date | One-to-many to OrderItem |
| `OrderItem` | `order_items` | productId, productName, quantity, price, prescription/lens fields, prescriptionJson | Many-to-one to Order |
| `Admin` | `admins` | id, unique username, password, createdAt | No role or staff relationship |
| `AdminActionAuthorization` | `admin_action_authorizations` | unique tokenHash, adminUsername, action, expiresAt, consumedAt, createdAt | Admin username is stored as text, not FK |
| `Appointment` | likely `appointments` | customer/contact, date/time, status, createdAt | No customer FK or slot uniqueness constraint shown |
| `MarketingBanner` | `marketing_banners` | title, imageUrl, targetUrl, placement, active, displayOrder, timestamps | No FK shown |
| `WebsiteImage` | `website_images` | unique imageKey, title, imageUrl, updatedAt | No FK shown |

The live database may contain additional tables created by entities not covered in this targeted inspection, earlier schema versions, manual SQL, or previous application releases. The table inventory must be completed from `information_schema` before DDL design.

## 4. Existing entity details and migration implications

### 4.1 Products — `products`

**Observed mapping:**

- Primary key: generated `Long id`.
- `name` is non-null.
- `price` is non-null `DECIMAL(10,2)` according to the JPA precision/scale annotation.
- `stockQuantity` is non-null `Integer`.
- `active` is a primitive boolean with Java default true.
- `specifications` is a JSON-typed `Map<String,Object>`.
- Product images are URL strings.
- No `@Version`, variant entity, SKU, barcode, location or inventory relationship was observed.

**Migration risks:**

- Do not rename or drop `stockQuantity` before the legacy value is reconciled and the replacement inventory summary is populated.
- `specifications` may contain variant-defining attributes (such as colour/size/shape) but is not a normalized variant catalogue. Its real JSON keys and value formats must be profiled.
- A parent product may have multiple sellable combinations hidden in JSON or represented inconsistently. Automatic variant creation is unsafe when the mapping is ambiguous.
- Current `price` is the parent/product price. The canonical schema needs a default product price and optional variant override. Preserve historical order-item prices rather than recalculating them from today's product price.
- Product creation/update paths must be reviewed together. The earlier compatibility audit found a direct repository-save path in `ProductController` as well as `ProductService` stock updates. All stock-changing paths must be retired or routed through the inventory ledger before cutover.

**Decision:** Preserve product IDs and current catalogue rows. Add variants and inventory additively. Keep the legacy stock field read-only/compatibility-only during a controlled transition; do not use it as the sole authoritative cutover quantity.

### 4.2 Online orders — `customer_orders`

**Observed mapping:**

- Generated `Long id`.
- Customer name, email, phone, address, city, state and pincode are stored on the order.
- `totalAmount` is `BigDecimal`.
- Payment and fulfilment/order status are strings.
- `reservation_expires_at` and `stock_reserved` record reservation lifecycle.
- Razorpay order/payment IDs are strings without explicit uniqueness annotations in the inspected entity.
- `trackingToken` is unique and length-limited to 64.
- `orderDate` is set at persistence time.
- `items` is one-to-many with cascade ALL and orphan removal.

**Migration risks:**

- Keep existing order IDs and order/item references unchanged.
- Keep existing customer and address fields as immutable order-time snapshots; a future customer profile must not rewrite past orders.
- Do not reinterpret the current `paymentStatus` and `orderStatus` strings without profiling all distinct values in the live data.
- Determine whether any orders have null/duplicate Razorpay IDs, unusual payment states, missing tracking tokens, or inconsistent `stockReserved`/expiry values.
- Never delete or recreate paid orders as part of migration.
- The current cascade/orphan-removal relationship means deleting an order or removing its item collection can delete order items. Preserve this historical relationship for existing code only until a reviewed retention strategy is in place; new financial history must not be cascade-deleted.
- `AdminOrderController.clearAllOrders` is a destructive operation guarded by a one-time authorization token. It must never be used for migration cleanup or reconciliation.

**Decision:** Retain `customer_orders` as the online order source of truth. Add new relationships/records only after mapping and preserving existing IDs and payment references.

### 4.3 Online order items — `order_items`

**Observed mapping:**

- Generated `Long id`.
- `productId` is a scalar `Long`, not an annotated FK to Product.
- Product name, quantity and unit price are stored on the item.
- Prescription type, lens type, photochromic and `prescription_json` are stored on the item.
- `prescription_json` is a string column with declared length 4000.
- `order_id` is a non-null many-to-one relation to `Order`.

**Migration risks:**

- Historical `productName` and `price` are transaction snapshots. Do not overwrite them using the current product catalogue.
- Existing order items have no variant ID. Map `productId` to a variant only when source product/specifications/cart data provide an unambiguous match.
- If a product had no variant distinction at sale time, create a documented legacy/default variant only after owner-approved reconciliation.
- Ambiguous variant history must remain explicitly unresolved; do not guess based on current product options.
- Preserve prescription JSON byte-for-byte or via a verified lossless copy during the initial migration. Do not parse and rewrite all legacy prescription values in one bulk update.
- Validate the physical column length and actual data lengths; the JPA annotation alone does not establish the live column definition.

**Decision:** Add a nullable `variant_id` mapping as a staged migration after the variant mapping rules and data-quality report are approved. Do not make it non-null until all required rows are mapped and verified.

### 4.4 Admin identity and sensitive action authorization

**Observed mapping:**

- `admins` has unique username, password and createdAt.
- No role, enabled/disabled state, staff code or session-version field appears in the inspected Admin entity.
- `admin_action_authorizations` stores a token hash, admin username, action, expiry and consumption timestamp.
- Token hash has a unique constraint; the repository uses a pessimistic lock for one-time consumption.
- Admin bootstrap creates an admin only when environment variables are supplied and the username does not already exist.

**Migration risks:**

- Do not replace or delete the existing admin account during the first POS schema migration.
- A new staff/role model must link to the existing identity in a controlled migration; do not create a second unlinked account for the same person.
- Existing authorization rows refer to username as text. Renaming usernames needs a deliberate mapping and must not silently invalidate audit ownership.
- Existing action authorization is not a full staff role/approval/audit model.

**Decision:** Preserve `admins` and current login behaviour during an additive staff migration. Role enforcement is a separate tested release, not an incidental schema change.

### 4.5 Appointments, banners and website images

These are not core POS inventory tables but are existing website/admin data and must not be dropped or renamed during POS migration.

- `appointments`: preserve booking history, contact details, selected date/time and status. The inspected repository checks for a conflicting date/time/status in application logic, but no database unique constraint was seen. Concurrent bookings need a later dedicated integrity review.
- `marketing_banners`: preserve placement, active state, display order and image/target URLs. The inspected entity does not show a foreign key.
- `website_images`: preserve unique `imageKey`, title, URL and update timestamp. The entity explicitly marks `imageKey` unique.
- The admin initializer inserts website image defaults only when a key is missing. Do not re-run destructive seed/reset logic or replace existing image rows as part of POS migration.

## 5. Current relationship and constraint map

### 5.1 Relationships established in inspected JPA source

| Parent | Child | Mapping | Delete behaviour |
|---|---|---|---|
| `customer_orders` / Order | `order_items` / OrderItem | One-to-many / many-to-one; child `order_id` non-null | Cascade ALL + orphanRemoval on Order.items |

### 5.2 Scalar references that are not established as JPA foreign keys in inspected entities

- `order_items.productId` → logically references `products.id`, but no JPA relation/FK annotation is declared on this field.
- `customer_orders.razorpayOrderId` and `razorpayPaymentId` → provider identifiers, not database foreign keys.
- `admin_action_authorizations.adminUsername` → logical username reference, not an FK to `admins`.
- `appointments` has no customer relation.
- Marketing banner and website image URL fields are external references, not relational FKs.

### 5.3 Explicit uniqueness observed in source

- `admins.username` — unique.
- `admin_action_authorizations.token_hash` — unique at table/column annotation level.
- `customer_orders.trackingToken` — unique.
- `website_images.imageKey` — unique.

No explicit uniqueness was observed in the inspected entities for Razorpay order/payment IDs, appointment date/time slot, product SKU/barcode, or product/location inventory (the latter does not yet exist in inspected source).

**Important:** JPA annotations do not prove the deployed database has exactly these constraints. Hibernate may have applied some schema changes over time, manual DDL may differ, and older rows may violate newly expected constraints. Verify the actual indexes and constraints from MySQL metadata.

## 6. Current stock semantics — critical cutover blocker

**Observed in `OrderService.createPendingOrder`:**

- Aggregates quantities by product ID.
- Calls `ProductRepository.reserveStock` for each product.
- The repository subtracts the requested quantity from `Product.stockQuantity` only if the product is active and has sufficient stock.
- A pending order is saved with `stockReserved=true` and an expiry around 15 minutes later.

**Observed in reservation release:**

- The scheduler checks Razorpay state before releasing expired reservations.
- Release locks the local order and rechecks reservation/payment/expiry state.
- `ProductRepository.releaseStock` adds the reserved quantity back to the product stock.
- Payment confirmation marks the reservation consumed and does not deduct stock again.

**Interpretation:** The current product stock field is reduced at reservation time. It is therefore closer to *available-to-sell quantity* while an order is pending than to the canonical *physical on-hand quantity*.

The approved canonical model uses:

`available_quantity = on_hand_quantity - reserved_quantity`

- Online reservation: on-hand unchanged; reserved increases.
- Online payment confirmation: on-hand decreases and reserved decreases together.
- Safe expiry/cancellation: reserved decreases only.
- POS sale: on-hand decreases only when unreserved available stock is sufficient.

**Do not copy legacy `stockQuantity` directly into canonical `on_hand_quantity`.** That would overstate physical stock if active reservations have already been subtracted. Also do not add all pending order quantities blindly: only active, valid, not-yet-released reservations belong in the reserved balance, and paid/failed/cancelled/expired orders must be reconciled against provider state and local flags.

### 6.1 Required inventory reconciliation inputs

For every product/variant/location, obtain and reconcile:

1. Physical counted sellable quantity at an agreed cutover time.
2. Legacy `products.stockQuantity` at the same controlled point.
3. Every pending order with `stockReserved=true`.
4. Each such order's item quantities grouped by product and proposed variant.
5. Payment state from local database and Razorpay for pending/uncertain cases.
6. Any manual stock edits, purchases, returns, write-offs or adjustments not represented by a ledger.
7. In-flight checkout requests during the cutover window.

Physical count is the authoritative starting point only after it is signed off. Provider-confirmed paid orders must be accounted as sales, not active reservations. Uncertain provider state must be quarantined for review, not released or silently added to available stock.

## 7. Persistence and migration tooling

**Observed in `application.properties`:**

- MySQL connection uses Aiven environment placeholders and `sslMode=REQUIRED`.
- `spring.jpa.hibernate.ddl-auto=update`.
- `spring.jpa.show-sql=true`.
- No explicit database timezone/session convention is shown in this file.

**Observed in `pom.xml`:**

- Spring Boot parent 4.1.2.
- Java version property 27.
- Spring Data JPA and MySQL Connector/J runtime dependency.
- H2 is present in test scope.
- No Flyway or Liquibase dependency is declared in the inspected pom.

**Assessment:**

Hibernate `update` is not an adequate production migration-control process for the planned schema changes. It does not provide a reviewed, ordered, repeatable migration history with deliberate data backfills, preconditions and rollback strategy. `show-sql=true` may also produce verbose SQL logs; production logging and sensitive-data exposure must be reviewed.

**Decision:**

- Select Flyway or Liquibase after confirming Spring Boot 4.1.2 compatibility and team workflow.
- Pin and document the actual deployed MySQL version.
- Use reviewed, versioned migrations; do not let application startup implicitly perform the POS production cutover.
- Separate schema expansion, data backfill, validation and write-path switch.
- Keep a tested restore path. A migration rollback is not a substitute for restoring a database and reconciling payments/stock.

## 8. Live database inspection still required

The following are **unknown**, not assumed:

- Exact deployed MySQL version and Aiven service configuration.
- Actual schema/table inventory and storage engines.
- Physical column types, defaults, nullability and collations.
- Actual primary keys, foreign keys, unique constraints and secondary indexes.
- Row counts and maximum identifier values.
- Distinct values and counts for payment/order/appointment statuses.
- Null, duplicate or orphan references.
- Product specifications JSON shape and variant-defining completeness.
- Negative, zero or null product stock values.
- Active reservation totals and mismatch between order flags and item quantities.
- Duplicate/missing Razorpay IDs and tracking tokens.
- Existing prescription JSON lengths, modes and image URL formats.
- Whether uninspected entities/tables exist.
- Database timezone, SQL mode, isolation level and transaction settings.
- Whether the live schema has manual changes not represented in current JPA source.

### 8.1 Required read-only metadata and profiling report

Run against a **read-only account** or a restored sanitized copy. Do not place credentials or customer/prescription data in GitHub documents.

The DBA/developer should produce a sanitized report containing:

- `SELECT VERSION()` and relevant session variables.
- `information_schema.tables` inventory with engine and estimated row counts.
- `information_schema.columns` for all application tables.
- `information_schema.key_column_usage` and `table_constraints`.
- `information_schema.statistics` index definitions.
- Counts and distinct status values for orders, appointments and products.
- Product stock distribution: null/zero/negative/min/max counts.
- Pending orders grouped by payment status, stockReserved and expiry status.
- Order items with missing product references.
- Duplicate tracking tokens and duplicate non-null Razorpay IDs.
- Prescription JSON mode/length counts only; never export prescription contents or image URLs to an unrestricted report.
- Products whose specifications cannot be deterministically mapped to approved variants.

Do not run write statements, repair scripts, `ALTER TABLE`, `DELETE`, `UPDATE`, `TRUNCATE` or Hibernate schema changes during this inspection.

## 9. Data quality checks to perform before migration

The exact SQL must be tailored to verified table/column names and MySQL version. The following are logical checks, not ready-to-run production scripts:

| Domain | Check | Required treatment |
|---|---|---|
| Products | Null/negative stock, missing price/name, duplicate or ambiguous variant attributes | Quarantine and owner-review; no automatic stock correction |
| Product specifications | Missing, inconsistent or malformed JSON keys | Manual mapping for variant creation |
| Order items | Product ID has no matching product | Preserve order history; resolve mapping without deleting item |
| Orders | Paid order still has stockReserved=true | Reconcile provider/local payment and stock movement |
| Orders | Pending order has stockReserved=false or missing expiry | Review before any inventory backfill |
| Orders | Expired pending reservation | Verify Razorpay state; never assume expiry means payment failed |
| Payments | Duplicate provider payment/order identifiers | Resolve before adding unique constraints |
| Orders | Duplicate/missing tracking token | Backfill only after collision check; keep old customer tracking access where possible |
| Prescriptions | JSON malformed/over length or image reference invalid | Preserve original; isolate for controlled remediation |
| Appointments | Same date/time with multiple non-cancelled bookings | Report and review; do not silently delete |
| Admin | Duplicate usernames under actual collation/case rules | Resolve before staff-account linking |
| Action authorizations | Expired/consumed token rows | Retain according to security audit/retention policy; do not migrate as active approvals |

## 10. Proposed migration sequence — no execution authorization

### Stage 0 — Freeze scope and capture baseline

- Confirm production database and exact environment.
- Take encrypted full backup and, where supported, point-in-time recovery position.
- Verify restore into isolated environment.
- Capture schema metadata and sanitized profiling report.
- Record current row counts, maximum IDs and status totals.
- Agree a maintenance/cutover window and operational owner.
- Do not proceed if restore has not been tested.

### Stage 1 — Introduce migration control

- Add and configure the selected migration tool in a separate reviewed code change.
- Baseline the existing schema without rewriting existing rows.
- Ensure application startup does not unexpectedly apply unreviewed schema changes.
- Run baseline against a restored copy first.
- Verify current website/admin flows still work.

### Stage 2 — Add new schema only

- Add new POS/variant/location/inventory/staff/prescription structures as additive tables.
- Add nullable columns such as `order_items.variant_id` only after naming/type/FK compatibility is confirmed.
- Do not drop or rename `products.stockQuantity`, existing order/payment fields or prescription JSON.
- Avoid adding strict constraints before legacy data is profiled and backfilled.
- Keep existing website and online checkout paths on the old model during this stage.

### Stage 3 — Catalogue and variant mapping

- Profile all products and their specifications.
- Define deterministic variant mapping rules.
- Create one or more variants only where the actual sellable combinations can be established.
- Assign permanent NMK SKUs with a controlled mapping file and uniqueness checks.
- Record unresolved products for manual owner review.
- Preserve parent product IDs and existing product cards.
- Backfill online order-item variant IDs only where historical mapping is unambiguous.

### Stage 4 — Inventory reconciliation

- Freeze inventory-changing operations for the agreed cutover window or use a specifically tested movement-capture protocol.
- Complete and sign off physical counts.
- Reconcile active reservations against order items and Razorpay state.
- Populate canonical on-hand and reserved quantities from approved reconciliation, not a blind copy of legacy stock.
- Insert opening-balance and reservation ledger entries with stable source references and idempotency keys.
- Verify summary balances equal ledger-derived balances.
- Keep unresolved rows out of the live sale path until reviewed.

### Stage 5 — Validate without changing production write authority

- Run staging tests with a production-like sanitized copy.
- Compare legacy and canonical available quantities by variant/location.
- Test checkout, payment verification, late capture, cancellation, expiry, concurrent reservation and scheduler retry.
- Test variant selection, POS sale, returns, goods receipt and stock adjustment.
- Test duplicate requests and rollback/retry behaviour.
- Verify online and POS paths cannot reserve/sell the same unit twice.

### Stage 6 — Controlled write-path switch

- Obtain separate approval for the exact code and migration release.
- Switch one controlled path at a time.
- Ensure old product-level stock mutation paths are disabled or safely routed before enabling canonical inventory writes.
- Do not dual-write two independent stock balances without a designed, observable reconciliation mechanism.
- Monitor ledger/summary drift, rejected sales, pending reservations and provider exceptions.
- Keep a documented rollback and reconciliation decision for every cutover checkpoint.

### Stage 7 — Retire legacy fields only after proven stability

- Keep legacy stock and old prescription representations available for a defined compatibility/retention period.
- Remove legacy fields only after all readers/writers are migrated, data is reconciled, backups are verified and owner approval is explicit.
- Never cascade-delete historical orders, items, payment evidence, prescriptions or audit events as a cleanup shortcut.

## 11. Rollback and recovery requirements

A safe rollback must distinguish schema rollback from business-data recovery.

- **Before any new writes:** additive tables/columns may be left unused or removed only through a separately reviewed migration; preserve original tables.
- **After variant mapping but before inventory cutover:** mapping can be corrected/re-run idempotently while old stock remains authoritative.
- **After opening balances/ledger entries:** do not delete ledger rows to roll back. Use compensating entries or restore the pre-cutover backup under a controlled reconciliation plan.
- **After POS/online transactions use new inventory:** never simply switch old stock writes back on. Reconcile every new sale, reservation, return, receipt and adjustment before restoring service.
- **After payments occur:** provider payment state and local order/payment records must be reconciled. A database restore can erase valid payment evidence; use provider reports/webhooks and a recovery ledger.
- **Prescription object migration:** restore database metadata and private objects together; verify checksums and access policy before re-enabling reads.
- **Staff migration:** retain old admin login until new staff identity, role enforcement, password verification and session invalidation have passed acceptance tests.

RPO/RTO targets must be selected based on actual Aiven plan, backup capability, object storage and tested recovery time. Do not promise a recovery target that has not been demonstrated.

## 12. Acceptance gates before executable DDL

The following must all be complete:

- [ ] Verified encrypted backup and successful isolated restore.
- [ ] Actual MySQL version, engine, collation, SQL mode and timezone recorded.
- [ ] Full live table/column/index/FK inventory captured.
- [ ] Sanitized row counts and status distributions captured.
- [ ] Product specification/variant mapping report reviewed by owner.
- [ ] Active online reservations and payment exceptions reconciled.
- [ ] Legacy order-item-to-variant mapping plan approved.
- [ ] Physical stock count and cutover quantity rules approved.
- [ ] Migration tool and baseline strategy approved.
- [ ] MySQL-specific DDL and constraint behaviour tested.
- [ ] Staging migration and rollback rehearsed.
- [ ] Online checkout/payment and reservation regression suite passes.
- [ ] Prescription JSON/file compatibility and backup plan passes.
- [ ] Separate implementation approval obtained before any GitHub code or database change.

## 13. Decisions and open questions

The existing business decisions remain unchanged: NMK is not GST-registered; variant-level stock is required; one customer-facing parent listing is retained; online and POS transactions remain separate; shared variant/location inventory is authoritative after cutover; POS sales are fully paid with split tender allowed; and prescription data requires private, controlled access.

Still required before physical DDL:

1. What exact MySQL version and Aiven service are currently deployed?
2. Can a sanitized schema-only dump and read-only metadata report be produced?
3. What are the actual product `specifications` keys/values and which represent sellable variants?
4. What is the physical count and reservation reconciliation date/time?
5. How should ambiguous legacy order items be represented if variant cannot be inferred?
6. Which migration tool (Flyway or Liquibase) is selected and tested with the current Spring Boot version?
7. What backup/restore and point-in-time recovery capabilities are enabled in the actual database plan?
8. What retention policy applies to old orders, prescriptions and admin authorization records?

These are not assumed or silently resolved by this audit.

## 14. Final conclusion

The existing schema must be treated as a live website/admin data model with historical customer, order, payment and prescription information. The highest-risk change is the transition from product-level reservation stock to variant/location-level physical inventory. A blind stock copy would produce incorrect quantities because the existing checkout path already subtracts pending reservations from product stock.

Preserve existing product, order and order-item identifiers and historical snapshots. Add the new POS schema in stages. Profile and reconcile real data before enforcing foreign keys, uniqueness and non-null variant mappings. Use versioned migrations rather than Hibernate auto-update for production. Keep payment and prescription recovery concerns separate from ordinary schema rollback.

**Audit result:** Source compatibility and migration risks are documented. **Live database audit is still pending** because no database access or sanitized schema/data export was used. This document does not authorize executable DDL, Java changes, production database writes, stock reconciliation or deployment changes.
