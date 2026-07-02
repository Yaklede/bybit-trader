# Volume Flow Long-Horizon Validation Report

Source note: this report was produced on 2026-07-02 from BTCUSDT Bybit linear
public kline data synced into
`build/runtime-test/bybit-trader-full-history.sqlite`. Results are backtest
outputs, not live-trading return guarantees.

## Data Availability

Bybit BTCUSDT linear history is available from 2020-03-25, not for a true
seven-to-ten-year horizon.

| Timeframe | Candles | Earliest | Latest |
| --- | ---: | --- | --- |
| M1 | `3,297,305` | `2020-03-25T10:36:00Z` | `2026-07-02T05:40:00Z` |
| M5 | `659,461` | `2020-03-25T10:40:00Z` | `2026-07-02T05:40:00Z` |
| M15 | `219,820` | `2020-03-25T10:45:00Z` | `2026-07-02T05:30:00Z` |

Implementation changes made for this validation:

- Raised research history sync to `3650` days and `10000` requests per
  timeframe.
- Raised volume-flow replay limits to `6,000,000` M1, `1,200,000` M5, and
  `400,000` M15 candles.
- Raised ledger candle read limit to `6,000,000` candles.
- Relaxed Bybit kline parsing so an empty historical result without `symbol`
  is treated as an empty candle page instead of a failed response.

## Replayed Current Strategy

Config: `config/volume-flow-composite-current.json`.

Initial equity: `1,000,000 KRW`.

| Requested horizon | Effective window | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | 2025-07..2026-06 | `43,650,870 KRW` | `4,265.08702%` | `1.03710%` | `30.81106%` | `38.59458%` | `85` | `64.70588%` | `2.73929` | `0.41281R` |
| 2 years | 2024-07..2026-06 | `1,984,118,480 KRW` | `198,311.84804%` | `1.04412%` | `38.52470%` | `39.12601%` | `181` | `62.98343%` | `2.72845` | `0.39246R` |
| 3 years | 2023-07..2026-06 | `10,091,334,327 KRW` | `1,009,033.43273%` | `0.84396%` | `39.23040%` | `39.69854%` | `266` | `58.27068%` | `2.72828` | `0.34700R` |
| 4 years | 2022-07..2026-06 | `3,688,548,973 KRW` | `368,754.89727%` | `0.56334%` | `53.80611%` | `53.96241%` | `354` | `53.10734%` | `2.72769` | `0.23619R` |
| 5 years | 2021-07..2026-06 | `588,631,960 KRW` | `58,763.19604%` | `0.34989%` | `66.20424%` | `66.31860%` | `461` | `49.02386%` | `2.71960` | `0.14943R` |
| 6 years | 2020-07..2026-06 | `281,638,134 KRW` | `28,063.81335%` | `0.25778%` | `73.92374%` | `74.01198%` | `599` | `47.57930%` | `2.69827` | `0.10364R` |
| Full available | 2020-03..2026-06 | `12,012,078 KRW` | `1,101.20785%` | `0.10857%` | `76.72480%` | `76.80356%` | `628` | `47.13376%` | `2.11465` | `0.08634R` |
| 7-10 years requested | 2020-03..2026-06 | `12,012,078 KRW` | `1,101.20785%` | `0.10857%` | `76.72480%` | `76.80356%` | `628` | `47.13376%` | `2.11465` | `0.08634R` |

## Walk-Forward Failure

The strategy is strongly regime-dependent. The three-year window looked strong
because it starts after the worst early regime.

| Horizon | First walk-forward window | Return | MDD | Profit factor | Expectancy |
| --- | --- | ---: | ---: | ---: | ---: |
| 3 years | `2023-07-02..2024-04-01` | `2.50247%` | `39.23040%` | `1.01492` | `0.08297R` |
| 4 years | `2022-07-02..2023-07-02` | `-45.65554%` | `53.80611%` | `0.42566` | `-0.06371R` |
| 5 years | `2021-07-03..2022-10-02` | `-57.01422%` | `60.31065%` | `0.42662` | `-0.09542R` |
| 6 years | `2020-07-03..2022-01-01` | `-44.79202%` | `60.28800%` | `0.80869` | `-0.06806R` |
| Full available | `2020-03-25..2021-10-18` | `-56.35231%` | `62.94684%` | `0.70576` | `-0.06857R` |

The worst full-history drawdown occurred around 2023-05:

- Worst mark-to-market drawdown: `76.80356%`.
- Worst realized drawdown: `76.72480%`.
- Worst event: `2023-05-28T18:52:00Z`,
  `m1_trend_up_breakout_scalp`, BUY, `TIME` exit.
- Max consecutive losses over full available history: `13`.

## Structural Diagnosis

The current candidate is not robust enough for new-market deployment.

- Recent windows overstate the edge. One-to-three-year results clear the
  target, but adding 2020-2023 market regimes collapses compound daily return
  from `0.84396%` to `0.10857%`.
- Drawdown is outside the accepted research gate. Full available MTM MDD is
  `76.80356%`, far beyond the current `30-40%` tolerance.
- The BUY side remains weak over the full replay: BUY generated only
  `212,040 KRW` net PnL with `1.05105` profit factor, while SELL generated
  `10,800,038 KRW` with `2.88622` profit factor.
- The RANGE regime remains a compounding drag: `-1,508,918 KRW` net PnL with
  `0.20315` profit factor.
- The strategy was tuned into the post-2023 regime. It needs regime gating or
  a different strategy family for 2020-2022 style markets.

## Decision

The current config is rejected as a new-market robust strategy. It can remain
as a post-2023 research baseline, but it should not proceed to live trading as
the main strategy.

Required next work:

1. Add backtest response metadata for actual candle coverage so requested
   horizon and effective replay horizon cannot be confused.
2. Split optimization into anchored train/test periods: train on earlier data,
   score only on later unseen windows, and reject candidates that fail any
   major walk-forward window.
3. Add hard regime filters that disable or reduce risk during the 2020-2022
   failure profile.
4. Remove or heavily gate RANGE/chop exposure until it is positive under
   full-history compounding, not only average R.
5. Rebuild the current strategy target around full-history survival first, then
   retest 1, 2, 3, 4, 5, 6, and full available windows.
