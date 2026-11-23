# Phase 5 — Batch A Backend Review

**Status:** Flyway dependencies added and locally build-tested; activation intentionally held pending baseline and compatibility checks.  
**Repository:** `Elavarasany/nmk-backend`, branch `master`  
**Inspected files:** `nmk-backend/pom.xml`, `nmk-backend/src/main/resources/application.properties`  
**Inspection source:** GitHub `master` at the time of this review.

## 1. Findings

### Maven project

- Spring Boot parent: `4.1.2`
- Java target: `27`
- MySQL Connector/J: present, runtime scope.
- Spring Data JPA: present.
- H2: present for tests.
- Flyway: `spring-boot-starter-flyway` and `flyway-mysql` are now declared using Spring Boot dependency management.
- No explicit Flyway version or MySQL Flyway database module is present.

### Application configuration

Current relevant settings:

```properties
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.datasource.url=jdbc:mysql://${AIVEN_HOST}:${AIVEN_PORT}/${AIVEN_DATABASE}?sslMode=REQUIRED
```

The datasource requires TLS. Database credentials are environment placeholders and must remain outside source control.

## 2. Why Batch A is not being activated yet

Adding Flyway dependencies alone is not sufficient. Flyway must know which migration version represents the existing live schema. The live database is not empty: the Phase 1 inspection found 10 existing application tables.

An incorrect baseline can cause Flyway to treat existing objects as absent or can attempt to create tables that already exist. Enabling Flyway before the baseline decision and staging proof is therefore prohibited.

Likewise, switching Hibernate away from `ddl-auto=update` without first validating the complete entity/schema relationship could break application startup or leave expected schema objects absent. The switch must be staged and separately reviewed.

## 3. Required Batch A sequence

1. Inspect the complete backend resource/configuration structure and all active profiles.
2. Confirm the Spring Boot 4.1.2 dependency-management versions for Flyway and its MySQL module. Pin versions only if the Boot dependency management does not provide a suitable compatible version.
3. Decide whether to use the Flyway Spring Boot starter or the core Flyway integration plus the MySQL database module; do not add both paths redundantly.
4. Create a sanitized staging copy of the existing schema and representative non-sensitive test fixtures.
5. Compare the staging schema against a documented baseline. The baseline must represent the actual pre-existing schema, not an assumed empty database.
6. Test Flyway baseline behavior on the disposable staging copy and prove it does not recreate or mutate existing tables.
7. Prove the current application starts and its existing APIs work with the chosen migration configuration.
8. Only after these checks, prepare a reviewed code change that adds migration support and prevents Hibernate from being the production schema migration authority.
9. Keep all migration execution pointed at staging until production approval is explicitly given.

## 4. Immediate no-change rules

Until the above sequence is complete:

- Do not add `spring.flyway.baseline-on-migrate=true` as a blind default.
- Do not choose a baseline version without a schema comparison and staging proof.
- Do not add an empty baseline migration that falsely claims to describe the existing schema.
- Do not change `spring.jpa.hibernate.ddl-auto=update` in the live deployment yet.
- Do not run Flyway against Aiven.
- Do not create POS tables or alter existing product/order/payment tables.
- Do not initialize inventory balances from `products.stockQuantity`.

## 5. Logging configuration note

`spring.jpa.show-sql=true` is currently enabled. Before production migration work, review SQL logging and application log levels to ensure parameters, personal data, prescription details and credentials are not exposed. Do not enable verbose SQL/parameter logging in production as a troubleshooting shortcut.

## 6. Batch A acceptance criteria

Batch A is accepted only when:

- The exact Flyway integration and versions are documented.
- The baseline version and procedure are justified by the existing schema.
- A sanitized staging restore has been tested.
- Flyway validation and application startup succeed on staging.
- Existing website order, payment, tracking, admin and appointment behavior passes regression tests.
- Hibernate no longer performs uncontrolled production schema updates, with the change proven on staging.
- No Aiven production migration has been executed as part of this review.

**Current result:** inspection only. No code, dependency, configuration or database changes were made in this review.

## 7. Verified Spring Boot 4.1.2 Flyway dependency guidance

The official Spring Boot 4.1.2 dependency coordinates list both:
- `org.springframework.boot:spring-boot-starter-flyway`
- `org.flywaydb:flyway-mysql`

The Spring Boot 4.1.2 managed dependency coordinates list Flyway `12.4.0`, including `flyway-mysql`. Therefore the preferred initial dependency approach, subject to a local Maven resolution/build check, is to use the Spring Boot-managed versions rather than hardcoding an unrelated Flyway version.

For this MySQL application, the intended dependency pair is:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

Do not add a separate `flyway-core` dependency unless the resolved dependency graph proves it is needed; avoid redundant declarations. These dependencies were subsequently committed using Spring Boot 4.1.2 dependency management, and the user confirmed Maven dependency resolution plus `mvn clean test` succeeded locally. Flyway remains disabled pending the baseline and staging gates below.

Official references:
- Spring Boot 4.1.2 managed coordinates: https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html
- Spring Boot database initialization guidance: https://docs.spring.io/spring-boot/4.2/how-to/data-initialization.html

## 8. Configuration and profile inspection

The repository's `src/main/resources` directory currently contains `application.properties` and a `static` directory; no `application-<profile>.properties` file was listed there. The active deployment environment and any externally supplied configuration still need checking before a migration feature is enabled.

The current `ddl-auto=update` setting remains a release blocker for production Flyway activation. The intended target is a controlled Hibernate validation mode after schema parity is proven on staging, but no mode change is authorized by this review.

## 9. Dependency implementation and local verification (2026-11-16)

The following code-only changes were committed to `master`:

- Commit `aedad3b9c6940ffde62224ed1cfa25ac8069f742`: added `spring-boot-starter-flyway` and `flyway-mysql` without explicit versions, using Spring Boot 4.1.2 dependency management.
- Commit `a7675abcc2b5648dddd996e5615416e7866e8d0c`: added `spring.flyway.enabled=false` to `application.properties`.

The user confirmed both standalone Maven dependency-resolution checks succeeded and then confirmed `mvn clean test` completed successfully after pulling these changes.

**Interpretation:** dependency resolution and the current backend test/build lifecycle pass locally. This does not prove that Flyway can baseline the existing Aiven schema, that the deployed environment uses the same configuration, or that the application is safe to migrate.

Flyway remains explicitly disabled. `spring.jpa.hibernate.ddl-auto=update` remains unchanged. No migration scripts were added and no database was changed.

## 10. Next required work — complete live schema metadata capture

Before selecting a Flyway baseline version, capture the complete DDL and metadata for every existing application table on a sanitized staging copy (or use read-only metadata access for the live schema). The earlier Phase 1 transcript truncated some `SHOW CREATE TABLE` output and did not establish all table engines/collations.

Required capture:
- Full `SHOW CREATE TABLE` for all ten application tables.
- For each table: engine, table collation, row format, and create options.
- Database and server character set/collation.
- SQL mode and timezone relevant to the application connection.
- Confirm whether `flyway_schema_history` already exists; do not create or modify it during inspection.
- Confirm all active deployment profiles and externally supplied Spring configuration without exposing credentials.

Use metadata-only/read-only inspection. Do not export customer/order rows, prescription JSON, payment payloads, credentials, or secrets. Do not run `flyway baseline`, `migrate`, `repair`, or any DDL against Aiven.

After capture, compare the actual schema against entity mappings and decide the baseline strategy on a disposable restored staging database.

## 11. Live schema-only export review (2026-11-16)

The user supplied the complete `CREATE TABLE` definitions from a structure-only Workbench export for all ten existing application tables in `defaultdb`. The export header identified MySQL Server `8.4.11` and local `mysqldump` `8.0.46`. The version warning was acknowledged and the export completed. The user supplied only DDL, not table rows.

### Confirmed table inventory

| Table | Primary key | Important unique/index/relationship details |
|---|---|---|
| `admins` | `id BIGINT AUTO_INCREMENT` | Unique username |
| `admin_action_authorizations` | `id BIGINT AUTO_INCREMENT` | Unique token_hash |
| `appointments` | `id BIGINT AUTO_INCREMENT` | No secondary indexes or unique slot constraint in exported DDL |
| `customer_orders` | `id BIGINT AUTO_INCREMENT` | Unique tracking_token |
| `marketing_banners` | `id BIGINT AUTO_INCREMENT` | No secondary indexes in exported DDL |
| `offer_bars` | `id BIGINT AUTO_INCREMENT` | No secondary indexes in exported DDL |
| `order_items` | `id BIGINT AUTO_INCREMENT` | Index and FK on order_id referencing customer_orders(id); no product_id FK |
| `products` | `id BIGINT AUTO_INCREMENT` | No secondary indexes in exported DDL |
| `razorpay_webhook_events` | `id BIGINT AUTO_INCREMENT` | Unique event_id |
| `website_images` | `id BIGINT AUTO_INCREMENT` | Unique image_key |

All ten exported tables use InnoDB, `utf8mb4`, and `utf8mb4_0900_ai_ci`. The earlier live metadata inspection also reported no explicit table create options/comments, no triggers, no stored procedures/functions, no scheduled events, and no `flyway_schema_history` table. These are inspection findings, not permission to alter the live schema.

### Material compatibility observations

1. `products.stock_quantity` is a required, product-level INT. It is not variant/location inventory and must not be treated as a verified physical count or automatically copied into new inventory balances.
2. `order_items.product_id` is nullable and has no FK to `products`; `order_items.order_id` is required and has the FK to `customer_orders`.
3. Existing online reservation state is represented by `customer_orders.stock_reserved` and `reservation_expires_at`. Keep this legacy order/payment flow intact during additive rollout and prove compatibility before cutover.
4. `customer_orders.tracking_token`, `admins.username`, `admin_action_authorizations.token_hash`, `razorpay_webhook_events.event_id`, and `website_images.image_key` have unique indexes.
5. The appointments table has no database-level unique constraint for date/time slots. Any prevention of double-booking must be reviewed against the actual appointment business rules and concurrency behavior before adding constraints.
6. Existing money columns use `DECIMAL(10,2)` for product price and `DECIMAL(38,2)` for order/order-item amounts. New POS money columns should follow the canonical design and explicit precision review; do not silently alter legacy columns as part of POS rollout.
7. All table definitions use BIGINT auto-increment primary keys. Preserve existing IDs and do not recreate these tables during baseline.

### Export safety warning

The supplied mysqldump output contains `DROP TABLE IF EXISTS` statements and a `SET @@GLOBAL.GTID_PURGED` statement. These are dump/restore instructions, not safe inspection commands. **Never execute these exported files against the live Aiven database.** They are retained only as schema reference material. No customer/order rows, password hashes, token hashes, webhook payloads, or prescription values were requested or reviewed.

The export uses a mysqldump client older than the server. The DDL was readable and consistent with the previously captured metadata, but this export alone does not prove a complete restorable backup. A separately verified backup/restore procedure is still required before any migration.

### Result and remaining gates

- DDL for all ten existing application tables has now been reviewed.
- The table inventory, key constraints, and principal legacy inventory/order compatibility points agree with the Phase 1 live metadata findings.
- No production DDL, data changes, Flyway command, or application configuration change was performed during this export review.
- The earlier requirements for schema/entity parity, sanitized staging restore, baseline proof, application regression tests, active deployment configuration inspection, and backup/restore verification remain open.

**Next step:** prepare a disposable staging restore from a proper backup (not by executing the supplied schema-only files against Aiven), compare Hibernate entity mappings with the captured DDL, and test the baseline procedure there. Keep Flyway disabled and production `ddl-auto=update` unchanged until those gates pass.


## 12. Hibernate entity-to-DDL comparison (2026-11-16)

The ten current entity classes under `nmk-backend/src/main/java/nmk_backend/entity` were inspected on GitHub `master` and compared with the ten schema-only table definitions supplied from the live database.

### Entity coverage

The existing ten tables each have a corresponding JPA entity:
- `Admin` → `admins`
- `AdminActionAuthorization` → `admin_action_authorizations`
- `Appointment` → `appointments`
- `Order` → `customer_orders`
- `OrderItem` → `order_items`
- `Product` → `products`
- `MarketingBanner` → `marketing_banners`
- `OfferBar` → `offer_bars`
- `RazorpayWebhookEvent` → `razorpay_webhook_events`
- `WebsiteImage` → `website_images`

The `Customer.java` file currently contains no mapped entity definition and does not correspond to an existing customer table.

### Main mapping checks

- All ten entities use identity-generated BIGINT identifiers, matching the exported primary keys.
- The `Order` ↔ `OrderItem` association maps to the existing `order_items.order_id` foreign key. `OrderItem.productId` is a scalar Long, not a JPA relationship, consistent with the absence of a product FK in the current schema.
- `Product.specifications` uses Hibernate JSON typing and a JSON column definition, matching the live JSON column.
- `RazorpayWebhookEvent.rawPayload` maps to LONGTEXT, matching the live table.
- The explicit varchar lengths and decimal precision on Product, Order, OrderItem, MarketingBanner, OfferBar, WebsiteImage, and authorization/webhook fields are broadly consistent with the exported DDL. Default JPA string lengths and implicit snake_case naming account for several columns without explicit annotations.
- Nullable legacy order/customer fields remain nullable in the schema; do not tighten them as part of this POS migration.

### Items requiring a controlled Hibernate schema-validation test

1. `AdminActionAuthorization.tokenHash` declares `unique = true` on the field and also declares a named table-level unique constraint for the same column. This duplicates uniqueness metadata in the entity mapping. The live database has one named unique index. Do not run Hibernate schema update to "fix" this; verify the mapping with Hibernate validation and, if needed, remove the redundant declaration in a separately tested code change.
2. `RazorpayWebhookEvent.eventId` has the same pattern: field-level `unique = true` plus a named table-level unique constraint. The live database has one named unique index. Validate and clean up the redundant mapping separately if required.
3. Entity lifecycle callbacks use `LocalDateTime.now()` for several timestamps, while the database server/session was previously observed to use UTC. Confirm the deployed JVM timezone is UTC or standardize timestamp creation to an explicit UTC clock in a separately tested change. Do not alter stored timestamps during this migration.
4. `Admin.createdAt` is initialized when the entity object is instantiated, rather than in a persistence callback; this is existing application behavior and is not a reason to alter the database schema.
5. The appointment entity leaves slot uniqueness to application logic. The database currently has no unique constraint on appointment date/time. Review concurrency and real booking rules before considering a database constraint.

### Result

This source review found no reason to recreate or directly alter the ten existing tables. It is not yet a formal schema-parity pass: the next proof must be a staging-only Hibernate `validate` run against a sanitized restored schema, plus application startup and regression checks. Keep Flyway disabled, keep production `ddl-auto=update` unchanged for now, and do not execute migrations or DDL against Aiven.
