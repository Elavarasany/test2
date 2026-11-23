# NMK Opticals POS — Phase 1 Backend Compatibility Audit

**Status:** Repository-source audit (GitHub `master` snapshot). Not a database inspection and not an implementation approval.  
**Companion documents:** `PHASE-1-DATABASE-DESIGN.md`, `PHASE-1-CANONICAL-SCHEMA.md`  
**Repository:** `Elavarasany/nmk-backend`  
**Audited source root:** `nmk-backend/src/main/java/nmk_backend`

## 1. Executive summary

The existing backend is a functioning online-store backend, not a POS-ready variant/location inventory platform. The current design has several useful foundations—Spring Security, BCrypt, JWT authentication, online order records, atomic product-level reservation updates and a fail-closed reservation-expiry scheduler—but its data model is materially different from the canonical POS blueprint.

**The largest blockers are:**

1. Product stock is stored on the parent `Product`, not on a sellable variant/location pair.
2. Online cart/order lines identify a product but not a variant.
3. Reservation currently subtracts from the product stock counter at checkout and later restores it on safe release. That counter behaves like available stock, not the canonical on-hand/reserved pair.
4. Product creation/update paths can write stock directly; one public product controller writes directly to the repository and bypasses service validation.
5. Product deletion is physical and conflicts with retained transaction history.
6. Admin JWT authentication currently creates an authenticated principal with no authorities. There are no OWNER_ADMIN/MANAGER/CASHIER roles in the inspected `Admin` entity.
7. Prescription data is currently serialized into an order-item JSON string, and uploaded-file handling uses a URL value. It is not yet the canonical private object-key/file-metadata model.
8. Hibernate schema auto-update is enabled; no approved versioned migration framework has been selected in the inspected configuration.

**Conclusion:** Do not start POS feature coding or executable schema migrations yet. First resolve the inventory cutover design, map current rows, stage identity/role migration, and inspect the remaining payment/prescription/file-storage paths identified below.

## 2. Audit method and evidence boundary

This review inspected the following source files on the backend `master` branch:

- `entity/Product.java`
- `entity/Order.java`
- `entity/OrderItem.java`
- `entity/Admin.java`
- `entity/AdminActionAuthorization.java`
- `repository/ProductRepository.java`
- `service/ProductService.java`
- `service/OrderService.java`
- `service/StockReservationScheduler.java`
- `controller/ProductController.java`
- `controller/AdminProductController.java`
- `dto/OrderItemRequest.java`
- `config/SecurityConfig.java`
- `config/AdminDataInitializer.java`
- `security/JwtAuthenticationFilter.java`
- `security/JwtService.java`
- `resources/application.properties`

This is a source-code audit, not a live schema dump. It does not prove the exact deployed MySQL columns, indexes, constraints, row counts, payment-provider state, object-storage configuration or production environment. Those must be verified against the actual database and deployment before DDL is written.

## 3. Compatibility classification

| Current component | Decision | Reason / required action |
|---|---|---|
| `Product` entity/table | EXTEND then gradually REPLACE stock responsibility | Preserve parent product identity and catalogue fields; introduce variants and move stock authority to inventory by location/variant |
| `Product.stockQuantity` | REPLACE as stock authority; retain temporarily for migration | Current value is decremented on reservation, so it must not be copied directly into canonical on-hand |
| `Product.specifications` JSON | REUSE as migration source, then EXTEND variant model | Map size/colour/options only when unambiguous; keep descriptive specs separate from sellable variant identity |
| `ProductRepository.reserveStock/releaseStock` | REPLACE after parity testing | Current updates operate on product-level stock and do not represent separate on-hand/reserved quantities |
| `ProductService` | EXTEND, then consolidate write paths | Add product/variant lifecycle operations; remove direct stock editing; stop physical delete for referenced products |
| `ProductController` | REPLACE unsafe write path | `POST /api/products` directly saves repository entity; route needs authorization and one validated service path |
| `AdminProductController` | EXTEND | Admin product operations currently rely on authenticated access, not role/permission checks |
| `Order` / `customer_orders` | REUSE and EXTEND | Preserve online order and Razorpay identifiers/status; add controlled variant/reservation references as migration allows |
| `OrderItem` / `order_items` | EXTEND | Keep historical product snapshots; add nullable variant mapping and backfill only deterministic legacy lines |
| `OrderItemRequest` | EXTEND | Current request carries productId, not variantId; new checkout must validate exact variant belongs to product |
| `OrderService.createPendingOrder` | REPLACE stock/pricing boundary in staged migration | It currently validates product and price, computes lens additions, reserves product stock and persists order items; migrate to variant-aware inventory with server-authoritative pricing |
| `StockReservationScheduler` | REUSE safety behaviour; EXTEND data model | Preserve external payment check, locked recheck and fail-closed retention; update release operation to reservation ledger |
| Online payment verification/webhook services | REUSE domain ownership; EXTEND inventory integration | Must verify exact payment state transitions, idempotency and concurrency before migration; not fully inspected in this audit |
| `Admin` / `admins` | EXTEND or staged identity migration | Current entity has username/password/createdAt only; do not create duplicate staff identities |
| `JwtService` | EXTEND/REPLACE token validation as part of security migration | Current token subject is username with 10-hour expiry; no role claims, auth version or revocation check |
| `JwtAuthenticationFilter` | REPLACE authentication construction | Currently creates principal with empty authorities; must load current account status and permissions |
| `SecurityConfig` | EXTEND | Authenticated-only is not role-based authorization; public product POST/PUT risk must be assessed and corrected |
| `AdminDataInitializer` | EXTEND | Bootstraps one admin from environment variables; safe owner-role assignment and last-owner protections are needed |
| `AdminActionAuthorization` | REUSE only after semantic review; likely EXTEND | One-time hashed token for admin username/action exists; not a complete approval workflow bound to target/value/approver |
| Prescription JSON on `OrderItem` | REPLACE as canonical prescription store; preserve legacy during migration | Current JSON string embeds manual prescription values or image URL; canonical model requires versioned prescription and private file metadata |
| Prescription file/object storage | AUDIT REQUIRED | Source inspected here does not establish storage provider, bucket privacy, authorization, retention or backup behaviour |
| `application.properties` JPA settings | REPLACE deployment migration strategy | `spring.jpa.hibernate.ddl-auto=update` is enabled; use approved Flyway/Liquibase migrations after version/schema inspection |
| POS registers, shifts, tenders, sales, returns, purchasing, suppliers, stock counts, alerts | CREATE | No matching POS-domain implementation was established by the inspected files |

## 4. Detailed findings

### 4.1 Catalogue and inventory

**Observed:**

- `Product` maps to `products` and contains one `stockQuantity` integer.
- `Product` has a JSON `specifications` map, but no separate variant identity, SKU or barcode fields in the inspected entity.
- `ProductService.addProduct` validates and saves stock quantity.
- `ProductService.updateProduct` copies request stock quantity directly onto the existing product.
- `ProductService.deleteProduct` physically deletes the product.
- `ProductController.POST /api/products` calls `productRepository.save(product)` directly, bypassing ProductService validation.
- `ProductRepository.reserveStock` atomically subtracts quantity when product is active and stock is sufficient.
- `releaseStock` increments the same product stock counter.

**Impact:**

This is not the canonical inventory representation. It does not distinguish physical on-hand from online-reserved stock, and it cannot independently stock two variants of one parent product or the same variant across locations. Direct stock updates can bypass a future stock ledger. Physical deletion can break historical order, return and audit references.

**Required migration:**

1. Preserve product IDs and parent catalogue rows.
2. Generate stable SKU identities for new variants.
3. Map existing specification combinations to variants; send ambiguous combinations to manual review.
4. Add location-scoped inventory summary and append-only stock movement ledger.
5. Reconcile physical count, legacy stock value, active reservations and pending/paid orders.
6. Stop direct stock writes only after both online and POS write paths have passed staging tests.
7. Replace physical deletion with lifecycle status and audited stock disposition.

**Critical warning:** Do not set `inventory.on_hand_quantity = products.stock_quantity` blindly. Existing checkout subtracts from that counter at reservation time. It may therefore represent available quantity. The actual migration must reconstruct on-hand and reserved quantities from physical count and order/reservation state.

### 4.2 Online checkout and order items

**Observed:**

- `Order` maps to `customer_orders` and stores customer contact/address snapshots, total amount, payment/order statuses, Razorpay order/payment IDs, reservation expiry and a boolean `stockReserved`.
- `OrderItem` maps to `order_items`, stores `productId`, product name, quantity, price and lens/prescription metadata; no variant ID is present in the inspected entity.
- `OrderItemRequest` accepts `productId` and quantity; it does not identify a variant.
- `OrderService.createPendingOrder` loads product by ID, checks active status, calculates lens options/prices, aggregates quantities by product ID and reserves through ProductRepository.
- Reservation expiry is 15 minutes.
- `releaseReservationForOrder` checks `stockReserved`, aggregates item quantities by product and restores product stock; it then marks the order reservation false and order/payment statuses cancelled/failed.

**Impact:**

A product with multiple colour/size/frame variants cannot be reserved deterministically. Historical order lines do not identify which variant was sold. Migration must not infer a variant where specifications are ambiguous.

**Required migration:**

- Add nullable `variant_id` to online order items first.
- Preserve productId and all historical order snapshots.
- Backfill only one-to-one deterministic product/variant mappings.
- New checkout requests must carry variant identity and validate parent ownership, active status, channel availability, price and inventory.
- Keep old checkout/reservation path only during a controlled compatibility period; never let old and new paths both deduct stock.
- Add tests for multiple order items referencing the same variant and concurrent reservations for the final available unit.

### 4.3 Razorpay reservation lifecycle

**Observed:**

- `StockReservationScheduler` runs every 60 seconds.
- It reads expired reservation candidates, checks Razorpay state before acquiring the order lock, retains stock when payment is unsettled/successful/uncertain, then locks and rechecks the order's reservation/payment/expiry state before invoking release.
- Exceptions are caught and treated fail-closed: stock is not released when payment status cannot be established safely.
- `OrderService` uses `stockReserved` to prevent duplicate release.

**Assessment:** This is an important safety behaviour and must not be lost during the inventory redesign.

**Not established by this file set:** Full webhook signature validation, event deduplication, payment verification endpoint behaviour, amount/currency checks, payment capture semantics, race handling between webhook and scheduler, and refund-provider behaviour. These need a separate targeted source audit before payment integration design is signed off.

### 4.4 Authentication and authorization

**Observed:**

- `Admin` maps to `admins` with username, password and createdAt; no role or status field is present in the entity.
- `AdminDataInitializer` optionally creates one initial admin using environment-provided username/password and BCrypt encoding.
- `JwtService` creates a username-subject JWT with a 10-hour expiry. The inspected token has no role, session ID or auth-version claim.
- `JwtAuthenticationFilter` validates the token, creates a Spring Security User with an empty authority list, and places it in the security context.
- `SecurityConfig` allows admin login publicly, permits product GETs publicly, and requires authentication for most other routes. It does not demonstrate OWNER_ADMIN/MANAGER/CASHIER method-level permissions.
- `AdminActionAuthorization` stores token hash, admin username, action, expiry and consumedAt; this is not by itself a full approval request record.

**Impact:**

A valid JWT establishes identity but does not establish the role matrix required by POS. Hiding UI controls is insufficient. Any role implementation must be enforced on the backend and must handle existing sessions after role change or account disablement.

**Required migration:**

1. Define how existing `admins` identities map to `staff_accounts`; avoid duplicate login identities.
2. Bootstrap the first OWNER_ADMIN safely and assign a role in a controlled migration.
3. Add account status and prompt session revocation/revalidation.
4. Load current authorities server-side or use a deliberately short-lived/revocable token strategy.
5. Protect product write endpoints and all sensitive POS, refund, inventory, purchase and staff operations with explicit permissions.
6. Prevent disabling/deleting the last active OWNER_ADMIN.
7. Add tests proving a CASHIER cannot invoke manager/owner endpoints directly.

### 4.5 Prescription data and file handling

**Observed:**

- `OrderItem` stores a `prescription_json` string column (length 4000) alongside prescription type/lens fields.
- `OrderService` serializes manual eye values or contact lens values into that JSON.
- For image mode, the code accepts an `imageUrl` only when it begins with HTTPS and stores that URL and a filename in JSON.
- This code does not establish that the URL points to private object storage, that access is authorized, or that the file is retained/backed up securely.

**Impact:**

The current representation is transaction-item-centric JSON, not a reusable/versioned prescription model with separate private file metadata and access audit. HTTPS alone does not make an image private.

**Required migration:**

- Inspect actual upload controller/service and storage provider before designing data migration.
- Determine whether existing URLs are public, signed, expiring or application-proxied.
- Migrate prescription values into a dedicated versioned record with explicit customer/order-item ownership.
- Store private object keys and metadata in MySQL; do not store public object URLs as the canonical file reference.
- Keep existing order snapshots readable until migration validation is complete.
- Restrict prescription read/download to authorized roles and audit every access.
- Do not expose prescription contents to cashier APIs or logs by default.

### 4.6 Product API and service boundaries

**Observed:**

- The public `ProductController` has POST and PUT handlers as well as public GET handlers.
- Its POST handler saves directly through `ProductRepository`.
- `SecurityConfig` explicitly permits GET product routes; POST is not explicitly permitted, so it falls under `anyRequest().authenticated()`. This is not public unauthenticated write access in the inspected security config, but the controller still bypasses service validation for an authenticated caller.
- `AdminProductController` uses ProductService, including physical delete.

**Required action:**

- Consolidate all writes behind validated application services.
- Separate public catalogue read endpoints from authenticated admin mutation endpoints.
- Add role/permission checks to mutation operations.
- Remove stock from generic product update DTOs after inventory cutover.
- Convert delete into lifecycle/archive workflow with reference checks.

### 4.7 Database migration configuration

**Observed:**

- `application.properties` sets `spring.jpa.hibernate.ddl-auto=update`.
- Datasource uses environment placeholders for Aiven MySQL connection.
- No Flyway/Liquibase configuration was present in the inspected properties file.

**Required action:**

- Verify actual deployed MySQL server version, schema, SQL mode, collation, timezone and current constraints.
- Select Flyway or Liquibase and establish a baseline only after reviewing the live schema.
- Disable automatic production schema mutation after a controlled migration plan is ready.
- Test forward migration and rollback/forward-fix strategy against a sanitized copy of production data.
- Do not assume the MySQL server version or capabilities from the JDBC driver dependency.

## 5. New modules that must be created

These are not established by the inspected files and should be treated as new domain implementation:

| Module | Core records | Dependencies |
|---|---|---|
| Variant catalogue | product_variants | Product catalogue mapping and admin UI |
| Shared inventory | inventory, stock_movements | Variant mapping, physical stock count, online reservation redesign |
| POS sales | sales, sale_items, pos_tenders | Staff roles, register/shift, inventory, payment verification |
| POS fulfilment | pos_fulfilments, events | Completed POS sale |
| Returns/refunds | returns, items, refunds, allocations | Original sale, payment verification, inspection, inventory |
| Staff roles/permissions | roles, permissions, assignments, sessions | Existing admin identity migration |
| Registers/shifts | registers, shifts, cash movements | Staff roles and tender/refund event definitions |
| Suppliers/purchasing | suppliers, PO, receipts, purchase returns, settlements | Variant inventory, stock ledger, valuation |
| Counts/adjustments | stock counts, adjustments | Inventory ledger, approvals, audit |
| Reorder alerts | rules, alerts | Correct available-stock summary |
| Invoice issuance | sequences, issuance | Online/POS transaction references and accountant-approved policy |
| Prescription privacy | prescription versions, private file metadata, access events | Storage provider and access policy |

## 6. Dependency-ordered implementation plan

Do not implement these modules in arbitrary order. The dependencies are:

1. **Environment/schema discovery:** Obtain current deployed MySQL schema and version, inspect actual indexes/FKs and identify migration framework.
2. **Data inventory:** Count products, order items, active reservations, pending/paid orders, prescription records and existing admin identities. No production mutation.
3. **Catalogue mapping:** Define variant options and manually resolve ambiguous existing products.
4. **Staff migration design:** Map existing admin identities to staff and assign initial OWNER_ADMIN without breaking current login.
5. **Additive database migration:** Add variant/inventory/ledger structures and nullable order-item variant mapping; preserve old fields.
6. **Inventory reconciliation:** Reconstruct on-hand/reserved from physical count and current reservation/payment state.
7. **Online inventory cutover:** Implement variant-aware reserve/release/confirm with existing fail-closed Razorpay semantics; run concurrency/idempotency tests.
8. **POS foundations:** Registers/shifts, POS sale/tender records, invoice issuance and backend permission checks.
9. **Returns and procurement:** Add approved return/refund and receipt-driven purchase workflows.
10. **Privacy/reporting/alerts:** Prescription storage migration, audit access, low-stock evaluation and reconciliations.
11. **Controlled rollout:** staging acceptance, backup/restore verification, production change approval and monitoring.

## 7. Required targeted audits before physical DDL

This audit deliberately does not claim that uninspected modules are absent. Inspect these source paths before finalizing the compatibility matrix:

- All payment verification, webhook, capture/refund and Razorpay service/controller classes.
- `OrderRepository` lock queries and reservation candidate queries.
- All product/order controllers and security matcher coverage.
- Prescription upload controller, storage service, file validation and download endpoints.
- Existing admin login controller/service/repository and action-authorization consumption logic.
- Existing database schema from the actual deployed MySQL instance (tables, columns, indexes, FKs, row counts).
- Frontend product/checkout/admin request payloads before changing DTOs.

## 8. Acceptance checklist

- [ ] Actual MySQL version and schema snapshot captured.
- [ ] Current row counts and reservation/payment states reconciled.
- [ ] Product-to-variant mapping approved; ambiguous legacy items listed.
- [ ] No stock value copied blindly from legacy `stockQuantity`.
- [ ] Existing online checkout and Razorpay lifecycle tested before/after variant cutover.
- [ ] Public product read endpoints separated from authenticated mutation endpoints.
- [ ] Existing admin identity migration and role/session revocation tested.
- [ ] Prescription storage privacy and backup verified.
- [ ] Migration framework and production DDL reviewed.
- [ ] POS sale/tender/shift/refund/purchase concurrency and idempotency tests pass.
- [ ] Backup restore and rollback/forward-fix procedure demonstrated.

## 9. Audit conclusion

The existing backend is reusable as the application foundation, but the inventory, identity/authorization and prescription persistence models require staged redesign. The online order and Razorpay lifecycle should be preserved rather than replaced wholesale; its stock adapter must be migrated carefully to the new variant/location ledger.

**No Java source, SQL migration, database table/data, authentication configuration, payment flow, inventory record, prescription file or deployment setting is changed by this audit document.** It is documentation only. Any later code or database change requires its own review and explicit approval under the project's step-by-step GitHub workflow.
