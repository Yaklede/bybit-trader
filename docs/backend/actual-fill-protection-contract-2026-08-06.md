# Actual-fill protection contract

Date: 2026-08-06

## Status

Implemented as an execution-safety milestone. This change does not approve a
strategy and does not make the current `REJECTED` runtime profile executable.

## Problem

The automatic order path calculated quantity, take profit, and stop loss from
the last closed candle before submitting an asynchronous market order. The
exchange could fill at a different price, leaving the realised risk and reward
different from the backtest contract. The UTC daily trade limit also counted
closed trades, so an entry that had not closed was absent from the limit.

## Contract

1. The signal is evaluated from closed candles only.
2. A provisional full-position TP/SL is attached to the entry request.
3. The lifecycle event stores the structural invalidation price, optional
   entry-anchored stop distance, expected R, planned entry, and protection
   deadline.
4. Any private execution event requests immediate reconciliation.
5. Reconciliation reads the exchange position average entry price, recalculates
   TP/SL from that actual fill price, calls `/v5/position/trading-stop`, and
   reads the position again.
6. `OPEN_PROTECTED` is recorded only when both exchange values match the
   calculated values within one configured price tick.
7. If an automatically opened position cannot be protected before the deadline,
   any matching active entry remainder is cancelled and a reduce-only market
   exit is submitted.
8. Manual and smoke-test positions are observed and alerted but are excluded
   from automatic fail-closed liquidation.
9. The daily limit counts distinct `AUTOMATIC_ENTRY_SUBMITTED` lifecycle IDs,
   including entries that remain open.

## Persistence

The append-only execution lifecycle now stores:

- `protection_required`
- `planned_entry_price`
- `structural_stop_price`
- `entry_anchored_stop_distance`
- `expected_r`
- `protection_deadline_at`

The additive SQLite migration upgrades an existing lifecycle table without
deleting prior events. Legacy events default to `protection_required = 0` and
are never treated as bot-owned fail-closed positions without ownership data.

## Configuration

- `BOT_EXECUTION_PRICE_TICK`, default `0.1` for BTCUSDT
- `BOT_EXECUTION_PROTECTION_GRACE_SECONDS`, default `120`

The exchange instrument metadata remains the preferred future source for price
and quantity steps. These settings are explicit until that metadata is wired
into the execution gateway.

## Verification

Regression coverage includes:

- actual-fill TP/SL recalculation and read-back verification
- protection API request mapping
- protection failure after deadline and reduce-only fail-closed exit
- open entry counting for the UTC daily cap
- lifecycle metadata persistence and legacy schema migration
- existing max-hold, closure, alert, and account-performance behavior

## Remaining work

- Make historical, paper, shadow, and exchange adapters consume one shared
  position-policy state machine.
- Persist execution fills as an idempotent `execId` ledger instead of relying
  only on the position average price for reconciliation.
- Define partial-fill remainder deadlines and asynchronous exit confirmation.
- Replace configured tick/quantity constraints with periodically refreshed
  exchange instrument metadata.
- Add daily loss, consecutive-loss, account drawdown, and reconciliation
  circuit breakers before any live strategy can be approved.

## Primary references

- Bybit place order: https://bybit-exchange.github.io/docs/v5/order/create-order
- Bybit set trading stop: https://bybit-exchange.github.io/docs/v5/position/trading-stop
- Bybit private execution stream: https://bybit-exchange.github.io/docs/v5/websocket/private/execution
- Bybit private position stream: https://bybit-exchange.github.io/docs/v5/websocket/private/position
