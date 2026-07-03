# Volume-Flow Robustness Evaluation

Date: 2026-07-03

## Goal

The strategy must not be judged by a single profitable backtest window. The
operating goal is robustness across unknown future markets:

- survive arbitrary market windows without account-level failure,
- keep rolling-window mark-to-market drawdown inside the operating limit,
- keep 6-12 month windows non-negative when possible,
- identify which leg causes each failed window before tuning.

## Implemented Gate

Composite volume-flow backtests now return `robustnessSummary`.

Default gate:

- `robustnessWindowDays = 365`
- `robustnessStepDays = 90`
- `robustnessMinReturnPct = 0.0`
- `robustnessMaxDrawdownPct = 40.0`
- `robustnessMinTrades = 5`

For shorter ad hoc periods, use a 6-month window:

- `robustnessWindowDays = 180`
- `robustnessStepDays = 30`
- `robustnessMinTrades = 3`

Each window is replayed from the same initial equity using each trade's
equity-relative return, instead of adding absolute PnL from the full-period
curve. This makes the rolling window comparable to running that period as a
fresh backtest.

## Current Findings

### Failed Era: 2021-04..2023-02

Full-period result:

| Return | CDR | MDD | MTM MDD | Trades | PF |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `-25.96097%` | `-0.04293%` | `45.41254%` | `46.16392%` | `139` | `0.86191` |

6-month rolling robustness:

| Window count | Passed | Failed | Pass rate | Worst return window | Worst return | Worst MTM MDD | Worst leg |
| ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| `18` | `10` | `8` | `55.55556%` | `2021-04-01..2021-09-27` | `-33.56978%` | `46.16392%` | `m1_failed_break_chop_scalp` |

1-year rolling robustness:

| Window count | Passed | Failed | Pass rate | Worst return window | Worst return | Worst MTM MDD | Worst leg |
| ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |
| `4` | `2` | `2` | `50.00000%` | `2021-04-01..2022-03-31` | `-20.69451%` | `46.16392%` | `m1_failed_break_chop_scalp` |

### Successful Era: 2024-06..2025-03

Full-period result:

| Return | CDR | MDD | MTM MDD | Trades | PF |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `1252.51394%` | `0.85761%` | `26.47314%` | `27.34783%` | `73` | `1.80342` |

6-month rolling robustness:

| Window count | Passed | Failed | Pass rate | Worst return window | Worst return | Worst MTM MDD |
| ---: | ---: | ---: | ---: | --- | ---: | ---: |
| `5` | `5` | `0` | `100.00000%` | `2024-08-30..2025-02-25` | `43.17895%` | `19.74255%` |

## First Candidate Sweep

Candidates were evaluated on both the failed era and the successful era with
the 6-month rolling robustness gate.

| Candidate | Failed-era return | Failed pass rate | Failed worst MTM MDD | Success-era return | Success pass rate | Decision |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Current | `-25.96097%` | `55.55556%` | `46.16392%` | `1252.51394%` | `100.00000%` | Baseline |
| Drop `m1_failed_break_chop_scalp` | `-25.21660%` | `55.55556%` | `38.90800%` | `999.96340%` | `100.00000%` | Reject: failed return still negative |
| `m1_failed_break_chop_scalp.riskFraction=0.05` | `-28.34943%` | `66.66667%` | `43.32271%` | `1141.36796%` | `100.00000%` | Reject: failed return worsens |
| Drop `range_failed_break_loose` | `-22.31599%` | `33.33333%` | `68.43753%` | `703.16779%` | `100.00000%` | Reject: drawdown worsens |
| `range_failed_break_loose.riskFraction=0.03` | `-3.80143%` | `77.77778%` | `46.16392%` | `879.86938%` | `100.00000%` | Watchlist: improves return, still fails MDD |
| Drop both weak legs | `27.90201%` | `61.11111%` | `38.90800%` | `553.19488%` | `100.00000%` | Reject for now: success-era return collapses |
| `monthlyStopPct=10` | `-27.03098%` | `72.22222%` | `51.05255%` | `502.38800%` | `60.00000%` | Reject |
| `monthlyStopPct=8` | `-30.75101%` | `72.22222%` | `50.84246%` | `502.38800%` | `60.00000%` | Reject |
| `dailyStopPct=1` | Same as current | `55.55556%` | `46.16392%` | Same as current | `100.00000%` | No effect |

## Decision

Do not promote a strategy config change yet.

The first safe improvement is the new robustness gate itself. It prevents
future tuning from being promoted when it only improves a chosen period. The
next strategy-level change should target the 2021-04..2021-09 window without
collapsing the 2024-06..2025-03 success window.

Next candidate direction:

- add regime-level risk-down logic for early crash/transition markets,
- gate `m1_failed_break_chop_scalp` by a broader trend/volatility condition,
- keep `range_failed_break_loose` under review, but do not delete it globally.

## Full-DB Rolling Robustness Promotion 2026-07-03

Purpose: supersede the first candidate sweep with independent rolling-window
validation across all locally held BTCUSDT candles. Each 180-day and 365-day
window was replayed from the same starting equity so that the result measures
period robustness instead of one continuous equity curve.

Source artifacts:

- Baseline: `build/volume-flow-recursive-robustness-20260703/independent-windows`
- Candidate sweeps:
  - `build/volume-flow-recursive-robustness-20260703/candidate-windows`
  - `build/volume-flow-recursive-robustness-20260703/candidate-windows-trend-only`
  - `build/volume-flow-recursive-robustness-20260703/candidate-windows-trend-only-exit`
  - `build/volume-flow-recursive-robustness-20260703/candidate-windows-add-down-runner`
  - `build/volume-flow-recursive-robustness-20260703/candidate-windows-m1-follow-through`
- Promoted validation:
  `build/volume-flow-recursive-robustness-20260703/current-config-validation`

Baseline before this loop:

| Gate | Windows | Passed | Failed | Pass rate | Worst return | Worst MTM MDD | Main weak leg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 180d rolling | `58` | `42` | `16` | `72.41379%` | `-40.86307%` | `68.43753%` | `m1_failed_break_chop_scalp` |
| 365d rolling | `18` | `12` | `6` | `66.66667%` | `-30.67534%` | `68.43753%` | `range_failed_break_loose` |

Promoted config:

- Removed `trend_down_close`.
- Removed `range_failed_break_loose`.
- Removed `m1_failed_break_chop_scalp`.
- Changed `m1_trend_up_breakout_scalp.profitProtectActivationR` from `0.50`
  to `0.35`.
- Changed `m1_trend_up_breakout_scalp.profitProtectFloorR` from `0.10` to
  `0.20`.

Promoted rolling result:

| Gate | Windows | Passed | Failed | Pass rate | Worst return | Worst MTM MDD | Remaining weak leg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 180d rolling | `58` | `48` | `10` | `82.75862%` | `-36.31126%` | `36.35382%` | `m1_trend_up_breakout_scalp` |
| 365d rolling | `18` | `16` | `2` | `88.88889%` | `-22.73117%` | `38.80249%` | `m1_trend_down_breakout_assist` |

Long-horizon max-limit validation:

| Period | Effective candles | Final equity | Return | CDR | MTM MDD | Trades | PF |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `2021-04-01..2022-04-01` | `525,601` M1 | `772,688.34` | `-22.73117%` | `-0.07043%` | `38.80249%` | `46` | `0.75112` |
| `2021-04-01..2023-04-01` | `1,051,201` M1 | `912,171.36` | `-8.78286%` | `-0.01257%` | `38.80249%` | `109` | `0.94749` |
| `2021-04-01..2024-04-01` | `1,578,241` M1 | `1,071,996.61` | `7.19966%` | `0.00634%` | `38.80249%` | `155` | `1.02579` |
| `2021-04-01..2025-04-01` | `2,103,841` M1 | `7,285,550.10` | `628.55501%` | `0.13593%` | `38.80249%` | `222` | `1.88450` |
| `2021-04-01..2026-04-01` | `2,629,441` M1 | `185,957,483.18` | `18,495.74832%` | `0.28643%` | `38.80249%` | `272` | `7.06869` |
| `2021-04-01..2026-07-02` | `2,762,261` M1 | `216,378,452.62` | `21,537.84526%` | `0.28059%` | `38.80249%` | `285` | `3.38215` |

Rejected directions:

- Removing only `m1_failed_break_chop_scalp` worsened 180d pass rate.
- Removing only `range_failed_break_loose` raised 180d pass rate slightly but
  left MTM MDD above `68%`.
- Dedupe and lower max-concurrency reduced recovery trades.
- Monthly stop and portfolio drawdown throttle reduced drawdown but lowered
  rolling pass rate.
- Extra M1 trend-down runner legs created new failed windows in 2024-2025.
- M1 follow-through exits reduced some full stops but cut too many winning
  continuation trades.

Decision:

- Promote the four-leg trend-only config because it improves both rolling
  robustness gates and keeps MTM MDD inside the `40%` operating limit.
- This does not complete the robustness goal. Remaining failed windows are
  return-only failures, concentrated in `2021-04..2022-03`,
  `2022-07..2023-01`, and `2023-07..2024-01`.
- The next viable improvement should add a new market-state selector before
  entry. Static relative-volume, macro-efficiency, follow-through, and runner
  additions have been rejected by rolling-window evidence.
