# Causal Strategy Revalidation Report

Date: 2026-07-10

Market: Bybit BTCUSDT linear perpetual

Capital model: 100 USDT, 0.001 BTC quantity step/minimum, 15x maximum leverage

Status: `UNVERIFIED`

## Problem

Previous profitability reports could not be used as evidence for live expansion. The legacy replays mixed signal-candle information with earlier fill timestamps, admitted unclosed higher-timeframe candles into context, skipped the actual entry minute during exit simulation, and did not consistently cap total notional by account leverage.

The objective of this pass was to remove those defects before changing strategy parameters. The 0.8% compound daily return target remains aspirational. Sustaining 0.8% every day for 365 days implies an 18.33x balance, or about 1,732.71% annual return, so it is not a release claim without independent forward evidence.

## Corrections

- Signals use only candles closed by the decision timestamp.
- M1 confirmation candles are used only for the decision; fills occur at the next contiguous M1 open.
- The entry M1 candle is included in stop/target evaluation, with stop-first ordering when both are touched.
- Gap exits use the candle open instead of an unreachable stop price.
- Replay warmup is loaded before a requested window but excluded from trades and reported coverage.
- Quantity step, minimum quantity, maximum quantity/notional, leverage, and liquidation buffer use the shared execution sizing rules.
- Concurrent positions share one aggregate leverage allowance.
- Liquidations and the fill-model version are explicit report fields.

Regression tests cover unclosed M15 context, missing next-M1 data, entry-minute stop/target ambiguity, zero-sized orders, leverage caps, liquidation gaps, and warmup isolation.

## Results

### Corrected legacy composite

Window: 2023-07-02 to 2026-07-02

| Metric | Result |
|---|---:|
| Initial/final equity | 100.00 / 88.13 USDT |
| Net return | -11.87% |
| Compound daily return | -0.01151% |
| Trades | 31 |
| Win rate | 48.39% |
| Profit factor | 0.787 |
| Mark-to-market MDD | 27.05% |
| Liquidations | 0 |

The old composite configuration is not profitable after causal and capital corrections.

### Development and later validation

The best development-only candidate combines two downside continuation legs:

- M5 breakout with only the first M1 retest confirmation.
- M1 breakout with at most two M1 confirmations.

It is stored as `config/volume-flow-composite-causal-unverified.json`; it is not the current or live profile.

| Window | Net return | CDR | MTM MDD | Trades | Profit factor | Expectancy |
|---|---:|---:|---:|---:|---:|---:|
| 2020-05-25 to 2023-12-31 development | +6.01% | +0.00443% | 5.59% | 11 | 2.186 | +0.146R |
| 2024-01-01 to 2026-07-02 validation | +6.17% | +0.00655% | 11.17% | 12 | 1.564 | +0.191R |

These aggregate results are positive, but several walk-forward subperiods remain negative and trade coverage is about 0.8% to 1.3% of days.

### Sealed deterministic random windows

Method: seed `20260710`, 20 windows, 1 to 60 months. The candidate was fixed before this run and was not tuned afterward.

| Metric | Result |
|---|---:|
| Windows at or above 0.8% CDR | 0 / 20 |
| Median CDR | +0.00221% |
| Average CDR | +0.00398% |
| Worst CDR | -0.02962% |
| Worst window net return | -10.13% |
| Maximum MDD | 11.74% |
| Windows with no trades | 4 / 20 |
| Average active-day coverage | 0.88% |

The candidate fails the profitability and coverage goals. Lowering the volume threshold from 3.0 to 2.5 changed development expectancy from +0.158R to -0.143R. Broad M1 continuation/reversal variants lost 84% to 96%. Further OHLCV threshold tuning would be overfitting rather than recovery.

## Gate Decision

| Gate | Status |
|---|---|
| No future data in signal/context/fill | Pass |
| Exchange-compatible sizing | Pass |
| Liquidations equal zero | Pass for evaluated candidate |
| MDD at or below 40% | Pass |
| Positive after-cost expectancy in every independent window | Fail |
| 0.8% CDR in random windows | Fail, 0/20 |
| Operational trade coverage | Fail |

Decision: keep live expansion blocked and keep both the legacy and causal candidate marked `UNVERIFIED`.

## Required Data Expansion

OHLCV volume records activity but not aggressor direction. The next strategy family requires point-in-time derivatives flow data:

1. Taker-buy and taker-sell quantity aggregated from public trades into M1/M5 buckets.
2. Open interest and open-interest change at 5m/15m resolution.
3. Funding and premium-index history.
4. Long/short account ratio as a secondary crowding feature.
5. Order-book imbalance only where historical coverage and storage cost are acceptable.

Bybit exposes taker side in public trades and publishes archived public trade files. Its V5 API also exposes historical open interest, funding, and long/short ratios. Sources checked on 2026-07-10:

- https://bybit-exchange.github.io/docs/v5/market/recent-trade
- https://bybit-exchange.github.io/docs/v5/market/open-interest
- https://bybit-exchange.github.io/docs/api-explorer/v5/market/long-short-ratio
- https://www.bybit.com/derivatives/kk-KZ/history-data

The next implementation must preserve raw event time, aggregate without forward filling directional fields, report coverage gaps, and lock a new holdout before tuning. No new live strategy should be enabled from these features until the same causal, cost, random-window, and forward-observation gates pass.
