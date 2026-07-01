# Volume Flow Target Plan

Source note: this plan is based on local BTCUSDT backtests against
`build/runtime-test/bybit-trader-1y-backtest.sqlite`. It is not a live-trading
return guarantee.

## Problem

The current composite candidate has positive expectancy and payoff edge, but it
does not yet compound fast enough. The bottleneck is sparse opportunity
coverage: `55` active days over `366` observed days, or about `15.03%` active
day coverage.

## Goals

- Phase 1 target: `compoundDailyReturnPct >= 0.35`, `maxDrawdownPct <= 6`,
  `activeDayCoveragePct >= 20`, `winRateEdgePct > 0`, and
  `maxConsecutiveLosses <= 4`.
- Phase 2 target: `compoundDailyReturnPct >= 0.50`, `maxDrawdownPct <= 8`,
  `activeDayCoveragePct >= 25`, `winRateEdgePct > 0`, and all walk-forward
  windows positive.
- Final readiness target: paper execution confirms the same direction of edge
  after fees, slippage, missed fills, alerts, and control pause/resume behavior.

## Non-Goals

- Do not force trades every day.
- Do not raise `riskFraction` only to make the compound return look better.
- Do not accept candidates that depend on one or two isolated large wins.
- Do not optimize for win rate alone.

## Requirements

- Sweep ranking must reward compounding, active-day coverage, positive
  expectancy, positive payoff edge, and controlled drawdown.
- Frequency gate must prevent overtrading without requiring minimum trades on
  every observed day.
- Candidate reports must expose `activeDayCoveragePct` so sparse strategies are
  easy to reject or combine with complementary legs.
- New legs should primarily cover currently inactive days or regimes with weak
  coverage, not duplicate the same `TREND_DOWN` opportunity set.

## Risks

- Increasing active-day coverage can add noisy trades and reduce payoff edge.
- More concurrent positions can hide correlation risk inside a strong total
  return.
- Low sample-size legs with no losses need walk-forward and multi-period
  validation before adoption.

## Next Implementation Steps

1. Add active-day coverage metrics to single, composite, and sweep responses.
2. Change sweep frequency gate from forced average daily trading to active-day
   coverage plus overtrading control.
3. Run sweeps with `minActiveDayCoveragePct=20` to search for complementary
   setups.
4. Reject candidates that raise coverage but lower `winRateEdgePct` below zero
   or push drawdown above the phase target.
