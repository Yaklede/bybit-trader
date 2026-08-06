# Multi-asset carry 2024 internal evidence

## Status

The predeclared 2024 internal-validation evidence is complete and sealed. No portfolio return,
trade, or gate outcome was calculated before this receipt was committed.

## Boundary

- Range: `2024-01-01T00:00:00Z` to `2025-01-01T00:00:00Z`
- Symbols: `BTCUSDT`, `ETHUSDT`, `SOLUSDT`
- Series per symbol: spot last, perpetual last, perpetual mark, perpetual index, funding
- Exact M5 rows per price series: `105,408`
- Exact funding rows per symbol: `1,098`
- Total synchronized portfolio M5 rows: `316,224`
- Total funding settlements: `3,294`
- Missing causal decision inputs: `0`

The prior BTC-only research had already exposed 2024 BTC metrics. ETH, SOL, and the combined
portfolio outcome remained unread before the protocol, collector, candidate, and simulator were
frozen. This stage is therefore an internal validation with a disclosed partial-independence limit,
not a sealed external test.

## Integrity anchors

- Protocol SHA-256: `b2e3b132e9cfa30322aeabf3e167863c9b4c65ccd2f7c2c27703aa0915a25da0`
- Candidate SHA-256: `2b46f1abe6caef9fb31eb6ad85de2ff4973985e52d76a1f377cc8ef2e6d974e9`
- Simulator SHA-256: `561d3b11a9d73cd8f82e35338aafeaab92d55f65728015be7510c7abc1a92c5c`
- Normalized evidence SHA-256: `c1ecb7277f6e0e74e7a5e3ba3f4a311cab4d88ad917a87baa671c94048430fb8`
- Snapshot SHA-256: `876a5230f369854663d42ee7da1a2d36116b88e43b5aeeea44f1f8d596a2514e`
- Acquisition report SHA-256: `958109f61569c44432b8a640394e57988fc5f64edeaff0c59bdcd5f6d892008b`

The SQLite snapshot and raw REST pages remain ignored build artifacts. The committed acquisition
receipt records every dataset response-chain and normalized-content hash so future replay can reject
any substituted input.

## Permission

This receipt allows one evaluation of the already frozen candidate on the 2024 internal stage. It
does not allow strategy tuning, candidate replacement, automatic exchange execution, or live trading.
The 2025 external and 2026 sealed evidence remain locked.
