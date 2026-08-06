# Multi-asset cost-recovery carry v3

## Hypothesis

The v2 failure came from closing positions after short interruptions in positive funding and paying
four-leg costs again. v3 admits a position only when median positive funding over a 30-to-90-day
holding hypothesis, plus observed basis, exceeds the modeled 0.41% round-trip cost by at least 0.10%.
It then tolerates 6, 12, or 24 consecutive non-positive settlements before exiting.

## Grid

The frozen grid has 54 candidates:

- positive funding streak: 3, 6, or 12 settlements
- minimum trailing median funding: 0.0075% or 0.01% per settlement
- maximum holding: 30, 60, or 90 days
- exit after: 6, 12, or 24 consecutive non-positive settlements
- projected carry horizon: 1.5 settlements per holding day

All candidates use the same two-pair 40% total matched-notional limit, -0.10% to +0.30% entry
basis, 0.30% mark-index premium limit, 1% basis divergence stop, 24-hour cooldown, actual funding,
taker fees, slippage, minimum quantities, liquidation path, and second-leg delay stress.

## Development gate

A candidate must be profitable in each of 2023, 2024, and 2025 under base cost, 1.5x cost, and
second-leg delay. Each year also requires at least three closed positions, all three assets traded,
two profitable assets, two profitable quarters, PF 1.1, a positive moving-block-bootstrap lower
bound, MDD at most 5%, no liquidation, and the frozen concentration and hedge limits. Across three
years it needs at least 15 positions and 8 positive quarters.

Only one candidate may be selected. The 2026 evidence remains unread and cannot be acquired unless
one candidate passes every development gate. Historical success still grants no live permission.
