# Derivatives Flow Research Record - 2026-07-10

## Scope

This record evaluates whether point-in-time Bybit derivatives-flow data improves
the existing causal BTCUSDT strategy family. It is a research record, not a
live-trading performance claim.

The target remains unchanged:

- Compound daily return (CDR) at or above `0.8%`.
- Maximum mark-to-market drawdown at or below `40%`.
- Zero liquidation events.
- Positive, operationally meaningful coverage across every sealed random window.

All results include the existing fee, quantity, leverage, liquidation, and
causal fill constraints.

## Data Basis

The research database is a local clone populated from Bybit public history:

| Dataset | Coverage | Records |
|---|---|---:|
| BTCUSDT taker flow, M1 | 2020-03-25 to 2026-07-02 | 3,269,804 |
| Open interest, M5 | 2020-07-20 to 2026-07-02 | 621,701 |
| Account long/short ratio, M5 | 2020-07-20 to 2026-07-02 | 621,231 |
| Premium index, M15 | 2020-03-25 to 2026-07-02 | 219,889 |
| Funding | 2020-03-25 to 2026-07-02 | 6,871 |

Taker flow is aggregated from public trade side data. The backtest rejects a
flow condition when any required M1 bucket is missing or duplicated. Open
interest, premium index, and funding are selected only when available at or
before the decision time; funding allows an eight-hour staleness bound.

Official data references:

- https://bybit-exchange.github.io/docs/v5/market/recent-trade
- https://bybit-exchange.github.io/docs/v5/market/open-interest
- https://bybit-exchange.github.io/docs/v5/market/long-short-ratio
- https://bybit-exchange.github.io/docs/v5/market/premium-index-kline
- https://bybit-exchange.github.io/docs/v5/market/history-fund-rate

## Reproduced Causal Baseline

`config/volume-flow-composite-causal-unverified.json` reproduces the existing
causal baseline but does not clear the target.

| Window | Return | CDR | Trades | MTM MDD | Result |
|---|---:|---:|---:|---:|---|
| 2020-05-25 to 2023-12-31 development | +6.00795% | +0.00443% | 11 | 5.58725% | Fail target and coverage |
| 2024-01-01 to 2026-07-02 validation | +6.17026% | +0.00655% | 12 | 11.17322% | Fail target and coverage |

The development replay has 5 passing and 8 failing 180-day windows under a
non-negative-return, one-trade minimum. The validation replay has 3 passing
and 6 failing 180-day windows. Neither is a candidate for live expansion.

## Flow Hypotheses Rejected

The following comparisons were intentionally small and directional. They are
not an optimization sweep and are not used to change the deployed profile.

| Hypothesis | Window | CDR | Trades | MTM MDD | Decision |
|---|---|---:|---:|---:|---|
| Existing current M5 legs with aligned 5-minute taker imbalance >= 0.30 | 2020-07-20 to 2023-12-31 | +0.00696% | 33 | 31.48448% | Reject: no material edge |
| Existing current M5 legs with stronger or opposed imbalance | 2020-07-20 to 2023-12-31 | negative | 27-28 | about 31% | Reject |
| High-volume follow-through with no flow filter | 2020-07-20 to 2021-12-31 | -0.33491% | 197 | 83.44945% | Reject |
| High-volume follow-through with opposed imbalance >= 0.50 | 2020-07-20 to 2021-12-31 | -0.00585% | 7 | 9.79023% | Reject: insufficient coverage and negative return |
| Volume rejection with opposed imbalance >= 0.30 | 2020-07-20 to 2021-12-31 | -0.08958% | 21 | 42.08989% | Reject |
| Volume rejection with opposed imbalance >= 0.30 | 2022-01-01 to 2023-12-31 | -0.00649% | 3 | 10.19129% | Reject |
| Immediate flow-exhaustion reversal prototype | 2020-07-20 to 2021-12-31 | -0.34704% | 92 | 87.49096% | Reject and remove |
| Confirmed flow-exhaustion reversal prototype | 2022-01-01 to 2023-12-31 | -0.42227% | 240 | 96.53453% | Reject and remove |

The immediate and confirmed flow-exhaustion setup prototypes were removed from
the working tree after both independent development segments failed.

## Conditional Feature Diagnostic

`scripts/bybit-flow-feature-diagnostics.mjs` is a read-only diagnostic. It
uses completed M5 candles, complete five-minute taker-flow buckets, and only
open-interest observations available by the candle close. It reports the
after-cost 15-minute conditional return for both continuation and reversal; it
does not simulate entries, sizing, stops, or liquidation.

Reproduction:

```bash
node --no-warnings scripts/bybit-flow-feature-diagnostics.mjs \
  --db=/private/tmp/bybit-trader-diagnostics-20260710.sqlite \
  --start=2020-07-20T00:00:00Z \
  --end=2021-12-31T23:55:00Z \
  --horizon-m5=3 \
  --round-trip-cost-bps=12 \
  --min-samples=100
```

The top-ranked states in each independent development segment were still
negative after the 12 basis-point round-trip cost assumption:

| Segment | Dimension | Best state | Continuation | Reversal | Samples |
|---|---|---|---:|---:|---:|
| 2020-07-20 to 2021-12-31 | Flow only | aligned, low imbalance, volume surge | -0.18940% | -0.05060% | 1,928 |
| 2020-07-20 to 2021-12-31 | Flow and OI | aligned, low imbalance, volume surge, OI contracting | -0.21916% | -0.02084% | 835 |
| 2022-01-01 to 2023-12-31 | Flow only | aligned, medium imbalance, volume surge | -0.13968% | -0.10032% | 4,941 |
| 2022-01-01 to 2023-12-31 | Flow and OI | opposed, medium imbalance, normal volume, OI contracting | -0.06675% | -0.17325% | 231 |

Decision: do not turn the tested flow/OI states into runtime setup modes. A
new strategy hypothesis needs information not represented by this simple
feature set, such as a pre-specified transition-state model or independently
available order-book/crowding history.

## Account Ratio Diagnostic

The public account long/short ratio was added as a separate M5 crowding
dimension. Each decision uses only the latest ratio snapshot timestamped at
or before the completed M5 candle close. The diagnostic uses fixed, untuned
bands: `SHORT_HEAVY` at buy ratio `<= 0.45`, `LONG_HEAVY` at `>= 0.55`, and
`BALANCED` otherwise. It evaluates the same flow, relative-volume, and
open-interest states for both continuation and reversal after a 12-basis-point
round-trip cost.

This is still a conditional-return diagnostic, not a backtest. It does not
model fills, sizing, stops, funding, liquidation, or compounded return.

| Holding time | 2020-2021 eligible M5 bars | 2022-2023 eligible M5 bars | Common positive states | Best shared worst-period mean after cost |
|---|---:|---:|---:|---:|
| 5 minutes | 139,358 | 207,444 | 0 | -0.09171% |
| 15 minutes | 139,356 | 207,442 | 0 | -0.07483% |
| 30 minutes | 139,353 | 207,439 | 0 | -0.08018% |
| 60 minutes | 139,347 | 207,433 | 0 | -0.06245% |

Only 25 otherwise eligible early-period candles lacked a prior account-ratio
snapshot; the later period had none. Therefore the rejection is not a coverage
artifact. No common state had a positive mean net return in both chronological
segments at any tested holding time.

Decision: reject account-ratio crowding as an additional filter for the current
volume-flow strategy family. It must not be added to a live or backtest profile
solely because it is now available in storage.

Reproduce each horizon by changing `--horizon-m5` to `1`, `2`, `3`, `6`,
`12`, `24`, `48`, `96`, or `288`:

```bash
node --no-warnings scripts/bybit-flow-feature-diagnostics.mjs \
  --db=/private/tmp/bybit-trader-diagnostics-20260710.sqlite \
  --start=2020-07-20T00:00:00Z \
  --end=2021-12-31T23:55:00Z \
  --horizon-m5=3 \
  --round-trip-cost-bps=12 \
  --min-samples=100
```

## Funding-Conditioned Holding Diagnostic

The same point-in-time diagnostic now adds the latest completed funding-rate
snapshot with a maximum age of eight hours. Funding bands are fixed before
evaluation: `NEGATIVE` at `<= -0.0001`, `POSITIVE` at `>= 0.0001`, and
`NEUTRAL` otherwise. These are the rate units returned by the public Bybit
funding-history endpoint, not a data-derived percentile split.

For holding times from 5 minutes through 4 hours, no flow/OI/account/funding
state had a positive after-cost mean in both 2020-2021 and 2022-2023. At eight
hours, three states were positive in both development segments; at 24 hours,
nine were. All were sent unchanged to the 2024-01-01 through 2026-07-01
holdout before any strategy implementation.

| Holding time | Development-positive states | 2024-2026 holdout result |
|---|---:|---|
| 8 hours | 3 | All reversed: `-0.13421%`, `-0.33855%`, and `-0.16963%` mean net return; each had at least 135 holdout samples. |
| 24 hours | 9 | No state had both positive mean net return and 100 holdout samples. States with sufficient samples reversed, including `-0.27949%` and `-0.28851%`. |

The most promising development-only 24-hour state had 300 and 351 observations
with `+0.42198%` and `+0.16735%` mean net returns, but it had no matching
holdout observations because the negative-funding and balanced-account regime
did not recur. It therefore fails the coverage requirement rather than being
treated as a dormant edge.

Decision: reject funding-conditioned states for runtime use. This is a
regime-stability failure, not a transaction-cost or missing-data artifact:
funding data was available for every eligible candle in all three periods.

Reproduction additionally requires `--funding-staleness-minutes=480`.

## Raw Absorption Simulator Correction

The previously reported raw M5 absorption candidate,
`absa_final_us_v1`, was audited because its reported CDR conflicted with the
causal Kotlin engine. The root cause was confirmed: the simulator selected a
breakout from a confirmation candle close but filled at that same candle's
earlier open and allowed that candle's high/low to resolve the exit.

The simulator now waits for the confirmation candle to close, fills at the
next M5 open, and begins exit evaluation from that fill candle. A regression
fixture asserts that a `19:05` confirmation fills at `19:10`, not `19:05`.

The candidate was then replayed against the existing fixed 20-window holdout,
without retuning:

| Candidate | Windows at or above 0.8% CDR | Worst CDR | Median CDR | Maximum drawdown |
|---|---:|---:|---:|---:|
| `absa_final_us_v1` after causal fill correction | 0 / 20 | -7.87551% | -4.63456% | 100.0% |

Decision: invalidate the historical raw-simulator 0.8% claim. The profile is
not eligible for deployment, and no subsequent candidate selection may use its
pre-correction output as evidence.

## Aggressive Profile Check

The `absa_final_us_v1` aggressive profile remains `UNVERIFIED`. Under the
current causal fill model it is not a fallback candidate:

| Window | CDR | Trades | Maximum drawdown |
|---|---:|---:|---:|
| 2020-07-20 to 2021-12-31 | -3.58219% | 1,048 | 100.0% |
| 2022-01-01 to 2023-12-31 | -3.79114% | 1,388 | 100.0% |

No flow integration is justified for this profile until it has a viable causal
baseline.

## Decision

No candidate passes the `0.8%` CDR, drawdown, liquidation, and coverage gates.
Do not promote a flow-filtered configuration or increase live exposure from
this work. The historical data foundation is retained because it supports
future causal research; failed signal hypotheses are not retained as runtime
strategy modes.

## Next Research Gate

The next strategy family must be specified before parameter selection and must
be evaluated as follows:

1. Develop on pre-2024 data only, with at least two chronological development
   folds rather than one combined score.
2. Freeze all feature definitions, thresholds, and risk rules before scoring
   2024 onward data.
3. Score the frozen candidate on 20 to 40 new random windows with durations
   from one to sixty months. Do not use those scores for tuning.
4. Promote only when every scored window meets the target, has no liquidation,
   stays within the drawdown gate, and has meaningful trade coverage.
5. Keep `BOT_EXECUTION_MAX_NOTIONAL` in place until the promotion gate passes
   and forward observation confirms order and alert parity.

## Forward Data Collection Gate

Historical Bybit REST data does not contain the event-level order-book history
needed to test book imbalance causally. The runtime now has a separate,
default-off collection path for future data only:

- `orderbook.{depth}.BTCUSDT` produces top-of-book depth snapshots that are
  aggregated into one-minute imbalance and spread bars.
- `allLiquidation.BTCUSDT` produces liquidation events that are aggregated into
  one-minute long/short liquidation notionals and counts.
- It has no dependency on the strategy evaluator or exchange execution
  service. Enabling it cannot submit an order or alter a signal.
- The dashboard marks collection as verified only when a completed order-book
  bar has arrived within the last three minutes. A missing liquidation bar is
  normal when no liquidation event occurred.

Source notes: Bybit documents REST order book as a snapshot and the public
WebSocket feed as snapshot-plus-delta. It also documents the all-liquidation
stream and the `Buy`/`Sell` liquidation-side mapping. See
[order book REST](https://bybit-exchange.github.io/docs/v5/market/orderbook),
[order book WebSocket](https://bybit-exchange.github.io/docs/v5/websocket/public/orderbook),
and [all liquidation WebSocket](https://bybit-exchange.github.io/docs/v5/websocket/public/all-liquidation).

This data must accumulate before any feature design or threshold tuning. The
first research pass must freeze all feature definitions, use only bars that
were available after their minute closed, and keep a final untouched time
segment for evaluation.

Use the forward-data diagnostic only after selecting a completed observation
window. It aggregates five completed M1 order-book bars, enters at the next M5
open, and reports descriptive after-cost outcomes by fixed order-book and
liquidation bands. It does not submit orders, choose thresholds from the
observed data, or declare a strategy pass:

```bash
node --test scripts/bybit-forward-capture-diagnostics.test.mjs
node scripts/bybit-forward-capture-diagnostics.mjs \
  --db=/path/to/bybit-trader.sqlite \
  --symbol=BTCUSDT \
  --start=2026-08-01T00:00:00Z \
  --end=2026-10-31T23:55:00Z \
  --horizons-m5=1,3,12 \
  --round-trip-cost-bps=12 \
  --min-samples=100 \
  --out=build/research/forward-capture-diagnostic.json
```
