# POS production deployment readiness

Status: configuration review in progress. This document is a deployment gate, not approval to deploy.

## Verified repository configuration

- Activate the production profile with `SPRING_PROFILES_ACTIVE=prod`.
- Backend listens on the hosting platform's `PORT` environment variable when supplied, and defaults to `8080` for local use.
- The base datasource requires `AIVEN_HOST`, `AIVEN_PORT`, `AIVEN_DATABASE`, `AIVEN_USER`, and `AIVEN_PASSWORD`. JDBC uses `sslMode=REQUIRED`; confirm the selected database provider's TLS requirements and certificate-validation policy before launch.
- `JWT_SECRET` is required. The application builds an HMAC signing key with JJWT; provision at least 32 random bytes and keep the value outside source control. The current implementation uses the configured string's UTF-8 bytes directly; do not Base64-decode the value. Startup now validates the HMAC key immediately, so a weak key fails application startup rather than the first login. Rotating it invalidates existing sessions.
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` are required by the base configuration. Use live credentials only in the production secret store, never in frontend `VITE_*` variables.
- `APP_CORS_ALLOWED_ORIGINS` is required by the production profile. Supply only the exact storefront/admin HTTPS origin(s), comma-separated; do not include paths, wildcard origins, localhost, or preview domains. The security configuration rejects wildcard origins and rejects non-HTTPS origins when the production profile is active.
- Production disables SQL display, enables Hibernate schema validation (not schema mutation), disables Flyway auto-run, disables open-in-view, and suppresses detailed error messages/stack traces.
- The frontend's `.env.example` documents `VITE_API_BASE_URL`. Set it at build time to the deployed backend API base ending in `/api`. If it is omitted from a separately hosted production frontend, the frontend falls back to same-origin relative API paths and will likely call the wrong host.
- `AdminDataInitializer` supports a one-time initial administrator via `ADMIN_BOOTSTRAP_USERNAME` and `ADMIN_BOOTSTRAP_PASSWORD`. It creates the account only if that username does not already exist. Remove both variables from the deployment environment immediately after verifying the first administrator can sign in and create/manage staff.
- Demo website-image seeding is now controlled by `website-images.bootstrap.enabled`. It defaults to enabled for existing development/test behavior and is explicitly disabled in `application-prod.properties`; production will not silently populate demo placeholder image URLs.

## Production API rate limiting

- `application-prod.properties` enables the shared database-backed rate limiter by default through `app.rate-limit.enabled=${APP_RATE_LIMIT_ENABLED:true}`. This closes the previous gap where the base default (`false`) silently disabled request protection even when the production profile was active.
- Apply and verify `nmk-backend/src/main/resources/db/manual/018-create-api-rate-limit-buckets.sql` on the approved, staging-rehearsed production schema before enabling the production service. Do not run it against a client database without approval and a verified backup.
- If the rate-limit table is missing or the database store fails, protected routes fail closed with HTTP 503. Do not disable the limiter just to bypass a missing migration; fix the schema/connection.
- `APP_RATE_LIMIT_ENABLED` is an optional explicit override. It should normally be omitted so production remains enabled by default; setting it to `false` requires a documented, approved exception.
- The limiter uses the socket peer address, not arbitrary forwarded headers. Configure the trusted hosting proxy to overwrite client IP headers and review edge-level rate limits separately.

## Required deployment environment

Backend:
- `SPRING_PROFILES_ACTIVE=prod`
- `PORT` (only when required by the selected hosting platform; otherwise the backend defaults to `8080`)
- `AIVEN_HOST`, `AIVEN_PORT`, `AIVEN_DATABASE`, `AIVEN_USER`, `AIVEN_PASSWORD`
- `JWT_SECRET` (at least 32 random bytes; supplied string bytes are used directly, not Base64-decoded)
- `APP_CORS_ALLOWED_ORIGINS` (exact HTTPS origins)
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
- Temporary only: `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD`

Frontend build:
- `VITE_API_BASE_URL=https://<backend-host>/api` (replace with the actual backend URL; this is a public API address, not a secret)

SMS remains disabled in the current base configuration. Do not enable it until client MSG91 credentials, sender/template approval, and delivery tests are available.

## CI and deployment workflow review

- The backend GitHub Actions workflow runs Java 27 tests and a separate MySQL 8.4 integration/concurrency suite. It requests read-only repository contents permission and cancels superseded runs.
- The frontend workflow runs `npm ci`, build, and lint. It does not deploy the frontend and does not currently provide a production API URL during CI; the real deployment pipeline must inject `VITE_API_BASE_URL` at build time.
- No repository-managed deployment workflow, Dockerfile, Render/Railway/Netlify/Vercel deployment manifest, or production hosting configuration was found in the inspected branch. Hosting setup therefore remains external and unverified.
- Repository `.gitignore` files exclude `.env*` files except `.env.example`, local secrets/key files, build output, and database dumps. This is a useful guardrail, not a substitute for scanning Git history and hosting secrets. A targeted code search of the accessible default-branch index returned no matches for common Razorpay live-key, JWT-secret, or Cloudinary API-secret patterns; that search does not prove that feature-branch history or all prior commits are clean.

## Deployment checks that remain mandatory

1. Configure secrets in the hosting provider's secret manager; verify they are not committed, printed in logs, or embedded in frontend build artifacts.
2. Confirm the production profile is active in the actual running service and verify the effective CORS allowlist from a real browser origin. Do not infer this from a successful backend build.
3. Terminate TLS at the hosting platform/reverse proxy, enforce HTTPS redirects at the edge, and verify forwarded-protocol handling for any secure-cookie or absolute-URL behavior. The application config reviewed here does not itself prove edge TLS or redirect behavior.
4. Confirm the database provider's TLS mode, network allowlist, backups, restore procedure, connection limits, and monitoring. The repository currently does not set explicit Hikari pool sizing/timeouts; agree these against the selected DB plan and expected concurrency before launch.
5. Production schema changes are not automatically applied: Hibernate uses `validate` and Flyway is disabled. Review and rehearse each SQL migration against a disposable schema matching the client baseline. Keep a signed execution record with migration filename and SHA-256, target DB, operator, approval, backup ID, timestamps, result, and post-migration verification. MySQL DDL may implicitly commit; a failed multi-statement migration may leave partial schema changes.
6. Do not run migrations against the client's live database until credentials, a verified backup/restore, baseline inspection, and an approved rehearsal plan exist.
7. Verify the initial administrator, remove bootstrap variables, rotate any temporary credentials, and confirm role restrictions through authenticated API tests.
8. Upload client-approved storefront assets through the admin workflow before making the storefront public; demo image seeding is disabled in production.
9. Configure monitoring/alerts for startup failures, database connection failures, payment webhook failures/manual review, and backup failures. No claim is made here that these operational integrations are already configured.
10. Run browser acceptance, physical register/receipt tests, payment-provider test/live-mode checks, and the outstanding manual release checklist before launch.

## Known limitations / items not proven by this repository review

- No production host, domain, DNS, TLS certificate, reverse-proxy policy, or hosting secret store has been verified.
- No client database credentials or production database have been accessed.
- No production backup/restore or migration rehearsal has been performed.
- No explicit connection-pool sizing/timeouts are configured in the inspected properties.
- No deployment-specific actuator/management exposure review can be completed until the actual hosting/runtime configuration is known.
- The frontend build-time API URL must be supplied by the deployment pipeline; repository defaults are development-oriented.
- Payment provider settlement, refund processing, bank reconciliation, GST/tax approval, and manual POS browser acceptance remain separate release gates.

## Payment webhook exception visibility

- Added a read-only paginated `GET /api/admin/pos/payments/webhook-review` endpoint for managers/admins. It returns safe event metadata and extracted provider refund/payment identifiers but never the raw webhook payload.
- Added unit tests for refund metadata extraction, malformed payload visibility, and pagination validation.
- This endpoint is not a refund processor or reconciliation workflow. No provider refund is created or marked successful, and there is no resolution action or frontend screen yet.

## Additional changes made in this review

- JWT signing-key strength is now validated at application startup; weak keys fail fast. Added unit tests for accepted and rejected key lengths.
- Backend `.env.example` now shows the production profile, hosting port, correct JWT secret handling, and removes an unused `APP_CORS_REQUIRE_HTTPS` variable (HTTPS enforcement is set by the production profile itself).

## Change made in the previous review

- Production profile sets `website-images.bootstrap.enabled=false`.
- The initializer skips demo image records when that property is false, while retaining its existing one-time admin bootstrap behavior.
- Development/test default remains enabled, preserving existing local behavior.

No deployment, live database access, migration execution, PR merge, or production credential change was performed.


## HTTP ingress limits and rate limiting

- The application now rejects request bodies larger than 1 MiB before MVC/Jackson controller deserialization and caps HTTP request headers at 16 KiB. This applies to non-read-only HTTP methods, including chunked requests without a declared content length.
- Keep the webhook's existing 1 MiB raw-body limit aligned with `app.http.max-request-body-bytes`; if either limit changes, update and test both together.
- This in-process body cap does not replace reverse-proxy limits. Configure the hosting edge to cap request headers and body sizes, enforce HTTPS, and apply per-IP rate limits to login, checkout, appointment booking and public order tracking. Exclude or separately tune Razorpay webhook traffic so legitimate provider retries are not dropped.
- No hosting provider or reverse proxy is defined in this repository, so edge rate limiting, header limits and TLS policy remain deployment tasks. Do not trust client-supplied forwarded-IP headers unless the hosting proxy is configured to overwrite them and the application is configured to trust only that proxy.


## Prescription file privacy

- [ ] Configure `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, and `CLOUDINARY_API_SECRET` on the backend only.
- [ ] Confirm new prescription uploads use the backend endpoint, are content-signature checked, limited to 5 MB, and stored with Cloudinary delivery type `authenticated`.
- [ ] Confirm Admin obtains a five-minute download URL only through the order/item-scoped endpoint.
- [ ] Disable the old unsigned `nmk_opticals` upload preset after the new flow is deployed and verified.
- [ ] Treat old order prescription URLs as legacy public files; inventory and re-upload them only under an approved migration plan.
- [ ] Configure the reverse proxy to permit the 6 MB multipart upload request ceiling for `/api/prescriptions/upload`; the JSON body ceiling remains 1 MB.


## Online Razorpay refund release checks

- [ ] Apply and verify manual migration 019 after migrations 017 and 018 on the approved target schema.
- [ ] Configure Razorpay key ID and secret on the backend only; confirm credentials are live/test as intended before enabling the endpoint.
- [ ] Verify only Admin can authorize refunds and that every request requires a fresh password authorization.
- [ ] Verify normal-speed partial/full refunds, minimum ₹1, remaining-balance enforcement, and the provider `X-Refund-Idempotency` header.
- [ ] Verify retries after timeout/409 reuse the same local idempotency key and do not create duplicate refunds.
- [ ] Verify refund webhooks arriving before the API response link through the unique receipt and do not create duplicate ledger rows.
- [ ] Confirm order shipment is blocked while a refund is pending; confirm completed refunds do not auto-cancel or restock stock.
- [ ] Keep bank settlement matching, POS tender provider verification and chargeback reconciliation as separate go-live gaps.


## Razorpay settlement reconciliation release checks

- [ ] Apply and verify manual migration 020 after migrations 017, 018 and 019 on the approved target schema.
- [ ] Configure Razorpay API key ID/secret on the backend only; verify the intended test/live account.
- [ ] Verify Admin-only access, provider API pagination, rate limiting and idempotent repeated synchronization.
- [ ] Verify provider settlement net amount, fee, tax and UTR totals; verify payment rows match local paid orders and refund rows match processed refund ledger rows.
- [ ] Review all transfer, adjustment, unmatched, mismatch and on-hold entries before treating a settlement as complete.
- [ ] Do not label provider reconciliation as bank reconciliation. Bank statement import, UTR-to-bank-credit matching and settlement variance approval remain outstanding.


## Razorpay dispute release checks

- [ ] Apply and verify manual migration 021 after migration 020 on the approved target schema.
- [ ] Configure and verify the signed Razorpay webhook URL and backend-only webhook secret.
- [ ] Subscribe to the dispute lifecycle events supported by the merchant account and confirm test events reach the backend.
- [ ] Verify dispute IDs/payment IDs match local orders; review unmatched and amount-mismatch records.
- [ ] Assign a staff owner to monitor response deadlines and submit evidence/decisions in Razorpay Dashboard.
- [ ] Review historical webhook payload retention separately; the new sanitizer only affects events received after deployment.
