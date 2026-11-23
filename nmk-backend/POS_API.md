# NMK Opticals POS API

All endpoints require an authenticated staff JWT unless marked as public. The login endpoint returns the staff role.

## Invoice and GST status — important release limitation

The current POS checkout stores commercial sale totals and payment snapshots. It does **not** calculate GST, store product HSN/SAC classifications, create a tax-component snapshot, or establish a compliant GST tax-invoice sequence. The printed POS receipt must not be represented as a GST tax invoice or used as evidence of tax compliance.

Before enabling GST invoicing, obtain written accountant/business approval for:
- supplier legal name, registered address, GSTIN and registration status;
- HSN classification for each sellable product category/SKU and any service SAC;
- applicable rate and effective date per classification, exemptions, and whether displayed prices are tax-inclusive or tax-exclusive;
- discount allocation, taxable-value rounding and tax rounding policy;
- intra-state CGST/SGST versus inter-state IGST determination, place-of-supply and delivery-address rules;
- customer identity/address/GSTIN capture rules, including when details are mandatory or requested;
- reverse-charge applicability and required invoice wording;
- invoice series format and financial-year reset/uniqueness controls.

The GST tax invoice requirements in CGST Rule 46 include a consecutive serial number unique for the financial year and not exceeding 16 characters, supplier identity/GSTIN, issue date, applicable recipient details, HSN/SAC, description, quantity/UQC, total and taxable values, tax rates/amounts, and place-of-supply details where applicable. The current random-suffix POS invoice number is not a verified implementation of that tax-invoice numbering control. See the [CBIC GST invoice rules](https://cbic-gst.gov.in/gst-invoice-rules.html).

Do not add guessed tax rates or silently reinterpret existing sale totals. A GST implementation requires an approved tax matrix, immutable per-line tax snapshots, a transactional financial-year invoice counter, tax-aware returns/credit notes, and reconciliation tests. Existing historical sales must remain unchanged.


## 1. Load POS locations

`GET /api/admin/pos/locations`

Returns active inventory locations. Use the selected location ID for catalog and checkout.

## 2. Load POS catalog and live stock

`GET /api/admin/pos/catalog?locationId=1`

Returns active products with active variants, SKU/barcode, server selling price, on-hand, reserved and available quantity for that location. Variants without a balance are returned with zero stock.

The checkout endpoint remains authoritative for price and stock. Never trust prices or stock quantities sent by the browser.

## 3. Receive stock

`POST /api/admin/inventory/receive`

Example:

```json
{
  "locationId": 1,
  "variantId": 42,
  "quantity": 10,
  "idempotencyKey": "receipt-2026-0001",
  "reason": "Supplier delivery"
}
```

The authenticated admin identity is recorded in the stock movement audit. Reusing the same key for the same receipt is safe; reusing it for a different operation is rejected. This endpoint records a normal stock receipt; migration 005 does not create opening stock.

## 4. Complete a POS checkout

`POST /api/admin/pos/checkout`

Example cash sale:

```json
{
  "locationId": 1,
  "shiftId": 12,
  "customerName": "Walk-in Customer",
  "customerPhone": null,
  "discountAmount": 50.00,
  "idempotencyKey": "pos-sale-unique-client-generated-key",
  "items": [
    { "productId": 10, "variantId": 42, "quantity": 2 }
  ],
  "payments": [
    {
      "paymentMethod": "CASH",
      "amount": 450.00,
      "tenderedAmount": 500.00,
      "transactionReference": null
    }
  ]
}
```

Supported payment methods are `CASH`, `UPI`, and `CARD`. For split tender, send multiple payment rows, one per method, and ensure their amounts add up exactly to the grand total. Cash rows require `tenderedAmount`; non-cash rows must omit it.

Every new checkout must reference the authenticated cashier's OPEN shift at the same location. The client must generate a fresh idempotency key for each new checkout and reuse that same key when retrying the same checkout after a network timeout. Do not reuse a key for a different sale.

The server calculates prices from product and variant records, validates discounts and payment totals, locks inventory in a stable order, writes sale/item/payment snapshots, deducts stock and appends stock movements in one transaction. Failed stock deduction rolls back the sale and payments.

The checkout service rejects any non-zero sale discount submitted by a CASHIER. ADMIN and MANAGER can apply a discount up to the original subtotal; the backend remains authoritative even if a browser client is modified.

## 5. POS sales history

- `GET /api/admin/pos/sales?page=0&size=20`
- Optional location filter: `GET /api/admin/pos/sales?page=0&size=20&locationId=1`
- Sale details: `GET /api/admin/pos/sales/{saleId}`

Page size is limited to 100.

## 6. Audited stock adjustment

`POST /api/admin/inventory/adjust`

Use only for verified physical count corrections, damage/write-offs, loss or documented discrepancies. Supplier deliveries must use the receipt endpoint.

```json
{
  "locationId": 1,
  "variantId": 42,
  "quantityDelta": 5,
  "idempotencyKey": "stock-count-adjustment-2026-0001",
  "reason": "Physical count correction; five additional units verified"
}
```

Positive `quantityDelta` adds stock; negative removes it. Zero is rejected. Adjustments cannot reduce on-hand below reserved stock. The balance update and append-only stock movement are transactional. A retry must reuse the exact payload and key.

## 8. Registers, cashier shifts and cash drawer reconciliation

The migration provisions one `MAIN` register for each active inventory location. Additional registers can be created by an ADMIN.

- `GET /api/admin/pos/registers?locationId=1` — list active registers.
- `POST /api/admin/pos/registers` — ADMIN only; body: `{"locationId":1,"registerCode":"MAIN-2","name":"Second Register"}`.
- `GET /api/admin/pos/shifts/current?registerId=1` — current open shift visible to its cashier, ADMIN or MANAGER.
- `POST /api/admin/pos/shifts/open` — open a shift.

```json
{
  "registerId": 1,
  "openingFloat": 1500.00,
  "idempotencyKey": "shift-open-device-generated-key"
}
```

- `POST /api/admin/pos/shifts/{shiftId}/cash-movements` — record `CASH_IN` or `CASH_OUT`, with positive amount, unique idempotency key and mandatory reason.
- `GET /api/admin/pos/shifts/{shiftId}/cash-movements` — view the append-only drawer ledger (cashier's own shift; ADMIN/MANAGER can review any shift).
- `POST /api/admin/pos/shifts/{shiftId}/close` — body: `{"closingCashCount":1475.00,"reason":"Short by ₹25 after recount"}`.

Expected drawer cash is calculated from opening float + cash sale tender allocations + cash-in − cash-out. Cash sales add the allocated payment amount, not the higher tendered amount; change returned to the customer is therefore not counted as drawer income. Cash-out cannot exceed the current expected drawer balance. Cash-movement idempotency is scoped to the original authenticated staff username and exact request, so another manager cannot replay a colleague's cash movement key. A closing count different from expected cash requires an explanation and is stored with the signed variance.

Only one OPEN shift can exist per register. The register row is locked during shift opening, and the shift row is locked during checkout/cash movement/close so a checkout cannot race with shift closure. Cashiers can operate their own shift; ADMIN/MANAGER can review and close other shifts. A cashier cannot close another cashier's shift.

The checkout transaction writes its cash tender movements in the same transaction as sale/payment/inventory changes. A failed sale therefore cannot leave a cash drawer entry behind. Every new POS sale is linked to the shift that accepted it.

## 9. Staff accounts and roles

All staff endpoints below require an ADMIN role. Passwords are BCrypt-hashed and are never returned by the API.

- `GET /api/admin/staff` — list staff identities without password hashes.
- `POST /api/admin/staff` — create a staff account.

```json
{
  "username": "cashier.one",
  "password": "use-a-unique-12-plus-character-secret",
  "role": "CASHIER"
}
```

- `PATCH /api/admin/staff/{id}/role` — body: `{"role":"MANAGER"}`.
- `PATCH /api/admin/staff/{id}/status` — body: `{"enabled":false}`.

Roles are `ADMIN`, `MANAGER`, and `CASHIER`. Usernames are normalized to lowercase. The last active ADMIN cannot be demoted or disabled, and a user cannot disable their own account. Disabled accounts are rejected at login and previously issued JWTs stop working on their next request.

### Current authorization boundary

| Capability | ADMIN | MANAGER | CASHIER |
|---|---:|---:|---:|
| POS catalogue and location selection | Yes | Yes | Yes |
| Complete POS checkout on own open shift | Yes | Yes | Yes |
| Apply sale discount | Yes | Yes | No |
| Open own shift and close own shift; cash in/out on own open shift | Yes | Yes | Yes |
| Review or close another staff member's shift and cash ledger | Yes | Yes | No |
| Browse all POS sales history | Yes | Yes | No |
| Inventory receipt and adjustment APIs | Yes | Yes | No |
| Create POS registers | Yes | No | No |
| Staff account administration | Yes | No | No |
| Other existing admin APIs | Yes | No | No |

This is the first authorization layer. Shift ownership is enforced in the service as well as the HTTP role matcher: CASHIER can operate only their own shift; MANAGER/ADMIN can review and close another staff member's shift. Per-register assignment, supervisor approval thresholds, role-permission tables and refund approvals remain future work.

## 10. Customer returns and refunds

Only ADMIN and MANAGER can process a return. The original sale and sale lines are never edited or deleted.

- `GET /api/admin/pos/sales/{saleId}/returns` — return history for the original sale.
- `POST /api/admin/pos/sales/{saleId}/returns` — create an audited return/refund.
- `POST /api/admin/pos/sales/{saleId}/void` — manager/admin-only full void of a completed sale that has no prior return.

Example:

```json
{
  "shiftId": 12,
  "reason": "Customer selected the wrong frame size",
  "idempotencyKey": "return-unique-device-key",
  "items": [
    {
      "saleItemId": 55,
      "quantity": 1,
      "restockDisposition": "RESTOCK",
      "dispositionReason": null
    }
  ],
  "refundPayments": [
    {
      "paymentMethod": "CASH",
      "amount": 950.00,
      "transactionReference": null
    }
  ]
}
```

The server locks the original sale while validating cumulative returned quantities. Return and void transactions use READ_COMMITTED isolation so that, after waiting for the sale lock, MySQL reads the latest committed return quantities rather than reusing a REPEATABLE_READ snapshot created before the lock. A sale line cannot be returned beyond the quantity originally sold. Refund values are calculated from the original line-price snapshot and a proportional allocation of the original sale discount; the client cannot choose the refund amount. A full return of the remaining line quantity receives any rounding remainder so partial returns cannot over-refund the line. Cumulative refund value cannot exceed the original sale total.

Restock dispositions:

- `RESTOCK`: only for physically received, inspected, resaleable goods; creates an append-only `CUSTOMER_RETURN_RESTOCK` stock movement.
- `DAMAGED`: no stock increase; a disposition reason is mandatory.
- `DISCARD`: no stock increase; a disposition reason is mandatory.

Refund tender allocations must add up exactly to the server-calculated refund total. CASH refunds create an OUT cash movement on the current open shift and are rejected if the drawer does not contain enough expected cash. UPI/card refund records require a transaction reference.

**Provider limitation:** this POS endpoint records UPI/card refund references; it does not call a UPI/card provider or Razorpay to move funds. The refund must be completed and verified through the relevant provider before recording the reference. Do not treat a saved reference as proof of provider settlement.

The return idempotency key is scoped to the original sale, normalized authenticated staff username and request fingerprint. Replaying the same request as the same staff account returns the prior return; another staff account cannot replay that key, and changing the sale or request while reusing the key is rejected. Returns, refund payments and resaleable stock movements commit in one database transaction. A MySQL 8.4 concurrency integration test verifies that two simultaneous attempts to return the same final unit result in one successful return and one rejection.


### Full sale void

A full void is distinct from a partial/customer return. It restores every original sale line as resaleable stock, creates one `VOID` audit record, and requires refund allocations to equal the original sale total. It is rejected if any return has already been processed for that sale. The original sale remains immutable and is not deleted or rewritten.

Example:

```json
{
  "shiftId": 12,
  "reason": "Duplicate sale entered by cashier before customer left",
  "idempotencyKey": "void-unique-device-key",
  "refundPayments": [
    { "paymentMethod": "CASH", "amount": 950.00, "transactionReference": null }
  ]
}
```

Cash refunds are recorded as an OUT movement on the open shift and cannot exceed expected drawer cash. Staff must execute and confirm any UPI/card refund through the payment provider's dashboard or terminal before entering its reference here; this API does not initiate or independently verify the provider refund. A full void uses the same transaction, locking and idempotency protections as customer returns. Only ADMIN and MANAGER may call it; CASHIER requests are forbidden.

## 11. Frozen physical stock counts

All stock count operations require ADMIN or MANAGER authority.

- `GET /api/admin/inventory/stock-counts/current?locationId=1` — retrieve the active count, if any.
- `POST /api/admin/inventory/stock-counts/start` — create a snapshot and freeze stock-changing operations at that location.

```json
{
  "locationId": 1,
  "reason": "Monthly physical stock count"
}
```

- `PATCH /api/admin/inventory/stock-counts/{sessionId}/lines/{lineId}` — body: `{"countedQuantity":7}`.
- `POST /api/admin/inventory/stock-counts/{sessionId}/submit` — submit after every line has a count.
- `POST /api/admin/inventory/stock-counts/{sessionId}/post` — post the verified differences as `COUNT_VARIANCE` stock movements.
- `POST /api/admin/inventory/stock-counts/{sessionId}/cancel` — cancel without posting and unfreeze the location.

The session snapshots every product variant at the selected location, including variants with no existing balance (expected on-hand zero). The location row is locked while the snapshot is created. While the session is COUNTING or READY_FOR_REVIEW, inventory receipts, adjustments, POS sales, customer return restocks and online stock reservations are blocked. Completed idempotent movements remain safely replayable.

The location remains frozen until the session is posted or cancelled. Posting holds the location lock while applying all variance movements and releasing the freeze in the same transaction. A failed variance adjustment rolls back the post and leaves the location frozen. Negative variances cannot reduce on-hand below reserved stock. Positive variances can create a missing balance for a physically discovered variant.

The stock count line stores the snapshot quantity, counted quantity, variance, user and timestamp. The stock ledger records each non-zero variance with a `STOCK_COUNT` reference. A person who started, entered or submitted the count cannot approve/post it; a different manager or administrator must review it. Cancellation is audited with the cancelling staff identity and timestamp. Repeated submit/post/cancel requests are safe to retry.

## 12. Suppliers, purchase orders and goods movements

All procurement endpoints require ADMIN or MANAGER authority.

- `GET /api/admin/procurement/suppliers`
- `POST /api/admin/procurement/suppliers` — create a supplier with name and optional contact details.
- `PATCH /api/admin/procurement/suppliers/{supplierId}/active` — body: `{"active":false}`.
- `GET /api/admin/procurement/purchase-orders`
- `POST /api/admin/procurement/purchase-orders` — create a DRAFT purchase order.
- `GET /api/admin/procurement/purchase-orders/{purchaseOrderId}`
- `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/submit` — DRAFT → ORDERED.
- `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/receive` — record a full or partial goods receipt.
- `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/supplier-returns` — return previously received goods to the supplier.

Create purchase order example:

```json
{
  "supplierId": 2,
  "locationId": 1,
  "notes": "Frames replenishment",
  "idempotencyKey": "po-create-device-unique-key",
  "items": [
    { "variantId": 15, "orderedQuantity": 20, "unitCost": 425.00 }
  ]
}
```

Goods receipt example:

```json
{
  "reason": "Supplier delivery checked against packing slip",
  "idempotencyKey": "gr-device-unique-key",
  "items": [
    { "purchaseOrderItemId": 44, "quantity": 12 }
  ]
}
```

A purchase order begins as DRAFT and must be submitted before goods can be received. Receipts can be partial. The backend locks the purchase order and its item rows, rejects over-receipt, records an immutable goods receipt and adds inventory in the same transaction. The PO status moves to PARTIALLY_RECEIVED or RECEIVED as appropriate. Purchase-order, goods-receipt and supplier-return idempotency fingerprints include the normalized authenticated staff username and request details. The same staff member can safely retry the same request; another staff member cannot replay that key.

Supplier returns can only include quantities already received and not previously returned. The backend locks the purchase order and item rows, creates a supplier-return record, and writes a negative `SUPPLIER_RETURN` stock movement. A return fails if it would reduce on-hand below reserved stock. Goods receipt and supplier return stock movements are blocked during a frozen physical stock count.

Supplier returns reverse physical stock and create an immutable supplier-return audit record. They do not automatically create a supplier credit note or reduce accounts payable. When the supplier issues an actual credit note, record it separately against the corresponding posted bill:

- `POST /api/admin/procurement/supplier-bills/{billId}/credit-notes` — ADMIN only; requires fresh password authorization with action `SUPPLIER_CREDIT_NOTE`.
- `GET /api/admin/procurement/supplier-bills/{billId}/credit-notes` — list the bill's recorded supplier credit notes.

Example credit-note request:

```json
{
  "creditNoteNumber": "SCN-2026-001",
  "issueDate": "2026-11-22",
  "amount": 125.00,
  "reason": "Supplier-issued credit for returned frames",
  "supplierReturnId": 18,
  "idempotencyKey": "supplier-credit-note-device-key"
}
```

The amount must match the actual supplier-issued document and cannot exceed the bill's remaining unpaid balance after confirmed payments and earlier credit notes. The optional `supplierReturnId`, when supplied, must belong to the same supplier and purchase order as the bill. A supplier return by itself never reduces AP. Credit notes against already-paid bills, supplier credit balances/refunds, debit notes and supplier advances are not supported. Credit notes do not automatically adjust inventory valuation or COGS.

Purchase cost is stored for purchasing visibility; GST and landed-cost valuation accounting are intentionally not calculated until the shop's accountant confirms the required rules.

## 13. Stock movement history

Managers and administrators can review the append-only stock ledger for a location:

- `GET /api/admin/inventory/movements?locationId=1&page=0&size=50`
- Optional variant filter: `GET /api/admin/inventory/movements?locationId=1&variantId=42&page=0&size=50`

The endpoint returns a newest-first page of movement snapshots, including quantity and reservation deltas, resulting on-hand/reserved balances, movement type, reference, staff actor ID, reason and timestamp. Page size is limited to 100. The endpoint is read-only; ledger entries are never edited or deleted through POS.

## 14. Reorder thresholds and low-stock alerts

- `PATCH /api/admin/inventory/reorder-level` — configure a threshold for one variant at one location.
- `GET /api/admin/inventory/low-stock?locationId={id}` — list configured variants whose available quantity is at or below their threshold.

Example:

```json
{
  "locationId": 1,
  "variantId": 25,
  "reorderLevel": 5
}
```

A reorder level of zero disables alerts. Alerts compare `availableQuantity` (on-hand minus reserved), not raw on-hand, so stock already committed to online orders is not treated as sellable. Threshold changes record the staff username and timestamp on the inventory balance. Thresholds are location-specific; they do not automatically create purchase orders.

## 15. POS sales and refund reports

Managers and administrators can request a location/date-scoped report:

- `GET /api/admin/pos/reports/sales?locationId=1&from=2026-11-01&to=2026-11-21`

The response includes transaction count, subtotal, discounts, sales after discount, sales reversals, recorded refund-payment allocations, net after reversals, tender totals, refund tender totals, cashier totals, daily totals and the top ten products by gross line total and gross quantity sold. `salesReversals` is the original-sale value reversed by completed returns and voids. `recordedRefundPayments` is the sum of the CASH/UPI/CARD refund allocations recorded against those reversals. `exchangeCreditApplied` is non-cash exchange credit consumed by replacement sales and is deliberately excluded from payment-method tender totals. The legacy `refunds` field remains as a compatibility alias for `salesReversals`; new clients should use the explicit field names. UPI/card amounts are operator-recorded references after external processing, not provider settlement confirmations. Product quantities are not reduced by returns processed in the same date window; use return history separately when reconciling net units. Refunds are grouped by the date the return was processed, not backdated to the original sale date. The report is limited to 367 calendar days per request.

This report does not calculate GST, profit, supplier liabilities or bank settlement. POS sales totals are not a substitute for accountant-approved financial statements.

### Inventory on-hand report

- `GET /api/admin/pos/reports/inventory?locationId=1`

Returns a location-scoped quantity snapshot with variant/product identity, on-hand, reserved, available, reorder level, and low-stock status, plus location totals. It includes variants that currently have an inventory-balance row. It does not calculate inventory value, cost of goods sold, margin, or profit: received stock currently lacks a complete, verified cost-layer/valuation model, so those figures must not be inferred from current product selling prices or the latest purchase cost.

## 16. Full sale voids

Managers and administrators can void a completed sale only when no earlier return has been recorded:

- `POST /api/admin/pos/sales/{saleId}/void`

Example:

```json
{
  "shiftId": 9,
  "reason": "Sale cancelled before customer leaves",
  "idempotencyKey": "void-device-unique-key",
  "refundPayments": [
    { "paymentMethod": "CASH", "amount": 900.00 }
  ]
}
```

A full void records a `VOID` return record, restocks all original sale lines, and records the full refund allocation. It is idempotent and cannot be applied after any partial/full return. The action must be processed against an open shift at the same location. Managers/admins may use another cashier's open shift; cashiers may use only their own. Cash drawer refunds are limited by expected drawer cash. UPI/card refunds require the actual provider/bank refund reference.

This endpoint records a refund; it does not initiate a UPI/card refund with the payment provider. Complete the external refund first and enter its real reference. Only use a full void when the goods are physically returned in resaleable condition before leaving the shop; use the normal return workflow for goods that are damaged or discarded.


## 17. Atomic linked exchanges

The feature branch provides one transaction for a linked return and replacement sale:

- `GET /api/admin/pos/sales/{saleId}/exchange` — returns the linked exchange audit records for the original sale (manager/admin only).
- `POST /api/admin/pos/sales/{saleId}/exchange` — creates the linked return and replacement sale (manager/admin only).

Example request:

```json
{
  "shiftId": 9,
  "reason": "Frame exchange requested by customer",
  "idempotencyKey": "exchange-device-unique-key",
  "returnItems": [
    { "saleItemId": 123, "quantity": 1, "restockDisposition": "RESTOCK" }
  ],
  "replacementItems": [
    { "productId": 45, "variantId": 78, "quantity": 1 }
  ],
  "replacementDiscountAmount": 0.00,
  "collectionPayments": [
    { "paymentMethod": "CASH", "amount": 50.00, "tenderedAmount": 50.00 }
  ],
  "refundPayments": []
}
```

The backend calculates the returned value (R) using the original sale's proportional discount allocation and the same partial-return rounding rules as ordinary returns. It calculates the replacement value (N) from current replacement variant prices and the authorized replacement discount. Settlement is explicit: exchange credit is `min(R,N)`; only `N-R` is collected when the replacement costs more, and only `R-N` is refunded when it costs less. Equal-value exchanges have no cash or provider payment.

The return record reverses the full returned value for net-sales reporting. The replacement sale records its full sale value. Actual payment rows contain only money collected or refunded; non-cash exchange credit is stored separately in `pos_exchange_credit_settlements` and reported as `exchangeCreditApplied`. Cash drawer entries are written only for actual cash collections/refunds. UPI/card collection and refund rows require a non-empty operator-entered transaction reference; this endpoint does not initiate or verify provider transactions.

Return quantities are checked against all earlier returns/exchanges. RESTOCK items alone increase sellable stock; DAMAGED and DISCARD items do not. The original sale, open shift, return, replacement sale, stock movements, credit settlement and cash ledger entries share one database transaction. A failed stock or cash operation rolls the entire exchange back. Retries must use the same idempotency key and identical request by the same staff member.

The automated integration suite covers equal-value credit, higher-value collection, lower-value actual refund, insufficient-stock rollback and idempotent replay. The MySQL 8.4 profile specifically exercises equal-value exchange, replay and transaction rollback against the real database engine. Migration `014-create-pos-exchanges.sql` is additive and manual; CI executes the exact DDL against a separate disposable MySQL 8.4 schema and verifies valid settlement insertion plus rejection of an invalid settlement equation. Do not run it against the client's live database until a client-specific disposable-schema migration, backup/restore verification and explicit release approval are complete.

## Important operational boundaries

- No GST/tax calculation is included pending the shop's tax configuration.
- Completed sales are immutable through the current API. Returns/refunds must be separate audited operations; never edit or delete completed sales.
- **Refund provider boundary:** POS payment rows retain payment method, amount and an operator-entered transaction reference; they do not retain a verified Razorpay payment ID, provider account, or provider refund ID. POS UPI/card refund rows are therefore audit records only. The backend does not submit a provider refund or confirm settlement. The Razorpay webhook worker currently processes `payment.captured` for online customer orders; other signed event types (including refund events) are stored and marked processed without changing POS or customer-order refund state. Do not treat webhook receipt, a typed reference, or a saved refund row as proof that money was returned.
- **Refund reconciliation required before automation:** link each refundable POS tender to its provider and original provider payment ID; model refund requests and provider refund IDs separately from the existing cash/non-cash refund allocations; use idempotent provider requests and verify final provider status; consume signed refund webhooks with amount/currency/payment-ID checks; support partial and multiple refunds without exceeding captured amount; and provide an exception queue for pending, failed, reversed and unmatched settlements. For non-Razorpay UPI/card terminals, use the terminal/acquirer settlement and refund API or a controlled operator-confirmation process with evidence. Cash refunds continue to use the audited cash drawer ledger.
- The atomic exchange endpoint is present on the POS feature branch but remains unreleased until its review, migration, and production release gates are approved. Do not manually simulate an exchange as a separate return and sale.
- Inter-location stock transfers are not implemented. The current shop operates one physical location; do not enable a second stock location until a transfer/dispatch/receipt workflow and its reconciliation controls are added.
- Manual SQL files are under `src/main/resources/db/manual/`. In addition to the numbered 001–014 POS chain, the webhook worker requires the pre-existing `razorpay_webhook_events` table plus `razorpay-webhook-claim-token.sql` and `razorpay-webhook-retry-columns.sql`; verify the base table and apply these two scripts only when their columns/index are absent. The scripts do not create the base webhook table. First verify the existing schema and choose the correct baseline path: `001` is the legacy product/order update for the verified reference schema; `002` creates variants/inventory; `003` inserts the `MAIN_STORE` location; `004` is a staging-only reconciliation draft for the partially migrated `nmk_staging_restore` database and is an alternative to overlapping parts of `001`—never run both blindly and never run `004` in production. After the approved baseline, apply POS additions in order: `005` sales/payments, `006` staff roles, `007` registers/shifts/cash, `008` returns/refunds, `009` stock counts, `010` procurement, `011` reorder-level audit fields, `012` full-void type, and `013` stock-count cancellation audit fields, then `014` linked POS exchange headers and non-cash credit settlements. Supplier bills, payments, supplier credit notes and receipt-level landed-cost allocations are created by manual migration `022-create-supplier-ap-and-landed-cost-ledger.sql`; apply it only after the procurement and POS-shift base tables are verified on a disposable MySQL 8.4 restore. Verify every precondition and record each applied migration on a disposable MySQL 8.4 restore first. Do not execute against the client's live database without a separately approved migration plan and backup.
- Production deployments must activate `SPRING_PROFILES_ACTIVE=prod` and provide `APP_CORS_ALLOWED_ORIGINS` as an explicit HTTPS origin allowlist. The production profile enforces Hibernate `validate`, disables automatic schema changes and hides exception details. Keep manual migrations separate from application startup.
- No database migration is executed by these code changes.


### Razorpay settlement reconciliation and exception visibility

- `POST /api/admin/pos/payments/settlements/sync?year=2026&month=11` synchronizes provider settlement batches for the requested month; optional `day` narrows the period. Requires configured Razorpay API credentials.
- `GET /api/admin/pos/payments/settlements?page=0&size=50` lists stored settlement batches.
- `GET /api/admin/pos/payments/settlements/{settlementId}/entries?page=0&size=100` lists the provider rows for one batch.
- `GET /api/admin/pos/payments/settlements/exceptions?page=0&size=50` lists all non-matched provider rows. Optional `status` filters to `UNMATCHED`, `MISMATCH`, `MANUAL_REVIEW` or `PENDING`.
- These endpoints are administrator-only. The exception queue is read-only and exposes provider settlement identifiers, amount/fee/tax snapshots and local match outcome without returning raw provider payloads.
- This is not bank reconciliation. It does not ingest bank statements, match settlement UTRs to bank credits, resolve exceptions, or link walk-in POS UPI/card tenders to provider payment IDs. Do not treat a provider settlement batch as proof that a bank credit has cleared.

### Procurement audit history

- `GET /api/admin/procurement/purchase-orders/{purchaseOrderId}/receipts` returns the chronological goods receipts for one purchase order, including receipt number, receiver, reason, timestamp, SKU, quantity and captured unit cost.
- `GET /api/admin/procurement/purchase-orders/{purchaseOrderId}/supplier-returns` returns the chronological supplier returns for one purchase order, including return number, processor, reason, timestamp, SKU, quantity and captured unit cost.
- Both endpoints validate the purchase order and return an empty array when no records exist. These are operational receiving/return records; they do not represent supplier credit-note settlement, supplier payments, or accounts-payable balances.


## 18. Financial action reauthorization

POS customer returns, full voids, and linked exchanges require a fresh one-time password authorization in addition to the manager/admin role:

1. Call `POST /api/admin/auth/authorize-action` with `{"password":"<staff-password>","action":"POS_RETURN"}`, using `POS_VOID` or `POS_EXCHANGE` for the other operations.
2. The response contains `authorizationToken` with a 120-second lifetime.
3. Send that token in the `X-Admin-Action-Authorization` header on the corresponding return, void, or exchange POST request.

The token is hashed at rest, bound to the authenticated staff username and action, and consumed once. Managers may authorize inventory receipt/adjustment, stock-count posting/cancellation, purchase-order submission, goods receipt, supplier return, POS return, full void and linked exchange. Administrators may authorize all configured actions, including global order/appointment clearing. Cashiers cannot access these endpoints. A missing, expired, reused, wrong-action, or wrong-staff token is rejected. A retry after an uncertain response must obtain a new authorization token but reuse the original idempotency key and identical operation payload.

Action-to-endpoint mapping:
- `INVENTORY_RECEIPT`: `POST /api/admin/inventory/receive`
- `INVENTORY_ADJUSTMENT`: `POST /api/admin/inventory/adjust`
- `STOCK_COUNT_POST` / `STOCK_COUNT_CANCEL`: `POST /api/admin/inventory/stock-counts/{sessionId}/post` or `/cancel`
- `PURCHASE_ORDER_SUBMIT`: `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/submit`
- `GOODS_RECEIPT`: `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/receive`
- `SUPPLIER_RETURN`: `POST /api/admin/procurement/purchase-orders/{purchaseOrderId}/supplier-returns`
- `SUPPLIER_CREDIT_NOTE`: `POST /api/admin/procurement/supplier-bills/{billId}/credit-notes`
- `POS_RETURN`, `POS_VOID`, `POS_EXCHANGE`: the corresponding POST endpoints documented above


### Procurement cost report

- `GET /api/admin/procurement/cost-report?locationId={id}&from=YYYY-MM-DD&to=YYYY-MM-DD` returns goods-receipt and supplier-return line costs recorded within the inclusive date range for one location.
- Summary fields distinguish received quantity/cost, supplier-return quantity/cost, and net recorded procurement cost. Detail rows retain receipt/return reference, purchase order, supplier, SKU, quantity, captured unit cost, actor and timestamp.
- This report is a historical operational procurement-cost view calculated from the unit cost entered on each purchase-order line and copied to its receipt/return audit rows. It is **not** a verified supplier-invoice cost or landed cost: freight, insurance, handling, supplier discounts/credit notes, recoverable/non-recoverable tax and other allocations are not captured. It is also **not** current inventory valuation, accounts payable, cash paid to suppliers, tax reporting, or COGS/profit. Purchase orders and receipt costs do not establish payment settlement. Opening stock without verified acquisition cost and issue-cost allocation remain outside valuation. Do not use this report to set selling prices or claim margin/profit.
