# On-Prem GitHub Actions Deployment

The deployment workflow is `.github/workflows/deploy-onprem.yml`.

It uses the official `twingate/github-action@v1` action to connect a GitHub
runner to the Twingate-protected on-prem host, builds a Docker image, uploads
the image tarball and Compose files with `appleboy/scp-action@v1`, runs remote
Docker commands with `appleboy/ssh-action@v1`, and restarts the service with
Docker Compose.

## Required GitHub Environment

Create a GitHub Environment named `onprem-live`. Add required reviewers if
you want every deploy to need manual approval.

Required connection secrets:

- `TWINGATE_SERVICE_KEY`: Twingate Service key with access only to the on-prem
  deploy host resource.
- `ONPREM_SSH_HOST`: private host name or IP reachable through Twingate.
- `ONPREM_SSH_USER`: deploy user on the host.
- `ONPREM_SSH_PORT`: SSH port, normally `22`.
- One SSH credential:
  - `ONPREM_SSH_PRIVATE_KEY`: private key for the deploy user.
  - `ONPREM_SSH_PASSWORD`: password for the deploy user.
- `ONPREM_DEPLOY_DIR`: deploy root, for example `/opt/bybit-trader`.
- `ONPREM_DOCKER_BIND_HOST`: Docker publish bind address. Use `127.0.0.1` for
  host-local access, or `0.0.0.0` only when Twingate or a private reverse proxy
  must reach the host port directly.

Required runtime secrets:

- `BOT_CONTROL_TOKEN`: private API control token for status/control/backtest
  endpoints.
- `BYBIT_API_KEY`: Bybit API key.
- `BYBIT_API_SECRET`: Bybit API secret.

Optional runtime secrets:

- `TELEGRAM_BOT_TOKEN`: required only when `TELEGRAM_ALERTS_ENABLED=true`.
- `DISCORD_WEBHOOK_URL`: required only when `DISCORD_ALERTS_ENABLED=true`.

Runtime variables can be set in the GitHub Environment Variables tab. The
workflow has safe defaults for all of these, so only set values you want to
override:

- `BOT_MODE`: default `LIVE`. Use `TESTNET` with testnet keys.
- `BOT_API_HOST`: default `0.0.0.0`.
- `BOT_API_PORT`: default `8080`.
- `BOT_DATABASE_PATH`: default `/data/bybit-trader.sqlite`.
- `BOT_SYMBOL`: default `BTCUSDT`.
- `BOT_TIMEFRAMES`: default `M1,M5,M15`.
- `BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH`: default
  `/opt/bybit-trader/config/volume-flow-composite-current.json`.
- `BOT_STRATEGY_PROFILE_STATE_PATH`: default
  `/data/strategy-profile-current.txt`.
- `BOT_FORWARD_MARKET_CAPTURE_ENABLED`: default `false`. Set `true` to record
  public order-book, taker-trade, and liquidation data for future research
  only. This does not submit orders or change the active strategy.
- `BYBIT_PUBLIC_WEBSOCKET_URL`: optional. Defaults by `BOT_MODE` to Bybit's
  public linear WebSocket URL.
- `BOT_FORWARD_ORDER_BOOK_DEPTH`: default `50`, valid range `1` to `50`.
- `BOT_FORWARD_RAW_ARCHIVE_ENABLED`: defaults to the forward-capture setting.
  When enabled, public WebSocket payloads and quality metadata are written to
  sealed minute gzip NDJSON segments.
- `BOT_FORWARD_RAW_ARCHIVE_PATH`: default `/data/market-events`. Only sealed
  `.ndjson.gz` files are complete; `.part` files indicate an interrupted segment.
- `BYBIT_PRIVATE_BASE_URL`: defaults from `BOT_MODE`: `https://api.bybit.com`
  for `LIVE`, `https://api-testnet.bybit.com` for `TESTNET`.
- `BYBIT_RECV_WINDOW_MILLIS`: default `5000`.
- `BYBIT_POSITION_IDX`: default `0`.
- `BOT_PRIVATE_EXECUTION_ENABLED`: default `true`.
- `BOT_EXECUTION_LOOP_ENABLED`: default `false`.
- `BOT_EXECUTION_RECONCILIATION_ENABLED`: default `true`; observes private
  order, position, fill, and closed-PnL state without enabling automatic entry.
- `BOT_EXECUTION_RECONCILIATION_INTERVAL_SECONDS`: default `60`.
- `BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE`: default `false`. This legacy
  override is limited to future unverified TESTNET candidates. It cannot enable
  the rejected `absa_final_us_v1` profile, and LIVE requires a verified profile.
- `BOT_EXECUTION_TIMEFRAME`: default `M5`.
- `BOT_EXECUTION_CANDLE_LIMIT`: default `18000`.
- `BOT_EXECUTION_SYNC_LIMIT`: default `1000`.
- `BOT_EXECUTION_ALERT_BATCH_LIMIT`: default `100`.
- `BOT_EXECUTION_INTERVAL_SECONDS`: default `300`.
- `BOT_EXECUTION_ACCOUNT_EQUITY`: default `660`.
- `BOT_EXECUTION_USE_LIVE_EQUITY`: default `true`.
- `BOT_EXECUTION_RISK_FRACTION`: default `0.055`.
- `BOT_EXECUTION_FEE_RATE`: default `0.0006`.
- `BOT_EXECUTION_SLIPPAGE_BUFFER_RATE`: default `0.0002`.
- `BOT_EXECUTION_QTY_STEP`: default `0.001`.
- `BOT_EXECUTION_MIN_QTY`: default `0.001`.
- `BOT_EXECUTION_MAX_QTY`: unset by default.
- `BOT_EXECUTION_MAX_NOTIONAL`: default `100` during live observation.
- `BOT_EXECUTION_LEVERAGE`: default `15`.
- `TELEGRAM_ALERTS_ENABLED`: default `false`.
- `TELEGRAM_CHAT_ID`: unset by default.
- `DISCORD_ALERTS_ENABLED`: default `false`.

Recommended optional secret:

- `ONPREM_SSH_FINGERPRINT`: SHA256 host key fingerprint for SSH host
  verification.

## Host Assumptions

The host should already have:

- A locked-down deploy user with SSH access.
- Docker Engine and the Docker Compose plugin.
- `ONPREM_DEPLOY_DIR` writable by the deploy user.
- `BOT_CONTROL_TOKEN`, alert tokens, and Bybit tokens stored only in GitHub
  Environment secrets or the ignored local `.env` file, not in the repository.

Templates:

- `Dockerfile`
- `compose.yaml`
- `.env.example`
- `deploy/docker/env/bybit-trader.env.example`

## Runtime Env Generation

The workflow generates `ONPREM_DEPLOY_DIR/env/bybit-trader.env` from the
`onprem-live` GitHub Environment secrets and variables on every deploy. Keep
`.env.example` as the local shape reference:

```bash
cp .env.example .env
$EDITOR .env
```

For CLI setup, use `gh secret set` for secrets and `gh variable set` for
variables:

```bash
gh secret set BOT_CONTROL_TOKEN --env onprem-live
gh secret set BYBIT_API_KEY --env onprem-live
gh secret set BYBIT_API_SECRET --env onprem-live
gh variable set BOT_EXECUTION_MAX_NOTIONAL --env onprem-live --body 100
# Optional forward-only research collection
gh variable set BOT_FORWARD_MARKET_CAPTURE_ENABLED --env onprem-live --body true
gh variable set BOT_FORWARD_ORDER_BOOK_DEPTH --env onprem-live --body 50
gh variable set BOT_FORWARD_RAW_ARCHIVE_ENABLED --env onprem-live --body true
gh variable set BOT_FORWARD_RAW_ARCHIVE_PATH --env onprem-live --body /data/market-events
```

Note: the local root `.env` is the application runtime env. The remote
`ONPREM_DEPLOY_DIR/.env` is generated by the workflow for Docker Compose
deployment values only.

## Workflow Actions

- `twingate/github-action@v1`: attaches the GitHub runner to the private
  Twingate network with `TWINGATE_SERVICE_KEY`.
- `appleboy/scp-action@v1`: uploads `deploy-package` contents to
  `ONPREM_DEPLOY_DIR`.
- `appleboy/ssh-action@v1`: creates remote directories, runs `docker load`,
  restarts Compose, and checks `/health`.

## Trigger

The workflow is currently `workflow_dispatch` only so it will not fail on pushes
before Twingate and SSH secrets are installed. After the deployment secrets are
configured and a Docker deploy succeeds, add a guarded `push` trigger if fully
automatic main-branch deployment is desired.
