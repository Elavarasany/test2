# NMK Opticals — Phase 1 MySQL Live Database Inspection Checklist

**Status:** Inspection procedure only; execution is pending authorized database access.  
**Repository:** `Elavarasany/nmk-backend`, branch `master`  
**Purpose:** Establish the actual MySQL schema and safe, privacy-preserving aggregate facts before designing executable POS migrations.  
**Companion documents:** `PHASE-1-DATABASE-DESIGN.md`, `PHASE-1-CANONICAL-SCHEMA.md`, `PHASE-1-BACKEND-COMPATIBILITY-AUDIT.md`, `PHASE-1-PAYMENT-AND-PRESCRIPTION-AUDIT.md`, `PHASE-1-EXISTING-DATABASE-AND-MIGRATION-AUDIT.md`

> **Strict boundary:** This checklist is for read-only inspection. It does not authorize schema changes, data corrections, migrations, application configuration changes, or production writes. No live database connection was made while preparing this document. Do not paste database credentials, connection strings containing passwords, customer data, prescription contents, payment secrets, or unredacted production exports into chat or GitHub.

## 1. Inspection objectives and exit criteria

Before any migration SQL is designed, record and verify:

1. MySQL server version, database name, character set, collation, SQL mode, and timezone/session settings.
2. Complete table and view inventory, including tables not represented by the currently inspected JPA entities.
3. Actual column definitions, nullability, defaults, generated columns, indexes, unique constraints, and foreign keys.
4. Row counts for relevant tables, with counts clearly marked as point-in-time observations.
5. Product stock anomalies and online reservation/order status aggregates.
6. Order-item references that do not resolve to a current product, without exposing customer or prescription details.
7. Duplicate or missing business identifiers that could block future uniqueness constraints.
8. Backup completion and a successful restore test into an isolated, non-production environment.
9. A reviewed reconciliation plan for legacy product stock and active online reservations.

**Do not approve executable migration DDL** until the above findings are recorded, the backup restore is proven, and the owner approves a migration window and rollback plan.

## 2. Safety rules and access prerequisites

### 2.1 Use a dedicated read-only account

- Have the database owner create or provide a dedicated inspection identity through the database provider's supported controls.
- Grant only the minimum permissions needed for reading the target schema and its metadata. Do not use the application's runtime account if it has write privileges.
- Do not place credentials in SQL files, shell history, screenshots, source control, issue comments, or this repository.
- Connect only through the provider's documented TLS-protected connection method.
- Confirm the selected host and database name with the owner before running any query.
- If the provider cannot supply a read-only account, stop and resolve access safely; do not improvise with the production application credentials.

### 2.2 Prohibited actions during inspection

Do not run any of the following against production:

- `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `RENAME`, `INSERT`, `UPDATE`, `DELETE`, `REPLACE`, or data-fixing scripts.
- Hibernate schema generation/update, Flyway/Liquibase migration, or application startup intended to modify the schema.
- Bulk exports containing customer, address, phone, email, prescription, payment, or authentication data.
- Queries that print prescription JSON, image URLs, payment tokens, passwords, JWTs, or full customer/order rows.
- Load tests, table locks, long-running scans, or expensive unbounded queries during business hours.

Use a SQL client in read-only mode where available. For InnoDB data checks, a read-only transaction can provide an additional guard, but it does not replace read-only database permissions.

## 3. Establish server and schema identity

After connecting with the approved read-only identity, capture only the following non-secret metadata:

```sql
SELECT
    VERSION() AS mysql_version,
    DATABASE() AS selected_database,
    CURRENT_USER() AS authenticated_database_user,
    @@character_set_server AS server_character_set,
    @@collation_server AS server_collation,
    @@session.time_zone AS session_time_zone,
    @@global.time_zone AS global_time_zone,
    @@session.sql_mode AS session_sql_mode;
```

Do not publish hostnames, usernames, or connection details if they are considered confidential by the provider or owner. Record the inspection date/time and timezone separately.

If `DATABASE()` is null or not the approved NMK database, stop. Do not run the remaining queries until the correct schema has been selected.

## 4. Enumerate actual tables and views

The entity audit lists expected names such as `products`, `customer_orders`, `order_items`, `admins`, `admin_action_authorizations`, and likely `appointments`, `marketing_banners`, and `website_images`. These are expectations from source inspection, not proof of the live table inventory.

Run:

```sql
SELECT
    TABLE_NAME,
    TABLE_TYPE,
    ENGINE,
    TABLE_ROWS,
    CREATE_TIME,
    UPDATE_TIME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_TYPE, TABLE_NAME;
```

**Important:** For InnoDB, `TABLE_ROWS` is an estimate and must not be treated as an exact count. Exact counts for selected business tables are covered in Section 7.

Record:
- Tables present in the live schema but not in the source audit.
- Expected tables missing from the live schema.
- Views and their purpose, if any.
- Storage engines; identify any business tables not using a transactional engine.
- Unexpected legacy, temporary, or abandoned tables. Do not delete them during this phase.

## 5. Inspect columns, indexes, and constraints

### 5.1 Column definitions

```sql
SELECT
    TABLE_NAME,
    ORDINAL_POSITION,
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_DEFAULT,
    EXTRA,
    CHARACTER_SET_NAME,
    COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME, ORDINAL_POSITION;
```

Review especially:
- Primary key types and auto-increment behavior.
- Decimal precision/scale for money.
- Signedness and range for quantities and identifiers.
- Nullable fields and defaults used by existing code.
- Date/time types and timestamp defaults.
- JSON columns and text columns used to store JSON.
- String lengths for provider IDs, tracking tokens, SKU/barcode candidates, and status values.

### 5.2 Indexes and uniqueness

```sql
SELECT
    TABLE_NAME,
    INDEX_NAME,
    NON_UNIQUE,
    SEQ_IN_INDEX,
    COLUMN_NAME,
    COLLATION,
    SUB_PART,
    INDEX_TYPE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
```

Check whether the actual database has the unique constraints and lookup indexes expected by the application. Do not infer that a Java annotation successfully created a constraint.

### 5.3 Foreign keys

```sql
SELECT
    TABLE_NAME,
    COLUMN_NAME,
    CONSTRAINT_NAME,
    REFERENCED_TABLE_NAME,
    REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND REFERENCED_TABLE_NAME IS NOT NULL
ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;
```

Also inspect referential actions:

```sql
SELECT
    rc.CONSTRAINT_NAME,
    rc.TABLE_NAME,
    rc.REFERENCED_TABLE_NAME,
    rc.UPDATE_RULE,
    rc.DELETE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
ORDER BY rc.TABLE_NAME, rc.CONSTRAINT_NAME;
```

Do not add foreign keys until orphan data and delete/update behavior have been reviewed. Historical online order items must not become invalid merely because a catalogue product is later deactivated or archived.

## 6. Confirm table definitions for key business tables

For each table confirmed to exist, use `SHOW CREATE TABLE` and save the output in a restricted inspection workspace, not in a public repository if it reveals sensitive schema details.

Expected first-pass candidates:

```sql
SHOW CREATE TABLE products;
SHOW CREATE TABLE customer_orders;
SHOW CREATE TABLE order_items;
SHOW CREATE TABLE admins;
SHOW CREATE TABLE admin_action_authorizations;
SHOW CREATE TABLE appointments;
SHOW CREATE TABLE marketing_banners;
SHOW CREATE TABLE website_images;
```

Run each statement only if the table exists. A missing table is a finding to record, not a reason to create it. Include every additional table discovered in Section 4 in the inspection record.

## 7. Exact row counts and safe aggregate checks

Run exact counts only for confirmed table names and at a low-traffic time. Large tables can take time to scan. Record the timestamp because data can change immediately after inspection.

### 7.1 Exact row counts

```sql
SELECT 'products' AS table_name, COUNT(*) AS exact_rows FROM products;
SELECT 'customer_orders' AS table_name, COUNT(*) AS exact_rows FROM customer_orders;
SELECT 'order_items' AS table_name, COUNT(*) AS exact_rows FROM order_items;
```

Add separate counts for other confirmed tables as needed. Do not export row contents.

### 7.2 Product stock quality

The following query reports aggregate values only:

```sql
SELECT
    COUNT(*) AS product_count,
    SUM(stockQuantity IS NULL) AS null_stock_count,
    SUM(stockQuantity < 0) AS negative_stock_count,
    MIN(stockQuantity) AS minimum_stock,
    MAX(stockQuantity) AS maximum_stock,
    SUM(stockQuantity) AS total_legacy_stock_quantity
FROM products;
```

If the actual column name differs, adapt the query only after checking Section 5. Do not update or normalize values.

### 7.3 Online order reservation/payment state distribution

```sql
SELECT
    paymentStatus,
    orderStatus,
    stock_reserved,
    COUNT(*) AS order_count,
    SUM(totalAmount) AS amount_total
FROM customer_orders
GROUP BY paymentStatus, orderStatus, stock_reserved
ORDER BY paymentStatus, orderStatus, stock_reserved;
```

This reports status and monetary aggregates, not customer identity. Treat amounts as internal reconciliation data; do not publish them outside the authorized project workspace.

Reservation-specific aggregate:

```sql
SELECT
    COUNT(*) AS active_reservation_orders,
    SUM(reservation_expires_at IS NULL) AS missing_expiry_count,
    SUM(reservation_expires_at <= CURRENT_TIMESTAMP) AS expiry_due_count
FROM customer_orders
WHERE stock_reserved = TRUE;
```

A due expiry is not permission to release stock. The existing payment verification and scheduler behavior must be reconciled with the payment provider before any operational correction. If payment state is uncertain, preserve the reservation.

### 7.4 Order-item product references

The current source model stores `order_items.productId` as a scalar product ID rather than a declared JPA relationship. Check aggregate reference quality without selecting item or customer details:

```sql
SELECT
    COUNT(*) AS total_order_items,
    SUM(oi.productId IS NULL) AS missing_product_id_count,
    SUM(oi.productId IS NOT NULL AND p.id IS NULL) AS unresolved_product_reference_count
FROM order_items oi
LEFT JOIN products p ON p.id = oi.productId;
```

Unresolved references may be legitimate historical records if a product was removed. Do not delete order items or fabricate product matches. Investigate them using restricted, authorized procedures while preserving order snapshots.

### 7.5 Duplicate identifier checks

Check duplicates as counts only. First verify the column names and whether the field exists.

```sql
SELECT COUNT(*) AS duplicate_tracking_token_groups
FROM (
    SELECT trackingToken
    FROM customer_orders
    WHERE trackingToken IS NOT NULL
    GROUP BY trackingToken
    HAVING COUNT(*) > 1
) d;
```

```sql
SELECT COUNT(*) AS duplicate_razorpay_order_id_groups
FROM (
    SELECT razorpayOrderId
    FROM customer_orders
    WHERE razorpayOrderId IS NOT NULL
    GROUP BY razorpayOrderId
    HAVING COUNT(*) > 1
) d;
```

```sql
SELECT COUNT(*) AS duplicate_razorpay_payment_id_groups
FROM (
    SELECT razorpayPaymentId
    FROM customer_orders
    WHERE razorpayPaymentId IS NOT NULL
    GROUP BY razorpayPaymentId
    HAVING COUNT(*) > 1
) d;
```

Do not print the actual identifiers in a report. Duplicate groups require controlled investigation before adding or tightening unique constraints.

### 7.6 Prescription presence counts — never inspect contents

```sql
SELECT
    COUNT(*) AS total_order_items,
    SUM(prescription_json IS NOT NULL) AS items_with_prescription_payload,
    SUM(prescription_json IS NULL) AS items_without_prescription_payload
FROM order_items;
```

This query intentionally reports only presence counts. Do not select, export, decode, or paste `prescription_json`, image URLs, filenames, or prescription values.

## 8. Legacy stock reconciliation requirements

The approved POS model uses variant/location inventory with:
- `on_hand_quantity`: physical sellable units, including units currently reserved online.
- `reserved_quantity`: the subset reserved for pending online payments.
- `available_quantity = on_hand_quantity - reserved_quantity`.

The existing checkout flow subtracts reserved quantities from `products.stockQuantity` and restores them when a reservation is safely released. Therefore the legacy field is closer to available stock than physical on-hand stock while reservations are active.

**Never set new on-hand stock equal to legacy `stockQuantity` without reconciliation.**

Before mapping legacy quantities:
1. Capture a verified, timestamped backup and a consistent inspection snapshot.
2. Identify every active pending reservation and its order items.
3. Identify paid orders, failed/cancelled orders, expired reservations, and any payment-state uncertainty.
4. Reconcile online order/payment state with Razorpay records through an authorized, controlled process. Do not use a simple status count as proof of provider settlement.
5. Perform a physical stock count at the shop, by sellable variant, before cutover.
6. Resolve product-to-variant mapping and ambiguous legacy product records.
7. Calculate and independently review proposed on-hand and reserved quantities.
8. Compare expected available quantities with physical count and explain every variance.
9. Obtain owner sign-off on the reconciliation report before any migration write.

The POS migration must not create inventory for a variant based only on a guessed interpretation of a product's old `stockQuantity`.

## 9. Backup and restore verification gate

Before any DDL or data migration:

- Create a provider-supported backup or export using a documented, tested method.
- Confirm backup timestamp, database identity, coverage, encryption, retention, and access restrictions.
- Keep backup artifacts outside the Git repository and restrict access to authorized personnel.
- Verify that any required object-storage files and metadata are covered by a separate coordinated backup.
- Restore the backup into an isolated non-production database.
- Confirm restored table inventory, key row counts, indexes, and foreign keys.
- Run application-independent integrity checks on the restored copy.
- Record restore duration and any errors.
- Confirm the rollback point and who is authorized to initiate recovery.

A backup is not considered verified merely because the provider dashboard reports that a backup job completed. A successful isolated restore test is required.

## 10. Inspection report template

Complete this record in a restricted project workspace. Do not commit sensitive raw output.

| Item | Result to record |
|---|---|
| Inspection date/time and timezone | Pending |
| Database identity confirmed by owner | Pending |
| MySQL version and SQL mode | Pending |
| Read-only identity verified | Pending |
| Table/view inventory complete | Pending |
| Column/index/constraint inventory complete | Pending |
| Exact row counts and capture time | Pending |
| Product stock anomalies | Pending |
| Reservation/payment status aggregates | Pending |
| Unresolved order-item product references | Pending |
| Duplicate identifier group counts | Pending |
| Prescription payload presence counts | Pending |
| Backup created and verified | Pending |
| Isolated restore test passed | Pending |
| Physical stock count completed | Pending |
| Legacy-to-variant mapping reviewed | Pending |
| Owner approval for migration planning | Pending |

Record only aggregate facts and non-sensitive schema metadata in any future repository report. Do not store raw production dumps, customer information, prescriptions, credentials, or provider payment identifiers in Git.

## 11. What happens after inspection

Only after the report is complete and reviewed:

1. Compare the live schema with the source/entity audit and canonical POS schema.
2. Document every difference between entity annotations, actual DDL, and expected table names.
3. Build a product-to-variant mapping plan, including ambiguous or unmappable products.
4. Produce a stock reconciliation report with physical count and outstanding reservation evidence.
5. Select and pin a migration framework/version compatible with the existing Spring Boot stack.
6. Design additive, versioned migrations first; preserve legacy columns and tables through the compatibility period.
7. Test migrations against a restored copy with representative anonymized data.
8. Validate online checkout, payment confirmation, reservation expiry, cancellation, order history, admin order views, and POS inventory together.
9. Define rollback triggers and rehearse recovery before scheduling production cutover.
10. Obtain explicit owner approval before each production migration step.

No production migration should be bundled with this inspection task.

## 12. Explicit non-actions

This checklist does not:
- Connect to Aiven or any production database.
- Request, store, or expose database credentials.
- Run SQL against any environment.
- Create a database user or change database permissions.
- Change Hibernate `ddl-auto`, add Flyway/Liquibase, or modify Maven dependencies.
- Alter, backfill, delete, or migrate any data.
- Change product stock, online reservations, orders, payments, prescriptions, or customer records.
- Create POS tables or APIs.

**Current decision:** Gather verified, privacy-preserving live-schema and aggregate evidence first. Keep the existing application and production database unchanged until the inspection, backup/restore test, reconciliation, and approval gates are complete.
