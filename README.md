# NMK Opticals backend

Spring Boot backend for the NMK Opticals customer website, administration portal and in-store POS.

## Project location

The Maven project is in `nmk-backend/`. Run Maven commands from that directory.

## Runtime requirements

- Java 27 (as configured in `nmk-backend/pom.xml`).
- MySQL 8 for local manual testing.
- Maven 3.9+ (or the Maven Wrapper if one is added).

## Local development

1. Start MySQL and create the local database `nmk_pos_manual_test`.
2. Configure the environment variables used by `nmk-backend/src/main/resources/application.properties` (database username/password, JWT secret, Razorpay test credentials, and Cloudinary credentials if uploads are tested). Never commit secrets.
3. Explicitly set `SPRING_PROFILES_ACTIVE=local` in the IDE run configuration or shell.
4. Run from `nmk-backend/`:

   ```powershell
   mvn spring-boot:run
   ```

The local profile uses Hibernate `ddl-auto=update` for the isolated manual-test database only. It does not run SQL-only scripts or replace a reviewed migration process. Read [LOCAL-DATABASE-SETUP.md](nmk-backend/LOCAL-DATABASE-SETUP.md).

## Production safety

- Set `SPRING_PROFILES_ACTIVE=prod` explicitly in the deployment environment.
- The base configuration no longer defaults to the local profile. Without an explicit profile, Hibernate uses `validate`, not `update`.
- Production uses `ddl-auto=validate`; review and apply the required database migrations separately before deployment.
- Configure `APP_CORS_ALLOWED_ORIGINS` with exact HTTPS frontend origins.
- Keep database credentials, JWT secret, Razorpay keys/webhook secret and Cloudinary API secret in hosting secrets.
- Do not deploy until the migration prerequisites in the POS deployment documentation have been checked against the actual database schema.

## Important release limitations

The POS API documentation records current limitations around GST tax invoices, external payment verification/refunds and migration prerequisites. Do not present the current POS receipt as a compliant GST tax invoice.
