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
- `BOT_EXECUTION_MAX_ACTUAL_RISK_OVERRUN_FRACTION`, default `0.05`

The protection model permits a nullable fixed take-profit. A stop-only policy
sends `takeProfit=0` when amending the position so an existing target is
explicitly removed, then verifies that no target remains and that the stop
matches the actual-fill plan. This follows Bybit V5's independent TP/SL fields
and zero-value cancellation contract:
<https://bybit-exchange.github.io/docs/v5/position/trading-stop>.

The exchange instrument metadata remains the preferred future source for price
and quantity steps. These settings are explicit until that metadata is wired
into the execution gateway.

## Bounded automatic entry

Automatic entries use a marketable IOC limit order instead of an unbounded
market request. The limit is the last closed-candle price plus the configured
entry slippage for buys, or minus it for sells, normalized conservatively to the
price tick. Sizing and planned protection use this worst acceptable price. Any
unfilled IOC quantity is cancelled by the exchange.

This avoids Bybit's market-only slippage parameters, which cannot be combined
with TP/SL, while retaining protection on the entry request. Bybit documents
both that market orders are internally converted to IOC limits and that order
creation acknowledgement is asynchronous:
<https://bybit-exchange.github.io/docs/v5/order/create-order>.

After a position appears, the service recomputes cost-adjusted risk from the
actual average fill, actual size, and actual-fill stop. If it exceeds persisted
`intendedRisk` by more than the configured fraction, the full position is
submitted for reduce-only closure before any strategy management continues.

## Fill journal

Every private execution is stored in the append-only `executionFillEvents`
journal before immediate reconciliation is requested. `mode + symbol + execId`
is the preferred deduplication identity; a deterministic
order/price/quantity/time identity is used only when the provider omits
`execId`. REST reconciliation backfills the same journal, so reconnects and
WebSocket retries cannot duplicate fees or quantities and process restarts do
not erase partial-fill evidence.

An order acknowledgement is not treated as a fill. The same authenticated
WebSocket subscribes to both `execution` and `order`. Order updates distinguish:

- `ENTRY_FILLED`: cumulative entry quantity is fully filled, position readback pending
- `PARTIALLY_FILLED`: a live partial fill, including IOC remainder cancellation
- `ENTRY_CANCELLED`: IOC ended with zero fill
- `ENTRY_REJECTED`: exchange rejected the entry with zero fill
- `ERROR`: inconsistent quantity or a rejected/cancelled reduce-only exit

Private order callbacks and REST reconciliation share one lifecycle mutex. A
filled entry that has neither an exchange position nor a closure by the
protection deadline becomes `ENTRY_FILL_POSITION_MISSING`; an acknowledged
entry whose final state cannot be recovered becomes
`ENTRY_ORDER_FINAL_STATE_UNKNOWN`.

## Verification

Regression coverage includes:

- actual-fill TP/SL recalculation and read-back verification
- protection API request mapping
- protection failure after deadline and reduce-only fail-closed exit
- open entry counting for the UTC daily cap
- lifecycle metadata persistence and legacy schema migration
- execution fill persistence and `execId` retry deduplication
- private order parsing and terminal IOC lifecycle classification
- existing max-hold, closure, alert, and account-performance behavior

## Remaining work

- Make historical, paper, shadow, and exchange adapters consume one shared
  position-policy state machine.
- Verify reduce-only exit completion from the position, order, and closed-PnL
  streams instead of leaving it in a pending lifecycle state.
- Replace configured tick/quantity constraints with periodically refreshed
  exchange instrument metadata.
- Add daily loss, consecutive-loss, account drawdown, and reconciliation
  circuit breakers before any live strategy can be approved.

## Primary references

- Bybit place order: https://bybit-exchange.github.io/docs/v5/order/create-order
- Bybit set trading stop: https://bybit-exchange.github.io/docs/v5/position/trading-stop
- Bybit private execution stream: https://bybit-exchange.github.io/docs/v5/websocket/private/execution
- Bybit private order stream: https://bybit-exchange.github.io/docs/v5/websocket/private/order
- Bybit private position stream: https://bybit-exchange.github.io/docs/v5/websocket/private/position
