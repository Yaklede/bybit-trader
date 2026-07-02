# Volume Flow Long-Horizon Validation Report

Source note: this report was produced on 2026-07-02 from local BTCUSDT Bybit
linear kline data in `build/runtime-test/bybit-trader-3y-backtest.sqlite` and a
small Bybit public API availability check. Results are backtest outputs, not
live-trading return guarantees.

## Data Availability

The local complete M1/M5/M15 dataset covers only three years:

| Timeframe | Candles | Earliest | Latest |
| --- | ---: | --- | --- |
| M1 | `1,578,240` | `2023-06-30T10:38:00Z` | `2026-06-30T10:37:00Z` |
| M5 | `315,648` | `2023-06-30T10:40:00Z` | `2026-06-30T10:35:00Z` |
| M15 | `105,216` | `2023-06-30T10:45:00Z` | `2026-06-30T10:30:00Z` |

The current API also rejects requests above `1,600,000` M1 candles, so a
four-year request and longer cannot run against the current endpoint. A small
Bybit public API check returned the first available BTCUSDT linear M1 candle at
`2020-03-25T10:36:00Z`, which means a true seven-to-ten-year BTCUSDT linear
backtest is not available from this venue even after extending local ingestion.

## Replayed Current Strategy

Config: `config/volume-flow-composite-current.json`.

Initial equity: `1,000,000 KRW`.

| Requested horizon | Status | Final equity | Net return | Compound daily | Realized MDD | Mark-to-market MDD | Trades | Win rate | Profit factor | Expectancy |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 year | Ran | `43,650,870 KRW` | `4,265.08702%` | `1.03710%` | `30.81106%` | `38.59458%` | `85` | `64.70588%` | `2.73929` | `0.41281R` |
| 2 years | Ran | `1,984,118,480 KRW` | `198,311.84804%` | `1.04412%` | `38.52470%` | `39.12601%` | `181` | `62.98343%` | `2.72845` | `0.39246R` |
| 3 years | Ran | `10,091,334,327 KRW` | `1,009,033.43273%` | `0.84396%` | `39.23040%` | `39.69854%` | `266` | `58.27068%` | `2.72828` | `0.34700R` |
| 4 years | Not run | - | - | - | - | - | - | - | - | M1 limit exceeds current endpoint guard and local data is unavailable. |
| 5 years | Not run | - | - | - | - | - | - | - | - | M1 limit exceeds current endpoint guard and local data is unavailable. |
| 6 years | Not run | - | - | - | - | - | - | - | - | M1 limit exceeds current endpoint guard and local data is unavailable. |
| 7 years | Not run | - | - | - | - | - | - | - | - | BTCUSDT linear venue history does not cover this horizon. |
| 8 years | Not run | - | - | - | - | - | - | - | - | BTCUSDT linear venue history does not cover this horizon. |
| 9 years | Not run | - | - | - | - | - | - | - | - | BTCUSDT linear venue history does not cover this horizon. |
| 10 years | Not run | - | - | - | - | - | - | - | - | BTCUSDT linear venue history does not cover this horizon. |

## New-Market Robustness Check

The strategy clears the aggressive three-year compounding target by a very thin
margin, but it is not yet proven robust for a new unseen market.

Positive signals:

- The latest one-year and two-year windows both compound above `1.0%` daily.
- The three-year replay reaches the `10,000x` target threshold with
  `0.84396%` compound daily return.
- Profit factor stays near `2.73` across one, two, and three years.
- The three-year mark-to-market drawdown remains just inside the current `40%`
  research gate.

Robustness risks:

- The first three-year walk-forward window, `2023-06-30..2024-03-30`, returned
  only `2.50247%` while reaching `39.23040%` realized drawdown. A new market
  that starts like this window would nearly exhaust the drawdown budget before
  the strategy compounds.
- Active-day coverage is only `14.58523%` over three years. Profit is
  concentrated in relatively few accepted events, so missing or degrading those
  events can materially change the result.
- The three-year side split is heavily SELL-dependent: SELL produced
  `9,675,656,485 KRW` net PnL with `3.79791` profit factor, while BUY produced
  `414,677,842 KRW` with only `1.17422` profit factor.
- The `RANGE` regime produced `-1,243,052,112 KRW` net PnL even though its
  average R expectancy is positive, showing path-dependence under compounding.
- The current candidate was tuned on this same three-year dataset. It needs a
  separate out-of-sample dataset before being treated as market-robust.

## Decision

The current strategy is a strong in-sample research candidate, not a verified
new-market strategy. It can proceed to paper/testnet parity work only with
strict drawdown controls and explicit monitoring, but it should not be treated
as live-ready until long-horizon ingestion and cross-market validation are
implemented.

Required next validation work:

1. Extend research-only history sync and backtest request limits so four-to-six
   years of BTCUSDT linear data can be collected and replayed where the venue
   has data.
2. Add explicit dataset coverage metadata to every backtest response so a
   four-to-ten-year request cannot be mistaken for a shorter available replay.
3. Run the same current config without retuning on at least ETHUSDT and SOLUSDT
   M1/M5/M15 data, then compare side, regime, drawdown, and active-day coverage.
4. Add anchored walk-forward validation where parameters are selected on an
   earlier window and scored only on the next unseen window.
5. Keep the current candidate out of live trading until paper/testnet confirms
   signal parity, slippage, funding, and order behavior.
