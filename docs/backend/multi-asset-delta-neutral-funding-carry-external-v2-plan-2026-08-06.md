# Multi-asset carry external v2 protocol

## Problem

The unchanged carry candidate was profitable in both 2023 and 2024, including cost and execution
stress, but v1 failed because a one-year internal sample closed 17 positions against a frozen minimum
of 20. That failed gate remains final for v1.

## Successor hypothesis

The candidate is treated as a low-turnover carry strategy rather than a high-frequency strategy.
The already disclosed 2023 and 2024 periods form a two-year development sample:

- 41 closed positions
- 2 / 2 positive calendar years
- 7 / 8 positive quarters
- +4.68412645% compounded return across annual runs
- maximum observed drawdown 0.44111425%
- positive 1.5x cost and second-leg delay stress in both years
- all three assets traded and no liquidations in both years

No candidate parameter changed after the 2024 result. Redesigning the sample gate is explicitly
counted as one new protocol decision.

## External 2025 gate

The 2025 annual external test requires at least 12 closed positions, 120 active days, 365 captured
funding settlements, all three assets traded, at least two profitable assets, and three positive
quarters. Net return, mean daily return, the bootstrap lower bound, 1.5x cost stress, and second-leg
delay stress must all remain positive. Profit factor must be at least 1.1, MDD at most 5%, and every
predeclared concentration and hedge-precision limit must pass.

## Sealed 2026 gate

Only an all-gates 2025 pass may unlock the still-unread `2026-01-01` to `2026-07-01` stage. The
half-year gate requires at least 6 positions, 60 active days, 180 funding settlements, positive net
and stress returns, MDD at most 5%, and the frozen diversification constraints.

Passing both historical stages still does not permit live trading. Shared execution parity, shadow,
paper, and a fresh forward seal remain mandatory.
