# Multi-Horizon Momentum Experiment Plan

Date: 2026-07-27

## Problem

The causal runtime profile is rejected and the previously tested candle-only
absorption, breakout, pullback, and single-channel macro trend candidates did
not retain positive after-cost expectancy across independent periods. Repeating
their thresholds would create another multiple-testing search over consumed
evidence.

The durable database still contains complete BTCUSDT M1, M5, and M15 candles
from March 2020 through July 2026. That data is sufficient to test another
candle-only hypothesis without waiting for forward order-book capture.

## Goal

Test one bounded, predeclared time-series momentum hypothesis on the four
pre-2024 development folds. The experiment asks whether agreement across
one-day, seven-day, and thirty-day price momentum can produce a positive,
cost-aware trend strategy without using event-level market data.

## Non-goals

- Do not enable paper, testnet, or live automatic execution.
- Do not change the rejected runtime profile.
- Do not inspect post-2024 results when the development gate fails.
- Do not expand the candidate grid after seeing a failed result.
- Do not claim that a feature-discovery result is a deployable backtest.

## Hypothesis

For each completed M5 candle:

1. Calculate trailing returns over 288, 2,016, and 8,640 M5 candles.
2. Cast a positive, negative, or neutral vote for each horizon using its fixed
   return threshold.
3. Require two or three directional votes.
4. Require the 288/1,152 EMA regime and the 288 EMA slope to agree.
5. Trigger only when the consensus direction changes.
6. Enter at the next contiguous M5 open with adverse slippage.
7. Use an ATR initial stop, ATR trailing stop, and fourteen-day time exit.

This differs from the consumed Donchian candidate: it does not require a
single price-channel breakout and it combines returns from three independent
horizons before entering.

## Candidate Boundary

The complete candidate space is fixed in
`config/multi-horizon-momentum-development-v1.json` and contains at most 108
combinations:

- threshold scale: `0.75`, `1.0`, `1.25`
- minimum consensus votes: `2`, `3`
- initial stop: `4`, `8` ATR
- trailing distance: `8`, `16`, `24` ATR
- side mode: both, long only, short only

Risk is fixed at one percent per trade. The simulator charges 0.06 percent on
entry and exit and 0.02 percent adverse slippage on entry and exit.

## Success Metrics

Every D01-D04 development fold must meet all of these conditions:

- compound daily return at least `0.2%`
- maximum drawdown at most `40%`
- at least three closed trades
- active-day coverage at least `2%`

Passing development only permits a Kotlin causal-engine implementation. It
does not permit automatic execution or live promotion.

## User Story

As the strategy operator, I need a bounded candle-only experiment so that the
existing six-year history can be used immediately without treating forward raw
capture as a blocker.

Acceptance criteria:

- the candidate list is deterministic and limited to 108 entries
- every signal uses only completed candles
- every fill occurs after its decision candle
- all four development folds are reported separately
- a failed gate is documented without searching the consumed post-2024 period
- the rejected runtime profile and deployment environment remain unchanged

## Risks

- Multiple candidates can still create selection bias; the fixed grid and
  all-fold gate limit but do not remove that risk.
- M5 OHLCV cannot represent spread variation, partial fills, funding, or
  order-book adverse selection.
- A development pass may fail in future data because crypto regimes change.
- The `0.2%` compound-daily target is a business gate, not a guaranteed outcome.

## Decision

Implement the research profile and run only D01-D04. Port a candidate to the
shared Kotlin engine only when one candidate passes every development gate.
Otherwise record the family as rejected and move to a genuinely independent
hypothesis or historical event-data acquisition.
