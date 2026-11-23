# POS Financial Integrations — Decision and Input Checklist

**Purpose:** Convert the remaining POS finance gaps into implementable work without guessing provider behavior or accounting policy. This is a requirements checklist, not approval to post financial entries or change production data.

## Bank statement reconciliation

**Required from the business/bank**
- One redacted real statement export for each account and supported format (CSV/XLSX/PDF/API); include headers, sample rows, date/time conventions, debit/credit signs, currency, opening/closing balances and bank transaction identifiers.
- Bank account ownership and account-to-location/payment-channel mapping.
- Confirm whether the bank reference/UTR is stable and unique, and how reversals, fees, refunds, chargebacks, split settlements and duplicate statement rows appear.
- Approve matching precedence, date window, amount tolerance, partial/aggregate settlement handling, manual match/unmatch permissions, evidence retention and close sign-off.

**Safe implementation boundary once inputs arrive**
- Parse/import into an immutable, idempotent statement-line staging ledger; retain source file hash, import identity and original line evidence.
- Keep import, suggested match, approved match and accounting posting as separate states.
- Never auto-clear a mismatch or create a journal merely because text/reference values look similar.
- Reconcile bank credits to provider settlements separately from provider settlements to POS tenders.

## Walk-in UPI/card verification

**Required from the merchant/provider**
- Actual provider/acquirer name, merchant account IDs, terminal/QR setup and which channels share each merchant account.
- API documentation and test credentials or a provider export sample; clarify lookup/search permissions, payment status meanings, settlement IDs, currency/amount units, refund APIs and webhook signatures/retry behavior.
- Confirm whether a payment can be queried by UTR/RRN/terminal reference and which immutable provider ID is returned.
- Confirm how offline/timeout/late-capture/duplicate/partial-payment cases are handled at the counter.

**Required operating rule**
- Define whether checkout may complete only after a provider-confirmed success, or whether a separately permissioned pending-tender workflow is allowed. Define who can resolve pending tenders and what happens to stock if confirmation fails.
- Until then, retain the current operator-entered reference as evidence only; do not label it verified or provider-confirmed.

## Supplier advances and paid-bill credits

**Required from owner/accountant**
- Whether an advance is a supplier asset/prepayment, how it is approved, and whether it can be allocated across suppliers, purchase orders or bills.
- Rules for partial application, excess/unallocated balances, supplier refunds, returned funds, ageing and statement presentation.
- For credits against fully paid bills, choose between a supplier receivable/unallocated credit, refund receivable, or another accountant-approved treatment; define application and refund workflow.
- Define whether credit balances can cross bills or purchase orders and the audit evidence needed.

## Supplier debit notes

**Required from owner/accountant**
- Whether NMK issues debit notes, the permitted reasons (e.g. shortages, price differences, returns), and whether the document adjusts AP, records a claim receivable, or is only a dispute memo until supplier acceptance.
- Required numbering, date, supplier reference, tax treatment while NMK is not GST-registered, approval roles, supporting documents and dispute/acceptance states.
- Clarify interaction with supplier-issued credit notes; do not post both sides of the same adjustment.

## Multi-bill supplier payments and reversals

**Required from owner/accountant**
- Allocation rule: explicit bill allocations only, FIFO by due date, FIFO by invoice date, or another approved order.
- Treatment of unallocated excess, payment fees, discounts, withholding, rounding, partial allocation and a single bank transfer covering multiple suppliers (if allowed).
- Reversal policy: who can request/approve, allowed reasons, whether a bank/provider reversal must be confirmed first, effective date, evidence, and how cash drawer or bank balances are corrected.
- Confirm whether a reversal is a new append-only compensating entry; historical payment records must not be edited/deleted.

## Supplier statement reconciliation

**Required from suppliers/business**
- Redacted statement samples from the actual suppliers and supported formats, including opening/closing balances, invoice/debit/credit/payment references and date conventions.
- Matching policy for supplier invoice numbers, supplier reference aliases, dates, amounts, credit notes, payments in transit, disputed items and rounding differences.
- Reconciliation frequency, reviewer/approver roles, dispute statuses, evidence retention and treatment of supplier opening balances.

## Landed-cost valuation and COGS

**Required from owner/accountant**
- Approve the valuation method (including whether weighted-average cost is appropriate) and effective date; define opening cost for existing inventory.
- Define eligible cost components and treatment of supplier discounts, rebates, freight, insurance, customs/duties, non-creditable taxes and payment fees. Do not infer input tax credit.
- Choose allocation basis (value, quantity, weight, volume or explicit line allocation) and whether costs attach at PO, shipment or accepted goods-receipt level.
- Define late invoices after stock is partly/fully sold, returns, exchanges, damaged/discarded stock, write-offs, negative/zero stock, transfers and cost corrections.
- Define valuation snapshots, audit trail, recalculation policy and how COGS/profit reports are restated or protected.

## Release and verification gates

- NMK is documented as not GST-registered. Keep tax collection and GST tax-invoice behavior disabled unless the owner confirms registration and the accountant supplies written tax, document and effective-date rules.
- No provider or bank integration can be marked verified without sandbox/live test evidence for the actual merchant account and documented status semantics.
- No financial posting policy is complete until the owner/accountant signs off the relevant rules above.
- Add automated tests for idempotent imports, duplicate rows, matching ambiguity, reversals, concurrency and audit authorization using redacted fixtures.
- Rehearse schema changes and import/reconciliation flows against a disposable schema; verify backup/restore before any production release.
- Do not run production migrations, use client credentials, or deploy as part of this requirements exercise.
