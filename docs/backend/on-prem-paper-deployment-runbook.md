# On-Prem Deployment Runbook

## Scope

This runbook prepares the bot for Docker-based on-prem operation behind
Twingate. `PAPER` mode runs public Bybit candles through the aggressive
`absa_final_us_v1` strategy and records paper signals/orders/fills/positions.
`LIVE` and `TESTNET` modes can submit private Bybit V5 linear futures market
orders with TP/SL, reconcile open orders/positions/executions, send alerts, and
accept authenticated control commands.

For the intended live-tuning workflow, start `LIVE` with a small
`BOT_EXECUTION_MAX_NOTIONAL` and `BOT_EXECUTION_LOOP_ENABLED=false`. Run one
manual order and reconciliation before enabling the loop.

## Docker Host Layout

```bash
/opt/bybit-trader/
  compose.yaml
  .env
  config/volume-flow-composite-current.json
  env/bybit-trader.env
  images/
```

`/opt/bybit-trader/.env` contains Docker Compose deployment values only:

```bash
BOT_IMAGE=bybit-trader:<git-sha>
BOT_BIND_HOST=127.0.0.1
BOT_API_PORT=8080
BOT_ENV_FILE=/opt/bybit-trader/env/bybit-trader.env
```

`/opt/bybit-trader/env/bybit-trader.env` contains application secrets and bot
runtime settings. For GitHub Actions deployment, this file is generated from
the `onprem-live` GitHub Environment secrets and variables. It must never be
committed.

## Required Application Environment

```bash
export BOT_MODE="LIVE"
export BOT_API_HOST="0.0.0.0"
export BOT_API_PORT="8080"
export BOT_CONTROL_TOKEN="<operator-control-token>"
export BOT_DATABASE_PATH="/data/bybit-trader.sqlite"
export BOT_SYMBOL="BTCUSDT"
export BOT_TIMEFRAMES="M1,M5,M15"
export BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH="/opt/bybit-trader/config/volume-flow-composite-current.json"
```

Optional forward-only market collection for later strategy research:

```bash
export BOT_FORWARD_MARKET_CAPTURE_ENABLED="false"
export BYBIT_PUBLIC_WEBSOCKET_URL="wss://stream.bybit.com/v5/public/linear"
export BOT_FORWARD_ORDER_BOOK_DEPTH="50"
```

Set `BOT_FORWARD_MARKET_CAPTURE_ENABLED=true` only after deployment when you
want to begin accumulating new public data. It does not submit an order or
change strategy evaluation. The dashboard shows `수집 확인됨` after completed
order-book and taker-trade minute bars are stored; an empty liquidation
timestamp is normal when the market has no liquidation event.

For private Bybit live execution, add:

```bash
export BYBIT_API_KEY="<bybit-live-api-key>"
export BYBIT_API_SECRET="<bybit-live-api-secret>"
export BYBIT_PRIVATE_BASE_URL="https://api.bybit.com"
export BYBIT_RECV_WINDOW_MILLIS="5000"
export BYBIT_POSITION_IDX="0"

export BOT_PRIVATE_EXECUTION_ENABLED="true"
export BOT_EXECUTION_LOOP_ENABLED="false"
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
```

Keep `BOT_EXECUTION_LOOP_ENABLED=false` for the first live smoke order. Turn it
on only for a replacement profile that passes its runtime replay gate. The
current `absa_final_us_v1` profile is unverified and must remain disabled.

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

3. Sync enough M5 history for the aggressive 60-day regime rules:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframes":["M5"],"daysBack":90,"pageLimit":1000,"maxRequestsPerTimeframe":1000}' \
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
  --data '{"symbol":"BTCUSDT","timeframe":"M5","candleLimit":18000}' \
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

7. Reconcile Bybit state after any private order:

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

## Operator Checks

- Twingate should be the only network path to the private API or to the host
  that can reach `127.0.0.1:8080` through an approved reverse proxy.
- `/health` is public but local/private only.
- `/status`, `/control/*`, `/market-data/*`, `/paper/evaluate`,
  `/execution/*`, and backtest endpoints require `BOT_CONTROL_TOKEN`.
- `pause-all`, `resume`, and `emergency-stop` write control events and emit
  alerts when an alert sink is configured.
- Paper loop duplicate signals are skipped when the same `ENTRY_AT_*` signal
  key has already been accepted.
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
- Aggressive replay fills at next-candle open plus slippage, while live sizing
  and TP/SL use the closed signal candle close as the pre-fill estimate. This is
  a documented approximation; operators should not expect post-fill TP/SL
  replacement in this release.

## Stop Condition Before Tokens

The repository can be considered ready for on-prem Docker live smoke deployment when
`./gradlew test lint build`, both OpenDock harnesses, smoke tests, and
`node scripts/bot-preflight.mjs` / `node scripts/docker-preflight.mjs` pass
after real operator, alert, and Bybit live tokens are provided through the host
environment file.
