# NMK Opticals POS — Phase 4 MySQL-Specific DDL Review

**Status:** Physical DDL design review; not executable migration SQL.  
**Branch:** `master`  
**Prerequisites:** Phase 1 reconciliations, Phase 2 inventory architecture, Phase 3 canonical schema blueprint.  
**Safety:** No database, application, stock, order, payment or prescription data is changed by this document.

## 1. Scope and non-goals

This review translates the canonical logical model into MySQL-compatible design rules: engines, types, keys, constraints, indexes, nullable uniqueness, concurrency guards and migration ordering.

It deliberately does **not**:
- claim the Aiven server version/settings are verified (Workbench is currently unavailable);
- create or execute SQL migrations;
- initialize opening stock or backfill online order variants;
- decide accountant/business policies that remain open;
- replace existing online order/payment tables or their stock reservation path.

The exact server version, SQL mode, charset/collation and timezone must be captured before a migration is generated and tested.

## 2. Compatibility baseline (must be verified)

| Capability | Design rule | Gate |
|---|---|---|
| Storage engine | InnoDB for all new transactional tables | Verify server/table engine |
| Charset | `utf8mb4` | Verify server/database defaults; set explicitly per new table |
| Collation | Explicit, consistent collation for identifiers/text | Confirm actual server collation before choosing |
| CHECK constraints | Use for local row invariants only; retain service guards | MySQL enforces CHECK from 8.0.16; verify exact version |
| JSON | MySQL JSON for structured variant attributes and safe metadata | Verify version and application serialization |
| Generated columns | Allowed only after version/behavior test; useful for nullable uniqueness | Test in staging before relying on them |
| Foreign keys | InnoDB, compatible type/sign/signature, indexed references | Review every FK and delete action |
| Time | Store business event instants consistently in UTC; render in Asia/Kolkata | Confirm JDBC/session/server timezone and DST assumptions |
| DDL transactions | Do not assume schema DDL is rollbackable as ordinary DML | Backup + forward repair plan required |

**Version gate:** Do not finalize syntax or rely on CHECK/generated-column behavior until `SELECT VERSION()` is captured from the actual Aiven service. A conservative minimum target is MySQL 8.0.16+ for enforced CHECK constraints, but that is not proof of the deployed version.

## 3. Standard physical conventions

- Use InnoDB and `utf8mb4` explicitly for new tables.
- Primary keys: `BIGINT NOT NULL AUTO_INCREMENT`, unless a reviewed reason requires another strategy.
- FK columns must match referenced column type and signedness exactly.
- Quantities: `INT` signed, with constraints/guards preventing invalid negative states; do not use FLOAT.
- Money: `DECIMAL(12,2)`; never FLOAT/DOUBLE. Compute authoritative totals in backend using decimal arithmetic.
- Rates/percentages, if introduced: choose explicit `DECIMAL` precision in a separate reviewed contract.
- Event timestamps: `DATETIME(6)` with a documented UTC convention; use application-generated UTC instants or a verified UTC DB session. Avoid mixing local wall-clock and UTC values.
- Boolean: `TINYINT(1)`/`BOOLEAN`, with service validation; don't rely on boolean type to reject arbitrary values.
- Status/type values: `VARCHAR` with application enums and CHECK constraints where supported. Prefer lookup tables only when administrators genuinely manage the catalogue.
- JSON: only for flexible attributes/safe metadata. Core searchable business fields must remain typed columns.
- Never store passwords, JWTs, payment secrets, prescription contents, or public prescription URLs in audit/metadata JSON.
- Avoid reserved SQL words as column/table names; use clear names such as `status`, `created_at`, `updated_at` only where safe and consistently quoted by migrations.
- Every table should have an explicit PK, deliberate FK/delete policy, and documented index rationale.

## 4. Unique keys and nullable values

### 4.1 Optional barcode uniqueness

A nullable barcode can use a unique index because MySQL permits multiple NULL values in a unique index. Normalize blank strings to NULL in the backend; do not store empty or whitespace-only barcodes.

- Candidate: `UNIQUE (barcode)` on `product_variants`.
- Before adding: detect and resolve duplicate normalized non-empty barcodes.
- Do not silently trim/change existing barcode identity during migration.

### 4.2 Unique invoice number

- `sales.invoice_number` may be NULL before issuance; a UNIQUE index allows multiple NULLs.
- Once issued, invoice number is immutable and globally unique within the approved numbering policy.
- Sequence allocation must lock the matching `invoice_sequences` row in a transaction; never use `MAX()+1`.
- Issuance scope/prefix/format/gap rules remain pending accountant/adviser approval.

### 4.3 One active shift per register

MySQL has no general filtered/partial unique index. Do not write a PostgreSQL-style partial index.

Preferred enforcement:
1. Lock the parent `pos_registers` row (`SELECT ... FOR UPDATE`) when opening/closing/reopening a shift.
2. In the same transaction, check for an OPEN/CLOSING shift and insert/update only if the guard passes.
3. Add a generated nullable active-register key plus UNIQUE index only if the exact MySQL version and generated-column expression are tested and reviewed. This is optional defense-in-depth, not a substitute for transaction locking.
4. Treat duplicate-key failures as a normal concurrency conflict and return a clear API error.

### 4.4 Idempotency

- Use a NOT NULL idempotency key on operations that must be retried safely (stock postings, POS completion/tenders, goods receipt posting, supplier dispatch, refund posting).
- Unique scope must be documented: globally unique key or `(operation_type, idempotency_key)`. Do not accidentally reuse one key across unrelated operation namespaces.
- Store a request fingerprint/hash where necessary so the same key with a different payload is rejected rather than replayed.
- Provider transaction/event IDs need provider-scoped uniqueness (provider + reference/event ID), not assumptions of global uniqueness.

## 5. Foreign keys and deletion rules

Recommended defaults for review (finalize table-by-table before SQL):

| Relationship | Delete policy |
|---|---|
| Product → variants | RESTRICT; deactivate catalogue instead |
| Location → balances/movements/sales/registers | RESTRICT; inactive location retained |
| Variant/location → inventory balance/movement | RESTRICT; never delete stock history |
| Sale → sale items/tenders/returns/events | RESTRICT; no cascade for completed business records |
| Sale item → return items | RESTRICT; preserve original line and return history |
| Customer → completed sales | SET NULL only if privacy deletion policy requires it and sale snapshot remains; otherwise RESTRICT/anonymize through approved process |
| Register → shifts/cash movements | RESTRICT |
| Supplier → purchase orders/receipts/returns | RESTRICT; deactivate supplier |
| Staff → audit/business actor references | RESTRICT or controlled anonymization; never cascade audit |
| Parent draft-only records | CASCADE only for explicitly disposable drafts and only after lifecycle review |

Use `ON DELETE RESTRICT`/default no-action for historical records. Avoid broad CASCADE because it can erase financial/inventory history. Soft-disable master data instead.

## 6. Constraint matrix to translate into DDL

The following are local row constraints; multi-row totals/cumulative caps still require transactional service guards and locking.

| Table | Candidate DB constraints |
|---|---|
| `product_variants` | SKU NOT NULL UNIQUE; barcode UNIQUE nullable; FK product; price override NULL or >= 0; active flag |
| `inventory_balances` | UNIQUE(location_id, variant_id); on_hand >= 0; reserved >= 0; reserved <= on_hand; reorder_level >= 0 |
| `stock_movements` | unique idempotency key; FK location/variant; on_hand_after >= 0; reserved_after >= 0; reserved_after <= on_hand_after |
| `customers` | non-unique normalized phone index initially; no automatic phone uniqueness/merge |
| `customer_addresses` | FK customer; default-address uniqueness handled transactionally unless a tested generated-key strategy is approved |
| `sales` | sale number UNIQUE; invoice number UNIQUE nullable; FK location/customer/staff; non-negative totals/discount/tax; status controlled |
| `sale_items` | FK sale/variant; quantity > 0; non-negative unit price/discount/tax/line total |
| `sale_tenders` | unique idempotency key; amount > 0; FK sale/shift/staff; status/method controlled |
| `returns` | return number UNIQUE; refund amount >= 0; FK original sale and actors |
| `return_items` | quantity > 0; refund >= 0; FK return and original sale item |
| `invoice_sequences` | UNIQUE(location_id, financial_year, prefix); next_number > 0 |
| `staff_accounts` | normalized username UNIQUE; session_version >= 0 |
| role mappings | UNIQUE(staff_id, role_id), UNIQUE(role_id, permission_id) |
| `pos_registers` | UNIQUE(location_id, register_code) |
| `pos_shifts` | opening_float >= 0; expected/count/difference nullable until close; controlled shift status |
| `cash_movements` | amount > 0; unique idempotency key; controlled direction/type |
| `purchase_order_items` | ordered_quantity > 0; cost fields >= 0 when present |
| `goods_receipt_items` | all quantities >= 0; delivered = accepted + rejected + quarantined once inspection is finalized |
| `purchase_return_items` | quantity > 0; cost snapshot >= 0 |
| `audit_events` | controlled outcome; no destructive FK cascade |

**Important:** CHECK constraints are defense-in-depth. Backend validation and transactional guards remain mandatory, especially for cumulative returns, tender totals, receipt totals, available-stock checks, shift uniqueness and approval authorization.

## 7. Index plan (initial; validate with query patterns)

Do not index every column. Indexes add write cost and should match real POS/online queries.

- `product_variants`: UNIQUE SKU, UNIQUE nullable barcode, index `(product_id, active)`.
- `inventory_balances`: UNIQUE `(location_id, variant_id)`; index `(variant_id, location_id)` only if reverse lookup workload needs it (unique index already begins with location).
- `stock_movements`: `(location_id, variant_id, created_at, id)`, `(reference_type, reference_id)`, unique idempotency key.
- `customers`: index normalized phone; index name only if search needs it.
- `sales`: `(location_id, sold_at, id)`, `(customer_id, sold_at, id)`, `(sale_status, created_at)`; unique sale/invoice numbers.
- `sale_items`: `(sale_id, id)`, `(variant_id, created_at)`.
- `sale_tenders`: `(sale_id, payment_status)`, `(shift_id, payment_status)`, unique idempotency/provider reference as scoped.
- `returns`: `(sale_id, created_at)`; unique return number.
- `return_items`: `(sale_item_id)`.
- `pos_shifts`: `(register_id, shift_status)`, `(opened_by_staff_id, opened_at)`.
- `cash_movements`: `(shift_id, created_at, id)`; unique idempotency.
- `purchase_orders`: `(supplier_id, ordered_at)`, unique PO number.
- `goods_receipts`: `(purchase_order_id, received_at)`, unique receipt/idempotency.
- `goods_receipt_items`: `(purchase_order_item_id)`.
- `purchase_return_items`: `(receipt_item_id)`.
- `audit_events`: `(actor_staff_id, created_at)`, `(action_code, created_at)`, `(target_type, target_id, created_at)`.

Check whether each FK is already covered by an index. MySQL requires suitable indexes for FK enforcement and may create one automatically; do not create duplicate indexes without reviewing `SHOW INDEX`.

## 8. Migration implementation strategy (still gated)

Use versioned migrations (Flyway recommended for Spring Boot; Liquibase is also acceptable if chosen deliberately). Do not use Hibernate `ddl-auto=update` as production migration authority.

Proposed migration batches:
1. **Baseline/guard:** verify database version/settings, migration ownership, backup and restore proof; no data mutation.
2. **Master structures:** inventory locations, variants, staff/role foundations (only after identity mapping review).
3. **Inventory structures:** balances + movement ledger; no opening balances.
4. **POS core:** customers, sales, sale items, tenders, fulfilment, invoice sequences/voids.
5. **Operations:** registers, shifts, cash movements, returns.
6. **Purchasing:** suppliers, POs, receipts, purchase returns.
7. **Privacy/audit:** prescriptions/access events and audit event tables after retention/access design.
8. **Compatibility mappings:** online order item variant mapping only after deterministic catalogue review.
9. **Opening inventory:** physical count and reconciliation approved; write opening movement and balance atomically.
10. **Cutover:** separately approved application release and monitored switch; no old/new stock double-write.

Each migration must be reviewed for:
- table/column names and exact types;
- FK order and delete action;
- index redundancy and key length/collation;
- CHECK/generated expression compatibility;
- row-count/lock/online-DDL impact;
- idempotent deployment behavior (migration runner, not hand-rerun SQL);
- forward repair plan (DDL may auto-commit);
- restore-tested backup and rollback/cutover procedure.

## 9. Required verification when Workbench is available

Run read-only environment queries:

```sql
SELECT
    VERSION() AS mysql_version,
    @@sql_mode AS sql_mode,
    @@time_zone AS session_timezone,
    @@system_time_zone AS system_timezone,
    @@character_set_server AS server_charset,
    @@collation_server AS server_collation;
```

Also capture database defaults and existing table engines/collations:

```sql
SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = DATABASE();

SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;
```

These are inspection queries only. If Workbench remains unavailable, use another trusted SQL client or Aiven's query interface; do not change production settings to make a client work.

## 10. Phase 4 acceptance checklist

- [ ] Actual Aiven MySQL version/settings recorded.
- [ ] InnoDB, charset/collation and UTC convention confirmed.
- [ ] All table columns have exact types/nullability/defaults.
- [ ] Every FK and deletion policy reviewed.
- [ ] Unique nullable keys and idempotency scope documented.
- [ ] CHECK constraints confirmed enforced on target version; service guards retained.
- [ ] One-open-shift strategy chosen and concurrency-tested.
- [ ] Indexes validated against query patterns and FK requirements.
- [ ] Migration tool selected and Hibernate schema auto-update disabled for production migration authority.
- [ ] Migration ordering, lock impact, forward repair and restore-tested rollback documented.
- [ ] No migration executed against Aiven; no opening stock/backfill/cutover performed.

**Completion status:** MySQL-specific physical design rules and migration review drafted. This is not executable SQL and is not authorization to modify Aiven.

**Next phase:** Phase 5 — Executable DDL/migration package for review and staging only, after server capability capture and remaining schema decisions are resolved.


## 11. Aiven environment confirmation — 2026-11-16

The Aiven service overview supplied by the project owner confirms:

| Setting | Confirmed value |
|---|---|
| Engine/version | MySQL 8.4.11 |
| Service state | Running |
| Database | `defaultdb` |
| Port | `17401` |
| TLS | Required |
| Region | DigitalOcean Bangalore (`blr`) |
| Plan resources | 1 CPU, 1 GB RAM, 1 GB storage |

### Consequences

- MySQL 8.4.11 is above the MySQL 8.0.16 minimum for enforced CHECK constraints. CHECK constraints may be included in the reviewed DDL, while backend validation and transactional guards remain mandatory.
- MySQL 8.4 supports the JSON and generated-column capabilities discussed above. Any generated-column uniqueness pattern must still be tested in staging before production use.
- Use InnoDB and explicit `utf8mb4` table definitions for new transactional tables.
- TLS remains required for every SQL client connection.

### Still unverified

The Aiven overview does not show the active SQL mode, session/system timezone, server charset/collation, database defaults, or existing table engines/collations. Capture those read-only values before final DDL generation. Do not infer them from the MySQL version.

### Resource caution

The service overview showed 77% memory usage and 28.8% storage usage at the time it was shared. This is a snapshot, not a capacity guarantee. Keep migrations additive and small, avoid unnecessary indexes/large table rewrites, and inspect resource usage before each migration. Do not run bulk backfills or opening-stock operations on production during schema preparation.

**Updated completion status:** MySQL version and connection/TLS context are confirmed. Remaining server/database settings and staging/restore validation are still required. No SQL migration has been generated or executed.
