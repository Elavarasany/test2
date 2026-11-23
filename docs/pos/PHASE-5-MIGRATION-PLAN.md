# NMK Opticals POS — Phase 5 Migration Plan

**Status:** Migration implementation plan for review; no executable SQL in this document.  
**Branch:** `master`  
**Prerequisites:** Phase 1 live database inspection, Phase 2 inventory architecture, Phase 3 canonical schema blueprint, Phase 4 MySQL DDL review and confirmed Aiven MySQL 8.4.11.  
**Safety:** Planning only. No Aiven schema/data changes, no application cutover, no opening-stock posting, and no online-order/payment changes are authorized by this document.

## 1. Objective

Define a controlled, additive migration sequence for introducing POS and shared variant/location inventory while preserving the current NMK website, online checkout, Razorpay records, prescriptions and admin access.

The first implementation target is a **sanitized staging database** matching Aiven MySQL 8.4.11 as closely as practical. Production migrations are a later, separately approved activity.

## 2. Non-negotiable safeguards

1. Do not drop, rename, truncate or repurpose any existing production table or column in the initial migration series.
2. Do not copy online orders into POS sales. Online order/payment history remains in its existing tables.
3. Do not change the existing online stock reservation/payment flow until a tested shared-inventory cutover is approved.
4. Do not initialize new inventory balances from legacy `products.stockQuantity`; opening quantities require a physical count and reconciliation.
5. Do not infer product variants or SKUs from incomplete catalogue text. Each sellable variant requires an explicitly reviewed mapping.
6. Every stock change must be transactional, idempotent, row-locked and represented in the append-only movement ledger.
7. Never store secrets, prescription values or public/private file URLs in migration logs or audit metadata.
8. No GST charging is assumed; tax and invoice wording remain subject to business/accountant review.
9. Do not run migrations against Aiven until backup restore, staging tests, migration review and explicit production approval are complete.
10. Production schema changes must use a versioned migration tool, not Hibernate `ddl-auto=update`.

## 3. Repository and migration conventions

Recommended location:

```text
src/main/resources/db/migration/
  V001__pos_migration_baseline_checks.sql
  V002__create_inventory_locations.sql
  V003__create_product_variants.sql
  V004__create_inventory_balances.sql
  V005__create_stock_movements.sql
  ...
```

Rules:

- Use Flyway versioned migrations with monotonically increasing version numbers.
- Once a migration has been applied in any shared environment, do not edit it; create a new forward migration.
- Keep each migration small and focused on one logical unit.
- Order parent tables before child tables and drop nothing in the additive phase.
- Specify InnoDB, `utf8mb4`, explicit nullability/defaults, PKs, FKs, unique keys and named indexes.
- Use MySQL 8.4.11-compatible syntax; test generated columns and CHECK constraints in staging.
- Do not use delimiter-dependent stored procedures/triggers in the initial migrations unless a separately reviewed requirement makes them necessary.
- Keep application data backfills separate from table-creation migrations.
- Record checksums and migration results through Flyway; never manually mark a failed migration successful without investigation and documented repair.

## 4. Migration batches and gates

### Batch A — Migration framework and baseline

**Candidate scope**
- Add Flyway dependency/configuration to the backend.
- Select and pin a Flyway version compatible with the actual Spring Boot/Java dependency set.
- Establish the migration directory and naming convention.
- Add a non-mutating preflight checklist for database identity, version, schema, backup and migration history.
- Decide how the existing schema is baselined without allowing Flyway to recreate or alter current tables.

**Gate A**
- Inspect the actual backend `pom.xml` and application configuration.
- Confirm Flyway compatibility with the project's Spring Boot 4.1.2 and Java 27 setup.
- Confirm whether an existing Flyway history table exists; do not baseline blindly.
- Prove on a disposable copy that application startup and current APIs still work.
- Keep Hibernate schema generation from acting as production migration authority; do not change current production behavior until tested.

### Batch B — Catalogue and location foundations

**Candidate scope**
- `inventory_locations`
- `product_variants`

**Gate B**
- Approve the initial location code/name for the Avalurpet shop.
- Review each current product and determine whether it has one or multiple sellable variants.
- Approve stable SKU generation and optional barcode normalization rules.
- Define variant attributes by category (frame, lens, contact lens, accessories) without guessing unsupported product properties.
- Confirm parent `products` identifiers and FK type/signedness.
- No stock quantity is inserted in this batch.

### Batch C — Shared inventory ledger

**Candidate scope**
- `inventory_balances`
- `stock_movements`

**Gate C**
- Confirm row-locking order and idempotency-key namespace.
- Test reserve, release, paid conversion, POS sale, accepted goods receipt, inspected return, adjustment and transfer transitions.
- Prove available stock cannot become negative and reserved quantity cannot exceed on-hand.
- Prove balance mutation and ledger insert commit or roll back together.
- Do not seed opening balances or switch website checkout to these tables yet.

### Batch D — POS transaction core

**Candidate scope**
- `customers`, `customer_addresses`
- `sales`, `sale_items`, `sale_tenders`
- `sale_fulfilment_events`
- `invoice_sequences`, `invoice_voids` (only after invoice policy approval)

**Gate D**
- Confirm sale numbering and invoice numbering are distinct.
- Confirm invoice prefix, scope, issue timing and void/gap policy with the business/accounting adviser before invoice sequence DDL is finalized.
- Confirm walk-in sale behavior, customer snapshots, discounts, rounding and refund policy.
- Test fully-paid sale completion and split tenders; server-calculated due must equal confirmed tender total.
- Do not assume GST registration or add GST by default.

### Batch E — Register and cash operations

**Candidate scope**
- `pos_registers`
- `pos_shifts`
- `cash_movements`

**Gate E**
- Approve register count and codes.
- Choose and concurrency-test the one-active-shift-per-register enforcement strategy.
- Verify opening float is counted exactly once in expected cash.
- Test cash sale, cash refund, cash-in/out, closing count, discrepancy and authorized correction.
- Confirm role and approval requirements for closing/reopening a shift.

### Batch F — Returns and procurement

**Candidate scope**
- `returns`, `return_items`
- `suppliers`, `purchase_orders`, `purchase_order_items`
- `goods_receipts`, `goods_receipt_items`
- `purchase_returns`, `purchase_return_items`

**Gate F**
- Approve return/refund thresholds and restock disposition policy.
- Test cumulative return quantity under concurrent requests.
- Ensure refund does not automatically restock.
- Ensure only physically accepted, sellable receipt quantity increases on-hand stock.
- Ensure supplier return reduces stock only when physically dispatched.
- Confirm over-receipt, short receipt, quarantine and landed-cost policies.

### Batch G — Staff, audit and prescription privacy

**Candidate scope**
- `staff_accounts`, `staff_roles`, `staff_permissions`
- `staff_role_permissions`, `staff_account_roles`
- `audit_events`
- `prescriptions`, `prescription_access_events`

**Gate G**
- Map existing admins to staff identities without creating duplicate/unlinked privileged accounts.
- Preserve existing admin authentication and destructive-action reauthorization.
- Confirm role matrix and least-privilege access.
- Approve prescription schema, private object storage, access controls, retention and deletion policy before creating prescription tables.
- Confirm audit logs exclude credentials, tokens, prescription contents and private object URLs.

### Batch H — Online compatibility mapping and eventual cutover

**Candidate scope**
- Reviewed online order-item-to-variant mapping, preferably additive and nullable until fully backfilled.
- Application service changes to make online and POS consume the same location/variant inventory safely.

**Gate H**
- Deterministically map every eligible legacy order item to a variant; ambiguous rows remain unmapped and block cutover.
- Test payment verification, webhook retries, reservation expiry/release and reconciliation against the new inventory ledger.
- Reconcile outstanding orders and held reservations before cutover.
- Prove no double decrement, double release, double payment conversion or oversell under concurrency.
- Obtain a separately approved maintenance/cutover window and a tested rollback/forward-repair plan.

## 5. Required staging test matrix

At minimum, automated integration tests must cover:

- Migration from a clean database.
- Baseline of a sanitized copy of the current schema without altering existing rows.
- Re-running application startup after migrations.
- Unique SKU and nullable unique barcode behavior.
- Inventory reserve/release/payment conversion idempotency.
- Two concurrent attempts to sell the last available unit.
- POS split tender and tender-total mismatch rejection.
- Duplicate payment/tender/provider event delivery.
- Return quantity cap under concurrent requests.
- Receipt acceptance and quarantine quantity accounting.
- Purchase return only on dispatch.
- One active shift per register under concurrent open requests.
- Staff permission denial and privileged-action reauthorization.
- Audit redaction and prescription access authorization.
- Existing online order retrieval/tracking/payment behavior unchanged.

## 6. Backup, recovery and production release gates

Before any production migration:

1. Identify the exact Aiven service/database and confirm sufficient storage headroom.
2. Capture schema-only and required data backups using a supported method.
3. Perform a restore into a separate disposable MySQL 8.4.11 environment and verify the restored application can read it.
4. Record current migration history and table counts/metadata.
5. Review expected DDL lock/metadata-lock behavior and migration duration.
6. Have a forward-repair plan because MySQL DDL may auto-commit and is not generally rolled back like ordinary DML.
7. Schedule a controlled window; pause or gate conflicting stock writes if required.
8. Run migrations in order and verify schema, FK/index metadata and application health.
9. Do not enable new inventory writes until reconciliation and cutover approval.
10. Monitor errors, latency, memory, storage, stock ledger/balance consistency and payment reconciliation.

A backup is not considered a rollback plan until a restore has been successfully tested.

## 7. Known environment facts and outstanding prerequisites

Confirmed from the Aiven service overview:
- MySQL 8.4.11.
- Service running.
- Database `defaultdb`.
- TLS required.
- DigitalOcean Bangalore region.
- Plan resources shown as 1 CPU, 1 GB RAM and 1 GB storage.
- A memory-use snapshot of 77% and storage-use snapshot of 28.8% was visible when inspected; recheck before any production operation.

Still required:
- SQL mode, session/system timezone, server/database charset and collation.
- Existing table engine/collation inventory.
- Actual backend Maven dependency/configuration review for Flyway integration.
- Migration baseline decision.
- Sanitized staging database and restore test.
- Product-to-variant/SKU mapping.
- Invoice/accounting and operational policy approvals.

Do not place database passwords, service URIs containing credentials, API keys or customer data in this document or repository.

## 8. Current phase completion criteria

Phase 5 planning is complete when the repository contains this sequence, gates and safety contract. It does **not** mean Flyway is installed, SQL migrations are written, or any database has been changed.

**Next implementation step:** inspect the backend `pom.xml`, `application.properties` and repository structure, then prepare Batch A as a code-only change for review. Do not touch Aiven.
