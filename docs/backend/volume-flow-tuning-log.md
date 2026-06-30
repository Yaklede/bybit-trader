# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `49.02336%` across 366 observed days, which is about `0.134%`
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
- `m1_trend_down_breakout_scalp`: M1 `TREND_DOWN` breakout-continuation scalp
  using close confirmation, `relativeVolumeThreshold=3.0`,
  `volumeZScoreThreshold=0.5`, `minBodyRatio=0.65`,
  `minDirectionalCloseStrength=0.6`, `targetR=0.8`, and
  `riskFraction=0.0125`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days | Average trades/day |
| --- | --- | --- | --- | --- | --- | --- |
| `49.02336%` | `2.43591%` | `57` | `4.10706` | `0.36326R` | `52` | `0.15574` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 11 | `1878.51924` | `9.43556` | `0.75567R` |
| `trend_down_close` | 5 | `258.75793` | `14.91165` | `0.20631R` |
| `range_failed_break` | 9 | `392.29937` | `2.72891` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1120.96405` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `274.66227` | `6.13908` | `0.36381R` |
| `chop_failed_break` | 3 | `352.86501` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `483.88251` | `1.91173` | `0.16886R` |
| `m1_trend_down_breakout_scalp` | 12 | `140.38533` | `1.26718` | `0.08780R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. The two M1 scalp legs add 25 trades and improve `WF1` from one
accepted trade to six accepted trades. A risk grid showed the best conservative
blend was asymmetric: `m1_trend_up_breakout_scalp` at `0.02` and
`m1_trend_down_breakout_scalp` at `0.0125`. This improves net return from the
equal-risk eight-leg candidate's `47.80985%` to `49.02336%` while keeping total
profit factor above `4.0`.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `205.03` | 2 |
| `2025-08` | `392.71` | 3 |
| `2025-09` | `-104.97` | 1 |
| `2025-10` | `207.57` | 3 |
| `2025-11` | `-90.83` | 3 |
| `2025-12` | `962.36` | 11 |
| `2026-01` | `481.15` | 7 |
| `2026-02` | `384.02` | 9 |
| `2026-03` | `922.56` | 6 |
| `2026-04` | `396.28` | 5 |
| `2026-05` | `-138.91` | 1 |
| `2026-06` | `1285.38` | 6 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `4.92762%` | `492.76` | 6 | `5.69411` | `0.99054%` |
| `WF2:2025-09-29..2025-12-29` | `10.28422%` | `1079.10` | 17 | `2.87804` | `1.93842%` |
| `WF3:2025-12-29..2026-03-31` | `15.44892%` | `1787.73` | 22 | `4.67930` | `2.43591%` |
| `WF4:2026-03-31..2026-06-30` | `11.54787%` | `1542.75` | 12 | `4.74132` | `1.80000%` |

Walk-forward note: all four windows remain profitable and `WF1` trade count
improves from `1` to `6`. The risk rebalance improves `WF2` return and drawdown,
but `WF2` profit factor is still only `2.87804`, so the next useful tuning loop
should improve October to December trade quality without removing the added July
to September coverage.

## Rejected Paths

- M5 setup-close breakout continuation increased trade count to 35 but lowered
  composite net return to `24.63207%`, raised max drawdown to `3.63243%`, and
  produced only `1.04259` profit factor on the added leg.
- Earlier broad M1 trend-down breakout close-confirmation variants increased
  trade count but lowered composite net return to `25.11653%`, raised max
  drawdown to `3.95224%`, and added a weak leg with only `1.06242` profit
  factor. The accepted `m1_trend_down_breakout_scalp` is a later, tighter M1
  variant with `minBodyRatio=0.65`, `relativeVolumeThreshold=3.0`, and
  `riskFraction=0.0125`.
- M1 range failed-break and M5 volume rejection had negative train-period
  results, so they were rejected before composite adoption.
- M5 `TREND_UP` long breakout still remains excluded. The best checked M5
  close-confirmation variant added 19 trades but lowered composite net return to
  `37.07135%`, raised max drawdown to `3.07149%`, and had `0.61797` profit
  factor on the added leg.
- A more aggressive `m1_trend_up_breakout_scalp` at `riskFraction=0.02` returned
  `47.14192%` with `2.43591%` max drawdown and `5.52380` profit factor. It was
  not selected by itself because the paired asymmetric M1 up/down configuration
  returned more (`49.02336%`) while keeping profit factor above `4.0`.
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
