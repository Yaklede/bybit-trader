# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `47.80985%` across 366 observed days, which is about `0.131%`
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
  `riskFraction=0.015`.
- `m1_trend_down_breakout_scalp`: M1 `TREND_DOWN` breakout-continuation scalp
  using close confirmation, `relativeVolumeThreshold=3.0`,
  `volumeZScoreThreshold=0.5`, `minBodyRatio=0.65`,
  `minDirectionalCloseStrength=0.6`, `targetR=0.8`, and
  `riskFraction=0.015`.

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days | Average trades/day |
| --- | --- | --- | --- | --- | --- | --- |
| `47.80985%` | `2.22453%` | `57` | `4.10151` | `0.36326R` | `52` | `0.15574` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 11 | `1866.29753` | `9.43832` | `0.75567R` |
| `trend_down_close` | 5 | `257.15439` | `14.78002` | `0.20631R` |
| `range_failed_break` | 9 | `391.31570` | `2.73820` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1111.83593` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `274.08743` | `6.16213` | `0.36381R` |
| `chop_failed_break` | 3 | `350.94808` | n/a | `0.45958R` |
| `m1_trend_up_breakout_scalp` | 13 | `363.16281` | `1.91688` | `0.16886R` |
| `m1_trend_down_breakout_scalp` | 12 | `166.18266` | `1.26489` | `0.08780R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. The two new M1 scalp legs add 25 trades and improve `WF1`
from one accepted trade to six accepted trades. They also lower total profit
factor from `9.12199` to `4.10151`, so they are intentionally capped at
`riskFraction=0.015` instead of the per-trade maximum `0.02`.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-07` | `153.60` | 2 |
| `2025-08` | `389.83` | 3 |
| `2025-09` | `-125.32` | 1 |
| `2025-10` | `247.49` | 3 |
| `2025-11` | `-121.46` | 3 |
| `2025-12` | `886.67` | 11 |
| `2026-01` | `554.40` | 7 |
| `2026-02` | `492.82` | 9 |
| `2026-03` | `856.08` | 6 |
| `2026-04` | `331.68` | 5 |
| `2026-05` | `-138.00` | 1 |
| `2026-06` | `1253.21` | 6 |

Walk-forward accepted PnL:

| Window | Return | PnL | Trades | Profit factor | Max drawdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| `WF1:2025-06-30..2025-09-29` | `4.18104%` | `418.10` | 6 | `4.33619` | `1.18865%` |
| `WF2:2025-09-29..2025-12-29` | `9.72056%` | `1012.70` | 17 | `2.71442` | `2.22453%` |
| `WF3:2025-12-29..2026-03-31` | `16.65057%` | `1903.29` | 22 | `6.07317` | `1.86434%` |
| `WF4:2026-03-31..2026-06-30` | `10.85103%` | `1446.89` | 12 | `4.21304` | `2.02492%` |

Walk-forward note: all four windows remain profitable and `WF1` trade count
improves from `1` to `6`. The new weak point is `WF2` profit factor `2.71442`,
so the next useful tuning loop should improve October to December trade quality
without removing the added July to September coverage.

## Rejected Paths

- M5 setup-close breakout continuation increased trade count to 35 but lowered
  composite net return to `24.63207%`, raised max drawdown to `3.63243%`, and
  produced only `1.04259` profit factor on the added leg.
- Earlier broad M1 trend-down breakout close-confirmation variants increased
  trade count but lowered composite net return to `25.11653%`, raised max
  drawdown to `3.95224%`, and added a weak leg with only `1.06242` profit
  factor. The accepted `m1_trend_down_breakout_scalp` is a later, tighter M1
  variant with `minBodyRatio=0.65`, `relativeVolumeThreshold=3.0`, and
  `riskFraction=0.015`.
- M1 range failed-break and M5 volume rejection had negative train-period
  results, so they were rejected before composite adoption.
- M5 `TREND_UP` long breakout still remains excluded. The best checked M5
  close-confirmation variant added 19 trades but lowered composite net return to
  `37.07135%`, raised max drawdown to `3.07149%`, and had `0.61797` profit
  factor on the added leg.
- A more aggressive `m1_trend_up_breakout_scalp` at `riskFraction=0.02` returned
  `47.14192%` with `2.43591%` max drawdown and `5.52380` profit factor. It was
  not selected because the paired 1.5% M1 up/down configuration returned more
  (`47.80985%`) with lower max drawdown (`2.22453%`) while keeping profit factor
  above `4.0`.
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
