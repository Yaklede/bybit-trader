# Docker Deployment

This is the preferred on-prem deployment path.

## Files

- `Dockerfile`: multi-stage JVM image build.
- `compose.yaml`: runtime service, SQLite volume, config mount, healthcheck.
- `deploy/docker/env/bybit-trader.env.example`: host-side environment template.

## Host Setup

Create the deployment directory:

```bash
sudo mkdir -p /opt/bybit-trader/{config,env}
sudo chown -R "$USER":"$USER" /opt/bybit-trader
```

For manual host setup, copy these files to the host:

```bash
cp compose.yaml /opt/bybit-trader/compose.yaml
cp config/volume-flow-composite-current.json /opt/bybit-trader/config/
cp deploy/docker/env/bybit-trader.env.example /opt/bybit-trader/env/bybit-trader.env
```

For GitHub Actions deployment, use `.env.example` as the local reference and
configure matching values in the `onprem-live` GitHub Environment secrets and
variables. The workflow generates the host runtime env file. Do not commit the
real local `.env` file.

## Local Build And Run

```bash
docker build -t bybit-trader:local .
docker compose --env-file /opt/bybit-trader/.env -f /opt/bybit-trader/compose.yaml up -d
```

The compose `.env` file is only for deployment variables:

```bash
BOT_IMAGE=bybit-trader:local
BOT_BIND_HOST=127.0.0.1
BOT_API_PORT=8080
BOT_ENV_FILE=/opt/bybit-trader/env/bybit-trader.env
```

The application secrets stay in `BOT_ENV_FILE`.

## GitHub Actions Deployment

The on-prem GitHub Actions workflow builds the Docker image in CI, saves it as
an image tarball, connects to the private host through Twingate, uploads the
package with `appleboy/scp-action@v1`, and restarts the container with
`appleboy/ssh-action@v1`.

Required GitHub Environment secrets are documented in
`docs/backend/on-prem-github-actions-deploy.md`. Keep Bybit keys, alert tokens,
and `BOT_CONTROL_TOKEN` only in the ignored local `.env` file or GitHub
Environment secrets, not in the repository.

## Live Startup Sequence

1. Start with `BOT_EXECUTION_LOOP_ENABLED=false`.
2. Run `/execution/evaluate-and-submit` once with a small
   `BOT_EXECUTION_MAX_NOTIONAL`.
3. Run `/execution/reconcile`.
4. Confirm Bybit order, TP/SL, position, and alert behavior.
5. Set `BOT_EXECUTION_LOOP_ENABLED=true` and restart the container.
