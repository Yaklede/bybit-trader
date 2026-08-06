# Execution API

All execution endpoints require `Authorization: Bearer $BOT_CONTROL_TOKEN`.

## Exchange-verified safety controls

`POST /control/safe-stop` changes the bot to `PAUSE_ALL`, cancels active entry
orders, keeps protected positions open, and submits a reduce-only close for any
unprotected position. `POST /control/flatten` changes the bot to
`EMERGENCY_STOP`, cancels active entries, submits reduce-only exits, and waits
for zero active orders and zero positions. The response includes a `safety`
object with `CONFIRMED`, `PENDING`, or `FAILED`, action counts, remaining
exchange exposure, and machine-readable issue codes.

When an alert sink is enabled, the control-mode alert and the exchange safety
result are delivered separately. The safety alert includes the remaining order
and position counts and translates each issue code into an operator action.
Alert delivery failure never changes the control command response or prevents
the exchange action. While the persisted mode remains `PAUSE_ALL` or
`EMERGENCY_STOP`, the reconciliation loop re-verifies the exchange state and
emits only safety-state transitions. A pending flatten therefore produces a
later `CONFIRMED` or changed `FAILED` alert without repeating the same status on
every reconciliation cycle. Returning to a non-safety mode resets this alert
fingerprint for the next operator command.

Safety issue codes:

- `SAFETY_SNAPSHOT_UNAVAILABLE`
- `SAFETY_ORDER_CANCEL_FAILED`
- `SAFETY_POSITION_CLOSE_FAILED`
- `SAFETY_MULTIPLE_ACTIVE_POSITIONS_UNSUPPORTED`
- `SAFETY_VERIFICATION_UNAVAILABLE`
- `SAFETY_VERIFICATION_PENDING`

`GET /dashboard/summary` and `GET /dashboard/mobile-summary` expose the last
persisted account gate as `riskReadiness`. It includes the unitized NAV state,
current daily loss and account drawdown fractions, configured limits,
consecutive losses, wallet-ledger reconciliation values, and the exact entry
blocking reason codes. This projection performs no Bybit request and never
refreshes or mutates a risk baseline; the reconciliation loop owns those
updates.

## POST /execution/evaluate-and-submit

Evaluates the runtime aggressive M5 strategy and submits a private Bybit market
order only when `BOT_PRIVATE_EXECUTION_ENABLED=true` and the bot state is
`RUNNING`. The current rejected profile remains unable to start the automatic
loop.

Only candles whose open time is before the current timeframe boundary are
evaluated. After filtering, insufficient warmup returns `NO_TRADE` with
`INSUFFICIENT_CLOSED_CANDLE_HISTORY`. Immediately before automatic submission,
active exchange orders or a positive position size reject the signal with
`EXISTING_EXCHANGE_EXPOSURE`. Manual reduce-only close orders are unaffected.
The same automatic position policy is shared with the aggressive backtest:
the current profile allows at most five completed entries per UTC day and a
maximum hold of 36 M5 candles (three hours). An expired Bybit position is
closed with a reduce-only market order and returns `EXIT_SUBMITTED`. While that
time-exit order remains open, evaluation returns `NO_TRADE` with
`MAX_HOLD_EXIT_PENDING` instead of submitting a duplicate exit.
Before loading a new signal, the service evaluates the persisted account risk
state. Missing or older-than-allowed state is refreshed from the private wallet
API; if it cannot be refreshed, the service fails closed. A daily equity loss,
account drawdown, or consecutive-loss breach returns `NO_TRADE` with one or
more of these reason codes:

- `RISK_STATE_STORE_UNAVAILABLE`
- `RISK_STATE_UNAVAILABLE`
- `RISK_STATE_STALE`
- `RISK_STATE_CLOCK_SKEW`
- `RISK_NAV_UNAVAILABLE`
- `RISK_NAV_BASELINE_PENDING`
- `RISK_NAV_INVALID`
- `DAILY_EQUITY_LOSS_LIMIT_REACHED`
- `ACCOUNT_DRAWDOWN_LIMIT_REACHED`
- `CONSECUTIVE_LOSS_LIMIT_REACHED`
- `ACCOUNT_RECONCILIATION_UNAVAILABLE`
- `ACCOUNT_RECONCILIATION_CLOCK_SKEW`
- `ACCOUNT_RECONCILIATION_STALE`
- `ACCOUNT_RECONCILIATION_BASELINE_PENDING`
- `ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE`
- `ACCOUNT_WALLET_DATA_UNAVAILABLE`
- `ACCOUNT_LEDGER_MISMATCH_PENDING`
- `ACCOUNT_LEDGER_MISMATCH_CONFIRMED`

Position management and reduce-only exits run before this entry-only gate, so
the breaker cannot strand an existing position. Wallet reconciliation compares
the observed USDT wallet-balance delta with persisted Bybit transaction `change`
values. Its first snapshot is a blocking baseline; only a fresh `MATCHED` state
allows a new entry.

When wallet reconciliation is enabled, daily loss and account drawdown use a
unitized strategy NAV rather than raw account equity. Transaction-log changes
outside `TRADE`, `SETTLEMENT`, `DELIVERY`, `LIQUIDATION`, `ADL`, `FEE_REFUND`,
and `INTEREST` are treated as external capital flows. Deposits and withdrawals
therefore change strategy units without resetting or fabricating strategy
returns. A new or migrated NAV state blocks entries for one baseline interval.
The automatic loop emits a Korean Discord/Telegram alert when an entry-risk
reason first appears or its reason set changes. Repeated evaluations with the
same reason set are suppressed in-process. A single recovery alert is emitted
when all entry-risk reasons clear; ordinary `NO_TRADE` strategy reasons do not
produce risk alerts.
The gross target move must exceed configured round-trip fees plus
`BOT_EXECUTION_SLIPPAGE_BUFFER_RATE`; otherwise the signal is rejected before
any private order call.

Entry-anchor caveat: the aggressive backtest confirms the breakout at candle
close and models the fill at the next candle open plus slippage. Live execution
must submit before its market fill is known, so sizing and TP/SL use the closed
breakout candle close plus the configured slippage safety buffer as an estimate.
This is an explicit approximation. The runtime does not perform a risky
post-fill TP/SL cancel-and-replace in this phase.

Aggressive backtest responses identify fill model `causal-m1-path-v2`. The
model loads pre-window M5 warmup separately, confirms on a closed M5 candle,
enters no earlier than the next contiguous M5 open, and resolves post-entry
stop/target order with contiguous M1 candles. Requests accept separate
`slippageRate`, `exitSlippageRate`, and estimated `fundingRatePer8h` values.
Responses expose gross PnL, fees, funding PnL, slippage cost, data-gap skips,
and liquidation count; use these fields for cost stress checks instead of
comparing net return alone.

The current aggressive endpoint derives its empty-request defaults from the
same `aggressive-runtime-v1` profile used by automatic execution. The response
includes `strategyContractVersion`, `runtimeSignalProfileMatched`, and an
`executionContract` object containing its SHA-256 `fingerprint`, risk fraction,
fees, entry/exit slippage, quantity limits, maximum notional, leverage, and
liquidation buffer. Changing a signal parameter marks the result as
`runtimeSignalProfileMatched=false` and changes `profileId` to a
`-research-override` identifier; such a result cannot be treated as runtime
profile evidence. The current `absa_final_us_v1` profile is `REJECTED` because
its causal after-cost replay has negative expectancy.

`GET /strategy/profiles` exposes the expected frozen execution-contract
fingerprint and the fingerprint assembled from the current process environment.
`executionContractMatched` must be `true` before comparing a runtime result to
the frozen aggressive backtest contract. A matching contract does not override
the profile's validation status. A `REJECTED` profile cannot start the
automatic execution loop even when the legacy unverified-profile override is
present.

Request:

```json
{
  "symbol": "BTCUSDT",
  "timeframe": "M5",
  "candleLimit": 18000
}
```

Response fields:

- `status`: `DISABLED`, `SKIPPED_BY_MODE`, `NO_TRADE`, `REJECTED`,
  `SUBMITTED`, or `EXIT_SUBMITTED`.
- `clientOrderId`: local idempotency id sent to Bybit as `orderLinkId`.
- `exchangeOrderId`: Bybit `orderId` when Bybit accepts the order request.
- `entryPrice`, `takeProfit`, `stopLoss`, `quantity`, `intendedRisk`: decimal
  strings used for the submitted order.

The create-order response is not treated as a fill. Use reconciliation to check
open orders, positions, and executions.

## GET /execution/lifecycle-events

Returns the append-only execution lifecycle ledger. Query params are
`symbol`, `mode=TESTNET|LIVE`, and `limit` (1-1000). Automatic, smoke,
manual-entry, and reduce-only exit submissions are recorded as
`ENTRY_SUBMITTED` or `EXIT_SUBMITTED`; a submission event is not proof of a
fill.

The lifecycle state contract is:

- `ENTRY_SUBMITTED`
- `PARTIALLY_FILLED`
- `ENTRY_FILLED`
- `ENTRY_CANCELLED`
- `ENTRY_REJECTED`
- `OPEN_UNPROTECTED`
- `OPEN_PROTECTED`
- `EXIT_SUBMITTED`
- `CLOSED`
- `ERROR`

The independent reconciliation loop advances submission events from Bybit open
orders, recent executions, positions, and closed PnL even when automatic entry
evaluation is disabled. A positive position with
the required exchange-reported protection becomes `OPEN_PROTECTED`; missing
protection becomes `OPEN_UNPROTECTED` and emits a critical Korean alert. The
order stream uses `ENTRY_FILLED` while position readback is pending,
`ENTRY_CANCELLED` for a zero-fill IOC cancellation, and `ENTRY_REJECTED` for a
zero-fill exchange rejection. A matching closed PnL advances the active
lifecycle to `CLOSED`.

This projection uses REST polling every
`BOT_EXECUTION_RECONCILIATION_INTERVAL_SECONDS` (60 seconds by default) as the
durable recovery path. When
`BOT_PRIVATE_EXECUTION_STREAM_ENABLED=true`, private `execution` and `order`
topics persist each `execId`, classify the acknowledged IOC order state, and
wake the same reconciliation loop immediately. REST backfills the same
idempotent fill ledger after reconnects. Closure deduplication and Discord
at-least-once delivery remain centralized in the projection flow.

## POST /execution/reconcile

Queries Bybit open orders, position list, recent executions, and closed-PnL
records for a symbol without writing projections. This keeps operator and
dashboard reads from consuming a new closure before the runtime alert path.
Position rows include Bybit `openTime` as `openedAt`; automatic maximum-hold
enforcement is skipped when the exchange does not provide a valid open time.

Request:

```json
{
  "symbol": "BTCUSDT"
}
```

## GET /execution/closed-trades

Lists persisted TESTNET/LIVE closed trades. Query params: `symbol`, `mode`,
`limit` (1-100), and keyset `cursor`.

## GET /performance/live/summary

Returns cumulative live/testnet performance calculated from all persisted
closures in the requested window; it is not limited by closed-trade API page
size. `session` starts when the execution service starts, while `7d` and `30d`
use rolling UTC durations from response capture time.
Query params: `mode=TESTNET|LIVE` and `window=session|7d|30d|all`.

The response also includes `accountEquity`, `accountPeakEquity`,
`maxAccountDrawdownPct`, and `accountEquityCapturedAt` when the reconciliation
loop has stored a Bybit wallet-balance snapshot. `maxClosedTradeDrawdownPct`
is the realized-PnL-only curve and must not be interpreted as account MDD.

## Reconciliation loop order

The reconciliation loop discovers and persists new Bybit closed-PnL rows, then
drains durable pending Korean close alerts. It runs independently of market
sync and automatic entry evaluation. `executionTradeClosures` stores `delivered_at`,
`suppressed_at`, `attempt_count`, and `last_attempt_at`. A false delivery result
or callback exception increments the attempt metadata and remains pending for
the next five-minute cycle. Each pending row is handled independently, so one
Discord failure does not prevent later pending alerts.

The same initial reconciliation request also projects the newest execution
lifecycle observation. Newly observed partial fills, protected positions, and
unprotected positions are passed to the alert layer before market sync. Closed
trade alerts continue to use their separate durable at-least-once queue.

Delivery semantics are at-least-once. A successful Discord request is marked
delivered only after the callback returns success. If the process exits after
Discord accepts the request but before the SQLite acknowledgment commits, that
closure can be sent again after restart.

On the first bootstrap for a mode and symbol with no stored closure history,
provider rows closed before the process `sessionStartedAt` are stored as a
suppressed baseline. This prevents the first Bybit page, currently at most 50
rows, from flooding Discord. Closures after process start remain pending. Once
history exists, a later restart treats previously unseen downtime closures as
pending even when they closed before the new process start. API and dashboard
reconciliation remain read-only; the background reconciliation loop is the only
closure writer.

## Migration note

The runtime creates missing projection tables when opening an existing SQLite
ledger. For ledgers created by the previous release, it additively adds the
NOT NULL `executionTradeClosures.identity_key`, backfills deterministic
identities, removes pre-existing duplicate identities while keeping the oldest
row, and recreates the unique index. The statements remain compatible with the
SQLDelight `sqlite_3_18` dialect.

The same additive startup migration creates `executionLifecycleEvents` and
its identity and lookup indexes. Rollback requires stopping the new binary
before dropping that table; older binaries ignore the additional table.

The alert-state migration additively creates nullable `delivered_at`, nullable
`suppressed_at`, `attempt_count INTEGER NOT NULL DEFAULT 0`, and nullable
`last_attempt_at`. Existing closure rows are marked suppressed during this
one-time upgrade so deployment cannot replay historical alerts. New rows use
pending defaults unless bootstrap suppression applies.

Migration caveat: SQLite 3.18 cannot drop this column directly. Rollback after
the identity migration requires restoring a pre-migration backup or rebuilding
`executionTradeClosures`; dropping only the index is not a complete rollback.

## POST /execution/orders/cancel

Cancels an open Bybit order by `exchangeOrderId` or `clientOrderId`.

Request:

```json
{
  "symbol": "BTCUSDT",
  "clientOrderId": "bt-BTCUSDT-1719705600000-1-B"
}
```

Errors from private exchange calls return:

```json
{
  "code": "EXCHANGE_EXECUTION_UNAVAILABLE",
  "message": "Private exchange execution provider is unavailable."
}
```
