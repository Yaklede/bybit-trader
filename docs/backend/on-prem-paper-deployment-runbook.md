# On-Prem Deployment Runbook

## Scope

This runbook prepares the bot for Docker-based on-prem operation behind
Twingate. The current deployment target is the frozen
`volume-confirmed-trend-ensemble-v1` H4 candidate in isolated Shadow mode. It
uses closed public Bybit H4 candles, records persistent decisions, trades,
funding, equity, integrity state, and forward-approval progress, and cannot
submit private orders. The older `multi-horizon-momentum-development-v2` Paper
loop remains available for development but is not the current approval
candidate.
`LIVE` and `TESTNET` modes can submit private Bybit V5 linear futures market
orders with TP/SL, reconcile open orders/positions/executions, send alerts, and
accept authenticated control commands.

For the current observation workflow, start `PAPER` with every order-producing
loop and all private exchange clients disabled. A fresh continuous 90-day
session is required before human live review; historical replay alone cannot
satisfy this gate.

## Docker Host Layout

```bash
/opt/bybit-trader/
  compose.yaml
  .env
  config/volume-flow-composite-current.json
  config/volume-confirmed-trend-ensemble-v1.json
  config/volume-confirmed-trend-ensemble-v1-bootstrap.json
  config/volume-confirmed-trend-ensemble-v1-external-result.json
  config/volume-confirmed-trend-ensemble-v1-kotlin-parity-result.json
  config/volume-confirmed-trend-ensemble-v1-runtime-parity-result.json
  config/volume-confirmed-trend-ensemble-v1-forward-policy.json
  env/bybit-trader.env
  images/
```

The `.env` file at the deploy root contains Docker Compose deployment values
only:

```bash
BOT_IMAGE=bybit-trader:<git-sha>
BOT_BIND_HOST=127.0.0.1
BOT_API_PORT=8080
BOT_ENV_FILE=/opt/bybit-trader/env/bybit-trader.env
```

The `bybit-trader.env` file under the deploy root's `env` directory contains
application secrets and bot runtime settings. For GitHub Actions deployment,
this file is generated from the `onprem-live` GitHub Environment secrets and
variables. It must never be committed.

## Required Application Environment

```bash
export BOT_MODE="PAPER"
export BOT_API_HOST="0.0.0.0"
export BOT_API_PORT="8080"
export BOT_CONTROL_TOKEN="<operator-control-token>"
export BOT_DATABASE_PATH="/data/bybit-trader.sqlite"
export BOT_SYMBOL="BTCUSDT"
export BOT_TIMEFRAMES="M1,M5,M15"
export BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH="/opt/bybit-trader/config/volume-flow-composite-current.json"
```

For the frozen trend forward observation, add this isolated configuration:

```bash
export BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED="true"
export BOT_VOLUME_CONFIRMED_TREND_PROTOCOL_PATH="/opt/bybit-trader/config/volume-confirmed-trend-ensemble-v1.json"
export BOT_VOLUME_CONFIRMED_TREND_BOOTSTRAP_PATH="/opt/bybit-trader/config/volume-confirmed-trend-ensemble-v1-bootstrap.json"
export BOT_VOLUME_CONFIRMED_TREND_SHADOW_INITIAL_EQUITY="660"
export BOT_PAPER_LOOP_ENABLED="false"
export BOT_MAKER_SHADOW_ENABLED="false"
export BOT_PRIVATE_EXECUTION_ENABLED="false"
export BOT_PRIVATE_EXECUTION_STREAM_ENABLED="false"
export BOT_EXECUTION_LOOP_ENABLED="false"
export BOT_EXECUTION_RECONCILIATION_ENABLED="false"
```

Do not enable another strategy loop in the same container. The deploy workflow
fails before upload when this isolation contract is violated. It also strips
Bybit API credentials from the generated runtime env in `PAPER` mode.

`node scripts/bot-preflight.mjs` recognizes this H4 Shadow profile as a valid
PAPER runtime even though `BOT_PAPER_LOOP_ENABLED=false`. It fails if any Paper,
maker, legacy private execution, private stream, or reconciliation loop is
mixed into the same process.

After the forward gate is ready for review, validate the Bybit TESTNET account
contract before enabling H4 execution. Use a separate temporary Compose project,
database volume, container names, and dashboard port; do not replace or mount
the 90-day Shadow database into this probe runtime. Configure `BOT_MODE=TESTNET`,
testnet credentials, and keep every order-producing flag and both H4 flags
`false`. Then run preflight and the authenticated read-only inspection:

```bash
node scripts/bot-preflight.mjs
curl -fsS \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  http://127.0.0.1:8080/strategy/volume-confirmed-trend/exchange-contract
```

The response must report `available=true`, `valid=true`, `UNIFIED_1` or
`UNIFIED_2`, `CROSS`, `ONE_WAY`, and buy/sell leverage `1`. The endpoint only
reads account, position, and instrument metadata. It cannot change leverage or
submit/cancel an order, and it does not satisfy the human approval gate.

Optional forward-only market collection for later strategy research:

```bash
export BOT_FORWARD_MARKET_CAPTURE_ENABLED="false"
export BYBIT_PUBLIC_WEBSOCKET_URL="wss://stream.bybit.com/v5/public/linear"
export BOT_FORWARD_ORDER_BOOK_DEPTH="50"
export BOT_FORWARD_RAW_ARCHIVE_ENABLED="false"
export BOT_FORWARD_RAW_ARCHIVE_PATH="/data/market-events"
```

Set `BOT_FORWARD_MARKET_CAPTURE_ENABLED=true` only after deployment when you
want to begin accumulating new public data. It does not submit an order or
change strategy evaluation. The dashboard shows `수집 확인됨` after completed
order-book and taker-trade minute bars are stored; an empty liquidation
timestamp is normal when the market has no liquidation event.
Use the panel's `최근 60분 공통 수집` value to verify that both streams are
continuously stored before running the forward-data diagnostic.
When enabled, raw public messages are stored as sealed minute `.ndjson.gz`
segments under `BOT_FORWARD_RAW_ARCHIVE_PATH`. Exclude `.part` files from replay
because they identify an interrupted segment. No automatic retention is applied,
so monitor the Docker volume and archive completed segments externally.
When the stream or minute-bar flush fails, Discord receives `시장 흐름 수집
점검 필요`; repeated alerts are limited to one every 15 minutes.

For causal paper operation, use:

```bash
export BOT_MODE="PAPER"
export BOT_PAPER_LOOP_ENABLED="true"
export BOT_PAPER_STRATEGY="multi-horizon-momentum"
export BOT_PAPER_TIMEFRAME="M5"
export BOT_PAPER_CANDLE_LIMIT="12000"
export BOT_PAPER_SYNC_LIMIT="1000"
export BOT_PAPER_INTERVAL_SECONDS="300"
export BOT_PAPER_INITIAL_EQUITY="1000000"
export BOT_PAPER_RISK_FRACTION="0.01"
export BOT_PAPER_FEE_RATE="0.0006"
export BOT_PRIVATE_EXECUTION_ENABLED="false"
export BOT_PRIVATE_EXECUTION_STREAM_ENABLED="false"
export BOT_EXECUTION_LOOP_ENABLED="false"
export BOT_EXECUTION_RECONCILIATION_ENABLED="false"
```

The loop automatically warms the minimum required M5 history before evaluation.
It does not reconstruct historical paper trades on first boot; it begins with
the latest closed candle and waits for the next contiguous candle before a fill.

For private Bybit live execution, add:

```bash
export BYBIT_API_KEY="<bybit-live-api-key>"
export BYBIT_API_SECRET="<bybit-live-api-secret>"
export BYBIT_PRIVATE_BASE_URL="https://api.bybit.com"
export BYBIT_RECV_WINDOW_MILLIS="5000"
export BYBIT_POSITION_IDX="0"

export BOT_PRIVATE_EXECUTION_ENABLED="true"
export BOT_EXECUTION_LOOP_ENABLED="false"
export BOT_EXECUTION_RECONCILIATION_ENABLED="true"
export BOT_EXECUTION_RECONCILIATION_INTERVAL_SECONDS="60"
export BOT_EXECUTION_TIMEFRAME="M5"
export BOT_EXECUTION_CANDLE_LIMIT="18000"
export BOT_EXECUTION_SYNC_LIMIT="1000"
export BOT_EXECUTION_ALERT_BATCH_LIMIT="100"
export BOT_EXECUTION_INTERVAL_SECONDS="300"
export BOT_EXECUTION_ACCOUNT_EQUITY="660"
export BOT_EXECUTION_USE_LIVE_EQUITY="true"
export BOT_EXECUTION_RISK_FRACTION="0.055"
export BOT_EXECUTION_FEE_RATE="0.0006"
export BOT_EXECUTION_SLIPPAGE_BUFFER_RATE="0.0002"
export BOT_EXECUTION_QTY_STEP="0.001"
export BOT_EXECUTION_MIN_QTY="0.001"
export BOT_EXECUTION_MAX_QTY=""
export BOT_EXECUTION_MAX_NOTIONAL="<initial-live-notional-cap>"
export BOT_EXECUTION_LEVERAGE="15"
export BOT_EXECUTION_SAFETY_VERIFICATION_ATTEMPTS="5"
export BOT_EXECUTION_SAFETY_VERIFICATION_INTERVAL_MILLIS="250"
export BOT_EXECUTION_CIRCUIT_BREAKER_ENABLED="true"
export BOT_EXECUTION_MAX_DAILY_LOSS_FRACTION="0.03"
export BOT_EXECUTION_MAX_ACCOUNT_DRAWDOWN_FRACTION="0.20"
export BOT_EXECUTION_MAX_CONSECUTIVE_LOSSES="3"
export BOT_EXECUTION_RISK_STATE_MAX_AGE_SECONDS="120"
export BOT_EXECUTION_WALLET_RECONCILIATION_ENABLED="true"
export BOT_EXECUTION_WALLET_RECONCILIATION_TOLERANCE="0.01"
export BOT_EXECUTION_WALLET_RECONCILIATION_MAX_AGE_SECONDS="180"
export BOT_EXECUTION_WALLET_RECONCILIATION_CONFIRMED_MISMATCHES="3"
```

Keep `BOT_EXECUTION_LOOP_ENABLED=false` for the first live smoke order. Turn it
on only for a replacement profile that passes its runtime replay gate. The
current `absa_final_us_v1` profile is rejected and must remain disabled. Keep
the reconciliation loop enabled so manual orders and exchange-side exits remain
observable.

The account circuit breaker is enabled by default. It persists the account
equity high-water mark, UTC-day opening equity, latest equity, consecutive
closed-trade losses, and the last processed closure. Missing or stale state,
a daily equity loss of 3%, a 20% account drawdown, or three consecutive losses
blocks new automatic entries. Existing positions remain under the shared
stop, trailing, and maximum-hold policy. Do not disable the breaker to make a
rejected strategy trade.

Wallet reconciliation is also enabled by default. After startup, wait for the
first `BASELINE` and the following `MATCHED` reconciliation before considering
any automatic entry. A missing/stale transaction sync or wallet-ledger mismatch
blocks new entries while existing position management remains active.
The same baseline initializes cash-flow-adjusted strategy NAV. Deposits and
withdrawals change units, while daily loss and drawdown remain based on NAV.
Use a dedicated Unified account; unrelated loans, earn products, conversions,
and account transfers are treated as capital outside this strategy. Do not place
manual trades in the same account because Bybit reports them as `TRADE`, which
is indistinguishable from bot performance at the account-transaction layer.
The alert sink sends `신규 진입 자동 차단` only when the active risk-reason set
first appears or changes, then sends `신규 진입 차단 해제` once after recovery.
Repeated five-minute loop evaluations with the same reason do not create alert
spam.

Enable at least one alert sink:

```bash
export TELEGRAM_ALERTS_ENABLED="true"
export TELEGRAM_BOT_TOKEN="<telegram-bot-token>"
export TELEGRAM_CHAT_ID="<telegram-chat-id>"
```

or:

```bash
export DISCORD_ALERTS_ENABLED="true"
export DISCORD_WEBHOOK_URL="<discord-webhook-url>"
```

## Bootstrap

1. Build locally if needed:

```bash
docker build -t bybit-trader:local .
```

2. Start or restart on the host:

```bash
cd /opt/bybit-trader
docker compose --env-file .env -f compose.yaml up -d
docker compose --env-file .env -f compose.yaml ps
```

3. The Paper loop automatically warms required M5 history. To pre-warm it
   manually before starting the loop, run:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframes":["M5"],"daysBack":45,"pageLimit":1000,"maxRequestsPerTimeframe":1000}' \
  http://127.0.0.1:8080/market-data/history/sync
```

4. Run preflight:

```bash
node scripts/bot-preflight.mjs
ONPREM_DEPLOY_DIR=/opt/bybit-trader node scripts/docker-preflight.mjs
```

5. Run a manual paper evaluation in `PAPER` mode:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M5","candleLimit":12000}' \
  http://127.0.0.1:8080/paper/evaluate
```

6. Run a manual execution evaluation in `LIVE` mode with
   `BOT_EXECUTION_LOOP_ENABLED=false`:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M5","candleLimit":18000}' \
  http://127.0.0.1:8080/execution/evaluate-and-submit
```

7. Confirm the background reconciliation result after any private order. The
manual endpoint remains available for an immediate read-only snapshot:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT"}' \
  http://127.0.0.1:8080/execution/reconcile
```

8. Cancel an open order if needed:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","clientOrderId":"<client-order-id>"}' \
  http://127.0.0.1:8080/execution/orders/cancel
```

9. Use safe stop when new entries must stop while protected positions continue
   to be managed. The response must report `safety.status=CONFIRMED`; `PENDING`
   or `FAILED` means the exchange state still requires review.

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"reason":"operator safe stop"}' \
  http://127.0.0.1:8080/control/safe-stop
```

10. Use flatten only when every open position must be closed. It cancels active
    entry orders, submits reduce-only market exits, and verifies that positions
    and orders are gone. `emergency-stop` is a backward-compatible alias.

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"reason":"operator flatten"}' \
  http://127.0.0.1:8080/control/flatten
```

## Operator Checks

- Twingate should be the only network path to the private API or to the host
  that can reach `127.0.0.1:8080` through an approved reverse proxy.
- `/health` is public but local/private only.
- `/status`, `/control/*`, `/market-data/*`, `/paper/evaluate`,
  `/execution/*`, and backtest endpoints require `BOT_CONTROL_TOKEN`.
- `pause-all`/`safe-stop`, `resume`, and `emergency-stop`/`flatten` write control
  events and emit alerts when an alert sink is configured. Safety commands emit
  a second exchange-verification alert containing cancellation/close counts,
  remaining exposure, and issue codes. Safe stop keeps protected positions
  managed; flatten requires exchange-confirmed zero positions and zero active
  orders before it reports `CONFIRMED`. Treat `PENDING` as unresolved exposure
  and `FAILED` as an immediate manual Bybit inspection condition. The
  reconciliation loop continues verification in a persisted safety mode and
  sends a later transition alert when the exchange confirms completion; an
  unchanged status is suppressed.
- Paper runtime state prevents a closed candle from being evaluated twice and
  restores pending/open positions after restart.
- A signal is persisted as `ENTRY_PENDING` and can fill only at the next
  contiguous M5 open. Paper exits and compounded equity are exposed by the API.
- Private execution is blocked unless `BOT_MODE=TESTNET` or `BOT_MODE=LIVE`,
  Bybit credentials are present, and `BOT_PRIVATE_EXECUTION_ENABLED=true`.
- The order create response is treated as submitted only. Use
  `/execution/reconcile` to inspect Bybit open orders, positions, and recent
  fills after submission. This endpoint is read-only; the enabled execution
  loop alone persists new closed PnL and sends close alerts.
- The execution loop handles closure persistence and alerts before closed M5
  sync and entry evaluation, so a public market-data failure does not delay
  closure detection until a later successful evaluation cycle.
- Close alerts are durable at-least-once. Failed deliveries increment the
  closure attempt metadata and retry on the next five-minute M5 cycle without
  blocking other pending alerts or trading evaluation. A crash after Discord
  accepts a message but before SQLite records `delivered_at` can duplicate it.
- An empty mode+symbol closure ledger suppresses provider history older than
  process start as the first-deploy baseline. After that baseline exists,
  restarts enqueue newly discovered downtime closures instead of suppressing
  them.
- Private execution recalculates TP/SL from reconciled actual fills and verifies
  exchange protection. The Paper candidate is not connected to private
  execution, so Paper evidence cannot be treated as live approval.
- H4 forward-validation alerts are transition based. A changed session, overall
  approval status, or gate PASS/PENDING/FAIL state sends one Korean summary with
  observed days, return, Profit Factor, and remaining gates. Repeated H4
  evaluations with the same gate states do not repeat the message. A
  `4시간 전략 검토 준비 완료` alert still requires artifact export and explicit
  human approval; it does not activate TESTNET or LIVE orders.

## Stop Condition Before Tokens

The repository can be considered ready for on-prem Docker live smoke deployment when
`./gradlew test lint build`, both OpenDock harnesses, smoke tests, and
`node scripts/bot-preflight.mjs` / `node scripts/docker-preflight.mjs` pass
after real operator, alert, and Bybit live tokens are provided through the host
environment file.
