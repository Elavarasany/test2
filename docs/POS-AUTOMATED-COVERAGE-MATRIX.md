# POS Automated Coverage Matrix

This document separates automated repository evidence from the 41 real-browser acceptance checks in [POS-RELEASE-ACCEPTANCE-CHECKLIST.md](https://github.com/Elavarasany/nmk-frontend/blob/feature/pos-operations-v1/docs/POS-RELEASE-ACCEPTANCE-CHECKLIST.md).

A passing test proves only the behavior it exercises. It does not complete a manual browser check, validate a payment-provider settlement, or authorize production use.

## Current CI evidence

- Backend Java 27 test/package workflow: [run 44990294122](https://github.com/Elavarasany/nmk-backend/actions/runs/44990294122) — passed.
- Backend MySQL 8.4 integration/concurrency workflow: [run 44990290330](https://github.com/Elavarasany/nmk-backend/actions/runs/44990290330) — passed.
- Both runs tested backend commit `92268c425b94456b4ba26032ae295758ee7bf2ba`.
- Frontend build/lint workflows on the POS branch: [build run 44988189592](https://github.com/Elavarasany/nmk-frontend/actions/runs/44988189592) and [lint/build run 44988186399](https://github.com/Elavarasany/nmk-frontend/actions/runs/44988186399) — passed for frontend commit `8c4d37844efbd98921768874094a85617b08b446`.

## Automated backend coverage verified in source

| Area | Test evidence | What it exercises |
|---|---|---|
| Checkout | `PosCheckoutTransactionIntegrationTest` | Insufficient-stock rollback; online-reservation/POS last-unit race; concurrent checkout contention; idempotent retry and changed-payload rejection |
| Returns | `PosReturnServiceTest`, `PosCheckoutTransactionIntegrationTest` | Discount-aware refund calculation; quantity cap; refund-reference validation; duplicate return contention; cash-refund rollback |
| Exchanges | `PosCheckoutTransactionIntegrationTest` | Equal-value, positive-difference and negative-difference settlement; replacement-stock rollback; report credit/tender separation |
| Shifts and cash drawer | `PosShiftServiceTest` | Opening/closing validation; cash-in/out; insufficient drawer cash-out; variance explanation; close/retry behavior; cashier ownership |
| Stock counts | `StockCountMySqlIntegrationTest` | Exactly-once posting; multi-line rollback; frozen location after failed post |
| Procurement | `ProcurementMySqlIntegrationTest` | Receipt/supplier-return idempotency and rollback on multi-line receipt failure |
| Reports | `PosReportServiceTest`, `PosCheckoutTransactionIntegrationTest` | Sales/tender/discount/return totals; refund processing-date grouping; inventory availability; exchange credit separated from tender/refund |
| Authorization | `PosAuthorizationIntegrationTest` | Cashier/manager/admin endpoint access, sensitive-action authorization, role refresh, disabled-account rejection, and report permissions |

## Still requires browser or operational evidence

The following are not proven by the backend test suite:

- Real login/logout and role-specific screen behavior with actual seeded staff accounts.
- Product search, SKU/barcode scanning, variant selection, displayed available stock and complete cashier interaction.
- Receipt print layout, printer/browser behavior, sale history navigation and reprint after refresh.
- Frontend pending-operation recovery after browser refresh, tab closure, session expiry or network interruption.
- Manager workflows across inventory, stock count and procurement screens, including usability and visible audit history.
- Full end-to-end report screen reconciliation using a known staged transaction set.
- Actual UPI/card provider refund execution, settlement exports and bank-credit reconciliation.
- Physical stock reconciliation, shop-owner operating sign-off and accountant tax/invoice approval.

## Release rule

Keep all 41 manual checklist items unchecked until a tester records date, tester, environment/build, test IDs and evidence. CI links above are supporting evidence only. Do not merge, deploy, or connect the client database based on this matrix.
