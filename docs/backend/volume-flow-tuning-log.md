# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `51.80084%` across 366 observed days, which is about `0.142%`
simple average return per observed day. The target is not met.

## Baseline

The previous three-leg composite was:

- `trend_down_retest`: M5 `TREND_DOWN` breakout continuation with retest
  confirmation.
- `trend_down_close`: M5 `TREND_DOWN` breakout continuation with close
  confirmation.
- `range_failed_break`: M5 `RANGE` failed-break reversal.

Baseline result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy |
| --- | --- | --- | --- | --- |
| `26.83583%` | `2.10462%` | `26` | `6.99393` | `0.46243R` |

## Current Candidate

The current best candidate is stored at
`config/volume-flow-composite-current.json`. Relative to the original three-leg
baseline, it adds:

- `trend_down_retest_runner`: relaxed M5 `TREND_DOWN` retest leg using
  `RUNNER` exit, `relativeVolumeThreshold=3.5`,
  `volumeZScoreThreshold=0.5`, `runnerTrailActivationR=0.8`,
  `runnerTrailDistanceR=0.75`.
- `range_failed_break_loose`: relaxed M5 `RANGE` failed-break leg using
  `relativeVolumeThreshold=2.5`, `targetR=0.8`, `minBodyRatio=0.35`,
  `minDirectionalCloseStrength=0.6`, `minRejectionWickRatio=0.2`.
- `chop_failed_break`: M5 `HIGH_VOLATILITY_CHOP` failed-break leg using
  `relativeVolumeThreshold=2.0`, `targetR=0.8`, `minBodyRatio=0.15`,
  `minDirectionalCloseStrength=0.7`, `minRejectionWickRatio=0.2`.
- `m1_trend_up_breakout_scalp`: M1 `TREND_UP` breakout-continuation scalp using
  close confirmation, `relativeVolumeThreshold=2.0`,
  `volumeZScoreThreshold=0.5`, `minBodyRatio=0.65`,
  `minDirectionalCloseStrength=0.7`, `targetR=0.8`, and
  `riskFraction=0.02`.
- `m1_chop_volume_rejection_scalp`: M1 `HIGH_VOLATILITY_CHOP`
  volume-rejection reversal scalp using close confirmation,
  `relativeVolumeThreshold=2.0`, `volumeZScoreThreshold=0.5`,
  `minBodyRatio=0.15`, `minDirectionalCloseStrength=0.7`, `targetR=0.8`, and
  `riskFraction=0.02`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days | Average trades/day |
| --- | --- | --- | --- | --- | --- | --- |
| `51.80084%` | `2.43591%` | `52` | `4.87126` | `0.40831R` | `47` | `0.14208` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 11 | `1902.24639` | `9.35888` | `0.75567R` |
| `trend_down_close` | 5 | `262.81403` | `14.95637` | `0.20631R` |
| `range_failed_break` | 9 | `396.45175` | `2.71162` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1141.85646` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `277.70229` | `6.09721` | `0.36381R` |
| `chop_failed_break` | 3 | `358.81454` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `489.34706` | `1.91057` | `0.16886R` |
| `m1_chop_volume_rejection_scalp` | 7 | `350.85138` | `2.30830` | `0.22573R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. Replacing the weaker `m1_trend_down_breakout_scalp` with
`m1_chop_volume_rejection_scalp` reduces accepted trades from 57 to 52 but
improves net return from `49.20519%` to `51.80084%`, total profit factor from
`4.01493` to `4.87126`, and `WF2` profit factor from `2.78650` to `4.75927`.
This is a quality improvement, not just a risk increase.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `205.03` | 2 |
| `2025-08` | `300.36` | 2 |
| `2025-10` | `121.05` | 2 |
| `2025-11` | `198.60` | 3 |
| `2025-12` | `1049.37` | 10 |
| `2026-01` | `198.23` | 4 |
| `2026-02` | `518.87` | 11 |
| `2026-03` | `934.02` | 6 |
| `2026-04` | `496.83` | 4 |
| `2026-05` | `-141.60` | 1 |
| `2026-06` | `1299.33` | 7 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `5.05392%` | `505.39` | 4 | n/a | `0.00000%` |
| `WF2:2025-09-29..2025-12-29` | `13.03150%` | `1369.01` | 15 | `4.75927` | `1.40377%` |
| `WF3:2025-12-29..2026-03-31` | `13.90491%` | `1651.13` | 21 | `3.62879` | `2.43591%` |
| `WF4:2026-03-31..2026-06-30` | `12.23284%` | `1654.56` | 12 | `5.78435` | `1.25159%` |

Walk-forward note: all four windows remain profitable. The replacement fixes
the previous `WF2` quality issue, but the new weak point is `WF3` profit factor
`3.62879`, driven mostly by January to February losses. The next useful tuning
loop should improve January to March trade quality without removing the stronger
October to December coverage.

## Rejected Paths

- M5 setup-close breakout continuation increased trade count to 35 but lowered
  composite net return to `24.63207%`, raised max drawdown to `3.63243%`, and
  produced only `1.04259` profit factor on the added leg.
- Earlier broad M1 trend-down breakout close-confirmation variants increased
  trade count but lowered composite net return to `25.11653%`, raised max
  drawdown to `3.95224%`, and added a weak leg with only `1.06242` profit
  factor. The later tighter `m1_trend_down_breakout_scalp` reached `49.20519%`
  at the composite level, but was replaced because `m1_chop_volume_rejection_scalp`
  improved return and profit factor with fewer trades.
- M1 range failed-break and M5 volume rejection had negative train-period
  results, so they were rejected before composite adoption.
- M5 `TREND_UP` long breakout still remains excluded. The best checked M5
  close-confirmation variant added 19 trades but lowered composite net return to
  `37.07135%`, raised max drawdown to `3.07149%`, and had `0.61797` profit
  factor on the added leg.
- A more aggressive `m1_trend_up_breakout_scalp` at `riskFraction=0.02` returned
  `47.14192%` with `2.43591%` max drawdown and `5.52380` profit factor. It was
  not selected by itself because the paired M1 trend-up and chop-rejection
  configuration returned more (`51.80084%`) while keeping profit factor above
  `4.0`.
- A fully max-risk M1 up/down configuration (`0.02` and `0.02`) returned
  `50.09672%`, but was rejected because max drawdown rose to `2.81584%` and
  total profit factor dropped to `3.62961`.
- M5 `RANGE` breakout continuation was rejected because the best checked
  variants were negative in both train and test periods. The strongest sampled
  variant had train `-5.23514%`, test `-5.07923%`, and weak profit factors.
- M5 `TREND_DOWN` close-confirmation runner variants looked good as standalone
  legs but added no accepted composite trades because their signals overlapped
  existing positions.

## Reproduction Body

POST `config/volume-flow-composite-current.json` to
`/backtests/volume-flow/composite/run` on a local server that uses the 1-year
runtime database.

## Tuning Gate

The composite backtest response now returns `monthlyPerformance` and
`walkForwardPerformance` so the next tuning loop can reject candidates that only
improve the annual total through one or two isolated trades.

Do not raise per-trade risk just to force the daily target until a candidate
passes the monthly and walk-forward stability gates. Raising risk before then
would only scale the same sparse-return profile.
