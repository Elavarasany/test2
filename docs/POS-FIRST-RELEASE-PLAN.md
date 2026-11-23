# NMK Opticals POS — Practical First-Release Plan

**Scope:** a usable, single-shop first release based only on behavior already implemented and documented on `feature/pos-operations-v1`. This is an operational scope, not production approval.

## 1. First-release workflows

| Workflow | First-release behavior | Boundary |
|---|---|---|
| Staff access | Individual ADMIN, MANAGER and CASHIER accounts; disabled accounts rejected; backend role checks apply | Review legacy accounts before use; remove one-time bootstrap credentials after creating the owner account |
| Product and stock | Active products, sellable variants/SKUs, location balances, barcode/SKU lookup, receipt, audited adjustment, stock-count freeze/review/post/cancel, movement history and low-stock thresholds | Physical opening count must be mapped and reconciled per sellable variant before checkout; never add legacy product stock a second time |
| Register | One active shift per register; opening float; append-only cash-in/out; cashier-owned shift operations; close with counted cash and explained variance; manager/admin daily reconciliation | No automatic variance adjustment or silent shift reopening; production date reporting requires the documented Asia/Kolkata runtime/database convention |
| Checkout | Server-calculated prices and stock; cash, UPI and card tenders; split tender; cash change; discounts restricted to MANAGER/ADMIN; idempotent retry; atomic sale, payment, stock and cash-ledger writes | No customer credit/pay-later; a sale must be fully paid; UPI/card references are operator-entered and must be independently confirmed in the merchant app before sale completion |
| Sales history | Paginated sale list and immutable sale details | Cashiers cannot browse global sales history |
| Returns and voids | Manager/admin return; original-price/discount-based refund calculation; quantity/refund caps; RESTOCK/DAMAGED/DISCARD dispositions; cash refunds through drawer; full void only when no prior return; fresh one-time password authorization | Non-cash refund references are records only. Complete and verify the provider refund separately before recording it |
| Exchanges | Atomic linked return plus replacement sale; difference collected/refunded; idempotency and stock rollback | Use the exchange workflow, not an unrelated full refund plus new sale |
| Procurement | Purchase orders, partial goods receipts, supplier returns and stock movement audit | Supplier returns do not automatically create supplier credits or reduce AP |
| Operational reporting | Sales, return/refund, tender, cash reconciliation, inventory-on-hand, reorder and procurement receipt/return reports | Tender totals are operator-recorded totals, not cleared-funds proof; procurement cost is not inventory valuation or profit |

## 2. Financial behavior deliberately excluded

These features must remain unavailable or clearly labelled operational-only until their external rules and integrations are approved:

- **Bank reconciliation:** no bank-statement ingestion, UTR-to-credit matching, or bank-cleared status.
- **Walk-in UPI/card verification:** no provider identity/payment-ID mapping or automatic validation. Until then, staff independently confirm payment in the merchant/provider app and retain the actual reference.
- **Walk-in non-cash refunds:** no automated provider refund execution or confirmation. A saved reference is not proof of a refund.
- **Razorpay settlement:** existing online-order settlement sync and exception queue cover supported online order/refund/dispute records only. They are not bank reconciliation and do not verify walk-in POS tenders. Deployment requires the documented manual schema migrations and merchant API access.
- **Inventory valuation and COGS:** receipt unit costs and receipt-level landed-cost allocations are operational cost records only. Do not calculate weighted-average valuation, COGS or profit from them.
- **Supplier accounting extensions:** no supplier advances, debit notes, paid-bill/unallocated credits, multi-bill allocations, payment reversals or supplier statement reconciliation.
- **Customer AR:** no store credit, instalments, layaway or pay-later. Checkout must be fully paid.
- **GST:** remain disabled. Do not calculate/collect tax, infer rates, display GSTIN/HSN/SAC, or issue a GST tax invoice. Do not retrofit tax onto historical sales.

## 3. Minimum outside decisions/access before production

Only the following items require an owner, accountant, provider or deployment operator. Do not invent substitute rules.

1. **Deployment owner — client database and hosting access:** identify the actual database/schema baseline and provide secure staging access. The application uses manual SQL migrations, Hibernate validation and disabled Flyway; migrations must be selected against the real schema and rehearsed on a restorable clone. Also configure the production profile, secrets, HTTPS origins, database TLS and timezone. Never send secrets in source control or chat.
2. **Deployment owner + shop owner — opening stock and staff:** approve the physical count, SKU/variant mapping, opening quantities, location/register setup and individual staff roles. Checkout must stay off until the opening balances reconcile to the signed count. Remove temporary bootstrap credentials after verifying the owner account.
3. **Shop owner + accountant — ordinary receipt:** confirm the legal business details, non-GST receipt wording and document-number policy before production receipt issuance. The first release must not call the document a GST tax invoice.
4. **Payment provider / finance operator — non-cash tender procedure:** confirm which merchant app/terminal is authoritative and who checks a successful UPI/card payment. Until provider integration is separately approved, use manual confirmation and retain provider evidence; do not treat typed references as verified.
5. **Accountant + owner — any future accounting/tax expansion:** written rules are required before supplier accounting extensions, inventory valuation/COGS, bank reconciliation, provider automation or GST are implemented. Those features are explicitly outside this first release.

## 4. Release sequence

1. Review the actual client schema and choose the applicable manual migration path. Record each migration checksum and execution evidence. Back up and restore to a disposable database before staging changes.
2. Deploy staging with the `prod` profile and staging-only secrets; verify startup, HTTPS CORS, database TLS, rate-limit storage and webhook configuration. Do not change live credentials as part of code work.
3. Configure the owner account and individual staff roles; remove bootstrap credentials; verify direct API role restrictions.
4. Create/verify locations and registers. Map active sellable variants and load the signed opening count through the audited inventory receipt workflow. Reconcile exported balances and movement history to the count.
5. Execute the browser acceptance checklist on staging: cashier login, barcode/product selection, cash and manually confirmed UPI/card checkout, split tender, retries, receipt print/reprint, returns, voids, exchanges, stock counts, procurement and reports. Record evidence for every test.
6. Complete the owner/accountant receipt approval and shop operational sign-off. Keep all provider, bank, valuation/COGS and GST features excluded unless separately approved and implemented.
7. Only after staging acceptance, verified backup/restore, exact-commit CI and explicit owner cutover approval may a separate production release be planned. This document does not authorize deployment or live migrations.

## 5. Verification references

- API and examples: [POS_API.md](../nmk-backend/POS_API.md)
- Deployment and migration gates: [POS_DEPLOYMENT_RUNBOOK.md](../nmk-backend/POS_DEPLOYMENT_RUNBOOK.md)
- Cash controls/timezone: [CASH-REGISTER-RECONCILIATION.md](pos/CASH-REGISTER-RECONCILIATION.md)
- Supplier AP/landed-cost boundaries: [SUPPLIER-AP-AR-LANDED-COST-DECISIONS.md](pos/SUPPLIER-AP-AR-LANDED-COST-DECISIONS.md)
- GST guardrails: [GST-INVOICE-COMPLIANCE-DECISIONS.md](pos/GST-INVOICE-COMPLIANCE-DECISIONS.md)
- Automated coverage and manual browser evidence: [POS-AUTOMATED-COVERAGE-MATRIX.md](POS-AUTOMATED-COVERAGE-MATRIX.md) and the linked frontend release checklist.

A passing CI run proves only automated backend behavior. It does not prove browser usability, provider settlement, accounting correctness, migration safety, physical stock accuracy or production readiness.
