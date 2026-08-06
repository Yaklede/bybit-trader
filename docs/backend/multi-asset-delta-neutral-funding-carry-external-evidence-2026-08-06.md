# Multi-asset carry 2025 external evidence

## Status

The complete 2025 external dataset is sealed before portfolio metrics are calculated. The unchanged
candidate, simulator, execution contract, annual gate, and 2026 sealed gate were committed first.

## Coverage

- Range: `2025-01-01T00:00:00Z` to `2026-01-01T00:00:00Z`
- Symbols: `BTCUSDT`, `ETHUSDT`, `SOLUSDT`
- Exact M5 rows per price series: `105,120`
- Exact funding rows per symbol: `1,095`
- Total synchronized portfolio M5 rows: `315,360`
- Total funding settlements: `3,285`
- Missing causal decision inputs: `0`

## Integrity anchors

- Protocol SHA-256: `563c30e593688d38ff4747f8bf21627f9d623161c8856102885e92635a17f24a`
- Candidate SHA-256: `2b46f1abe6caef9fb31eb6ad85de2ff4973985e52d76a1f377cc8ef2e6d974e9`
- Simulator SHA-256: `561d3b11a9d73cd8f82e35338aafeaab92d55f65728015be7510c7abc1a92c5c`
- Normalized evidence SHA-256: `3ee5a26fef70cc672c7376bcc88e9b8165d79f1fabfab1a472060777cefa3d40`
- Snapshot SHA-256: `29bf7e59de7f2af3bf59ea91d70ddf8377bb58e7b70b14350027e29a9d219d8e`
- Acquisition report SHA-256: `b838a3f157d458c79afbe0b68f7c5b38e7de4030e1e13da07410cbe798ebedac`

The receipt allows exactly one replay of the frozen candidate against the frozen 2025 gate. It does
not unlock 2026 data or any automatic or live execution path.
