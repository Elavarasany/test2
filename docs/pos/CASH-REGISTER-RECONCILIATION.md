# NMK Opticals POS — Cash Register and Daily Reconciliation

**Status:** Implemented in code; production release still requires timezone and operational-policy confirmation.

## 1. Cash movement model

A register shift is opened by an authenticated cashier with a non-negative opening float and an idempotency key. A register cannot have a second active shift while the first remains open.

The append-only cash movement ledger records:
- Opening float as one IN movement at shift creation.
- Confirmed cash sale tenders as IN movements.
- Confirmed cash refunds as OUT movements.
- Independent cash-in and cash-out operations as IN/OUT.
- Supplier cash payments, when implemented, must be one OUT movement linked to the supplier payment and shift; do not count the same payment again through a generic cash-out entry.

Expected cash is derived from the ledger:

**Expected drawer cash = sum(IN movements) − sum(OUT movements)**

Do not add the opening float or cash refunds separately if they already have a ledger movement. Do not count UPI/card tenders as drawer cash.

## 2. Shift close

At close, the cashier records the physical cash count. The backend calculates and persists:
- Expected cash from the ledger.
- Counted cash.
- Variance = counted cash − expected cash.
- A reason when the variance is non-zero.

A close retry with the same values is idempotent. A different close payload cannot overwrite a previously closed shift. Posted cash movements are not edited or deleted to make a drawer balance.

## 3. Daily reconciliation report

The endpoint GET /api/admin/pos/reports/cash-reconciliation?locationId={id}&date=YYYY-MM-DD is manager/admin-only.

The report groups shifts by their **business date of opening** and includes:
- Number of shifts, open shifts and closed shifts.
- Expected cash recomputed from each shift's movement ledger.
- Counted cash for closed shifts.
- Recalculated variance.
- Recorded expected cash and variance for comparison.
- A ledger-consistency flag and the closing reason.
- Daily expected, counted and variance totals.

An open shift contributes its current expected cash but has no counted cash or closing variance yet. A closed shift is flagged for review if the physical count differs from expected cash or the stored close totals differ from the movement-ledger recomputation.

## 4. Access controls

- Cashiers can open, operate and close their own shift.
- Cashiers cannot view location-wide shift history or daily reconciliation.
- Managers/admins can review location-wide shift history and daily reconciliation subject to their location assignment.
- The UI restriction is not the security boundary; the backend enforces the role check.

## 5. Timezone release requirement

The current POS entities store LocalDateTime values without a timezone offset. The daily report interprets the supplied date as the shop's local wall-clock business date. Production must therefore run the application with the shop timezone set to **Asia/Kolkata** (including the JVM default timezone) and keep database/session timestamp conventions consistent. Verify this against the actual deployment before enabling the daily report. Do not reinterpret historical timestamps or change timezone settings on a live database without a migration/reconciliation review.

## 6. Remaining owner decisions

Before production, the owner should confirm:
- Whether only managers/admins may close another cashier's shift (current code keeps the cashier as the shift closer).
- Cash discrepancy escalation threshold and who may approve a correction/reopen.
- Whether cash-in/out requires a manager password above a threshold.
- Whether supplier cash payments are allowed from the drawer and the approval limit.
- Whether the business day always follows India local midnight or uses a custom trading-day cutoff.

Until these policies are approved, the safe default is to record the discrepancy and require review; never auto-adjust cash or silently reopen a shift.
