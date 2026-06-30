# Volume Flow Tuning Log

## 2026-07-01 BTCUSDT 1-year replay

Source note: measured against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`, covering
`2025-06-30T10:38:00Z` to `2026-06-30T10:37:00Z`. These numbers are local
backtest outputs, not live-trading return guarantees.

Target note: the current daily objective is `0.5%` to `2%`. The best candidate
below returns `40.94073%` across 366 observed days, which is about `0.112%`
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

Candidate result:

| Net return | Max drawdown | Trades | Profit factor | Expectancy | Active days |
| --- | --- | --- | --- | --- | --- |
| `40.94073%` | `2.10462%` | `32` | `9.12199` | `0.54553R` | `30` |

Per-leg accepted performance:

| Leg | Trades | Net PnL | Profit factor | Expectancy |
| --- | ---: | ---: | ---: | ---: |
| `trend_down_retest` | 11 | `1819.06204` | `9.51345` | `0.75567R` |
| `trend_down_close` | 5 | `245.99218` | `14.71631` | `0.20631R` |
| `range_failed_break` | 9 | `375.34234` | `2.68826` | `0.17512R` |
| `trend_down_retest_runner` | 1 | `1060.16596` | n/a | `4.06695R` |
| `range_failed_break_loose` | 3 | `258.58777` | `6.15682` | `0.36381R` |
| `chop_failed_break` | 3 | `334.92260` | n/a | `0.45958R` |

Risk note: the runner leg still contributes one accepted trade in this replay:
`2026-06-25T13:30:00Z`, short side, `+4.06695R`, exit reason
`TRAILING_STOP`. The new `chop_failed_break` leg contributes three accepted
trades, all winners in this sample. This improves the annual replay but should
still be treated as a candidate, not a production-ready strategy, until
walk-forward validation is added.

Monthly accepted PnL:

| Month | PnL | Trades |
| --- | ---: | ---: |
| `2025-08` | `219.19` | 1 |
| `2025-10` | `211.73` | 1 |
| `2025-11` | `62.69` | 2 |
| `2025-12` | `791.07` | 8 |
| `2026-01` | `271.54` | 3 |
| `2026-02` | `435.62` | 5 |
| `2026-03` | `622.69` | 3 |
| `2026-04` | `291.73` | 3 |
| `2026-05` | `-130.33` | 1 |
| `2026-06` | `1318.15` | 5 |

## Rejected Paths

- M5 setup-close breakout continuation increased trade count to 35 but lowered
  composite net return to `24.63207%`, raised max drawdown to `3.63243%`, and
  produced only `1.04259` profit factor on the added leg.
- M1 trend-down breakout close-confirmation increased trade count to 41 but
  lowered composite net return to `25.11653%`, raised max drawdown to
  `3.95224%`, and added a weak leg with only `1.06242` profit factor.
- M1 range failed-break and M5 volume rejection had negative train-period
  results, so they were rejected before composite adoption.
- M5 `TREND_UP` long breakout had negative train-period results, so it remains
  excluded.
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

The composite backtest response now returns `monthlyPerformance` so the next
tuning loop can reject candidates that only improve the annual total through one
or two isolated trades. The next missing validation is a walk-forward gate that
compares multiple contiguous train/test slices instead of one `60/40` split.

Do not raise per-trade risk just to force the daily target until a candidate
passes the monthly and walk-forward stability gates. Raising risk before then
would only scale the same sparse-return profile.
