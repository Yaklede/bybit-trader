# On-Prem Deployment Runbook

## Scope

This runbook prepares the bot for on-prem operation behind Twingate. `PAPER`
mode runs public Bybit candles through the aggressive `absa_final_us_v1`
strategy and records paper signals/orders/fills/positions. `TESTNET` mode can
submit private Bybit V5 linear futures market orders with TP/SL, reconcile open
orders/positions/executions, send alerts, and accept authenticated control
commands.

Use `TESTNET` before `LIVE`. Live capital should stay blocked until real
operator tokens are supplied, testnet order placement/reconciliation is smoke
tested, and the deployment host is reachable only through Twingate.

## Required Environment

```bash
export BOT_MODE="PAPER"
export BOT_API_HOST="127.0.0.1"
export BOT_API_PORT="8080"
export BOT_CONTROL_TOKEN="<operator-control-token>"
export BOT_DATABASE_PATH="$PWD/data/bybit-trader.sqlite"
export BOT_SYMBOL="BTCUSDT"
export BOT_TIMEFRAMES="M1,M5,M15"

export BOT_PAPER_LOOP_ENABLED="true"
export BOT_PAPER_STRATEGY="volume-flow-aggressive"
export BOT_PAPER_TIMEFRAME="M5"
export BOT_PAPER_CANDLE_LIMIT="18000"
export BOT_PAPER_SYNC_LIMIT="1000"
export BOT_PAPER_INTERVAL_SECONDS="300"
export BOT_PAPER_INITIAL_EQUITY="1000000"
export BOT_PAPER_RISK_FRACTION="0.055"
export BOT_PAPER_FEE_RATE="0.0006"

export BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH="$PWD/config/volume-flow-composite-current.json"
```

For private Bybit testnet execution, switch the mode and add execution settings:

```bash
export BOT_MODE="TESTNET"
export BYBIT_API_KEY="<bybit-testnet-api-key>"
export BYBIT_API_SECRET="<bybit-testnet-api-secret>"
export BYBIT_PRIVATE_BASE_URL="https://api-testnet.bybit.com"
export BYBIT_RECV_WINDOW_MILLIS="5000"
export BYBIT_POSITION_IDX="0"

export BOT_PRIVATE_EXECUTION_ENABLED="true"
export BOT_EXECUTION_LOOP_ENABLED="true"
export BOT_EXECUTION_TIMEFRAME="M5"
export BOT_EXECUTION_CANDLE_LIMIT="18000"
export BOT_EXECUTION_SYNC_LIMIT="1000"
export BOT_EXECUTION_INTERVAL_SECONDS="300"
export BOT_EXECUTION_ACCOUNT_EQUITY="1000000"
export BOT_EXECUTION_RISK_FRACTION="0.055"
export BOT_EXECUTION_FEE_RATE="0.0006"
export BOT_EXECUTION_QTY_STEP="0.001"
export BOT_EXECUTION_MIN_QTY="0.001"
export BOT_EXECUTION_MAX_QTY=""
export BOT_EXECUTION_MAX_NOTIONAL="<testnet-notional-cap>"
```

For live mode, set `BOT_MODE=LIVE` and use a non-testnet `BYBIT_PRIVATE_BASE_URL`.
Keep `BOT_EXECUTION_MAX_NOTIONAL` low for the first live gate.

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

1. Sync enough M5 history for the aggressive 60-day regime rules:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframes":["M5"],"daysBack":90,"pageLimit":1000,"maxRequestsPerTimeframe":1000}' \
  http://127.0.0.1:8080/market-data/history/sync
```

2. Run preflight:

```bash
node scripts/bot-preflight.mjs
```

3. Run a manual paper evaluation in `PAPER` mode:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M5","candleLimit":18000}' \
  http://127.0.0.1:8080/paper/evaluate
```

4. Run a manual execution evaluation in `TESTNET` mode:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M5","candleLimit":18000}' \
  http://127.0.0.1:8080/execution/evaluate-and-submit
```

5. Reconcile Bybit state after any private order:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT"}' \
  http://127.0.0.1:8080/execution/reconcile
```

6. Cancel an open order if needed:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","clientOrderId":"<client-order-id>"}' \
  http://127.0.0.1:8080/execution/orders/cancel
```

7. Start the app under the on-prem process manager:

```bash
./gradlew :modules:bot-app:run
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
  fills after submission.

## Stop Condition Before Tokens

The repository can be considered ready for on-prem testnet deployment when
`./gradlew test lint build`, both OpenDock harnesses, smoke tests, and
`node scripts/bot-preflight.mjs` pass after real operator, alert, and Bybit
testnet tokens are provided through the environment.
