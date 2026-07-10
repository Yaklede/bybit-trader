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
| Premium index, M15 | 2020-03-25 to 2026-07-02 | 219,889 |
| Funding | 2020-03-25 to 2026-07-02 | 6,871 |

Taker flow is aggregated from public trade side data. The backtest rejects a
flow condition when any required M1 bucket is missing or duplicated. Open
interest, premium index, and funding are selected only when available at or
before the decision time; funding allows an eight-hour staleness bound.

Official data references:

- https://bybit-exchange.github.io/docs/v5/market/recent-trade
- https://bybit-exchange.github.io/docs/v5/market/open-interest
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
