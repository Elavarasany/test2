# NMK Opticals POS — Staging Trial Package

**Purpose:** an executable, non-production trial for the first-release POS workflows already documented on this branch. This supplements (does not replace) `POS_DEPLOYMENT_RUNBOOK.md` and `docs/POS-FIRST-RELEASE-PLAN.md`.

**Safety:** this is a staging-only procedure. It does not authorize production access, live migrations, live stock changes, real customer transactions, real payment-provider calls, or a production cutover.

## 1. People and access needed to start

| Owner | Must provide / do | Why |
|---|---|---|
| **Deployment owner** | A disposable MySQL 8.4 staging database restored from a client-matching schema backup (or a documented fresh-schema baseline), secure DB access for the migration operator, staging backend/frontend hosting, HTTPS origins, TLS settings, and secret storage | The application uses manual SQL and Hibernate `validate`; the actual schema must be known and restorable before the application can start safely |
| **Deployment owner** | Confirm the exact migration baseline and provide backup/restore evidence plus a place to store the signed migration execution record | MySQL DDL can partially commit; a failed script cannot be assumed rolled back |
| **Shop owner** | Two test staff identities (one manager/admin-authorized operator and one cashier), approved test product/variant attributes, and test-only prices | The trial needs real role separation and catalogue data, but must not invent optical SKU attributes or selling prices |
| **Shop owner** | A test register/location choice and a test opening-float amount | Cash-close arithmetic needs a known starting drawer balance; use test money only |
| **Payment operator (only if testing UPI/card)** | A staging-safe merchant test mode or a staff member who can confirm a deliberately small test payment and provide its actual provider reference | A typed reference is not provider verification; never use an unverified reference as a successful payment |

Do not send credentials, API keys, database passwords or staff passwords in GitHub issues, commits or this document. Use the hosting provider's secret manager and a separate approved channel.

## 2. Stage 0 — create an isolated target

**Deployment owner — required**

1. Identify the client database engine/version, schema name, TLS mode and timezone. Do not assume a local developer database is structurally equivalent.
2. Take a full logical backup of the source schema/data and restore it to a separately named disposable staging database. Verify the restore by connecting to it and checking representative tables/row counts.
3. Keep production credentials and production network access out of the staging environment. Use a separate database user restricted to the staging schema.
4. Record the branch and exact commit being trialled. Do not point the staging deployment at `main` or an unreviewed moving branch name; pin the tested commit.
5. Keep a migration record outside the application database with: script filename, SHA-256 checksum, target database, operator, approval/ticket, backup identifier, start/end time, result, verification output and recovery action.

**Stop condition:** if no verified restorable clone exists, do not run SQL. If the schema does not match one of the documented baselines, stop and request a schema-specific migration review.

## 3. Stage 1 — inspect and migrate the clone

**Deployment owner / database operator — required. Never run these steps on production.**

The scripts are under `nmk-backend/src/main/resources/db/manual/`. Hibernate schema generation is not the migration mechanism: `ddl-auto=validate`; Flyway is disabled. Run scripts individually, inspect the result after each, and stop immediately on any error or partial change.

### 3A. Choose exactly one baseline

- **Path A — verified legacy schema:** only if inspection confirms the original `products`, `customer_orders`, and `order_items` schema without the variant/inventory migration fields/tables: run `001-legacy-order-and-product-columns.sql`, then `002-create-variant-inventory-tables.sql`, then `003-create-main-store-location.sql`.
- **Path B — known partial staging schema:** `004-staging-schema-reconciliation.sql` is an alternative, not a continuation of 001–003. Use it only when every exact precondition in the deployment runbook is confirmed (existing variant/inventory tables, `AVP-01`, `products.version`, `customer_orders.payment_creation_status`, `order_items.variant_id` and its expected FK, and only the documented 004 columns missing). It is staging-specific; do not use it for a new client schema.
- If neither baseline matches exactly, stop. Do not combine Path A and Path B.

### 3B. Apply POS/application migrations after the chosen baseline

For a clone that is missing the corresponding schema, use the branch's full migration set in dependency order, **one script at a time**. The checkout/return/cash trial itself does not exercise every module, but the complete backend artifact has mapped entities beyond the cashier workflow and Hibernate `validate` checks the deployed schema at startup. Do not omit a migration merely because its screen is not part of this trial unless the deployment owner has confirmed that the exact application build does not require the corresponding mapped table:

`005` sales/payments → `006` staff roles → `007` registers/shifts/cash → `008` customer returns → `009` stock counts → `010` procurement base → `011` reorder audit → `012` void type → `013` stock-count cancellation audit → `014` exchanges → `015` report indexes → `016` admin audit chain → `017` provider-refund ledger → `018` shared API rate-limit storage → `019` idempotent refund requests → `020` Razorpay settlement reconciliation → `021` Razorpay dispute ledger → `022` supplier AP/landed-cost ledger.

This list is **not** permission to run every script blindly. Before each script, inspect whether its tables/columns/indexes already exist and whether its preconditions are met. In particular:

- `018-create-api-rate-limit-buckets.sql` is required before using production-profile defaults with the shared DB rate limiter. Verify the table, primary key, expiry index and CHECK constraint; `IF NOT EXISTS` does not prove an existing definition is equivalent.
- `019-add-idempotent-refund-requests.sql` follows 017 and 018; `020-create-razorpay-settlement-reconciliation.sql` follows 017, 018 and 019; `021-create-razorpay-dispute-ledger.sql` follows 020. Preserve these dependencies if those schemas are required by the exact backend artifact.
- `022-create-supplier-ap-and-landed-cost-ledger.sql` depends on procurement and POS-shift base tables; verify those tables first. The supplier screens are not exercised by this trial, but mapped entities may still require the tables during Hibernate validation.
- `017`, `019`, `020`, and `021` support provider-refund / Razorpay capabilities. Their presence does not enable or verify a provider integration. Do not configure provider credentials or run settlement sync as part of the cash-only trial.
- `015` and `016` are additive reporting/audit schema changes. Inspect their exact preconditions and preserve the branch's expected schema before startup.

### 3C. Separate webhook and rate-limit prerequisites

- The Razorpay webhook worker expects an existing `razorpay_webhook_events` base table. The numbered POS scripts do not create that base table. If the worker is enabled, verify the base table first, then inspect and apply `razorpay-webhook-claim-token.sql` followed by `razorpay-webhook-retry-columns.sql` only for missing columns/indexes.
- Do not configure real Razorpay credentials or a production webhook URL for this basic POS trial. If online-order webhook testing is explicitly included, use provider test credentials and a staging webhook endpoint.

### 3D. Schema verification before app startup

1. Compare the final staging schema against all JPA mappings for the exact application commit. Confirm required tables, columns, foreign keys, unique constraints and indexes.
2. Confirm there are no duplicate register codes, SKU/barcode values or idempotency keys.
3. Start the backend against the staging schema with Hibernate `validate`; confirm it starts without schema changes.
4. Rehearse restore/rollback from the pre-migration backup on a disposable database.
5. Save the migration record and verification output. Never use direct SQL inserts to create stock balances or movements.

## 4. Stage 2 — configure and deploy staging

**Deployment owner — required**

1. Deploy the backend with `SPRING_PROFILES_ACTIVE=prod` to staging so the strict production profile is exercised. Use a staging database user and staging-only secrets.
2. Set `APP_CORS_ALLOWED_ORIGINS` to the exact HTTPS frontend origin(s); do not use wildcard CORS. Verify database TLS and HTTPS frontend/API connectivity.
3. Set JVM default timezone to `Asia/Kolkata` and keep DB/session timestamp conventions consistent. Verify the active runtime timezone before relying on daily cash reconciliation. Do not reinterpret old timestamps.
4. Configure a strong staging-only `JWT_SECRET`. Configure only the credentials needed for the trial. Keep SMS disabled unless separately testing it. Do not turn on payment webhooks/provider sync for the basic cash trial.
5. Set the frontend build-time `VITE_API_BASE_URL` to the staging API origin ending in `/api`, then rebuild/redeploy the frontend.
6. Confirm startup logs show the expected profile and that protected API requests are rate-limited using the shared DB store. A missing rate-limit store must not be bypassed by disabling the production default.
7. Replace placeholder storefront assets with approved staging assets or keep the storefront private. Confirm no placeholder image is exposed publicly.

## 5. Stage 3 — seed test-only staff, register and products

### Staff — shop owner / deployment owner

1. Create individual staging accounts: one designated owner/admin or manager test account and one cashier test account. Do not share logins.
2. Review all accounts that pre-date migration 006: it assigns existing rows the default `ADMIN` role. Downgrade operational accounts to `MANAGER` or `CASHIER`, and disable stale/shared accounts.
3. If bootstrap environment variables are used for first-time owner creation, remove both immediately after verifying the account and rotate the password. Do not leave bootstrap credentials configured on routine restarts.
4. Verify direct API access, not only UI hiding: cashier can operate own shift and checkout, but cannot browse global sales, apply discounts, process returns/voids/exchanges, or use manager/admin inventory actions.

### Register — shop owner / deployment owner

1. Call `GET /api/admin/pos/locations` and select the intended staging location. The migration may provision a main location/register; verify it rather than assuming its ID.
2. Call `GET /api/admin/pos/registers?locationId={locationId}`; confirm the `MAIN` register exists and is active.
3. Use only a staging opening float agreed for the trial. Do not infer the live shop's float from the example below.

### Products and stock — shop owner

Create at least two **test-only** sellable variants through the existing product/variant admin workflow, for example:

- `STG-FRAME-001`: one optical frame variant; owner supplies actual staging-only colour/size/shape attributes and a test selling price.
- `STG-FRAME-002`: a second frame variant with a distinct SKU/barcode and a different test price.

These are fixture identifiers, not real NMK inventory or recommended product attributes. Use clearly marked staging products and never import them into production.

1. Ensure both products and variants are active and each variant has a unique SKU/barcode.
2. Seed test quantities using the approved admin inventory receipt workflow (or the documented opening-count workflow if the test is explicitly validating opening stock). Each receipt requires a unique idempotency key and reason. Do not write balances directly with SQL.
3. Verify `GET /api/admin/pos/catalog?locationId={locationId}` shows the expected SKU, server price, on-hand, reserved and available quantity.
4. For a first checkout/return rehearsal, use a single-unit quantity for the first test SKU and make sure at least one unit remains available.

## 6. Stage 4 — cashier checkout / manager return / cash-close acceptance script

Run the following in staging only. Record tester, date, commit, environment, IDs and screenshots/API responses. Use the backend response values as the source of truth; do not hard-code prices, totals, sale IDs, shift IDs or return values. The JSON snippets are request templates: replace every angle-bracket placeholder with the real value using the correct JSON type (IDs as numbers where the DTO expects numbers; money values as JSON numbers), then validate the final body before sending.

### A. Open shift — cashier

1. Authenticate as the staging cashier.
2. Call `POST /api/admin/pos/shifts/open` with the verified register ID, the owner-approved **staging-only** opening float, and a fresh idempotency key:

```json
{
  "registerId": "<stagingRegisterId>",
  "openingFloat": "<agreedStagingFloat>",
  "idempotencyKey": "stg-shift-open-001"
}
```

3. Save the returned shift ID and confirm `GET /api/admin/pos/shifts/current?registerId={registerId}` reports the same OPEN shift.

### B. Cash checkout — cashier

1. Load `GET /api/admin/pos/catalog?locationId={locationId}`. Choose `STG-FRAME-001` and confirm available quantity is at least one.
2. Submit one unit using the product ID and variant ID returned by the catalog. Use the server-calculated line total as the exact payment amount; cashier discounts must be zero:

```json
{
  "locationId": "<stagingLocationId>",
  "shiftId": "<openShiftId>",
  "customerName": "POS Staging Test",
  "customerPhone": null,
  "discountAmount": 0,
  "idempotencyKey": "stg-sale-001",
  "items": [
    { "productId": "<catalogProductId>", "variantId": "<catalogVariantId>", "quantity": 1 }
  ],
  "payments": [
    {
      "paymentMethod": "CASH",
      "amount": "<serverCalculatedGrandTotal>",
      "tenderedAmount": "<sameAmount>",
      "transactionReference": null
    }
  ]
}
```

3. Verify a completed sale, one payment allocation, one unit deducted, one stock movement and one cash IN movement. Save the sale ID and sale-item ID.
4. Retry the exact same payload with the same cashier and idempotency key. It must return the original result, not create a second sale. Do not reuse that key with a changed payload.
5. Confirm the cashier cannot access global sales history or the manager-only return endpoint.

### C. Full one-unit cash return — manager/admin

1. Authenticate as the staging manager/admin. Confirm the physical test item is actually returned and resaleable before choosing `RESTOCK`.
2. Obtain a fresh one-time action token using `POST /api/admin/auth/authorize-action` with the manager/admin password and action `POS_RETURN`. Use the returned token in the `X-Admin-Action-Authorization` header. The token expires after 120 seconds and is single-use.
3. Use the sale ID and sale-item ID from step B. Use the server-calculated full refund amount from the original sale/return calculation; do not calculate it in the browser:

```json
{
  "shiftId": "<sameOpenShiftId>",
  "reason": "Staging acceptance test return",
  "idempotencyKey": "stg-return-001",
  "items": [
    {
      "saleItemId": "<saleItemId>",
      "quantity": 1,
      "restockDisposition": "RESTOCK",
      "dispositionReason": null
    }
  ],
  "refundPayments": [
    {
      "paymentMethod": "CASH",
      "amount": "<serverCalculatedRefundTotal>",
      "transactionReference": null
    }
  ]
}
```

4. Verify the original sale remains immutable, return history records the return, one unit is restored, and a cash OUT movement is present. Verify the cashier cannot perform this operation.
5. Optional negative test: attempt to return the same sold unit again. It must be rejected because cumulative returned quantity cannot exceed quantity sold.

### D. Cash drawer close — cashier

1. Fetch the shift cash movement ledger and compute the expected cash from the ledger (opening float + cash sales + cash-in − cash refunds − cash-out). Do not count the customer's tendered amount above the allocated sale amount; change is not drawer income.
2. Physically count the staging cash. If it matches expected cash, close with that amount. If deliberately testing a variance, use a clearly documented staging-only discrepancy and supply a reason; never edit ledger rows to force a match.
3. Call `POST /api/admin/pos/shifts/{shiftId}/close`:

```json
{
  "closingCashCount": "<actualStagingCashCount>",
  "reason": "Staging acceptance test close"
}
```

4. Verify expected cash, counted cash and variance are stored; a non-zero variance requires an explanation. Retry with the identical close payload and verify idempotent behavior. A different payload must not overwrite the closed shift.
5. As manager/admin, call `GET /api/admin/pos/reports/cash-reconciliation?locationId={locationId}&date={YYYY-MM-DD}` using the shift's opening business date. Verify the report's expected cash, counted cash, variance and ledger-consistency flag agree with the shift ledger.

### E. Acceptance result

Mark each as PASS / FAIL / NOT RUN and attach evidence:

- [ ] Cashier opens only their own shift; a second active shift for the same register is rejected.
- [ ] Catalog shows correct test SKU, price and available stock.
- [ ] One-unit cash sale completes and stock/cash ledgers update exactly once.
- [ ] Same-key identical checkout retry does not duplicate sale, payment, stock or cash movement.
- [ ] Cashier is denied global sales history and manager-only return access.
- [ ] Manager/admin return requires a fresh one-time authorization token.
- [ ] Full return restores only the physically resaleable item and records cash refund OUT.
- [ ] Duplicate/over-quantity return is rejected.
- [ ] Shift close persists expected/count/variance and cannot be overwritten by a changed retry.
- [ ] Daily cash reconciliation agrees with the shift ledger and the shop's Asia/Kolkata business date.

**Do not mark a browser workflow PASS based only on API calls.** Repeat the same journey in the actual staging frontend, including login, product selection, receipt/print behavior, refresh/recovery and role-specific screens. The separate frontend release checklist contains the broader browser suite.

## 7. Explicitly not part of this trial

- Real UPI/card collection or refund unless a provider-approved staging test method is available. Typed references are not verification.
- Bank statement reconciliation or proof of bank-cleared settlement.
- Razorpay settlement/dispute automation for walk-in POS tenders.
- Inventory valuation, COGS, margin or profit calculations.
- Supplier advances, multi-bill allocations, debit notes, payment reversals or supplier statement reconciliation.
- GST calculation, GST tax invoices, HSN/SAC or tax-rate assumptions.
- Production data, production stock, production customer sales, production credentials or live migrations.

## 8. Trial exit and production boundary

The deployment owner supplies the migration record, backup/restore evidence, startup/profile evidence and staging URL/build. The shop owner signs off the test SKU/price fixture, staff role matrix, opening float, physical-stock reconciliation and browser acceptance evidence. Record defects with the exact commit and reproduction steps.

A successful staging trial is not production authorization. Production still requires a separate client-specific migration review, verified backup/restore, receipt/accountant decisions, operational sign-off, cutover window and rollback approval. Keep PR #1 open and draft until the owner explicitly authorizes a separate release decision.
