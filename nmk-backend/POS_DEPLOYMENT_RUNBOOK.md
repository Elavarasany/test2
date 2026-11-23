# NMK Opticals POS Deployment Runbook

This runbook is a controlled deployment checklist. The POS feature branch is not permission to run migrations against a live database. Do not deploy until the client database credentials, an approved maintenance window, a restorable backup, and the accountant's tax decisions are available.

## 1. Current migration behavior

- Hibernate is configured with `spring.jpa.hibernate.ddl-auto=validate`; it must not create or alter the production schema.
- Flyway is intentionally disabled (`spring.flyway.enabled=false`). The SQL files under `src/main/resources/db/manual` are manual scripts, not automatically ordered migrations.
- Never run all files 001 through 004 as one sequence. File 004 is an alternative reconciliation for the already partially migrated staging schema and overlaps parts of 001. It is not the next step after 001-003.
- Never rerun an `ALTER TABLE ... ADD COLUMN` script without first inspecting the target schema; most scripts are not rerun-safe.

## 2. Before touching a database

The SQL directory has no automatic migration runner or durable migration-history table. Maintain a signed migration execution record outside the application database for each target environment. For every script, record its exact filename and SHA-256 checksum, target database, operator, approval/ticket, backup identifier, start/end time, result, verification output and any recovery action. Never infer that a script ran successfully from the presence of some of its tables: MySQL DDL can implicitly commit, so a failed script may have left a partially changed schema. Stop, inspect and prepare a schema-specific repair before continuing.

1. Obtain written client approval and the correct database credentials. Confirm host, database name, MySQL version, TLS mode and timezone.
2. Take a full logical backup and verify that it can be restored to a separate database.
3. Restore a copy to a disposable staging database. Do not use the live database for experiments.
4. Compare the staging schema with the entity mappings and each SQL script. Record which columns, tables, indexes and constraints already exist.
5. Choose exactly one baseline path below. If the schema does not match either path, stop and prepare a new, schema-specific migration; do not improvise.

## 3. Choose the correct baseline

### Path A — legacy schema, not yet variant-inventory migrated

Use only when the inspected database has the original `products`, `customer_orders` and `order_items` schema and does not already contain the corresponding migration columns/tables:

1. `001-legacy-order-and-product-columns.sql`
2. `002-create-variant-inventory-tables.sql`
3. `003-create-main-store-location.sql`
4. `005-create-pos-sales-tables.sql` through `014-create-pos-exchanges.sql`, in numeric order.

Run each script individually, inspect its output, and stop immediately on any error. Do not continue to the next script after a partial failure.

### Path B — the specific partially migrated staging schema

Use `004-staging-schema-reconciliation.sql` only if schema inspection confirms all of these exact preconditions: variant/inventory tables already exist; the existing `AVP-01` location is present; `products.version` already exists; `customer_orders.payment_creation_status` already exists; `order_items.variant_id` already exists with its expected foreign key; and only the specific columns added by script 004 are missing. Script 004 does **not** add `products.version` or `order_items.variant_id`, and it modifies rather than creates `customer_orders.payment_creation_status`. If any precondition is absent or uncertain, do not run Path B.

After 004 succeeds and its verification queries match expectations, apply 005 through 014 in numeric order.

Path B is a staging-specific reconciliation, not a generic production migration. Do not assume a new client database matches it.

### Razorpay webhook retry schema (separate from POS migrations)

The webhook worker expects the pre-existing `razorpay_webhook_events` table plus four retry/claim columns and a due-event index. The numbered 001–014 POS migration chain does not create or alter this table.

1. Confirm the existing webhook event table and its original columns on the inspected database. No base-table creation script is included here; if the table is absent, stop and resolve the baseline separately.
2. Apply `razorpay-webhook-claim-token.sql`.
3. Apply `razorpay-webhook-retry-columns.sql`.
4. Verify `claim_token`, `attempt_count`, `next_attempt_at`, `processing_started_at`, and `idx_razorpay_webhook_due` exist before starting the application with Hibernate validation.

Do not skip these scripts on a database where the webhook worker is enabled, and do not run them blindly if the columns or index already exist.

### Shared API rate-limit storage (required for production defaults)

Before activating the production profile, inspect the target schema and apply `018-create-api-rate-limit-buckets.sql` if the table and its expiry index are not already present. Verify the table, primary key, expiry index and CHECK constraint. This script uses `CREATE TABLE IF NOT EXISTS`, but still review the existing table definition rather than treating that clause as proof of schema equivalence. Production enables `app.rate-limit.enabled` by default; the application returns 503 for protected requests if the shared database store is unavailable. Do not override `APP_RATE_LIMIT_ENABLED=false` as a routine workaround. Rehearse the migration and rate-limit behavior on the disposable MySQL staging clone first.

## 4. Verify schema before starting the application

- Confirm all POS tables, foreign keys, unique idempotency constraints, indexes and required columns exist.
- Confirm the application can start with `ddl-auto=validate` against the staging database.
- Confirm no duplicate register codes, SKU values, barcodes, location codes or idempotency keys exist.
- Confirm a failed startup does not cause Hibernate schema changes.
- Test the rollback/restore procedure on the disposable database.

## 5. Product variants and opening stock

The schema scripts deliberately do **not** invent product variants or opening stock. Existing `products.stock_quantity` values are preserved as legacy data; POS and online variant availability is calculated from `product_variants` and `inventory_balances`.

Product removal from the admin catalogue is a **soft deactivation**, not a physical database delete. Product, variant, inventory-balance, stock-movement, and historical sale records are retained for auditability. Deactivated products are excluded from active catalogue/checkout flows and cannot receive new stock or have new variants created. Existing reservations can still be consumed or released to complete/cancel an in-flight order. Reactivate a product only after reviewing its variants and stock state.

Before the first live sale:

1. Review every active product and decide its real sellable SKU/variant and barcode. Do not blindly create one default variant for every optical product if frame size, colour, power, lens or other attributes distinguish saleable stock.
2. Create the variants through the admin product-variant workflow.
3. Perform a signed physical opening count at the shop. Map every counted sellable unit to exactly one active product variant (SKU/barcode plus the required frame/lens/power/colour/size attributes). Resolve unmatched, damaged, consigned, reserved and unidentifiable items before posting; do not silently map them to a generic variant.
4. The current application has **no dedicated bulk opening-stock import or `OPENING_BALANCE` movement type**. Load approved quantities through the existing manager/admin-authorized `POST /api/admin/inventory/receive` workflow, one variant/location movement at a time. It records a normal `RECEIPT` movement, so use a clear reason such as `Approved opening stock count YYYY-MM-DD` and a unique idempotency key per variant movement. This is an audited quantity load, not a supplier purchase or proof of acquisition cost. Do not insert balances or movements directly with SQL.
5. Export the resulting balances and stock-movement history, then reconcile every variant and the location total to the signed worksheet. Investigate every difference before checkout is enabled.
6. Do not add legacy `products.stock_quantity` on top of a physical count that already includes the same units. That legacy field is not the POS or online variant-availability source.
7. Keep the signed opening-count worksheet, approvals, balance export and movement export as the audit record.

No online or POS checkout should be enabled until the active sellable variants and opening balances are verified.

## 6. Staff, register and cashier setup

1. Confirm the owner/admin account and test that disabled accounts cannot authenticate. If `ADMIN_BOOTSTRAP_USERNAME` and `ADMIN_BOOTSTRAP_PASSWORD` are used for first-time creation, treat them as temporary one-time deployment secrets: verify the account exists, remove both variables immediately after bootstrap, and rotate the password through the approved account process. Do not leave bootstrap credentials configured across routine restarts/deployments.
2. **Review every account that existed before migration 006.** That migration assigns the default role `ADMIN` to existing rows; do not leave all legacy accounts as administrators. Keep only the designated owner/admin accounts as `ADMIN`, downgrade operational staff to `MANAGER` or `CASHIER` through staff administration, and disable stale/shared accounts.
3. Create individual MANAGER and CASHIER accounts; do not share logins.
4. Create/verify the MAIN register for the active location.
5. Open the first cashier shift with the physically counted opening float.
6. Test checkout, split tender, cash change, cash-in/out, shift close and variance explanation using disposable staging records.
7. Confirm each cashier can access only their own shift operations and cannot browse all sales, process returns/exchanges or apply discounts. Managers/admins must re-enter their password for inventory receipts/adjustments, stock-count post/cancel, purchase-order submissions, goods receipts, supplier returns, POS returns, full voids and exchanges; each one-time action token is bound to the staff member and operation.

## 7. Application hosting configuration

**Production must explicitly activate the `prod` Spring profile** using `SPRING_PROFILES_ACTIVE=prod`. The stricter CORS allowlist, HTTPS-origin enforcement, reduced error details and production logging settings are in `application-prod.properties`; they are not applied merely because the deployment is hosted online. Verify the active profile in startup configuration/logs before exposing the API. If the profile is missing, the base configuration defaults CORS to localhost development origins and does not enforce HTTPS origins.

Backend secrets must be configured in the hosting provider, never committed to Git:

- `AIVEN_HOST`, `AIVEN_PORT`, `AIVEN_DATABASE`, `AIVEN_USER`, `AIVEN_PASSWORD`
- a strong random `JWT_SECRET`
- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`
- `APP_CORS_ALLOWED_ORIGINS`: exact frontend origin(s), comma-separated, no wildcard
- `APP_CORS_REQUIRE_HTTPS=true` in production (the production profile currently hard-enables HTTPS origin validation)

Frontend build-time variable:

- `VITE_API_BASE_URL`: deployed backend origin ending in `/api`, for example `https://api.example.com/api`. Vite embeds this at build time, so rebuild/redeploy after changing it. The POS no longer silently falls back to localhost in production.

Use HTTPS for both frontend and API. Verify TLS/database connectivity, webhook signature verification, health/startup logs, and browser CORS behavior before enabling staff.

## 8. POS release acceptance checks

Run these checks on staging after migration and seed data are complete:

- Cash, UPI, card and split-tender checkout; exact cash change; duplicate retry with same key; reject changed request with reused key.
- Two concurrent attempts to sell the final unit; stock never becomes negative.
- Open/close shift races against checkout; cash ledger and expected drawer reconcile.
- Receipt, partial/full return, damaged/discard dispositions, cash refund, recorded non-cash refund reference, and full void after a sale with no prior returns.
- Equal-value exchange, higher-value replacement with difference collected (including mandatory UPI/card transaction references), lower-value replacement with only the excess refunded, same-key replay, failed-stock rollback, and concurrent exchange attempts against the same remaining sold quantity.
- Exchange-credit reporting reconciles separately from actual tender/refund totals; inventory on-hand report reconciles to location balances.
- A manager/admin cannot perform a stock receipt/adjustment, post/cancel a stock count, submit a purchase order, receive purchase-order goods, return goods to a supplier, process a customer return, full void, or exchange without a fresh one-time password authorization token; token reuse, expiry, wrong staff, and wrong action are rejected.
- Concurrent return attempts; cumulative returned quantity and refund never exceed the original sale.
- Stock receipt, adjustment, low-stock alert, stock count freeze/review/post/cancel, and movement history.
- Purchase order, partial goods receipt, over-receipt rejection, supplier return and stock ledger reconciliation.
- ADMIN/MANAGER/CASHIER authorization for every endpoint and direct API calls (not just hidden UI controls).
- Daily sales, return, refund, net-after-refund and payment-method totals reconcile to sale/return/shift ledgers.
- Frontend build/lint and backend Java + MySQL integration/concurrency CI pass on the exact commits proposed for release.

Do not test production by creating disposable sales, refunds or stock movements. Use staging.

## 9. Known external decisions and unsupported accounting behavior

These are genuine release gates, not items to guess in code:

- **GST/HSN and statutory invoice rules:** the current POS product and sale models do not store HSN/SAC classifications or immutable per-line tax components. The current date-plus-random invoice number is not a verified consecutive financial-year tax-invoice sequence. Do not describe the printed POS receipt as a GST tax invoice, do not infer a rate from product category, and do not retrofit tax onto historical sales. Before implementation, obtain accountant-approved written decisions for supplier GSTIN/address/status; HSN/SAC per sellable SKU/service; rate and effective dates; exemptions; inclusive/exclusive pricing; discount allocation; taxable-value/tax rounding; CGST/SGST versus IGST and place of supply; recipient GSTIN/address capture; reverse-charge handling; and the invoice series. CGST Rule 46 requires, among other applicable particulars, a consecutive serial number unique for the financial year and not exceeding 16 characters, supplier details, HSN/SAC, taxable value, tax rate/amount and applicable place-of-supply details. The current random-suffix invoice number does not demonstrate that control. After approval, implement immutable sale-line tax snapshots, a transactionally allocated financial-year invoice counter, tax-aware return/credit-note handling and reconciliation tests; rehearse on staging before release. Reference: [CBIC GST invoice rules](https://cbic-gst.gov.in/gst-invoice-rules.html).
- **Provider-side refunds and reconciliation:** POS payment rows retain payment method, amount and an operator-entered reference, but no verified Razorpay payment ID/provider account/refund ID. UPI/card refund rows are audit allocations only; they do not initiate or verify money movement. The Razorpay webhook worker currently processes `payment.captured` for online customer orders. Other signed event types—including refund events—are stored and marked processed without changing POS or customer-order refund state. Do not treat a webhook receipt, typed reference or saved refund row as proof of settlement. Before automating refunds, link each POS tender to its provider and original payment ID; add idempotent refund request/provider refund records; verify provider status and amount/currency; process signed refund webhooks; support partial/multiple refunds within captured amount; and reconcile pending, failed, reversed and unmatched settlements. For non-Razorpay UPI/card terminals, obtain the acquirer/terminal refund and settlement integration or require a controlled operator-confirmation procedure with evidence. Keep cash refunds tied to the audited drawer ledger. Obtain merchant API permissions and rehearse on staging before describing any non-cash refund as automated.
- **Exchanges:** an atomic linked return-plus-replacement-sale workflow is implemented on the POS feature branch, including exchange credit, actual difference collection/refund, stock rollback, idempotency, audit history, and migration 014. It is not merged to production. Rehearse it on a client-matching disposable schema and staging data before staff use; do not simulate an exchange as a separate full refund plus unrelated full sale.
- **Supplier accounting:** purchase orders, goods receipts and supplier stock returns do not constitute supplier credit notes, accounts payable, supplier payment, landed-cost accounting or bank settlement. Obtain the client's accounting workflow and required fields before implementing these ledgers.
- **Profit reporting:** gross sales/product totals are not profit. Cost history and approved accounting treatment are not yet available for a reliable POS profit report.

## 10. Production content initialization

The current `AdminDataInitializer` creates missing default website-image records (including placeholder logo/hero/category/store image URLs) on application startup in every profile. It does not overwrite existing keys, but a fresh production database will receive these defaults. Before making the storefront public, replace and verify every seeded placeholder with client-approved assets, or change the initializer to use an explicitly controlled non-production seed profile. Confirm no placeholder assets remain in the public website.

## 11. Release decision

Do not merge the draft POS pull requests or point staff at production until:
- the client database migration has been reviewed and tested on a restorable clone;
- product variants and opening stock have been physically reconciled;
- the client's tax/invoice requirements are approved;
- refund and exchange operating procedures are explicitly accepted (or the missing integrations/workflows are implemented);
- exact-commit CI and staging acceptance checks pass; and
- the owner signs off on the cutover and rollback plan.

The feature branches and draft PRs intentionally remain separate from `main`/`master` until those gates are satisfied.


## Private customer prescription storage

New customer prescription uploads are sent to `POST /api/prescriptions/upload`. The backend enforces a 5 MB limit, checks the file signature (JPEG/PNG/WEBP/PDF), and uploads it to Cloudinary as an `authenticated` asset under the `nmk-prescriptions` public-ID prefix. The browser never receives the Cloudinary API secret or a public delivery URL.

Required server environment variables:
- `CLOUDINARY_CLOUD_NAME` (defaults to the existing cloud name `ydkweahp`)
- `CLOUDINARY_API_KEY`
- `CLOUDINARY_API_SECRET`

If credentials are missing, upload is disabled and returns HTTP 503. Do not re-enable the old unsigned preset as a fallback. Once the new flow is deployed and verified, disable/delete the old unsigned `nmk_opticals` preset in Cloudinary.

Checkout verifies Cloudinary's upload-response signature and stores the opaque asset ID and verification metadata. Admin access is scoped to the owning order and item and returns a five-minute private download URL. Existing orders with legacy public `imageUrl` values remain readable for compatibility; they are not retroactively made private. A separate migration/re-upload plan is required to replace those legacy files.

The upload endpoint has a dedicated rate limit and a 6 MB multipart transport ceiling (5 MB file plus multipart overhead). The general JSON request-body limit remains unchanged. Configure the reverse proxy to allow at least 6 MB for this endpoint and keep request timeouts bounded.


## Online Razorpay refund initiation

- Backend uses normal-speed Razorpay refunds only; instant refunds are intentionally not exposed.
- Admin → Orders requires a fresh admin password authorization for each initiation. The manager role is not authorized by default.
- The amount is in paise, must be at least ₹1.00, and cannot exceed the captured amount less all pending/processed refunds. A reason is required.
- The backend stores a pending local request and unique receipt before calling Razorpay. It sends `X-Refund-Idempotency` and the same request body on retry. If a timeout or provider 409/5xx leaves the outcome uncertain, retry with the same idempotency key; do not create a new one.
- Signed refund webhooks match the pending local request by receipt, including when the webhook arrives before the API response is stored.
- Apply manual schema migration `019-add-idempotent-refund-requests.sql` after migrations 017 and 018 and before deploying this code. Stage, inspect exact existing columns/indexes/constraints, rehearse on a disposable MySQL 8.4 restore, and take a verified backup. Do not apply to the client database without explicit approval.
- Refund completion does not automatically cancel an order or restore stock. Use the approved audited stock adjustment/return process after provider confirmation. Bank settlement reconciliation remains a separate task.


## Razorpay provider settlement reconciliation

- Admin → Payment Settlements fetches the read-only Razorpay combined settlement reconciliation report with pagination and stores provider batches/entries idempotently.
- Each batch is checked against provider net amount, fees, tax and UTR. Payment rows match local captured orders; refund rows match processed local refund ledger records; lost-dispute debit rows match the local dispute ledger. Transfers, adjustments and unknown provider entry types are flagged for manual review.
- `PROVIDER_RECONCILED` means the provider report balances and its supported rows match local NMK records. It does not mean the UTR credit has been verified against the bank statement.
- Apply manual migration `020-create-razorpay-settlement-reconciliation.sql` after migrations 017, 018 and 019 and before deploying this code. Inspect exact existing schema/indexes/constraints, rehearse on a disposable MySQL 8.4 restore, and take a verified backup. Do not apply to the client database without explicit approval.
- Configure Razorpay API key ID/secret on the backend only. The sync endpoint is Admin-only, read-only against Razorpay and rate-limited. If credentials are absent it fails closed with HTTP 503.
- Bank statement import/matching, bank-account confirmation, chargeback/dispute accounting and POS terminal/UPI tender verification remain separate work.


## Razorpay dispute and chargeback webhooks

- Apply manual migration `021-create-razorpay-dispute-ledger.sql` after migration 020. Rehearse and back up as described above; do not apply to the client's live database without approval.
- In the Razorpay Dashboard, subscribe the configured webhook URL to `payment.dispute.created`, `payment.dispute.under_review`, `payment.dispute.action_required`, `payment.dispute.won`, `payment.dispute.lost` and `payment.dispute.closed`. Keep the webhook secret backend-only and verify the exact event names supported by the merchant account before enabling.
- Admin → Payment Disputes displays dispute amount, amount deducted, reason, phase, response deadline and local order match. Older/out-of-order webhook events cannot downgrade a newer recorded status.
- The app does not submit evidence, accept/contest a dispute, cancel an order, change payment status or adjust stock. Respond to evidence deadlines and make dispute decisions in the Razorpay Dashboard.
- Newly received webhook payloads are stored after removing customer email, phone, VPA, card/bank metadata, notes and dispute evidence references. This is not retroactive: historical webhook rows may still contain fields received before this sanitization change and need a separately approved retention/redaction plan.
