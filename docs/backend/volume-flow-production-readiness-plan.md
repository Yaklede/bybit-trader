# Volume Flow Production Readiness Plan

> 2026-08-06 update: the aggressive profile remains `REJECTED`. The current
> Paper-only forward candidate is `multi-horizon-momentum-development-v2` with
> `UNVERIFIED` status. It has execution parity but has not passed external or
> sealed profitability gates.

## Goal

Turn a replay-validated BTCUSDT strategy into an on-prem operated bot that can
be monitored and controlled through the private API behind Twingate.

The previous aggressive profile is retained only for audit. It is not an
automatic-live candidate because its sealed runtime replay fails the return and
drawdown gates.

## Current Baseline

- The runtime baseline is `absa_final_us_v1`, marked `REJECTED` after causal
  replay confirmed negative after-cost expectancy.
- The 40-window runtime audit recorded 0/40 passes, a -0.38433% mean CDR, and
  31/39 replay windows above 40% MDD. See
  [derivatives-flow-research-2026-07-10.md](derivatives-flow-research-2026-07-10.md).
- The profile is now represented in Kotlin by
  `VolumeFlowAggressiveBacktestService`.
- Operators can run the current aggressive profile through:
  `POST /backtests/volume-flow/aggressive/current/run`.
- The paper loop defaults to `multi-horizon-momentum`, uses its M5 causal
  execution contract, restores persistent runtime state, and automatically
  warms missing public history. The aggressive strategy remains available only
  as a rejected audit baseline.
- `scripts/bot-preflight.mjs` checks the on-prem paper deployment environment
  before startup.
- Bybit V5 private execution client is implemented for linear futures order
  create, cancel, open-order query, position query, and execution query.
- `POST /execution/evaluate-and-submit` can submit a manual private Bybit
  execution smoke order when `BOT_PRIVATE_EXECUTION_ENABLED=true`.
- `POST /execution/reconcile` reports open orders, positions, and recent fills.

## Milestones

### M1. Kotlin Strategy Parity

Objective: prove the Kotlin engine reproduces the predeclared research engine
under the same causal entry and position contract.

Acceptance criteria:

- The candidate has a versioned profile and execution-contract fingerprint.
- Node and Kotlin compare trade count, signal/entry/exit time, side, exit reason,
  prices, quantity, PnL, return R, and post-trade equity.
- Fixed real-data parity has zero mismatch within the declared tolerance.
- A parity pass is never reported as profitability approval.

Status: implemented for `multi-horizon-momentum-development-v2`.

### M2. Paper Strategy Loop

Objective: run closed public candles through the causal candidate without
private exchange order calls or historical signal backfill.

Acceptance criteria:

- Paper loop uses the `UNVERIFIED` M5 candidate and cannot enable private execution.
- Signals, pending entries, orders, fills, positions, exits, performance, and
  persistent runtime state are linked.
- Pause/resume blocks new entries while existing Paper positions keep their exit policy.
- Telegram/Discord alerts cover startup, shutdown, paper fills, paper
  rejections, closures, control actions, and loop failures.
- Incremental Paper execution matches batch backtest PnL and final equity for
  the same deterministic replay fixture.

Status: implemented for causal paper operation. Atomic multi-record writes and
crash reconciliation remain part of the ledger milestone and block live use.

### M3. Testnet Execution

Objective: add Bybit private testnet execution with reconciliation before any
live capital is considered.

Acceptance criteria:

Status: implemented for order submission and read-side reconciliation. Needs
credentialed testnet smoke verification before it is treated as operational.

Acceptance criteria:

- Create, cancel, query order, query position, and query fills work on testnet.
- `clientOrderId` is generated and stored for duplicate detection and Bybit
  order lookup.
- Order create responses are persisted as `SUBMITTED`; fills remain reconciled
  from Bybit rather than assumed from the create response.
- Reconciliation endpoint returns open orders, positions, and recent fills.
- Remaining gap before live: emergency stop must cancel open orders and apply
  the configured position policy automatically.

### M4. On-Prem Deployment

Objective: deploy the bot as a private on-prem service reachable only through
Twingate and local operator credentials.

Acceptance criteria:

- API binds to the intended private interface or localhost reverse proxy.
- Twingate resource membership controls network access.
- `BOT_CONTROL_TOKEN` is required for control/status/backtest endpoints.
- Secrets are supplied by environment or local secret manager, never committed.
- The service runs under systemd or Docker Compose with restart policy.
- SQLite DB, logs, and config are backed up or recoverable.
- Health checks and startup/shutdown alerts are verified.

### M5. Small Live Gate

Objective: enable live mode only after paper and testnet parity prove the
strategy and execution path are consistent.

Acceptance criteria:

- Live mode requires explicit `BOT_MODE=LIVE` and private credentials.
- The current rejected profile cannot execute automatically in TESTNET or LIVE.
- A future unverified candidate may use the explicit override in TESTNET only;
  LIVE automatic execution requires a verified profile.
- Startup sends a live-mode warning alert.
- Reconciliation passes before trading starts.
- Initial notional/risk caps are configured separately from research sizing.
- Operator can pause, resume, and emergency-stop through the Twingate-protected
  API.

### M6. Docker On-Prem Deployment

Objective: run the bot as a Docker Compose service on the Twingate-protected
on-prem host.

Acceptance criteria:

- Multi-stage Docker image builds the app and includes the current strategy
  config.
- Compose mounts SQLite data and strategy config outside the image.
- App secrets are supplied through a host env file, never baked into the image.
- GitHub Actions can build the image, upload it through Twingate+SSH, load it on
  the host, and restart Docker Compose.
- Healthcheck uses `/health`.

Status: implemented. Host smoke remains pending until Docker/Twingate/Bybit
live credentials are provided.

## Next Engineering Step

Run Docker preflight with real operator, alert, Twingate, SSH, and Bybit live
tokens. Keep `BOT_EXECUTION_LOOP_ENABLED=false`, keep the independent
reconciliation loop enabled, submit one manual order, and verify its persisted
lifecycle and close alert. Do not enable automatic trading for
`absa_final_us_v1`; first replace it with a profile that passes the runtime
replay and validation gates.
