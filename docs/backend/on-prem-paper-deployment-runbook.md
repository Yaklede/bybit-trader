# On-Prem Paper Deployment Runbook

## Scope

This runbook prepares the bot for on-prem paper/shadow operation behind
Twingate. The current deployable mode is `PAPER`: it can sync public Bybit M5
candles, evaluate the aggressive `absa_final_us_v1` runtime strategy, record
paper signals/orders/fills/positions, send alerts, and accept authenticated
control commands.

Private Bybit testnet/live order execution is still a separate milestone. Do
not run `TESTNET` or `LIVE` as a capital-deploying mode until private execution,
reconciliation, and emergency order handling are implemented and tested.

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

3. Run a manual paper evaluation:

```bash
curl -X POST \
  -H "Authorization: Bearer $BOT_CONTROL_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"symbol":"BTCUSDT","timeframe":"M5","candleLimit":18000}' \
  http://127.0.0.1:8080/paper/evaluate
```

4. Start the app under the on-prem process manager:

```bash
./gradlew :modules:bot-app:run
```

## Operator Checks

- Twingate should be the only network path to the private API or to the host
  that can reach `127.0.0.1:8080` through an approved reverse proxy.
- `/health` is public but local/private only.
- `/status`, `/control/*`, `/market-data/*`, `/paper/evaluate`, and backtest
  endpoints require `BOT_CONTROL_TOKEN`.
- `pause-all`, `resume`, and `emergency-stop` write control events and emit
  alerts when an alert sink is configured.
- Paper loop duplicate signals are skipped when the same `ENTRY_AT_*` signal
  key has already been accepted.

## Stop Condition Before Tokens

The repository can be considered ready for on-prem paper deployment when
`./gradlew test lint build`, both OpenDock harnesses, smoke tests, and
`node scripts/bot-preflight.mjs` pass after real operator and alert tokens are
provided through the environment.
