# Local MySQL database setup

## Automatic table creation

The backend does **not** default to the `local` profile. Select `local` explicitly for development. With no active profile, the base configuration uses Hibernate `validate`, not automatic schema updates.

The local profile uses Hibernate `ddl-auto=update`. When the backend starts, Hibernate reads the JPA `@Entity` mappings and:

- creates missing entity tables;
- creates mapped relationship/join tables;
- adds missing mapped columns and indexes to existing tables;
- keeps existing rows and tables.

This includes the POS entities (sales, sale items, payments, registers, shifts, cash movements, returns, exchanges, inventory locations, variants, balances, stock movements, stock counts, suppliers, purchase orders and receipts), plus the other entity-backed application tables.

## Local prerequisites

1. Start your local MySQL server.
2. Create the database if it does not already exist:

   ```sql
   CREATE DATABASE nmk_pos_manual_test
     CHARACTER SET utf8mb4
     COLLATE utf8mb4_0900_ai_ci;
   ```

3. Configure the local environment variables required by `application.properties` (database password, JWT secret and payment test credentials). Do not commit real secrets.
4. Set `SPRING_PROFILES_ACTIVE=local` in your IDE run configuration or process environment.
5. Start the backend. No manual SQL file needs to be run for Hibernate-managed entity tables.

## Important limits

- Hibernate `update` only manages schema represented by JPA entities and their mappings. It does not execute scripts under `src/main/resources/db/manual`, and it does not create database objects that exist only in handwritten SQL (for example, SQL-only checks, triggers, or tables used only by JDBC).
- It does not delete existing tables or rows, but it is not a substitute for reviewed, versioned migrations.
- If an existing column has an incompatible type or a database constraint conflicts with an entity mapping, startup may still fail. Investigate that mismatch; do not drop tables to get past it.
- This profile is for the isolated local manual-test database only.

## Production and automated tests

- Production must explicitly set `SPRING_PROFILES_ACTIVE=prod`. `application-prod.properties` keeps Hibernate at `validate` and Flyway disabled; production schema changes remain separately reviewed.
- Automated tests that activate the `test` profile continue using `application-test.properties` and its isolated H2 `create-drop` database.
- Never point the local profile at the client's live database.
