# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `56.78605%` across 366 observed days, which is about `0.155%`
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
baseline, it retunes `trend_down_retest` to catch earlier M5 trend-down retests
with `relativeVolumeThreshold=3.5`, `setupRangeLookback=8`, and
`minBodyRatio=0.55`, and it adds:

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
  `setupRangeLookback=6`, `minBodyRatio=0.15`,
  `minDirectionalCloseStrength=0.7`, `targetR=0.8`,
  `maxHoldM1Candles=10`, and `riskFraction=0.02`.
- `m1_trend_down_breakout_assist`: low-risk M1 `TREND_DOWN`
  breakout-continuation assist using close confirmation,
  `relativeVolumeThreshold=3.0`, `volumeZScoreThreshold=0.5`,
  `minBodyRatio=0.65`, `minDirectionalCloseStrength=0.6`, `targetR=0.6`,
  `maxHoldM1Candles=10`, and `riskFraction=0.0125`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days | Average trades/day |
| --- | --- | --- | --- | --- | --- | --- |
| `56.78605%` | `2.43591%` | `63` | `4.43173` | `0.37244R` | `55` | `0.17213` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 14 | `2246.70249` | `9.07206` | `0.68485R` |
| `trend_down_close` | 2 | `53.51745` | `3.71620` | `0.10914R` |
| `range_failed_break` | 9 | `411.22543` | `2.72135` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1179.35553` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `290.78560` | `6.12139` | `0.36381R` |
| `chop_failed_break` | 3 | `373.17441` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `502.16191` | `1.90218` | `0.16886R` |
| `m1_chop_volume_rejection_scalp` | 7 | `399.50278` | `2.75632` | `0.25054R` |
| `m1_trend_down_breakout_assist` | 11 | `222.17899` | `1.80223` | `0.14503R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. The latest accepted loop improves the annual candidate from
`52.32671%` to `56.78605%` without increasing max drawdown. The changes are not
a broad risk increase: `m1_chop_volume_rejection_scalp` shortens the setup
lookback and hold window, `m1_trend_down_breakout_assist` uses a smaller
`0.6R` target and shorter hold window, and the M5 retest leg accepts earlier
trend-down retests while keeping portfolio profit factor above `4.0`.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `205.03` | 2 |
| `2025-08` | `366.42` | 3 |
| `2025-09` | `-100.85` | 1 |
| `2025-10` | `180.63` | 3 |
| `2025-11` | `259.87` | 4 |
| `2025-12` | `1376.21` | 11 |
| `2026-01` | `227.19` | 7 |
| `2026-02` | `660.02` | 12 |
| `2026-03` | `977.27` | 6 |
| `2026-04` | `461.89` | 5 |
| `2026-05` | `-147.57` | 1 |
| `2026-06` | `1212.50` | 8 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `4.70603%` | `470.60` | 6 | `5.66639` | `0.95398%` |
| `WF2:2025-09-29..2025-12-29` | `17.35056%` | `1816.71` | 18 | `5.88273` | `1.40377%` |
| `WF3:2025-12-29..2026-03-31` | `15.17399%` | `1864.48` | 25 | `3.71822` | `2.43591%` |
| `WF4:2026-03-31..2026-06-30` | `10.78887%` | `1526.82` | 14 | `4.07889` | `1.62043%` |

Walk-forward note: all four windows remain profitable. `WF2` improves sharply
after the retest and assist changes, but `WF3` is now the binding stability
constraint at `3.71822` profit factor and `2.43591%` max drawdown. The next
useful tuning loop should improve January and February trade quality without
turning the strategy into a higher-risk version of the same sparse signal set.

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
  configuration plus trend-down assist returned more (`56.78605%`)
  while keeping profit factor above `4.0`.
- Risk-only `m1_trend_down_breakout_assist` increases were rejected despite
  small annual-return gains. The accepted assist change pairs
  `riskFraction=0.0125` with `targetR=0.6` and `maxHoldM1Candles=10`, improving
  the leg profit factor from `1.07729` to `1.80223` before the higher risk is
  allowed into the composite.
- A fully max-risk M1 up/down configuration (`0.02` and `0.02`) returned
  `50.09672%`, but was rejected because max drawdown rose to `2.81584%` and
  total profit factor dropped to `3.62961`.
- A standalone M5 trend-down retest retune returned `53.71427%`, but its total
  profit factor was `3.99367`; it was only accepted after the simultaneous M1
  chop-rejection and assist changes lifted total profit factor to `4.43173`.
- The first 10th-leg search on top of the `56.78605%` candidate did not produce
  a robust addition. The only positive add-on was M1 `TREND_DOWN`
  volume-rejection at `riskFraction=0.0075`, which lifted the composite to
  `57.39298%` with `4.46439` profit factor, but it added exactly one accepted
  trade across the full year. It remains rejected as an isolated-trade
  improvement until a broader variant proves repeatable.
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

Do not raise per-trade risk by itself just to force the daily target. A risk
increase is only acceptable when paired with an exit-quality or setup-quality
change and when the composite still passes the monthly and walk-forward
stability gates.
