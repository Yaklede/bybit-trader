# Volume Flow Aggressive Risk Register

## Purpose

This document records known risks for continuing with the aggressive volume-flow
strategy profile. These items are not the active strategy-improvement backlog;
they are deferred risk controls that must remain visible before testnet or live
capital is increased.

Source note: measurements below are local BTCUSDT backtest outputs from
`build/runtime-test/bybit-trader-full-history.sqlite`, using the raw M5
feature-discovery profile `absorption-adaptive-regime-final`
(`absa_final_us_v1`). They are not live-trading return guarantees.

## Current Decision

- Validation status: `UNVERIFIED`.
- Profitability reports produced before `causal-next-open-v1` are invalid for
  live-readiness decisions because they selected a breakout from a candle close
  and filled the trade at the same candle open.
- Keep `BOT_EXECUTION_MAX_NOTIONAL` configured for every live automatic loop.
- Do not increase live exposure until causal walk-forward and sealed holdout
  gates pass with fees, slippage, liquidation, and drawdown included.

## Observed Risk Events

| Event | Observed window | Peak | Trough | Drawdown | Note |
| --- | --- | ---: | ---: | ---: | --- |
| 2024 stress event | 2024-05-07 to 2024-06-11 UTC | varies by replay start | varies by replay start | 77.53% | Same event appears in multiple overlapping random windows. |
| 2026 YTD stress event | 2026-04-01 to 2026-04-14 UTC | 31,250,936 | 11,331,707 | 63.74% | Year-to-date replay still compounds, but April is the main risk cluster. |
| 2025 late stress event | 2025-10-11 to 2025-10-27 UTC | varies by replay start | varies by replay start | 52.19% | Repeated in 2025-07 and 2025-09 start windows. |

## Deferred Risk Register

| ID | Risk | Impact | Deferred mitigation |
| --- | --- | --- | --- |
| R1 | Raw feature-discovery strategy is represented in Kotlin, but raw-script versus Kotlin trade-level parity is not fully proven. | Backtest result can diverge if candle boundaries, setup indexing, or runtime signal timing differ. | Keep `VolumeFlowAggressiveBacktestService` and `VolumeFlowAggressiveStrategy` as the implementation baseline, then add trace-level parity checks against script outputs before testnet execution. |
| R2 | Aggressive compounding allows 50-77% peak-to-trough drawdowns. | Live account may become unusable before long-run recovery, especially with futures leverage and margin rules. | Add optional risk presets later: max leverage, max account risk, consecutive-loss throttle, and stress-regime size reducer. |
| R3 | Backtest assumes simplified fills, fees, and slippage. | Real execution may reduce or invert expected edge during high-volume candles. | Run paper and testnet shadow mode with realized spread, slippage, rejection, and funding records. |
| R4 | Large simulated balance growth ignores exchange liquidity and order-size limits. | Later-stage compounding is unrealistic if position size becomes too large for BTCUSDT depth. | Add notional caps, depth-aware sizing, and liquidity cap reporting before scaling capital. |
| R5 | Same drawdown event appears in overlapping random windows. | Window-level risk can be overcounted or misinterpreted. | Track independent drawdown events by peak/trough timestamp instead of only replay-window MDD. |
| R6 | On-prem deployment is protected by Twingate, but local API compromise remains possible inside the private network. | Any exposed control endpoint could pause, resume, or eventually place orders if token handling is weak. | Keep API bound to private interfaces, require `BOT_CONTROL_TOKEN`, restrict Twingate resource membership, and log all control actions. |
| R7 | Private Bybit execution is implemented, but credentialed testnet smoke evidence is not recorded yet. | Code-level tests can pass while exchange permissions, account mode, quantity step, or symbol limits still reject real orders. | Run testnet order create/cancel/reconcile with real Bybit testnet credentials before any live capital. |
| R8 | Emergency stop currently changes bot mode but does not automatically cancel Bybit open orders or close/reduce positions. | A private order may remain live after an operator emergency action. | Add emergency-stop execution policy: cancel open orders first, then apply configured reduce/hold/close position behavior. |

## Revisit Triggers

- Before enabling Bybit live private order placement.
- Before enabling live mode with any capital.
- When paper/testnet signal parity differs from backtest by more than one trade
  over a replayable sample.
- When realized slippage or fees reduce profit factor below the accepted paper
  threshold.
- When any 7-day live or paper drawdown exceeds the configured operator limit.
