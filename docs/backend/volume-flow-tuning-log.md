# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `68.72947%` across 366 observed days, which is about `0.188%`
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
`minBodyRatio=0.55`. The latest tuning loop also extends selected time exits
after full-trade analysis showed that most losses were `TIME` exits rather than
hard stops. It adds:

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
  `maxHoldM1Candles=20`, and `riskFraction=0.02`.
- `m1_trend_down_breakout_assist`: low-risk M1 `TREND_DOWN`
  breakout-continuation assist using close confirmation,
  `relativeVolumeThreshold=3.0`, `volumeZScoreThreshold=0.5`,
  `minBodyRatio=0.65`, `minDirectionalCloseStrength=0.6`, `targetR=0.6`,
  `maxHoldM1Candles=20`, and `riskFraction=0.0125`.

The accepted time-exit retune changes these existing hold windows:

- `trend_down_retest`: `maxHoldM1Candles=30 -> 35`.
- `trend_down_close`: `maxHoldM1Candles=15 -> 20`.
- `range_failed_break`: `maxHoldM1Candles=30 -> 35`.
- `range_failed_break_loose`: `maxHoldM1Candles=30 -> 20`.
- `m1_chop_volume_rejection_scalp`: `maxHoldM1Candles=10 -> 20`.
- `m1_trend_down_breakout_assist`: `maxHoldM1Candles=10 -> 20`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days | Average trades/day |
| --- | --- | --- | --- | --- | --- | --- |
| `68.72947%` | `2.28970%` | `63` | `5.93627` | `0.43774R` | `55` | `0.17213` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 14 | `2466.75720` | `11.29885` | `0.73816R` |
| `trend_down_close` | 2 | `182.83345` | n/a | `0.34430R` |
| `range_failed_break` | 9 | `663.50483` | `4.40823` | `0.26689R` |
| `trend_down_retest_runner` | 1 | `1269.19482` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `397.98197` | n/a | `0.46982R` |
| `chop_failed_break` | 3 | `387.32061` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `513.22812` | `1.90105` | `0.16886R` |
| `m1_chop_volume_rejection_scalp` | 7 | `597.39918` | `3.46163` | `0.33755R` |
| `m1_trend_down_breakout_assist` | 11 | `394.72718` | `3.70605` | `0.24903R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. The two latest accepted loops improve the annual candidate from
`52.32671%` to `56.78605%`, then to `68.72947%`, while reducing max drawdown
from `2.43591%` to `2.28970%`. The latest change is not a risk increase: it
keeps all risk fractions unchanged and only adjusts time exits after the full
trade list showed that the main drag was premature or unproductive `TIME`
exits.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `205.03` | 2 |
| `2025-08` | `366.42` | 3 |
| `2025-09` | `-79.42` | 1 |
| `2025-10` | `181.00` | 3 |
| `2025-11` | `187.50` | 4 |
| `2025-12` | `1543.54` | 11 |
| `2026-01` | `480.50` | 7 |
| `2026-02` | `831.67` | 12 |
| `2026-03` | `1017.45` | 6 |
| `2026-04` | `545.16` | 5 |
| `2026-05` | `-163.51` | 1 |
| `2026-06` | `1757.59` | 8 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `4.92035%` | `492.03` | 6 | `7.19553` | `0.75125%` |
| `WF2:2025-09-29..2025-12-29` | `18.22377%` | `1912.04` | 18 | `6.31248` | `1.09857%` |
| `WF3:2025-12-29..2026-03-31` | `18.78115%` | `2329.63` | 25 | `4.94701` | `2.28970%` |
| `WF4:2026-03-31..2026-06-30` | `14.51936%` | `2139.24` | 14 | `6.89684` | `1.48738%` |

Walk-forward note: all four windows remain profitable and all measurable
profit-factor windows now clear `4.9`. `WF3` still owns the worst drawdown at
`2.28970%`, but it is no longer the weak profit-factor window. The remaining
business gap is frequency: 63 accepted trades across 366 observed days still
averages only `0.17213` trades per observed day.

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
- The accepted time-exit retune was selected from one-at-a-time and combined
  `maxHoldM1Candles` checks. It beat the previous `56.78605%` candidate without
  adding trades or raising risk. A near variant with `range_failed_break_loose`
  left at `30` returned `67.65894%`; the accepted `20`-candle loose range hold
  improved the final composite to `68.72947%`.
- M5 `RANGE` breakout continuation was rejected because the best checked
  variants were negative in both train and test periods. The strongest sampled
  variant had train `-5.23514%`, test `-5.07923%`, and weak profit factors.
- M5 `TREND_DOWN` close-confirmation runner variants looked good as standalone
  legs but added no accepted composite trades because their signals overlapped
  existing positions.

## Reproduction Body

POST `config/volume-flow-composite-current.json` to
`/backtests/volume-flow/composite/run` on a local server that uses the 1-year
runtime database. Add `"tradeLimit":10000` to the request body when the full
accepted trade list is needed for loss analysis; the default response still
returns the most recent 50 trades.

## Tuning Gate

The composite backtest response now returns `monthlyPerformance`,
`walkForwardPerformance`, and a request-scoped `tradeLimit`, so the next tuning
loop can reject candidates that only improve the annual total through one or two
isolated trades.

Do not raise per-trade risk by itself just to force the daily target. A risk
increase is only acceptable when paired with an exit-quality or setup-quality
change and when the composite still passes the monthly and walk-forward
stability gates.
