# Multi-asset carry 2025 external result

## Decision

The v2 carry candidate is rejected. This is an economic failure, not only a sample-count failure, so
the 2026 sealed stage remains locked.

## Metrics

| Metric | 2025 result |
|---|---:|
| Starting equity | 660.00000000 USDT |
| Ending equity | 659.65699882 USDT |
| Net return | -0.05196988% |
| Compound daily return | -0.00014242% |
| Closed positions | 9 |
| Active calendar days | 86 |
| Captured funding settlements | 332 |
| Profit factor | 0.69183308 |
| Win rate | 33.33333333% |
| Maximum drawdown | 0.18913231% |
| Positive quarters | 1 / 4 |
| Bootstrap lower mean daily return | -0.00091924% |
| 1.5x cost-stress return | -0.36436683% |
| Second-leg delay-stress return | -0.26134920% |

Funding contributed `2.94703468 USDT`, while fees and modeled slippage consumed `4.15461325 USDT`.
The unchanged positive-funding rule entered too few durable regimes and did not earn enough carry to
recover four-leg execution costs.

## Consequence

Lowering the trade-count or activity gate would not repair negative net, bootstrap, cost-stress, or
delay-stress results. v2 cannot be retuned or promoted. The 2023 through 2025 periods may be disclosed
as development evidence for a new cost-recovery hypothesis, but that hypothesis must be frozen before
the still-unread 2026 evidence is acquired.

No automatic or live execution permission is granted.
