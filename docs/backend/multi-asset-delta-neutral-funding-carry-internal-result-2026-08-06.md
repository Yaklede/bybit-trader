# Multi-asset carry 2024 internal result

## Decision

`multi_asset_delta_neutral_carry_04` is rejected for the v1 protocol. It closed 17 positions while
the frozen gate required at least 20. The gate cannot be relaxed after observing the result, so the
v1 protocol cannot acquire or inspect 2025 external evidence.

## Metrics

| Metric | 2024 result |
|---|---:|
| Starting equity | 660.00000000 USDT |
| Ending equity | 680.52748433 USDT |
| Net return | +3.11022490% |
| Compound daily return | +0.00836876% |
| Closed positions | 17 |
| Active calendar days | 203 |
| Captured funding settlements | 1,153 |
| Profit factor | 26.63077294 |
| Win rate | 76.47058824% |
| Maximum drawdown | 0.43591163% |
| Liquidations | 0 |
| Positive quarters | 3 / 4 |
| Bootstrap lower mean daily return | +0.00435713% |
| 1.5x cost-stress return | +2.52398486% |
| Second-leg delay-stress return | +1.79362814% |

All gates except `minimumClosedPositions` passed. Positive returns do not override a predeclared
statistical-sufficiency gate.

## Next protocol boundary

The 2023 and 2024 outcomes are now disclosed development evidence. A successor protocol may use
both years to assess sample sufficiency, but it must have a new protocol ID and must freeze its
candidate, annual external gates, simulator, and trial accounting before acquiring 2025 evidence.
The 2026 sealed period and fresh forward period remain unread.

No automatic or live execution permission is granted by this result.
