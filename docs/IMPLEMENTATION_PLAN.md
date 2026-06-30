# Bybit Trader Implementation Plan

## Current Scope

This implementation scope is intentionally narrow:

- Kotlin/JVM trading bot.
- Ktor server for private status and control APIs.
- Bybit V5 REST and WebSocket integration.
- SQLite event ledger through SQLDelight.
- Telegram alerts first, optional Discord webhook mirror.
- Backtest, paper trading, testnet, then small live trading.

Out of scope for this implementation phase:

- Android app.
- Public web dashboard.
- Multi-exchange support.
- Machine learning strategy logic.
- Automated withdrawals.
- Public SaaS or multi-user operation.

## Final Stack

- Language: Kotlin.
- Build: Gradle Kotlin DSL.
- Server: Ktor Server.
- HTTP and WebSocket client: Ktor Client.
- Concurrency: Kotlin coroutines.
- Serialization: kotlinx.serialization.
- Database: SQLite.
- Database access: SQLDelight.
- Test: Kotest or JUnit 5.
- Mocking: MockK.
- Static checks: Detekt or ktlint.
- Alerts: Telegram Bot API first, Discord webhook optional.
- Deployment: local process first, then `systemd` or Docker Compose.

## Repository Shape

```text
bybit-trader/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  config/
    application.example.conf
  modules/
    bot-domain/
    bot-strategy/
    bot-exchange-bybit/
    bot-ledger/
    bot-engine/
    bot-api/
    bot-alerts/
    bot-app/
  docs/
    PROJECT_PLAN.md
    IMPLEMENTATION_PLAN.md
```

## Module Responsibilities

### `bot-domain`

Owns plain Kotlin domain types. No Ktor, SQLDelight, or Bybit DTOs here.

Deliverables:

- `Symbol`, `Timeframe`, `Side`, `OrderType`, `OrderStatus`.
- `Candle`, `SignalIntent`, `SignalScore`, `RiskDecision`.
- `PositionSnapshot`, `OrderIntent`, `FillEvent`.
- `BotMode`: `RUNNING`, `PAUSE_NEW_ENTRIES`, `PAUSE_ALL`,
  `EMERGENCY_STOP`, `RESUME_PENDING_CHECK`.
- Money and quantity value types with explicit rounding rules.

Acceptance:

- Domain module compiles without infrastructure dependencies.
- Unit tests cover value-object validation and bot mode transitions.

### `bot-strategy`

Owns indicators, signal scoring, and strategy decisions.

Deliverables:

- RSI, EMA, Bollinger Band, VWAP, ATR.
- Mean reversion signal scorer.
- Breakout/retest signal scorer.
- Runner management decision logic.
- Signal arbitration when long and short intents conflict.

Acceptance:

- Indicator tests use fixed candle fixtures.
- Strategy tests show accepted, downgraded, rejected, and no-trade cases.
- No strategy class sends orders directly.

### `bot-exchange-bybit`

Owns Bybit V5 HTTP and WebSocket integration.

Deliverables:

- REST signing and request timestamp handling.
- Market candle fetch.
- Public WebSocket candle stream.
- Private order, position, and fill stream after testnet credentials are ready.
- Order create, cancel, and query functions.
- Rate-limit and retry policy.
- Reconnect policy with stale-data detection.

Acceptance:

- Signing has deterministic tests with fixed timestamp and payload fixtures.
- HTTP client can run against fake server tests.
- WebSocket stream can reconnect without duplicating candle events.
- Exchange DTOs are mapped into domain objects before leaving the module.

### `bot-ledger`

Owns persistence and audit history.

Deliverables:

- SQLDelight schema for `bot_state`, `market_candles`, `signals`, `orders`,
  `fills`, `positions`, `control_events`, `alert_events`, and
  `performance_snapshots`.
- Repository classes for writes and query summaries.
- Startup migration check.
- Daily performance snapshot writer.

Acceptance:

- Migration test creates a fresh database and runs all queries.
- Every control command and trading decision can be written as an event.
- Restart reads the last bot mode and latest exchange reconciliation state.

### `bot-engine`

Owns runtime orchestration.

Deliverables:

- Market data loop.
- Strategy evaluation loop.
- Risk manager.
- Order intent processor.
- Exchange reconciliation loop.
- Paper execution adapter.
- Testnet/live execution adapter boundary.
- Daily summary job.

Acceptance:

- Paper mode can run without Bybit private credentials.
- `PAUSE_NEW_ENTRIES` blocks entries but keeps exits and reconciliation active.
- `PAUSE_ALL` blocks strategy-created orders.
- `EMERGENCY_STOP` cancels open orders and applies configured position policy.
- Restart enters `RESUME_PENDING_CHECK` before returning to `RUNNING`.

### `bot-api`

Owns private Ktor API routes. Route handlers must stay thin.

Initial endpoints:

```text
GET  /health
GET  /status
GET  /performance/summary
GET  /signals/recent
GET  /trades/recent
POST /control/pause-new-entries
POST /control/pause-all
POST /control/resume
POST /control/emergency-stop
```

Acceptance:

- All mutating control endpoints require an auth token.
- Control endpoints write `control_events`.
- API DTOs are separate from domain and database types.
- Error responses do not leak credentials or raw exchange payloads.

### `bot-alerts`

Owns outbound notifications.

Deliverables:

- `AlertSink` interface.
- Telegram alert sink.
- Discord webhook alert sink.
- Composite sink that logs failures and continues.
- Alert templates for startup, shutdown, trade signal, fill, rejected order,
  risk lock, pause, resume, emergency stop, and daily summary.

Acceptance:

- Alert failures do not stop trading loops.
- Alert payloads include mode, strategy, symbol, score, risk, and reason code
  when available.
- Fake alert sink is used in tests.

### `bot-app`

Owns executable wiring.

Deliverables:

- Loads config.
- Creates Ktor server.
- Creates database.
- Wires exchange, ledger, engine, alerts, and API.
- Supports `paper`, `testnet`, and `live` runtime modes.

Acceptance:

- App starts in paper mode without private exchange credentials.
- Missing live credentials fail fast before trading starts.
- Shutdown closes WebSocket, database, and coroutine scopes cleanly.

## Configuration

Use a checked-in example config and environment variables for secrets.

Required non-secret config:

- `bot.symbol`.
- `bot.timeframes`.
- `bot.mode`.
- `risk.normalRiskPerTrade`.
- `risk.highConfidenceRisk`.
- `risk.maxDailyLoss`.
- `risk.maxWeeklyLoss`.
- `risk.maxMonthlyLoss`.
- `risk.maxConsecutiveLosses`.
- `api.host`.
- `api.port`.
- `alerts.telegram.enabled`.
- `alerts.discord.enabled`.

Secrets:

- `BYBIT_API_KEY`.
- `BYBIT_API_SECRET`.
- `BOT_CONTROL_TOKEN`.
- `TELEGRAM_BOT_TOKEN`.
- `TELEGRAM_CHAT_ID`.
- `DISCORD_WEBHOOK_URL`.

Rule:

- `.env`, real config files, API keys, and exchange credentials must not be
  committed.

## Build Order

### Step 1: Gradle skeleton

Create the multi-module Gradle project and make the empty application boot.

Done when:

- `./gradlew test` passes.
- `./gradlew :modules:bot-app:run` starts Ktor.
- `GET /health` returns ok.

### Step 2: Domain and config

Add domain types, bot modes, config loading, and validation.

Done when:

- Invalid config fails before runtime starts.
- Bot mode transitions are unit tested.
- Paper mode starts without Bybit private credentials.

### Step 3: Ledger

Add SQLDelight schema and repositories.

Done when:

- Fresh database migration passes.
- Control events, signals, orders, fills, and alert events can be inserted.
- Recent status and performance queries return deterministic test data.

### Step 4: Alerts

Add Telegram and optional Discord outbound alerts.

Done when:

- Startup and shutdown alerts work in a dry-run or fake-sink test.
- Failed alert delivery is stored in `alert_events`.
- Trading loops are not blocked by alert failures.

### Step 5: Ktor control API

Add private status and control endpoints.

Done when:

- Status returns current mode, heartbeat, open position summary, risk limits,
  and latest performance snapshot.
- Control endpoints require `BOT_CONTROL_TOKEN`.
- Pause, resume, and emergency stop write ledger events.

### Step 6: Market data

Add Bybit public market data integration.

Done when:

- Historical candles can be fetched.
- Public WebSocket candles can be consumed.
- Duplicate and stale candle handling is tested.
- Candles are stored in the ledger.

### Step 7: Indicators and strategy scoring

Add indicator and scoring logic.

Done when:

- Indicators match fixed fixture expectations.
- Mean reversion scorer emits accepted, rejected, and no-trade cases.
- Breakout/retest scorer emits accepted, rejected, and no-trade cases.
- Runner management only acts on existing profitable position state.

### Step 8: Backtest and performance report

Add a simple deterministic backtest engine before real orders.

Done when:

- Fees, slippage assumption, partial take profit, breakeven stop, ATR trailing,
  and funding cost placeholders are represented.
- Report includes net PnL, PnL by strategy, profit factor, expectancy, average
  R, max drawdown, consecutive losses, and no-trade reasons.
- Report includes observed trading days, trades per day, trades per active day,
  and whether the strategy fits the 1 to 5 trades per day operating cadence.
- Volume-flow backtests can compare M1 and M5 setup candles and optionally
  require M5 VWAP alignment before 15m context confirmation.

### Step 9: Paper trading

Run live market data through strategy, risk, paper execution, ledger, and alerts.

Done when:

- Paper trades are linked to signals.
- Daily summaries are sent by alert sink.
- Pause and resume behavior works during paper mode.
- No real private Bybit order endpoint is called.

### Step 10: Testnet execution

Add private Bybit order, position, and fill integration.

Done when:

- Testnet order create, cancel, query, and reconciliation work.
- Client order ids prevent duplicate order creation.
- Partial fills and rejected orders are stored correctly.
- Emergency stop behavior is verified on testnet.

### Step 11: Small live readiness

Prepare live mode but keep it gated.

Done when:

- Live mode requires explicit config.
- Max risk limits are low by default.
- Startup sends a live-mode warning alert.
- Reconciliation must pass before trading starts.

## First Milestone Cut

The first useful milestone should stop after Step 5.

Milestone output:

- Kotlin multi-module project.
- Ktor health, status, and control API.
- SQLite event ledger.
- Telegram alert sink.
- Bot mode state machine.
- No exchange trading yet.

Why stop there:

- It proves the operational shell before any money-moving logic exists.
- It makes pause, resume, emergency stop, logging, and alerting testable early.
- It keeps the first review focused on code readability and maintainability.

## Second Milestone Cut

The second milestone should stop after Step 9.

Milestone output:

- Public Bybit market data.
- Indicators.
- Strategy scoring.
- Backtest report.
- Paper trading loop.
- Alerts and control API integrated with paper mode.

Why stop there:

- It answers whether the strategy shape has evidence before private Bybit order
  permissions are connected.
- It creates comparable performance metrics before live execution complexity is
  introduced.

## Third Milestone Cut

The third milestone should stop after Step 10.

Milestone output:

- Bybit testnet private execution.
- Reconciliation loop.
- Duplicate order protection.
- Emergency stop verification.

Why stop there:

- It validates operational safety before small live capital is considered.

## Coding Rules

- Prefer explicit classes and constructor injection over framework magic.
- Route handlers must only parse requests, call services, and return DTOs.
- Strategy code must not import exchange or API modules.
- Exchange modules must not decide strategy or risk.
- Ledger writes should be append-first for audit events.
- Every order intent needs a linked signal or manual control reason.
- Any code path that can place or cancel orders needs tests.

## Review Checklist

- Can the owner read the code path from signal to order intent without jumping
  through hidden framework behavior?
- Can every state-changing command be traced in the ledger?
- Can alert failure happen without stopping the bot?
- Can restart recover mode, orders, positions, and latest reconciliation state?
- Can strategy profitability be reviewed after fees and estimated costs?
- Are live credentials impossible to commit through normal config files?
