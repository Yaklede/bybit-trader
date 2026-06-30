# Bybit Trader Project Plan

## PRD

### Problem

BTC perpetual trading bots often fail in two ways: they either wait too long for
large trend setups or overtrade without enough risk control. This project needs
a bot that can pursue high upside while keeping operations, monitoring, and
maintenance simple enough for one operator to run.

### Goals

- Build a Bybit BTCUSDT perpetual bot with repeatable strategy logic.
- Support short-term cashflow trades, breakout/retest trades, and runner-based
  trend participation.
- Keep every signal, rejected signal, order, fill, fee, funding cost, and manual
  control action auditable.
- Provide webhook alerts and remote pause/resume controls through a private
  Ktor API.
- Verify whether the system is actually profitable after fees, slippage, and
  funding costs.

### Non-goals

- Do not promise fixed monthly returns.
- Do not add machine learning in the first version.
- Do not support many exchanges in the first version.
- Do not optimize for public SaaS users in the first version.
- Do not build the Android app in the first implementation phase.
- Do not automate withdrawals or credential management beyond local secure
  configuration.

### Success metrics

- Paper trading and live trading produce comparable reports using the same
  metrics.
- Every live order can be traced back to a signal score, strategy, risk decision,
  and control state.
- The operator can pause new entries, pause all trading, resume, and emergency
  stop through authenticated local or private-network API commands.
- Strategy-level net PnL is visible after fees, slippage estimates, and funding
  costs.
- Backtests rank strategies by compounded net return, return-to-drawdown ratio,
  profit factor, and expectancy. Trade frequency is reported as an operator
  observation metric, not a pass/fail target.
- The bot can restart without losing knowledge of open orders, open positions,
  or current control state.
- Daily 0.5 to 2.0 percent return is treated as an upside target to validate,
  not as a guaranteed operating assumption.

### Requirements

- Exchange: Bybit V5 API for BTCUSDT perpetual trading.
- Execution: isolated margin, low leverage, explicit position sizing, and
  kill-switch behavior.
- Data: 1m, 5m, and 15m candles at minimum, plus account, order, fill, fee, and
  funding records.
- Strategy: score-based entry rules that use 1m execution triggers, 5m local
  flow, and 15m context filters. Runner logic is handled as position
  management, not as the default entry mode.
- Risk: daily, weekly, monthly, and consecutive-loss limits.
- Control: `RUNNING`, `PAUSE_NEW_ENTRIES`, `PAUSE_ALL`, `EMERGENCY_STOP`, and
  `RESUME_PENDING_CHECK` states.
- Monitoring: Telegram bot messages first, optional Discord webhook mirror for
  passive logs.
- Control API: Ktor endpoints for status, pause, resume, emergency stop, and
  performance snapshots.
- Backtesting: include fees, slippage assumptions, funding costs, partial take
  profit, breakeven stop, ATR trailing stop, and rejected signal analysis.

### Risks

- Backtest overfitting can make the strategy look profitable before live costs.
- WebSocket disconnects or stale market data can create incorrect decisions.
- Partial fills, rejected orders, or duplicate retries can create unintended
  exposure.
- A remote pause command can be dangerous if it stops position management while
  a position is open.
- Monthly 20 percent targets can pressure the system toward excessive leverage
  or overtrading.
- Bybit API limits, exchange outages, and account mode differences can break
  assumptions.

## Product Scope

### Core user

The initial ICP is a single technical operator who wants to run, inspect, and
control a private BTC trading bot through server logs, a private API, and
webhook alerts. The product is not positioned as a public managed investment
service.

### Positioning

The system is positioned as an auditable personal trading engine: strategy
logic, risk controls, operational state, and profitability evidence are all
visible before scaling capital.

### Channel

Private GitHub repository workflow first, then direct operation through a
private Ktor API and webhook notifications. Android native operation is a later
phase after the trading engine proves stable.

### Pricing

No external pricing in v1. The economic model is personal capital efficiency,
measured by risk-adjusted net return after fees and operational costs.

## User Stories and Acceptance Criteria

### Operator status check

As an operator, I want to check the bot status through a private API so that I
can decide whether to leave it running.

Acceptance criteria:

- Shows current mode, account equity, open position, open orders, and latest
  heartbeat.
- Shows today, week, and month net PnL.
- Shows whether risk limits are close to being hit.

### Operator pause and resume

As an operator, I want to pause and resume the bot through authenticated control
commands so that I can manage market or personal risk without editing runtime
state manually.

Acceptance criteria:

- `PAUSE_NEW_ENTRIES` blocks only new entries and keeps exits active.
- `PAUSE_ALL` blocks new order creation except explicitly allowed protective
  actions.
- `RESUME_PENDING_CHECK` reconciles exchange state before returning to
  `RUNNING`.
- Every control action is logged with timestamp, actor, previous state, and new
  state.

### Webhook alerting

As an operator, I want important bot events sent to Telegram and optionally
Discord so that I can notice issues without opening logs.

Acceptance criteria:

- Sends alerts for startup, shutdown, heartbeat failure, accepted trade signal,
  rejected order, filled order, stop loss, take profit, daily summary,
  risk-limit lock, pause, resume, and emergency stop.
- Alerts include symbol, strategy, score, action, risk, reason code, and current
  bot mode when relevant.
- Alert delivery failures are logged without blocking trading or
  reconciliation.

### Profitability review

As an operator, I want to see profitability by strategy and signal grade so that
I can disable losing logic instead of judging the bot only by total PnL.

Acceptance criteria:

- Reports net PnL after fees, slippage estimates, and funding costs.
- Splits performance by mean reversion, breakout/retest, and runner management.
- Shows win rate, profit factor, expectancy, average R, max drawdown, and
  consecutive losses.
- Includes rejected signal count and top no-trade reasons.

### Emergency handling

As an operator, I want an emergency stop so that I can cancel exposure quickly
when the bot behaves incorrectly.

Acceptance criteria:

- Cancels open orders.
- Optionally closes open position only when the configured emergency policy
  allows it.
- Sends a critical webhook alert.
- Requires reconciliation before normal trading can resume.

## Strategy Plan

### Engine 1: Intraday volume-flow scalping

- Timeframe: 1m trigger, 5m local flow, 15m context.
- Purpose: primary cashflow engine that compounds only when high-conviction
  flow setups appear; quiet periods are acceptable.
- Example inputs: relative volume expansion, volume z-score, candle body and
  close location, local range breakout or failed breakout, VWAP side, estimated
  fee-to-risk ratio.
- Position management: fixed stop, optional fixed target, optional runner
  trailing exit, daily stop lock, optional daily target lock, and
  consecutive-loss lock.
- Constraint: trade frequency is diagnostic only; a passing result must show
  repeatable compounded net return after fees, slippage, and drawdown.

### Engine 2: Cashflow mean reversion

- Timeframe: primarily 15m.
- Purpose: frequent small opportunities.
- Example inputs: RSI, Bollinger Band re-entry, VWAP reclaim/rejection, ATR risk
  width.
- Position management: partial take profit at fixed R levels, then breakeven or
  trailing stop.

### Engine 3: Breakout and retest

- Timeframe: 1h structure with 15m execution confirmation.
- Purpose: secondary medium-frequency higher R opportunities.
- Example inputs: prior high/low break, failed retest, EMA alignment, VWAP side,
  volatility and spread checks.

### Engine 4: Runner management

- Timeframe: position state and trend continuation checks.
- Purpose: keep a minority of already-profitable size for larger moves.
- Rule: runner is not a standalone entry engine in v1.

### Signal arbitration

- Each strategy emits an intent with direction, score, setup grade, invalidation
  price, expected R, and reason codes.
- A single decision layer chooses whether to enter, skip, reduce risk, or only
  log the signal.
- Conflicting long/short intents on the same symbol result in no new entry
  unless one side passes a stronger threshold and risk rules allow it.

## Technical Stack

### Runtime

- Kotlin/JVM for the live trading engine and private API.
- Gradle Kotlin DSL for builds.
- `kotlinx.coroutines` for structured concurrency.
- `kotlinx.serialization` for API payloads and exchange DTOs.
- Docker Compose or `systemd` for deployment after local validation.

### Server framework decision

Decision: use Ktor, not Spring Boot, for v1.

Reasoning:

- The project is a private trading daemon with a small control API, not a large
  enterprise CRUD system.
- The owner is already comfortable with Kotlin and wants code that remains easy
  to read, audit, and manually edit after AI generation.
- Ktor keeps routing, plugins, serialization, authentication, and WebSocket
  behavior explicit in code.
- The trading engine needs coroutine-first long-running work: market streams,
  reconciliation loops, risk checks, alerts, and scheduled summaries.
- Using Ktor for both server and Bybit HTTP/WebSocket clients keeps the
  asynchronous stack consistent.
- Spring Boot has stronger built-in production features, especially Actuator,
  but those benefits come with more framework conventions, annotations,
  reflection, proxy behavior, and dependency-injection magic than this v1 needs.

Spring Boot should be reconsidered only if the project later needs a larger
back-office API, team-based enterprise conventions, deep Spring Security usage,
Actuator-first infrastructure, or broader Spring ecosystem integrations.

Implementation rule:

- Do not mix Ktor and Spring in v1.
- Keep application wiring explicit.
- Prefer constructor injection by plain Kotlin classes.
- Keep route handlers thin and delegate to domain services.
- Keep exchange DTOs, app API DTOs, and database records as separate types.

### Exchange and data

- Custom Bybit V5 REST and WebSocket client in Kotlin.
- Ktor Client for HTTP and WebSocket transport.
- SQLite for runtime state, orders, fills, control events, and audit logs.
- SQLDelight for readable SQL files, generated type-safe queries, and SQLite
  migrations.
- Parquet plus DuckDB exports for larger offline performance analysis.
- Indicator calculations implemented in Kotlin with deterministic fixture tests.

Source notes:

- Bybit documents its V5 API as the unified API surface for account, market, and
  trade endpoints: https://bybit-exchange.github.io/docs/v5/intro
- Bybit publishes API rate-limit behavior in its V5 documentation:
  https://bybit-exchange.github.io/docs/v5/rate-limit
- Ktor Client supports WebSocket sessions on the client side:
  https://ktor.io/docs/client-websockets.html
- Spring Framework provides first-class Kotlin support:
  https://docs.spring.io/spring-framework/reference/languages/kotlin.html
- Spring Boot Actuator provides production-ready monitoring and management
  features: https://docs.spring.io/spring-boot/reference/actuator/index.html

### Backend and control plane

- Ktor Server for private API, health checks, and control actions.
- Kotlin service modules for strategy, risk, execution, ledger, performance, and
  control state.
- Telegram Bot API for critical fallback alerting and basic emergency commands.
- Optional Discord webhook mirror for passive logs.

Source notes:

- Telegram Bot API supports bot-based message sending and command handling:
  https://core.telegram.org/bots/api
- Discord webhook execution is available through Discord developer
  documentation: https://discord.com/developers/docs/resources/webhook
- Ktor provides Kotlin server and client tooling:
  https://ktor.io/docs/welcome.html

### Server module boundaries

The server should start as a Gradle multi-module project:

- `bot-domain`: orders, positions, signals, risk rules, control states, and
  value objects.
- `bot-strategy`: indicators, scoring, mean reversion, breakout/retest, and
  runner management.
- `bot-exchange-bybit`: Bybit V5 REST/WebSocket client, signing, rate-limit
  handling, reconnects, and exchange DTO mapping.
- `bot-ledger`: SQLDelight database access, migrations, event ledger, and
  performance snapshots.
- `bot-engine`: orchestration loop, reconciliation loop, risk gating, and order
  intent processing.
- `bot-api`: Ktor routes for health checks, status, performance, control
  commands, and future client integrations.
- `bot-alerts`: Telegram and optional Discord notification adapters.
- `bot-app`: executable application wiring.

Future Android app work must call `bot-api`; it must not connect directly to
Bybit or hold exchange API keys. The Android app is out of scope for the first
implementation phase.

### Quality and testing

- Kotest or JUnit 5 for unit and integration tests.
- MockK for exchange and API boundary tests.
- ktlint or Detekt for style and static analysis.
- Deterministic fixture-based tests for indicator values, signal scores, risk
  decisions, database migrations, control state transitions, and order state
  transitions.

## Architecture

```text
Market Data
  -> Indicator Engine
  -> Strategy Engines
  -> Signal Arbitration
  -> Risk Manager
  -> Execution Manager
  -> Exchange

All events
  -> Event Ledger
  -> Notifier
  -> Performance Reports

Control Plane
  -> Bot State Machine
  -> Risk Manager
  -> Execution Manager
```

## Data Model

Minimum tables:

- `bot_state`: current mode, heartbeat, restart metadata.
- `market_candles`: symbol, timeframe, OHLCV, source timestamp.
- `signals`: strategy, direction, score, grade, reason codes, accepted flag,
  rejection reason.
- `orders`: exchange order id, client order id, side, type, status, intended
  risk, linked signal id.
- `fills`: fill price, quantity, fee, liquidity role, timestamp.
- `positions`: reconciled position state and realized/unrealized PnL snapshots.
- `control_events`: pause, resume, emergency stop, actor, reason.
- `performance_snapshots`: daily, weekly, monthly, and strategy-level metrics.

## Operating Modes

```text
RUNNING
PAUSE_NEW_ENTRIES
PAUSE_ALL
EMERGENCY_STOP
RESUME_PENDING_CHECK
```

Rules:

- `RUNNING`: signals, entries, exits, and reconciliation are active.
- `PAUSE_NEW_ENTRIES`: entries are blocked; exits, stops, take profits, runner
  management, and reconciliation continue.
- `PAUSE_ALL`: strategy decisions are blocked; reconciliation and explicitly
  approved protective actions continue.
- `EMERGENCY_STOP`: open orders are cancelled and emergency position policy is
  applied.
- `RESUME_PENDING_CHECK`: exchange orders, positions, and local state are
  reconciled before returning to `RUNNING`.

## Profitability Validation

The system must prove profitability in stages:

1. Historical backtest.
2. Walk-forward backtest.
3. Paper trading on live data.
4. Testnet execution validation.
5. Small live capital.
6. Gradual risk scaling only after stable net performance.

Required metrics:

- Net PnL after fees, slippage estimates, and funding costs.
- PnL by strategy.
- PnL by setup grade.
- Profit factor.
- Expectancy per trade.
- Average R multiple.
- Max drawdown.
- Consecutive losses.
- Daily and weekly loss-limit hits.
- Rejected signals and no-trade reasons.

Decision gates:

- Do not scale capital if live or paper results diverge materially from
  backtest assumptions.
- Disable a strategy if its net expectancy stays negative across a sufficient
  sample.
- Keep daily 0.5 to 2.0 percent as an upside target to validate, not a
  requirement that overrides risk limits.

## Implementation Roadmap

### Phase 0: Repository foundation

- Add project documentation.
- Add Gradle multi-module Kotlin project skeleton.
- Use Ktor as the only web server framework.
- Add config model and safe `.env.example`.
- Add lint and test commands.

### Phase 1: Data and ledger

- Implement Bybit market data client.
- Store candles and account snapshots.
- Store event ledger records for signals, orders, fills, and control events.
- Add restart-safe bot state.

### Phase 2: Backtesting and reports

- Implement indicator calculations.
- Implement intraday volume-flow, mean reversion, and breakout/retest signal
  scoring.
- Implement partial take profit, breakeven, and ATR trailing simulation.
- Generate strategy-level performance reports with compounded return,
  return-to-drawdown, expectancy, and trade-frequency observations.

### Phase 3: Paper trading

- Run live market data through the strategy and risk engines.
- Simulate orders and fills.
- Compare expected versus actual market movement.
- Start Telegram alerts for signal, trade, error, and daily summary events.

### Phase 4: Control plane and webhook operations

- Add Ktor private API service.
- Add status, performance, pause, resume, and emergency stop endpoints.
- Add Telegram and optional Discord webhook alerts.
- Add Telegram commands for status, pause new entries, resume, and emergency
  stop only after one-way alerts are stable.
- Add authentication before exposing any API beyond localhost or private
  network.

### Phase 5: Testnet and small live trading

- Integrate Bybit testnet execution.
- Add exchange reconciliation loop.
- Add duplicate order protection with client order ids.
- Start small live trading only after paper and testnet acceptance criteria are
  met.

### Phase 6: Optimization

- Tune scoring thresholds using out-of-sample results.
- Remove or disable losing strategy branches.
- Add more symbols only if BTCUSDT operation is stable.

### Later phase: Android native app

- Build only after the Ktor bot, event ledger, webhook alerting, paper trading,
  and testnet execution are stable.
- Use the existing Ktor API instead of introducing exchange keys into the app.
- Keep the app focused on status, trades, signals, performance, and control
  screens.

## Handoff Checklist

- PRD includes problem, goals, non-goals, success metrics, requirements, and
  risks.
- User stories include acceptance criteria.
- Product scope includes ICP, channel, pricing, and positioning.
- Claims include source notes where external platform capability is referenced.
- No guaranteed return claim is made.
- Release notes are not required yet because no product release or breaking
  change exists.
