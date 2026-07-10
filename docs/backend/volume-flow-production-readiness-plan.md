# Volume Flow Production Readiness Plan

## Goal

Turn a replay-validated BTCUSDT strategy into an on-prem operated bot that can
be monitored and controlled through the private API behind Twingate.

The previous aggressive profile is retained only for audit. It is not an
automatic-live candidate because its sealed runtime replay fails the return and
drawdown gates.

## Current Baseline

- The runtime profile is `absa_final_us_v1`, marked `UNVERIFIED`.
- The 40-window runtime audit recorded 0/40 passes, a -0.38433% mean CDR, and
  31/39 replay windows above 40% MDD. See
  [derivatives-flow-research-2026-07-10.md](derivatives-flow-research-2026-07-10.md).
- The profile is now represented in Kotlin by
  `VolumeFlowAggressiveBacktestService`.
- Operators can run the current aggressive profile through:
  `POST /backtests/volume-flow/aggressive/current/run`.
- The paper loop can run `VolumeFlowAggressiveStrategy` through
  `BOT_PAPER_STRATEGY=volume-flow-aggressive`, using stored M5 history for the
  60-day side-regime rules and syncing the latest 1000 public candles per loop.
- `scripts/bot-preflight.mjs` checks the on-prem paper deployment environment
  before startup.
- Bybit V5 private execution client is implemented for linear futures order
  create, cancel, open-order query, position query, and execution query.
- `POST /execution/evaluate-and-submit` can submit a manual private Bybit
  execution smoke order when `BOT_PRIVATE_EXECUTION_ENABLED=true`.
- `POST /execution/reconcile` reports open orders, positions, and recent fills.

## Milestones

### M1. Kotlin Strategy Parity

Objective: prove the Kotlin service reproduces the raw M5 feature-discovery
strategy closely enough to become the production source of truth.

Acceptance criteria:

- `absa_final_us_v1` parameters are represented in typed Kotlin config.
- The endpoint can replay arbitrary date windows over stored M5 candles.
- Script result and Kotlin result are compared for known windows:
  - `2021-08-01..2022-07-01`
  - `2024-05-07..2024-06-11`
  - `2026-01-01..2026-07-02`
  - the latest 20 random replay windows.
- Any difference in trade count, entry time, side, exit reason, or final equity
  is either fixed or documented.

### M2. Paper Strategy Loop

Objective: run live Bybit public candles through the aggressive strategy without
private exchange order calls.

Acceptance criteria:

- Paper loop uses the aggressive M5 profile, not the mean-reversion strategy.
- Signals, paper orders, fills, positions, and performance snapshots are linked.
- Pause/resume blocks or allows new aggressive entries.
- Telegram/Discord alerts cover startup, shutdown, paper fills, paper
  rejections, control actions, and loop failures.
- Duplicate paper entries for the same `ENTRY_AT_*` signal are skipped.

Status: implemented for paper/shadow operation. Daily summary events remain a
future reporting enhancement, not a blocker for on-prem paper deployment.

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
- Automatic execution of the current unverified profile requires the separate
  `BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE=true` override and is blocked by
  default.
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
tokens. Keep `BOT_EXECUTION_LOOP_ENABLED=false`, submit one manual order, and
reconcile it. Do not enable the loop for `absa_final_us_v1`; first replace it
with a profile that passes the runtime replay and validation gates.
