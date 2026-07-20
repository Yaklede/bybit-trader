# Volume Flow 0.2% Target Reassessment - 2026-07-13

## Decision

The compound-daily research target is now `0.2%`. No runtime strategy is
promoted by this change. The current automatic execution profile remains
`absa_final_us_v1` and remains `UNVERIFIED`.

`config/aggressive-runtime-replay-contract-v2.json` is the frozen runtime audit
contract for this target. It retains the `aggressive-runtime-v1` strategy and
execution assumptions while changing only the evaluation CDR gate from `0.8%`
to `0.2%`.

The old `0.8%` sealed records are immutable historical evidence. Development
for the new target uses `config/volume-flow-development-folds-v3.json`, which
allows parameter selection before 2024 but does not allow deployment or live
exposure expansion.

## Runtime Baseline

The causal 40-window runtime audit of `absa_final_us_v1` remains the operative
baseline:

| Metric | Result |
|---|---:|
| Passed windows at the old gate | `0 / 40` |
| Median CDR | `-0.36437%` |
| Mean CDR | `-0.38433%` |
| Best CDR | `+0.00861%` |
| Median MDD | `96.23%` |
| Maximum MDD | `99.09%` |

Lowering the target to `0.2%` does not make this profile viable because its
after-cost expectancy is negative and its drawdown is near total loss.

The V2 audit reran all 40 sealed windows after runtime-contract parity was
implemented. Thirty-nine windows returned a replay and the earliest window
still failed its pre-window warmup requirement. There were zero profile,
strategy-contract, runtime-signal, or execution-fingerprint mismatches.

| V2 audit metric | Result |
|---|---:|
| Passed windows | `0 / 40` |
| Valid replay responses | `39 / 40` |
| CDR below `0.2%` | `39 / 39` |
| MDD above `40%` | `31 / 39` |
| CDR minimum / median / maximum | `-0.81833% / -0.36437% / +0.00861%` |
| MDD median / maximum | `96.23% / 99.09%` |
| Contract mismatch windows | `0` |

## Development Hypotheses

All experiments below use completed candles, causal next-candle entry, the
existing fee assumption, and only the four development folds ending before
2024. They are feature-discovery results, not deployable runtime backtests.

### Existing aggressive entry modes

The live-profile absorption breakout, strict breakout retest, and confirmed
failed-break reversal were replayed through the Kotlin aggressive endpoint.
Every candidate lost in every development fold. The strict retest reduced
overtrading, but its CDR still ranged from `-0.02782%` to `-0.12980%`.

### Trend pullback acceptance

This hypothesis uses short/medium EMA alignment, trend efficiency, a
lower-volume pullback, and a directional confirmation candle. The broad sweep
produced meaningful coverage but remained negative in every fold. The best
focused candidate was long-only with a `5R` target:

| Fold | CDR | Trades | Profit factor | MDD |
|---|---:|---:|---:|---:|
| D01 | `-0.00512%` | 67 | `0.96864` | `16.16%` |
| D02 | `-0.00485%` | 49 | `0.95636` | `12.13%` |
| D03 | `+0.03654%` | 37 | `1.50838` | `10.09%` |
| D04 | `-0.00566%` | 23 | `0.93397` | `9.83%` |

The result is close to break-even in three folds but does not provide a stable
positive edge and is far below the target.

### Macro trend breakout

This hypothesis uses one-day and four-day EMA alignment, a 12-hour to two-day
channel breakout, and multi-day ATR trailing exits. The best candidate was
long-only and still lost in all folds:

| Fold | CDR | Trades | Profit factor | MDD |
|---|---:|---:|---:|---:|
| D01 | `-0.01060%` | 145 | `0.86728` | `11.89%` |
| D02 | `-0.00030%` | 86 | `0.99500` | `8.47%` |
| D03 | `-0.00539%` | 69 | `0.89787` | `5.21%` |
| D04 | `-0.01760%` | 45 | `0.69311` | `7.17%` |

## Interpretation

Increasing risk cannot repair these candidates because their pre-scaling
expectancy is not positive across the development folds. It would only amplify
loss and drawdown. The next candidate needs a new information source rather
than another OHLCV threshold sweep.

The durable six-year database contains complete M1/M5/M15 candles but no
durable taker-flow, order-book, or liquidation-flow history. Earlier simple
taker imbalance, open-interest, account-ratio, premium, and funding filters
were also rejected, so merely restoring those fields is not a sufficient
strategy hypothesis. The remaining research direction is a predeclared market
transition model using independently available order-book or cross-venue flow
data, followed by a new untouched holdout.

## Promotion Gate

A future candidate may be frozen for sealed evaluation only when all four
development folds meet the V3 gates. Sealed evaluation must then use new random
windows that were not used for feature or parameter selection. No existing
live setting, maximum notional, leverage, or deployment environment is changed
by this research record.

## Runtime Contract Parity

The runtime profile, aggressive backtest defaults, and strategy-profile API now
read the same `aggressive-runtime-v1` contract. A local Ktor smoke run using the
frozen 100 USDT assumptions returned the same execution fingerprint from
`GET /strategy/profiles` and `POST /backtests/volume-flow/aggressive/current/run`:
`c8d1a10ca516e78df918d0c72d9af9fb02c08ca3ce3c162531078cacfca604a7`.

The default backtest response identified `absa_final_us_v1`, reported
`runtimeSignalProfileMatched=true`, and used initial equity `100`, risk
fraction `0.055`, quantity step/minimum `0.001`, maximum notional `100`, and
leverage `15`. Replaying the same request with only `targetR` changed returned
`absa_final_us_v1-research-override` and
`runtimeSignalProfileMatched=false`. This prevents research parameter overrides
from being reported as evidence for the automatic execution profile.

## Cross-Venue Flow Result

Binance USD-M BTCUSDT 5-minute archives from June 2020 through December
2023 were downloaded with their published SHA-256 checksums. The causal
features included taker imbalance, relative quote volume, and 3/6/12/24/36
candle signed flow. These inputs were joined only on exact candle timestamps.

The 72 flow-continuation candidates all lost in all four development folds.
The best candidate had median CDR `-0.20747%` and maximum MDD `56.58%`.
Flow-absorption reversal candidates reduced risk but became unusably sparse;
the best positive-average result made no trades in D03 and only one trade in
D04. Cross-venue taker flow is therefore rejected as a standalone entry
signal.

## Macro Donchian Candidate

The earlier macro experiment used channels no longer than two days. A new
candidate uses a five-day (`1440` M5 candle) Donchian breakout, one/four-day
EMA alignment, a one-day structural stop, a `16 ATR` minimum stop, and a
`48 ATR` trailing exit. It allows both sides, at most one new trade per day,
and a 60-day maximum hold.

The feature-discovery simulator was positive in all four development folds.
The candidate was then implemented in the Kotlin aggressive engine so that
quantity steps, minimum quantity, maximum notional, leverage rejection,
fees, entry/exit slippage, M1 execution paths, liquidation checks, and replay
boundary exits use the same machinery as the runtime strategy.

| Development fold | CDR, max notional 100 | CDR, scalable notional | MDD, scalable | Liquidations |
|---|---:|---:|---:|---:|
| D01 | `0.06464%` | `0.15205%` | `15.58%` | `0` |
| D02 | `0.02278%` | `0.02672%` | `15.03%` | `0` |
| D03 | `0.04723%` | `0.16568%` | `14.34%` | `0` |
| D04 | `0.08064%` | `0.17203%` | `11.50%` | `0` |

The fixed `100` maximum notional suppresses compounding, but removing that
temporary cap still does not meet `0.2%` in any development fold.

## New Sealed Holdout

The existing random protocol had already been consumed by earlier tuning and
was not reused as untouched evidence. The candidate was frozen in
`config/aggressive-macro-donchian-candidate-v1.json` before generating the
post-2024 protocol in `config/macro-donchian-sealed-windows-v1.json`. Its 40
windows cover 1 to 30 months, use seed `20260713`, and have hash
`efd913efa476d5d8cedbc0208cbb767b512a0e52e06db9716c17f295d66df516`.

| Sealed metric | Result |
|---|---:|
| Passed windows | `1 / 40` |
| CDR minimum / median / maximum | `-0.39178% / -0.00390% / +0.87712%` |
| Mean CDR | `-0.00325%` |
| Negative windows | `25 / 40` |
| Median / maximum MDD | `17.58% / 39.19%` |
| Median trade count | `18` |
| Liquidation failures | `0` |

After the replay-boundary fix, the 30-month 2024-01 through 2026-07 window
returned `+10.97%`, equivalent to CDR `0.01141%`, with MDD `37.60%`. Long
trades earned `+33.00` USDT while short trades lost `-22.03` USDT. Long-only
improved the same diagnostic period to `+40.01%` and CDR `0.03691%`, which
remains far below the target.

The macro candidate is rejected and remains `UNVERIFIED`. These holdout
results must now be treated as consumed development evidence; changing the
candidate and rerunning the same windows cannot create new sealed proof.

## Public Order-Book Depth Result

The official Binance COIN-M `BTCUSD_PERP` daily `bookDepth` archives were
evaluated as an additional development-only information source. The dataset
contains cumulative notional at one through five percent from the reference
price every 30 seconds. It does not contain top-of-book prices, order IDs,
queue position, or individual add/cancel events and therefore cannot validate
maker fills.

For 2023, `364` daily archives were checksum-verified. Binance did not publish
the `2023-09-25` archive. Complete five-minute buckets covered `97.62%` of the
year. Three predeclared hypothesis grids were screened on four quarterly
development folds:

| Hypothesis | Candidates | Passed folds | Best median CDR | Best fold CDRs |
|---|---:|---:|---:|---|
| Static imbalance continuation | `72` | `0 / 4` | `0.00341%` | `0.00682%, 0%, 0%, 0.01109%` |
| Displayed-depth failure | `72` | `0 / 4` | `0%` | `0.01080%, 0%, 0%, -0.01250%` |
| Intrabar imbalance shift | `72` | `0 / 4` | `0.00344%` | `0.00452%, 0%, 0.04210%, 0.00236%` |

The strongest rows were too sparse to support the target. Lower thresholds
increased activity but turned expectancy negative. No depth candidate was
ported to the runtime engine or exposed to sealed data.

## Open-Interest Result

The official Binance USD-M daily `metrics` archives from 2021 through 2023
were then checksum-verified. All `1,095` daily files were available. Parsing
preserves open-interest rows when optional trader-ratio fields are absent,
resulting in `314,889` five-minute rows. Thresholds were fixed from the 75th
and 90th percentiles of 15-minute, one-hour, and three-hour absolute OI change;
taker thresholds used the 50th, 75th, and 90th percentiles.

Six half-year development folds were used. Post-2024 data was not downloaded
or inspected because no candidate passed development:

| Hypothesis | Candidates | Passed folds | Worst / median CDR | Maximum MDD |
|---|---:|---:|---:|---:|
| OI build + metrics taker continuation | `72` | `1 / 6` | `-0.33909% / -0.10176%` | `51.63%` |
| OI build + kline taker, continue/fade | `144` | `1 / 6` | `-0.69446% / -0.17656%` | `75.53%` |
| OI unwind + kline taker, continue/fade | `144` | `0 / 6` | `-0.01850% / -0.00527%` | `8.34%` |

The isolated passing build-flow folds were regime-specific and reversed
sharply in adjacent folds. The best unwind candidate was materially more
stable but remained negative in four of six folds and was more than an order
of magnitude below `0.2%` in its positive folds.

## Current Research Boundary

No tested candidate has demonstrated a repeatable `0.2%` compound-daily edge.
Increasing leverage or risk cannot repair negative pre-scaling expectancy and
would violate the liquidation and MDD gates. Further threshold sweeps over the
same OHLCV, five-minute taker, OI, and cumulative-depth inputs are classified
as overfitting and are not permitted as promotion evidence.

The next independent experiment requires one of these data contracts:

1. Historical event-level L2 order-book updates plus trades and liquidation
   events from a licensed source, with exchange timestamps and sequence gaps.
2. A forward collector that stores the same public streams continuously long
   enough to create development and untouched holdout periods.

Either path must support queue-aware maker fill simulation, latency, partial
fills, adverse selection, and funding before a maker-assisted candidate can be
compared with the current taker-only contract. Until that dataset exists, the
`0.2%` objective is blocked by missing independent evidence, not by an
unsearched parameter range.

Source note: archive structure and checksum requirements follow the official
[Binance public-data repository](https://github.com/binance/binance-public-data).
