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

## GET /strategy/volume-confirmed-trend/live

Returns the persisted H4 live checkpoint, append-only lifecycle events, and the
accounting evidence needed to audit the strategy. It performs no Bybit request
and does not synthesize a position. Query parameter `limit` defaults to 50 and
accepts 1-100.

The response contains:

- `runtimeMode`: the process wiring selected at startup. `DISABLED` has no
  private H4 loop, `MANAGEMENT_ONLY` can only reconcile and safely reduce
  persisted exposure, and `SIGNAL_ENABLED` is the only mode with Shadow signal
  evaluation and new-entry capability.
- `runtimeActive`: whether the selected process-local coroutine is currently
  active. This is runtime evidence and is not reconstructed from persisted
  checkpoints.
- `state`: approval ID, decision key, pending target side, exchange-observed
  position, order/fill IDs, halt reason, and update time.
- `account`: the latest persisted USDT account snapshot.
- `risk`: unitized NAV equity/peak/drawdown, the frozen 35% drawdown limit,
  exact entry-blocking reason codes, and the persisted risk evaluation time.
- `walletReconciliation`: observed wallet change versus transaction-ledger
  change, tolerance, mismatch count, and last successful reconciliation.
- `performance`: `SESSION`, `SEVEN_DAYS`, `THIRTY_DAYS`, and `ALL` snapshots
  calculated from H4-owned closures. Each row includes net PnL, fees, profit
  factor, expectancy, closed-trade drawdown, account-equity drawdown, BTCUSDT
  funding, and H4-attributed transaction fees.
- `recentClosedTrades`: closures attributed to H4 client order IDs.
- `recentExecutionFills`: fills attributed to H4 client order IDs, including
  Bybit `execId`, price, quantity, fee, execution PnL, exchange time, and local
  receive time.
- `recentAccountTransactions`: the latest persisted USDT transaction records
  used for wallet reconciliation, including funding, fees, cash flow, and
  balance change.

The endpoint deliberately does not update the shared generic latest-performance
table. H4 performance is calculated on read from H4-attributed closures so it
cannot overwrite or be contaminated by the legacy M5 dashboard projection.
BTCUSDT funding is reported separately because a funding transaction does not
always carry an H4 client order ID.

`enabled` reflects whether H4 signal execution was requested in process
configuration. It does not describe recovery capability: `enabled=false` can
coexist with `runtimeMode=MANAGEMENT_ONLY` when persisted exposure still needs
reconciliation. A persisted state can also be returned while the runtime mode is
`DISABLED` so an operator can inspect a prior halted run. `ORDER_SUBMITTED` is an
acknowledgement, not fill proof; `ENTRY_FILL_OBSERVED` or `EXIT_FILL_OBSERVED`
plus the observed position state is the current recovery evidence. A
missing/stale unitized NAV or wallet reconciliation, a confirmed wallet
mismatch, or drawdown at or above 35% returns `risk.allowsNewEntry=false`.
Existing position exits remain allowed. This endpoint requires the control
Bearer token.

Runtime approval loss is also an entry gate, not permission to abandon an
existing position. A process with no prior live state remains private-read
free. When a persisted live state exists, the executor first recovers any
pending order. It then submits a bounded reduce-only IOC safety exit only when
the persisted side and quantity exactly match the exchange position. A known
unfilled safety exit may retry after one minute with a new client order ID;
unknown order outcomes are reconciled and never blindly resubmitted. Missing
mark/reference price or unproven ownership halts automatic handling and emits a
critical operator alert. The safety exit is not suppressed solely because the
remaining position is below the normal entry minimum-notional check.

The same rule applies across process restarts. If the approval receipt,
approval report, or current Shadow evidence cannot be validated during startup,
the normal H4 loop is not constructed. A management-only loop has no Shadow
store, ticker provider, or signal-evaluation path and calls only reconciliation.
It recovers persisted pending orders and manages a position only when the
persisted side and quantity prove ownership. With no prior live state it makes
no private exchange read; if persisted work exists but private credentials are
missing, startup fails instead of silently abandoning the position. The frozen
protocol and persisted Live checkpoint are loaded independently of the Shadow
and Live enable flags, so disabling both flags cannot hide a pending order, a
halted checkpoint that still retains order identity, or an observed position
during restart.
`enabled=true` does not by itself prove signal execution is available: operators
and deployment automation must require `runtimeMode=SIGNAL_ENABLED` and
`runtimeActive=true`. A management-only runtime remains available for position
recovery but cannot satisfy a live rollout verification.

## GET /strategy/volume-confirmed-trend/exchange-contract

Runs a fresh, read-only inspection of the private Bybit account contract used
by the frozen H4 strategy. It calls only account-info, position-info, and public
instrument-info reads. It never changes leverage, creates or cancels an order,
or reads account balance and trade history.

The response contains:

- `valid`: whether every frozen execution-contract check passes.
- `failures`: machine-readable mismatch codes.
- `account`: configured account type, Unified account generation, margin mode,
  and provider update time.
- `position`: one-way/hedge mode, observed position indices, buy/sell leverage,
  and reduce-only restriction state.
- `instrument`: BTCUSDT contract status/type/currencies, Unified-margin support,
  minimum quantity, quantity step, minimum notional, tick size, and leverage
  range.

The H4 executor requires Unified 1.0/2.0, cross margin, one-way position mode,
buy and sell leverage `1`, unrestricted reduce-only exits, a trading
`LinearPerpetual` BTCUSDT instrument settled in USDT, and the frozen
`0.001 BTC` minimum/step contract. A mismatch is reported but never corrected
automatically. `available=false` means no private exchange client is configured
in the process. The endpoint requires the control Bearer token and does not
grant H4 live approval.

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
- `ACCOUNT_CLOSURE_SYNC_UNAVAILABLE`
- `ACCOUNT_CLOSURE_SYNC_STALE`
- `ACCOUNT_CLOSURE_SYNC_CLOCK_SKEW`
- `ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE`
- `ACCOUNT_TRANSACTION_SYNC_STALE`
- `ACCOUNT_TRANSACTION_SYNC_CLOCK_SKEW`
- `ACCOUNT_WALLET_DATA_UNAVAILABLE`
- `ACCOUNT_LEDGER_MISMATCH_PENDING`
- `ACCOUNT_LEDGER_MISMATCH_CONFIRMED`

Position management and reduce-only exits run before this entry-only gate, so
the breaker cannot strand an existing position. Wallet reconciliation compares
the observed USDT wallet-balance delta with persisted Bybit transaction `change`
values. Its first snapshot is a blocking baseline; only a fresh `MATCHED` state
allows a new entry.

Foreign USDT-settled positions and operator-owned open orders are also
entry-only blockers when the persisted BTCUSDT position still exactly matches
the exchange. An opposite H4 signal may submit the owned BTCUSDT quantity as a
reduce-only exit. A `vct-*` open order that is not represented by a pending
lifecycle state remains a hard halt because another automatic exit could be a
duplicate.

Safety-loop failures such as an invalid Shadow checkpoint, a future-dated
signal, or an expired signal that disagrees with the owned position do not only
write `HALTED`. If persisted side and quantity exactly match the exchange and
no unresolved `vct-*` order exists, the executor submits a bounded reduce-only
IOC exit. Position, order, instrument, or ownership uncertainty remains a hard
halt without a new order.

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

Bybit execution and closed-PnL recovery sends explicit inclusive start and end
times. A longer request is partitioned into adjacent windows no greater than
seven days, and every 100-row response cursor is followed in every window. The
1,000-page budget applies to the complete logical request, not to each window.
A repeated cursor, missing result, or exhausted budget aborts reconciliation;
a first-page-only or silently truncated ledger is never accepted. Provider rows
repeated across windows or a five-minute retry overlap are deduplicated before
projection.

The H4 runtime seeds restart recovery from its persisted state timestamp.
Pending-order execution recovery and periodic closed-PnL accounting both read a
five-minute overlap. The accounting success watermark advances only after the
fetched rows are durably projected; a failed fetch or projection retries from
the previous successful range.

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
