# NMK Opticals POS — Supplier AP, Customer AR and Landed-Cost Rules

**Status:** Initial supplier AP and receipt-level landed-cost allocation code implemented; accountant/owner review required before production posting.  
**Scope:** Supplier bills/payables, supplier payments, customer receivables and inventory acquisition cost.

## 1. Implemented supplier accounting scope

The POS now has an additive supplier-accounting ledger:
- Supplier bills with invoice number/date, optional due date, bill type, supplier, optional purchase order, amount, status, creator and idempotency fingerprint.
- Confirmed partial supplier payments and supplier credit notes linked to one bill. Payments and credit notes both reduce the outstanding balance and cannot exceed the remaining amount. Supplier advances and unallocated credit balances are not supported.
- Bill balances are calculated as posted bill amount less confirmed payments and confirmed credit notes; the records are source-linked and idempotent.
- A read-only supplier payables-aging summary is available at `GET /api/admin/procurement/suppliers/{supplierId}/payables-aging`; optional `asOfDate` uses the supplied business date, or defaults to the current Asia/Kolkata date. It groups outstanding bills into not-due, 1–30, 31–60, 61–90 and over-90-day buckets.
- Supplier bill posting, payment recording and credit-note recording require fresh administrator-password authorization. Managers cannot authorize these financial actions.
- Cash supplier payments require an open POS shift, verify that the shift belongs to the bill's purchase-order location, ensure the drawer has sufficient expected cash, and create one linked OUT movement in the cash ledger.
- Bank/UPI/other payments require a reference. The administrator is responsible for verifying the external payment evidence before recording the payment as confirmed. Credit notes may optionally link to a supplier return and must match the bill's supplier and purchase order.
- Bill, payment, credit-note history and supplier payables-aging endpoints are administrator-only.

Manual schema migration: 022-create-supplier-ap-and-landed-cost-ledger.sql. It is additive and has not been applied to any client database.

## 2. Implemented landed-cost allocation

A supplier bill with type LANDED_COST must link to a purchase order with accepted goods receipts. The service allocates the bill amount across received receipt lines in proportion to each line's goods value (accepted quantity × purchase-order unit cost). Allocation rounding is deterministic: non-final lines are rounded down to paise and the remaining amount is assigned to the final receipt line in stable receipt-item ID order.

The allocation ledger records:
- Source supplier bill.
- Goods receipt item.
- Base goods value.
- Allocated landed-cost amount.
- Derived receipt-line landed unit cost exposed by the allocation API.

This is receipt-level landed-cost evidence, **not yet a complete inventory valuation or COGS engine**. It does not update a weighted-average inventory-cost balance, adjust the cost of stock already sold, or make profit reports authoritative. Existing procurement cost reporting must continue to be described as acquisition-cost reporting, not profit.

## 3. Customer accounts receivable

The approved POS scope says POS sales are fully paid and split tender is allowed; online orders are paid online. Therefore the initial release has **no customer credit / accounts receivable**.

- Do not let a cashier complete a sale with an unpaid balance.
- Do not create a customer receivable merely because the customer profile exists.
- Do not treat an online order awaiting payment as a receivable; it remains a pending order/payment attempt.
- Any future store-credit, layaway, instalment or pay-later feature requires a separately approved credit policy, limits, collections/aging process, refund treatment and accounting review.

## 4. Supplier credits and accounting gaps

The AP implementation supports supplier credit notes applied to an unpaid bill, but does not support debit notes, supplier advances, unallocated credits on already-paid bills, supplier statement reconciliation, payment reversal/chargeback, multi-bill payment allocations, or automated bank reconciliation. Supplier returns reduce stock through the existing supplier-return flow but do **not** automatically reduce a supplier bill balance; the supplier must issue a credit note and an administrator must record it against the linked bill. If the bill has already been fully paid, the resulting supplier credit/refund balance is not yet supported.

Landed-cost allocations are persisted but not yet rolled into weighted-average inventory valuation or COGS. Do not use them as a profit calculation.

## 5. Provisional accounting model and rules

- A supplier bill is recorded only when an actual supplier invoice is available. GOODS and LANDED_COST bills require accepted goods receipts against the linked purchase order; OTHER bills may be unlinked.
- A bill is posted as a payable credit. A confirmed payment reduces its outstanding balance.
- Partial payments are allowed. Payments are allocated to exactly one bill; a single payment cannot silently pay multiple bills.
- Cash payments must reference an open POS shift and create exactly one cash-out movement. Bank/UPI payments require a reference and administrator confirmation.
- Do not edit a posted amount; correct it with a reversal/credit/debit record once those workflows are implemented.
- Only direct, attributable acquisition costs (e.g. inbound freight and handling) should be entered as LANDED_COST. General overhead, selling expenses, finance charges and unrelated operating costs are excluded.
- Quarantined/rejected goods must not receive sellable inventory cost. The current goods receipt model records accepted quantities only.
- Supplier returns reverse physical stock under the existing return workflow; a supplier-issued credit note must be separately recorded against the matching bill. The supplier return ID is optional but, when supplied, must belong to the same supplier and purchase order.
- Do not infer GST input tax credit from a supplier GSTIN or supplier tax invoice. NMK's existing design decision says it is not GST-registered.

## 6. Required accountant / owner confirmations

Before production AP postings or valuation reports are treated as accounting records, obtain written confirmation of:
- Whether bills should be recorded at supplier invoice date, goods-receipt date, or both with a controlled accrual workflow.
- Supplier payment approval limits/roles and whether supplier cash payments may be made from the POS drawer.
- Treatment of supplier advances, debit notes, credit notes, discounts, rebates, freight, insurance, customs/duties and payment fees.
- Whether tax shown on supplier invoices is included in cost, separately recorded, or otherwise treated while NMK is not registered.
- Weighted-average valuation acceptance and the treatment of negative/zero inventory, stock write-offs and returns.
- Whether landed costs are allocated at PO, shipment or goods-receipt level and how late freight invoices adjust already-sold stock/COGS.
- Supplier statement reconciliation frequency and required audit evidence.
- Confirm the payables-aging buckets and whether bills without a due date should be grouped as not due before treating the summary as a formal accounting report.
- Whether any customer credit/AR facility is intended; if yes, define a separate policy before implementation.

## 7. Release gate

Code-side AP balances, bill-linked credit notes, AP aging and receipt-level landed-cost allocations are operational records only until accountant/owner review, unallocated supplier credit handling, inventory valuation/COGS integration, and bank evidence controls are complete. No live database migration or financial opening-balance posting is authorized by this document.
