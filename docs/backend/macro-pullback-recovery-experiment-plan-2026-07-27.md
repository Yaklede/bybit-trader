# Macro Pullback Recovery Experiment Plan

Date: 2026-07-27

## Decision Boundary

This document freezes the hypothesis and parameter grid before any result is
calculated. The experiment may use only the four development folds in
`config/volume-flow-development-folds-v3.json`. It must not inspect candles on
or after `2024-01-01T00:00:00Z` unless every development gate passes.

The experiment is research only. It cannot change the Kotlin runtime, paper
loop, automatic execution profile, deployment environment, maximum notional,
or leverage.

## Problem

The rejected aggressive strategy overtrades short-lived M5 volume breakouts.
The latest multi-horizon momentum experiment found a positive long-side edge,
but it stayed far below the `0.2%` compound-daily target and failed one fold
under double-cost stress.

The next experiment must not expand that momentum grid. It tests a different
entry event: recovery after a material countertrend shock inside an intact
macro regime.

## Hypothesis

BTC can undergo a multi-day countertrend move without invalidating its broader
regime. Once a shorter recovery horizon turns back toward the macro direction,
the recovery may offer a better entry location than entering continuous
momentum.

The causal long rule is:

1. The completed 30-day return is above the candidate regime threshold.
2. The completed 3-day return is below the negative countertrend threshold.
3. The completed 12-hour return is at least `+1%`.
4. The 4-day EMA slope is positive.
5. The complete long direction was not present on the preceding M5 candle.

The short rule is symmetric. Both rules use only completed M5 candles. A signal
on candle `t` may fill only at the open of contiguous candle `t+1`.

This differs from the previous short-horizon trend-pullback family, which used
EMA20/EMA50 alignment and a local confirmation candle. It also differs from the
multi-horizon momentum family, which entered continuous agreement rather than
a recovery after an opposing three-day move.

## Fixed Contract

| Parameter | Value |
|---|---:|
| Regime lookback | `8,640` M5 candles / 30 days |
| Countertrend lookback | `864` M5 candles / 3 days |
| Recovery lookback | `144` M5 candles / 12 hours |
| Recovery threshold | `1%` |
| Slow EMA | `1,152` M5 candles / 4 days |
| EMA slope lookback | `288` M5 candles / 1 day |
| Risk per trade | `1%` of current equity |
| Maximum holding period | `4,032` M5 candles / 14 days |
| Maximum entries | `1` per UTC day |
| Fee | `0.06%` per fill |
| Entry slippage | `0.02%` |
| Exit slippage | `0.02%` |

The candidate grid is exactly:

```text
regime threshold:       5%, 10%
countertrend threshold: 3%, 6%
initial stop:           8 ATR, 16 ATR
trailing stop:          16 ATR, 24 ATR
side mode:              BOTH, LONG_ONLY, SHORT_ONLY
```

The Cartesian product contains exactly `48` candidates. No additional value
may be introduced after reading the results.

## Development Gates

Every one of D01-D04 must satisfy:

| Gate | Required value |
|---|---:|
| Compound-daily return | at least `0.2%` |
| Maximum drawdown | at most `40%` |
| Trades | at least `3` |
| Active-day coverage | at least `2%` |

Any candidate passing the base gate must be replayed unchanged with fees and
slippage multiplied by `1.5` and `2.0`.

## Decision Policy

- Zero candidates pass: mark the family `REJECTED`, do not expand the grid,
  do not inspect post-2024 data, and do not port it to Kotlin.
- A candidate passes base costs but fails stress: retain it only as research
  evidence.
- A candidate passes all gates and stress: freeze the exact candidate before
  creating a new post-2024 sealed protocol.
- Passing never enables automatic promotion or live execution.

## Reproduction Target

The implementation must expose:

```text
--profile macro-pullback-recovery
```

It must add regression tests for the exact candidate count, unique candidate
IDs, causal next-candle fill, and cost multiplication without grid expansion.
