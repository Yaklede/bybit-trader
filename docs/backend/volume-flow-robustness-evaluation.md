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
