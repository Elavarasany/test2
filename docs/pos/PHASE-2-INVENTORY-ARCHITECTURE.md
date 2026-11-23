# NMK Opticals POS — Phase 2 Inventory Architecture

**Status:** Architecture specification for review; not an executable migration.  
**Repository branch:** `master`  
**Depends on:** `PHASE-1-DATABASE-DESIGN.md` and `PHASE-1-LIVE-DATABASE-INSPECTION-REPORT.md`  
**Scope:** Shared variant-level inventory for online checkout and the physical-store POS.  
**Safety:** No Java code, SQL migration, live database record, schema, stock balance or deployment setting is changed by this document.

---

## 1. Purpose and governing decisions

Phase 1 established the target inventory rules. This document turns those rules into one coherent operational architecture for the first POS release.

The following decisions are authoritative:

1. The website and physical shop use one shared inventory source of truth.
2. Stock identity is a **product variant at an inventory location**, not the parent product.
3. The first release operates one active shop location in the UI, while the schema remains location-scoped for future expansion.
4. Online checkout reserves available stock. Reservation does not reduce physical on-hand quantity.
5. Verified online payment converts the reservation into a sale: on-hand and reserved quantities both decrease by the reserved amount.
6. Safe cancellation/failure/expiry releases only the reservation. It does not increase on-hand quantity.
7. A completed, fully paid POS sale deducts only unreserved stock.
8. An uncertain Razorpay state must fail closed: keep the reservation and send it for reconciliation rather than releasing stock.
9. Every inventory transition and its ledger event commit in one database transaction.
10. Corrections are new, authorized stock movements. Historical movements are never edited or deleted.
11. No offline POS sale or offline inventory mutation is included in the initial release.
12. Existing online orders, Razorpay identifiers, order items and prescription records remain in their existing online transaction model.

This document does not override the detailed schema reconciliations in Sections 10.25–10.28 of Phase 1. If a wording conflict is found, the later authoritative Phase 1 reconciliation sections take precedence until explicitly amended.

## 2. Inventory ownership and identities

### 2.1 Product catalogue

The existing `products` table remains the parent catalogue entity. It describes the customer-facing product, such as a frame or contact lens.

A product may have one or more sellable variants. A product without selectable options still receives one default variant so every stock-tracked transaction has an unambiguous stock identity.

### 2.2 Product variant

Each `product_variants` record belongs to exactly one parent product.

Required logical attributes:

- Stable database ID.
- Parent product ID.
- Globally unique NMK SKU.
- Structured variant attributes, such as colour, size, frame style, power or pack configuration where applicable.
- Optional normalized manufacturer barcode.
- Active/inactive state.
- Optional explicit selling-price override; otherwise inherit the parent product price.
- Created/updated timestamps.

SKU is the stable internal identifier. It must not be regenerated when a product name or variant attribute changes. Barcode is optional and is a lookup aid, not the primary identity. A variant cannot be enabled for sale until it has a valid SKU and a deterministic effective price.

Do not assume every category shares the same attribute set. Frame colour/size, contact-lens power/box configuration and lens options require category-aware validation. Avoid creating meaningless variant dimensions for products that do not use them.

### 2.3 Inventory location

The inventory model is location-scoped. The first release exposes the Avalurpet shop as the active location. A location record should still exist so stock and movements are never global, ambiguous balances.

A future second branch must receive its own location and inventory rows; it must not share or silently pool the first shop's on-hand quantity.

### 2.4 Inventory summary

The logical inventory key is:

`(location_id, variant_id)`

There must be exactly one summary row for each stocked variant at each active inventory location.

| Field | Meaning |
|---|---|
| `on_hand_quantity` | Physical sellable units currently held at the location, including units temporarily reserved for online checkout |
| `reserved_quantity` | Subset of on-hand units held for active online checkout/payment attempts |
| `available_quantity` | Derived value: on-hand minus reserved; never separately editable |
| `version` | Optional optimistic-lock version if selected in addition to row locking |
| `updated_at` | Last successful balance update |

Required invariant:

`0 <= reserved_quantity <= on_hand_quantity`

`available_quantity = on_hand_quantity - reserved_quantity`

Do not store a separately editable available quantity. Do not put a product-level stock balance beside variant-level balances as a second source of truth.

### 2.5 Reorder thresholds

Low-stock thresholds are scoped to location and variant, not parent product. The first release may allow an owner-configured default threshold and a per-variant override. A threshold is an alert setting; it is not inventory and must not change a stock balance.

## 3. Authoritative stock movement ledger

The `stock_movements` ledger is append-only and is the audit trail for every change to on-hand or reserved quantity.

Each movement must capture at least:

- Unique movement ID.
- Location ID and variant ID.
- Controlled movement type.
- `quantity_delta` (change to on-hand).
- `reserved_delta` (change to reserved).
- `on_hand_after` and `reserved_after` resulting balances.
- Source/reference type and source/reference ID.
- Unique idempotency key for retryable business events.
- Actor identity or explicit system actor.
- Reason/notes where required.
- Created timestamp.
- Optional immutable SKU and product/variant description snapshots for readable history.

Use one sign convention throughout. Positive deltas increase the relevant balance; negative deltas decrease it.

A movement is not a free-standing note. It must correspond to a real business event and be committed atomically with the inventory summary update and source transaction.

### 3.1 Movement types

The controlled movement catalogue should cover:

- `OPENING_BALANCE`
- `ONLINE_RESERVATION`
- `ONLINE_RESERVATION_RELEASE`
- `ONLINE_SALE` (reservation converted to a sale)
- `POS_SALE`
- `PURCHASE_RECEIPT`
- `CUSTOMER_RETURN_RESTOCK`
- `DAMAGE_WRITE_OFF`
- `STOCK_ADJUSTMENT_IN`
- `STOCK_ADJUSTMENT_OUT`
- `PURCHASE_RETURN_DISPATCH`
- `COUNT_VARIANCE` (approved stock-count correction)

Do not accept arbitrary movement-type strings from the client. A movement correction must reference the original movement or business event where applicable.

## 4. Stock transition rules

Let Q be the positive quantity affected by one business event.

| Event | On-hand delta | Reserved delta | Effect on available |
|---|---:|---:|---|
| Online reservation created | 0 | +Q | -Q |
| Online reservation safely released | 0 | -Q | +Q |
| Verified online payment converts reservation | -Q | -Q | 0 |
| Fully paid POS sale | -Q | 0 | -Q |
| Accepted purchase receipt | +Q | 0 | +Q |
| Approved resaleable customer return | +Q | 0 | +Q |
| Damage/write-off | -Q | 0 | -Q |
| Purchase return physically dispatched | -Q | 0 | -Q |
| Approved positive count correction | +Q | 0 | +Q |
| Approved negative count correction | -Q | 0 | -Q |

A sale/return/purchase request alone does not move stock. The relevant commitment point (verified payment, physical acceptance, inspection approval or dispatch) must be reached first.

### 4.1 Online reservation creation

Within one transaction:

1. Validate the order item and resolve its exact variant.
2. Resolve the active inventory location.
3. Lock the inventory row (or use an equivalent guarded atomic update).
4. Calculate available quantity from the locked row.
5. Reject if available quantity is less than requested quantity.
6. Increase `reserved_quantity` by Q.
7. Insert one `ONLINE_RESERVATION` movement with `quantity_delta = 0` and `reserved_delta = +Q`.
8. Persist the order-to-variant reservation reference and idempotency identity.

The customer cart is informational. The backend must re-check available quantity during reservation.

### 4.2 Verified online payment

Only after the existing Razorpay verification path has verified signature/provider state, order identity, amount and currency:

1. Lock the order and relevant inventory rows.
2. Confirm the order still owns the exact active reservation.
3. Confirm the reservation has not already been consumed or released.
4. Decrease on-hand by Q and reserved by Q.
5. Insert one `ONLINE_SALE` movement with both deltas equal to -Q.
6. Mark the reservation consumed and persist the payment/order transition.
7. Commit all changes together.

A duplicate callback must return the prior confirmation result and must not deduct stock again. If the reservation is missing or inconsistent, do not invent a replacement deduction; move the payment/order to an explicit manual-reconciliation path.

### 4.3 Safe reservation release

Release is allowed only after the payment state is established as not paid and the order is eligible for cancellation/expiry.

Within one transaction:

1. Lock the order and inventory row(s) in deterministic order.
2. Re-check order state, reservation ownership, expiry and payment state.
3. Decrease reserved quantity by Q only.
4. Insert one `ONLINE_RESERVATION_RELEASE` movement with `quantity_delta = 0` and `reserved_delta = -Q`.
5. Mark the reservation released and record the reason.
6. Commit.

If Razorpay is unavailable, the state is unsettled, or payment cannot be established, keep the reservation held and flag it for reconciliation. A timeout alone is not proof of failed payment.

### 4.4 POS sale completion

The initial POS sale must be fully paid before it becomes `COMPLETED`. Split tender is supported, but the sum of confirmed tender allocations must equal the backend-calculated amount due.

Within one transaction:

1. Validate the cashier, active shift/register, sale draft and permissions.
2. Resolve each sale line to a specific active variant and location.
3. Recalculate effective prices, discounts and totals on the backend.
4. Lock all affected inventory rows in stable ascending key order.
5. Verify each requested quantity is no greater than available quantity.
6. Validate confirmed tender allocations total exactly the amount due.
7. Decrease on-hand by the sold quantity; reserved quantity is unchanged.
8. Insert one `POS_SALE` movement per variant/location line or consolidated movement, with an idempotency key.
9. Persist sale items and immutable catalogue/price snapshots.
10. Persist tenders, invoice snapshot/number allocation and initial fulfilment state according to the approved transaction design.
11. Mark the sale completed and commit.

If any step fails, the transaction must roll back all sale, tender, invoice allocation (subject to the approved invoice-number void/gap policy), inventory and ledger changes. Never leave stock deducted without a completed sale or a completed sale without its required stock and tender records.

### 4.5 Fulfilment, returns and purchasing

- Packing, ready-for-collection, collection and handover do not deduct stock again.
- A return request or refund does not restock inventory.
- Only physically received, inspected and approved resaleable quantity increases on-hand.
- Damaged/non-sellable returns are recorded as non-sellable dispositions and do not enter available stock.
- A purchase order does not increase stock.
- Only physically delivered and accepted sellable goods increase on-hand.
- Purchase returns reduce stock when goods are physically dispatched, not when the return is merely requested.

## 5. Concurrency, idempotency and transaction safety

### 5.1 Locking

For operations affecting multiple variants:

- Lock rows in a deterministic order (for example, ascending `location_id`, then `variant_id`) to reduce deadlocks.
- Recalculate available quantity after locks are acquired.
- Keep the lock, summary update, ledger insert and source transaction in one database transaction.
- Retry only recognized transient deadlocks/lock timeouts, with a bounded retry policy and the same idempotency key.
- Never retry a business rejection such as insufficient stock as though it were a transient database failure.

### 5.2 Idempotency

Every retryable event needs a stable idempotency identity scoped to its business operation. At minimum cover:

- Online reservation creation.
- Online payment conversion.
- Online reservation release.
- POS sale submission.
- Purchase receipt acceptance.
- Return restock/disposition.
- Stock adjustment/count approval.
- Purchase return dispatch.

A repeated request with the same key and same payload returns the original result. Reuse of a key with a different payload must be rejected. Database uniqueness is required; a frontend disabled button is not idempotency.

### 5.3 Ledger/balance reconciliation

The system must support a reconciliation query/process that compares:

- Current inventory summary balances.
- Sum of opening and subsequent movement deltas.
- Active online reservation records.
- Completed online sales and POS sales.
- Accepted purchase receipts.
- Approved return restocks and write-offs.

For each location/variant:

`current on-hand = opening on-hand + sum(quantity_delta)`

`current reserved = opening reserved + sum(reserved_delta)`

Any mismatch blocks automatic cutover or requires an authorized reconciliation event. Never repair a mismatch by directly editing the summary without a corresponding ledger event.

### 5.4 Invariant violations

The backend must reject and audit any operation that would produce:

- Negative on-hand.
- Negative reserved quantity.
- Reserved quantity greater than on-hand.
- Sale quantity greater than available quantity.
- Duplicate source-event movement.
- A movement without a valid location/variant.
- A completed sale without sufficient confirmed tenders.
- A stock-changing event without an actor or explicit system identity.

## 6. Existing NMK backend compatibility

The live database inspection found:

- 7 existing products.
- 9 existing customer orders and 9 order items.
- All 9 order items have a non-null, resolvable product reference and positive quantity.
- 6 order items contain non-empty prescription JSON.
- 9 observed orders are PAID/CONFIRMED with `stock_reserved = 0`.
- Legacy product stock totals 27 across 7 products, with 1 product at zero stock and 6 above zero.
- No live physical stock count has been provided; 27 is not a verified on-hand figure.

The current backend's legacy checkout path reduces `products.stockQuantity` at reservation time and restores it on safe release. The target design in this document does not do that: it keeps on-hand unchanged and increments reserved quantity. These are different models and must not run simultaneously against the same sale.

### 6.1 Compatibility rules

1. Keep the legacy product stock path authoritative until the complete variant-aware online and POS path is ready.
2. Do not introduce a POS-only stock balance that competes with the website.
3. Do not copy `products.stockQuantity` directly to new on-hand quantity.
4. Do not backfill all legacy products to one default variant until each product's actual physical options/specifications and order references have been reviewed.
5. Backfill a variant reference for every existing online order item before the new variant stock path is activated.
6. Preserve existing order IDs, payment IDs, tracking tokens, item names/prices, and prescription JSON exactly.
7. Keep the existing Razorpay fail-closed reservation scheduler behaviour throughout the transition.
8. Consolidate product create/update paths so catalogue edits cannot directly overwrite inventory balances.
9. Do not remove the legacy stock column or old code path until the controlled cutover and rollback window are complete.
10. Do not dual-write legacy and new stock balances unless a separately specified, tested reconciliation protocol explicitly authorizes it.

### 6.2 Required catalogue mapping work

Before variant creation/backfill:

- Export a catalogue-only review sheet containing product ID, product name, category, active state, current specifications, effective price and current stock value. Exclude customer/order/prescription data.
- Determine which products have real physical variants and which have one default variant.
- Define category-specific variant attributes and required/optional fields.
- Resolve duplicate/missing SKU and barcode data.
- Assign a stable SKU to every sellable variant.
- Map each historical order item to its exact variant. Where the evidence is ambiguous, stop and ask for manual resolution; do not guess.
- Confirm parent/variant effective pricing and preserve all existing customer-facing prices through the cutover.

## 7. Stock count and opening-balance cutover

The live stock aggregate of 27 is not the opening balance.

Cutover requires a controlled process:

1. Verify a complete database backup and perform a restore test against a non-production environment.
2. Choose a cutover date/time and control stock-changing operations during the count/reconciliation window.
3. Print or prepare a variant/SKU count sheet for the physical shop.
4. Count sellable units by exact variant and location. Separate damaged, quarantined, display-only and non-sellable items.
5. Identify all active online reservations and unresolved payment attempts at the cutoff.
6. Reconcile legacy `products.stockQuantity`, physical counts, active reservations, paid/pending orders and manual adjustments.
7. Derive the opening `on_hand_quantity` and `reserved_quantity` from the verified count and reservation ownership. Do not infer on-hand from legacy available-like stock alone.
8. Create one traceable opening-balance movement for each initialized variant/location balance and reservation opening events for valid active reservations.
9. Validate that available quantity is non-negative and that every online order item maps to one variant.
10. Compare the opening balances with the count sheets and signed reconciliation totals.
11. Run the new flow in staging with copied/sanitized data and test rollback.
12. Switch online checkout and POS together to the new shared inventory source in a controlled deployment.
13. Monitor reservation age, stock mismatches, failed payment reconciliation and duplicate event detection.
14. Retain the legacy field/path until the approved rollback window expires and all reconciliation gates pass.

No opening-balance migration may be run against production until the user has reviewed the count, mapping and rollback report and explicitly approved the production change.

## 8. Monitoring and operational controls

The first release should expose owner/manager-only operational views for:

- Available, reserved and on-hand quantity by variant/location.
- Active reservations with age and order reference.
- Reservations held because provider payment state is uncertain.
- Reservation release failures and retry counts.
- Inventory movements by date, variant, actor, source and reason.
- Summary-versus-ledger discrepancies.
- Negative/invalid balance invariant violations (expected count zero).
- Variants at or below reorder threshold.
- Failed POS/online stock transactions and idempotency conflicts.

Alerts should not include prescription values, customer addresses, payment secrets or private file URLs.

A reservation that remains held because Razorpay cannot be checked must be visible for manual reconciliation. Do not automatically release it to improve a dashboard number.

## 9. Acceptance test matrix

| Scenario | Expected result |
|---|---|
| Reserve exactly available online quantity | Reservation succeeds; on-hand unchanged; reserved increases |
| Reserve one unit above available | Rejected; no movement |
| Two concurrent online reservations compete for final unit | Exactly one succeeds |
| POS sale competes with an online reservation for final unit | Only unreserved stock can be sold; no oversell |
| Verified online payment | On-hand and reserved decrease by same reserved quantity once |
| Duplicate payment callback | No second stock or ledger change |
| Safe failed/cancelled online order | Reserved decreases once; on-hand unchanged |
| Razorpay unavailable/unknown state | Reservation remains held; reconciliation alert |
| Expiry scheduler runs twice | At most one release movement |
| POS sale with split tender exactly equal to total | Sale, tenders, stock and ledger commit together |
| POS underpayment or overpayment | Sale rejected; no stock deduction |
| Duplicate POS submit with same idempotency key | Original sale result returned; no duplicate movement |
| POS transaction fails after inventory lock | Entire transaction rolls back |
| Purchase order created but no receipt | No stock change |
| Goods delivered but quarantined | No sellable on-hand increase |
| Accepted purchase receipt retried | Stock increases once |
| Return requested but not inspected | No stock change |
| Return approved as damaged | No sellable stock increase |
| Return approved as resaleable | Approved quantity increases on-hand once |
| Stock adjustment without permission/reason | Rejected |
| Product renamed after sale | Historical movement/sale snapshots remain unchanged |
| Ledger sum differs from summary | Reconciliation flags mismatch; no silent repair |

## 10. Decisions intentionally not made here

The following must be finalized in implementation-specific design before executable DDL or code:

- Exact deployed MySQL version, SQL mode, collation, timezone and CHECK-constraint behaviour.
- Final physical column types, index names, foreign-key deletion rules and DDL order.
- Final category-specific variant attribute schema and mapping for the existing seven products.
- Final inventory location master data and location activation policy.
- Final SKU sequence implementation and barcode normalization rules.
- Exact reservation-to-order-item allocation table/relationship and idempotency-key format.
- Flyway or Liquibase selection and migration baseline procedure.
- Exact cutover stock-count date, physical quantities and active-reservation snapshot.
- Backup retention, recovery point objective, recovery time objective and verified restore evidence.
- Final operational alert thresholds and staff permissions.

These are not permission to guess. Resolve them in the canonical schema/DDL review and obtain explicit approval before migration.

## 11. Completion gate

This architecture is ready for detailed schema mapping only when:

- The variant and location identity is consistent across inventory, movements, online order items, POS sale items, purchases and returns.
- The movement sign convention is implemented consistently.
- Reservation/payment/release transitions are idempotent and transactional.
- The existing Razorpay fail-closed path is preserved.
- The seven-product catalogue has a reviewed variant/SKU mapping.
- A physical stock count and opening-balance reconciliation plan exist.
- The migration, backup/restore and rollback plan is reviewed.
- The acceptance tests above are mapped to automated integration tests.

**Current status:** Architecture documented; not approved for executable SQL, application implementation, stock initialization or production cutover.
