# NMK Opticals POS — GST and Invoice Compliance Decisions

**Status:** Implementation guardrails and launch decision register  
**Applies to:** POS receipts/invoices and customer-facing sales documents  
**Current branch:** `feature/pos-operations-v1`

## 1. Decisions already recorded in the POS design

The existing project design records that NMK Opticals is **not GST-registered** at the time the design was written.

Until the business confirms a change in registration status:

- Do not request or require an NMK GSTIN.
- Do not charge, calculate, collect, or describe any amount as GST.
- Do not hardcode a GST rate or tax category.
- Keep tax fields in the data model only for future extensibility; the current tax amount remains zero.
- Do not print a GST tax invoice or imply that NMK is GST-registered.
- Preserve the actual selling price and payment total; do not add a tax amount at checkout.
- Do not infer registration from a supplier's GSTIN or a supplier tax invoice.

These are the existing documented business assumptions, not a legal opinion.

## 2. Safe implementation behavior

Until an accountant/adviser confirms the correct non-GST document format:

1. The POS may produce an ordinary sales receipt showing NMK's trading name/contact details, document number, issue date/time, item descriptions, quantities, unit prices, discounts, total paid, payment method and change/balance where relevant.
2. The document must not be titled "GST Tax Invoice".
3. It must not display an invented GSTIN, tax rate, CGST/SGST/IGST split, HSN/SAC code, or GST amount.
4. If the existing sale model has tax fields, persist zero tax and ensure the amount due is not increased by tax.
5. Keep invoice/receipt numbering separate from sale IDs and do not reuse issued document numbers.
6. Corrections to an issued document must use a void/reissue or other accountant-approved process; never silently rewrite historical sale snapshots.
7. Do not add tax collection to online checkout as part of this POS implementation.

## 3. Decisions that must be confirmed before production invoice issuance

The following remain external business/accountant decisions and are not guessed by this implementation:

| Decision | Required confirmation |
|---|---|
| Exact legal name, address and contact details to print | Business owner |
| Whether an ordinary non-GST bill/receipt is the correct document and required wording | Accountant / qualified adviser |
| Required fields for NMK's business type and applicable Indian state/local rules | Accountant / qualified adviser |
| Whether a separate serial-number sequence is required for POS and online sales | Accountant + owner |
| Prefix, numbering scope (shop/year/channel), reset policy and handling of voided/gap numbers | Accountant + owner |
| Retention period and export format for sales documents | Accountant + owner |
| Whether any registration status or tax obligation has changed since the design decision | Business owner + accountant |

Do not issue a document that represents itself as a tax invoice until registration status and invoice wording are verified.

## 4. Change-control rule if GST registration changes

If NMK becomes GST-registered, pause tax activation until the accountant confirms the effective registration date and applicable treatment. Then implement a separately reviewed change covering:

- Verified legal entity name, registered address and GSTIN.
- Effective date and applicable place-of-supply/tax treatment.
- Item classification/HSN and tax rates approved by the accountant.
- CGST/SGST versus IGST rules where applicable.
- Inclusive/exclusive pricing and rounding.
- Credit/debit note, cancellation, refund and invoice amendment handling.
- Historical transactions before the effective date (must not be retroactively reclassified without professional direction).
- Invoice numbering and statutory record retention.
- Online checkout, POS, reports, exports and audit evidence.

Never apply GST retroactively to existing sales merely because configuration changes.

## 5. Release gate

**Code-side default:** no GST collected; tax amount zero; no GSTIN or tax-invoice claim.

**Production document gate:** accountant/adviser confirmation of the non-GST receipt wording and owner confirmation of legal business details and numbering policy.

This document records existing project decisions and outstanding confirmations. It does not provide legal or tax advice and does not authorize tax collection or production deployment.
