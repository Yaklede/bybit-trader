# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `52.32671%` across 366 observed days, which is about `0.143%`
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
- `m1_trend_down_breakout_assist`: low-risk M1 `TREND_DOWN`
  breakout-continuation assist using close confirmation,
  `relativeVolumeThreshold=3.0`, `volumeZScoreThreshold=0.5`,
  `minBodyRatio=0.65`, `minDirectionalCloseStrength=0.6`, `targetR=0.8`, and
  `riskFraction=0.009`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days | Average trades/day |
| --- | --- | --- | --- | --- | --- | --- |
| `52.32671%` | `2.43591%` | `63` | `4.02808` | `0.34352R` | `55` | `0.17213` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 11 | `1904.43667` | `9.35033` | `0.75567R` |
| `trend_down_close` | 5 | `264.46454` | `14.84701` | `0.20631R` |
| `range_failed_break` | 9 | `400.06949` | `2.73548` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1145.81206` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `281.92456` | `6.12730` | `0.36381R` |
| `chop_failed_break` | 3 | `362.05588` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `490.43388` | `1.90553` | `0.16886R` |
| `m1_chop_volume_rejection_scalp` | 7 | `353.78903` | `2.31175` | `0.22573R` |
| `m1_trend_down_breakout_assist` | 11 | `29.68443` | `1.07729` | `0.03719R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. Replacing the weaker `m1_trend_down_breakout_scalp` with
`m1_chop_volume_rejection_scalp` reduces accepted trades from 57 to 52 but
improves net return from `49.20519%` to `51.80084%`, total profit factor from
`4.01493` to `4.87126`, and `WF2` profit factor from `2.78650` to `4.75927`.
This is a quality improvement, not just a risk increase. The later
`m1_trend_down_breakout_assist` addition pushes annual return to `52.32671%`
and restores `WF3` profit factor above `4.0`, but it is a weak standalone leg
(`1.07729` profit factor), so risk is capped at `0.009`.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `205.03` | 2 |
| `2025-08` | `366.85` | 3 |
| `2025-09` | `-75.40` | 1 |
| `2025-10` | `183.37` | 3 |
| `2025-11` | `86.71` | 4 |
| `2025-12` | `995.39` | 11 |
| `2026-01` | `406.62` | 7 |
| `2026-02` | `602.08` | 12 |
| `2026-03` | `947.31` | 6 |
| `2026-04` | `434.07` | 5 |
| `2026-05` | `-142.91` | 1 |
| `2026-06` | `1223.56` | 8 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `4.96483%` | `496.48` | 6 | `7.58489` | `0.71319%` |
| `WF2:2025-09-29..2025-12-29` | `12.05610%` | `1265.47` | 18 | `3.43398` | `1.78155%` |
| `WF3:2025-12-29..2026-03-31` | `16.62998%` | `1956.01` | 25 | `4.08717` | `2.43591%` |
| `WF4:2026-03-31..2026-06-30` | `11.04181%` | `1514.71` | 14 | `4.03464` | `2.17832%` |

Walk-forward note: all four windows remain profitable; `WF1` has no accepted
losses, and the three measurable profit-factor windows clear `3.4`. The binding
constraints are `WF2` profit factor `3.43398`, `WF4` profit factor `4.03464`,
and the low standalone quality of `m1_trend_down_breakout_assist`. The next
useful tuning loop should improve February and June trade quality without
relying on more risk.

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
  configuration plus low-risk trend-down assist returned more (`52.32671%`)
  while keeping profit factor above `4.0`.
- Higher-risk `m1_trend_down_breakout_assist` variants were rejected despite
  higher annual return. `riskFraction=0.00925` returned `52.34036%`, but `WF4`
  profit factor was only `4.00086`; `riskFraction=0.01` returned `52.38103%`
  but dropped total profit factor below `4.0`.
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
