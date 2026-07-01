# Volume Flow Expectancy Strategy

Source note: this document records the strategy gate for local backtests. It is
not a live-trading return guarantee.

## Principle

The volume-flow bot should not optimize for a high win rate. A futures strategy
can compound with a win rate below `50%` when losses are small, winners are
larger, and position sizing survives normal losing streaks.

The primary question for every candidate is:

```text
expectancyR = winRate * averageWinR - lossRate * averageLossR
```

When `winRate` is `40%`, the raw payoff ratio must be greater than `1.5` before
costs. Because the backtest return R includes fees and slippage, accepted
candidates should show a positive edge after costs:

```text
payoffRatio = averageWinR / averageLossR
breakevenWinRate = 1 / (1 + payoffRatio)
winRateEdge = actualWinRate - breakevenWinRate
```

## Candidate Gate

A volume-flow candidate may have low win rate, but it must pass these gates:

- `netReturnPct > 0`
- `expectancyR > 0`
- `winRateEdgePct >= 0`
- `maxDrawdownPct <= 10`
- `compoundDailyReturnPct` improves without relying on one or two isolated
  trades
- monthly and walk-forward results do not concentrate all return in one regime

`profitFactor` remains useful context, but it is no longer the main adoption
gate. A low win-rate strategy with a strong payoff edge should rank above a
high win-rate strategy that has weak asymmetric payoff and poor compounding.

## Execution Bias

The next tuning loop should favor setups with cheap invalidation and asymmetric
upside:

- Volume expansion at a level, not random candle movement.
- Entry close enough to invalidation that one failed idea loses about `1R`.
- Target or runner logic that allows winners to exceed average losses.
- Breakeven or trailing behavior only after the trade proves momentum; moving
  to breakeven too early can destroy the payoff edge.
- Daily and consecutive-loss locks remain active so compounding does not depend
  on revenge trading after a bad sequence.

## Backtest Fields

Volume-flow API responses expose these low-win-rate strategy metrics:

- `averageWinR`
- `averageLossR`
- `payoffRatio`
- `breakevenWinRatePct`
- `winRateEdgePct`

These fields are returned on single-leg, composite, tag, period, and sweep
summary responses so each leg and market regime can be judged by asymmetric
expectancy rather than win rate alone.
