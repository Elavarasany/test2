# POS Exchange Workflow

Status: implemented on `feature/pos-operations-v1`; not merged to production.

The feature branch now includes the exchange entities, repositories, request/response DTOs, transactional service, manager/admin API, sales-history audit endpoint, POS exchange UI, replacement invoice credit breakdown, and migration 014. The runtime uses the shared `PosReturnPricing` component for original-sale discount allocation and partial-return rounding. It does not call public return or checkout service methods to simulate an exchange.

## API

- `GET /api/admin/pos/sales/{saleId}/exchange` — manager/admin exchange audit history for an original sale.
- `POST /api/admin/pos/sales/{saleId}/exchange` — creates a linked return and replacement sale in one transaction.

The request includes the open shift, reason, idempotency key, returned sale lines and dispositions, replacement product variants, replacement discount, actual collection payments, and actual refund payments. The response links the original sale, return, replacement sale, shift, credit, collection, and refund.

## Business invariants

1. An exchange references one completed original sale and creates one replacement sale.
2. Returned quantities are checked after all earlier completed returns and exchanges.
3. Returned goods use RESTOCK / DAMAGED / DISCARD rules. Only RESTOCK increases sellable on-hand stock.
4. Return, replacement sale, stock movements, exchange credit, and cash drawer entries are in one READ_COMMITTED transaction. A failure rolls the complete operation back.
5. Exchange credit is non-cash value. It never creates a CASH/UPI/CARD tender or refund row by itself.
6. Exchange credit is generated and consumed only inside the authenticated exchange service. Ordinary checkout cannot mint or submit it.
7. Idempotency is staff- and request-fingerprint-scoped. An identical retry returns the original exchange; changed details or another staff identity are rejected.
8. Lock order is inventory location → shift → original sale → product/variant/inventory rows.

## Settlement

Let R be the returned original-sale value after proportional discount allocation and partial-return rounding. Let N be the replacement sale value after its approved discount.

- N > R: credit applied = R; collect exactly N − R.
- N = R: credit applied = R; no collection or refund.
- N < R: credit applied = N; actually refund exactly R − N.

The return record reverses the full returned value for net-sales reporting. The replacement sale records its full value. Payment rows contain only actual money collected/refunded. Non-cash credit is stored in `pos_exchange_credit_settlements` and shown separately as `exchangeCreditApplied`. Cash drawer entries are created only for actual cash collection/refund. UPI/card amounts are operator-entered references after external processing; this POS does not initiate or verify provider transactions.

## Persistence and stock

Migration `014-create-pos-exchanges.sql` adds an immutable exchange header and separate credit-settlement table, unique idempotency/return/replacement links, restrictive foreign keys, amount equations, and audit indexes. The migration is manual and additive.

The exchange service uses the existing inventory service for RESTOCK and replacement SALE movements, with exchange-scoped idempotency keys. If a stock movement or cash drawer operation fails, the surrounding exchange transaction rolls back the return, replacement sale, exchange header, credit, and all preceding stock/cash mutations.

## Automated verification on the feature branch

- Java 27 Maven test-and-package suite passes.
- MySQL 8.4 integration suite passes for equal-value credit, same-request replay, insufficient-stock rollback, and concurrent exchanges against the same remaining sold unit.
- MySQL migration smoke test executes the exact migration 014 SQL in a separate disposable schema, verifies valid settlement insertion, and confirms an invalid settlement equation is rejected.
- H2-backed integration tests cover equal-value, higher-value collection, lower-value actual refund, and rollback.
- API authorization tests verify cashiers are denied and managers reach exchange request validation.
- Frontend build and lint pass for the exchange flow, replacement invoice, and exchange audit history.

These tests do not replace a client-specific migration rehearsal or live operational acceptance test.

## Remaining release gates

- Keep both feature branches and draft PRs separate from production until review and release approval.
- Rehearse the complete ordered migration set against a disposable schema matching the client's actual schema; verify backup and restore before any production migration.
- Do not apply migration 014 or any other POS migration to the client's live database without explicit approval.
- Obtain payment-provider details before implementing provider-side UPI/card refund execution or settlement verification.
- Obtain accountant approval for GST/HSN treatment, statutory invoice rules, cost accounting, and inventory valuation. Current reports are operational and quantity-based; they do not calculate profit, COGS, GST returns, supplier liabilities, or bank settlement.
- Reconcile product variants, barcodes, opening quantities, and physical stock before go-live.
