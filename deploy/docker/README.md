# Docker Deployment

This is the preferred on-prem deployment path.

## Files

- `Dockerfile`: multi-stage JVM image build.
- `apps/dashboard/Dockerfile`: React dashboard build and Nginx runtime image.
- `apps/dashboard/nginx.conf`: dashboard static serving and `/api` reverse proxy.
- `compose.yaml`: backend service, dashboard service, SQLite volume, config mount, healthchecks.
- `deploy/docker/env/bybit-trader.env.example`: host-side environment template.
- `deploy/docker/backup-runtime-state.sh`: validated pre-deploy SQLite snapshot and Shadow continuity evidence.
- `deploy/docker/activate-runtime-release.sh`: staged activation, health/profile verification, and automatic release rollback.
- `deploy/docker/verify-runtime-backup.sh`: isolated, network-disabled backup restore drill.
- `deploy/docker/verify-runtime-profile.sh`: selected-profile, restarted Shadow-session, and active H4 signal-runtime verification.

## Host Setup

Create the deployment directory:

```bash
sudo mkdir -p /opt/bybit-trader/{config,env}
sudo chown -R "$USER":"$USER" /opt/bybit-trader
```

For manual host setup, copy these files to the host:

```bash
cp compose.yaml /opt/bybit-trader/compose.yaml
cp \
  config/volume-flow-composite-current.json \
  config/volume-confirmed-trend-ensemble-v1.json \
  config/volume-confirmed-trend-ensemble-v1-bootstrap.json \
  config/volume-confirmed-trend-ensemble-v1-external-result.json \
  config/volume-confirmed-trend-ensemble-v1-kotlin-parity-result.json \
  config/volume-confirmed-trend-ensemble-v1-runtime-parity-result.json \
  config/volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json \
  config/volume-confirmed-trend-ensemble-v1-forward-policy.json \
  config/volume-confirmed-trend-live-approval.json \
  /opt/bybit-trader/config/
cp deploy/docker/env/bybit-trader.env.example /opt/bybit-trader/env/bybit-trader.env
```

For GitHub Actions deployment, use `.env.example` as the local reference. The
workflow freezes the generated runtime to public-data-only H4 Shadow and ignores
stale mutable LIVE/execution variables. It never injects Bybit private keys.
Do not commit the real local `.env` file.

## Local Build And Run

```bash
docker build -t bybit-trader:local .
docker build -f apps/dashboard/Dockerfile -t bybit-trader-dashboard:local apps/dashboard
docker compose --env-file /opt/bybit-trader/.env -f /opt/bybit-trader/compose.yaml up -d
```

The compose `.env` file is only for deployment variables:

```bash
BOT_IMAGE=bybit-trader:local
DASHBOARD_IMAGE=bybit-trader-dashboard:local
DASHBOARD_BIND_HOST=127.0.0.1
DASHBOARD_PORT=8080
BOT_ENV_FILE=/opt/bybit-trader/env/bybit-trader.env
```

The backend API is not published directly by compose. The dashboard publishes
`DASHBOARD_BIND_HOST:DASHBOARD_PORT` and proxies `/api/*` to the backend service
inside the Docker network. The application secrets stay in `BOT_ENV_FILE`.

The workflow uploads each package to
`ONPREM_DEPLOY_DIR/.deploy-staging-<GitHub run ID>` instead of overwriting the
active release. Before a running container or active configuration is replaced,
it stores a consistent SQLite snapshot under
`ONPREM_DEPLOY_DIR/backups/<UTC timestamp>`.
Each snapshot contains `bybit-trader.sqlite`, a SHA-256 manifest, and, for H4
Shadow, the authenticated pre-deploy Shadow and approval responses. The newest
14 snapshots are retained. Deployment fails when SQLite `PRAGMA quick_check`
fails or the H4 Shadow session ID changes after restart.

Every new snapshot is restored into a temporary Docker volume before the real
container is restarted. The drill starts the application with `--network none`
and all order-producing flags disabled, waits for `/health`, then removes its
temporary container and volume. A current H4 Shadow session must also contain a
matching checkpoint and exactly one `SESSION_STARTED` event with no invalidation.

Only after the backup and restore drill pass does the activation script build a
complete runtime candidate and replace the active `env`, `config`, `bin`,
Compose, and release files. A failed Compose start, health probe, profile check,
or Shadow continuity check restores the previous files exactly and restarts the
previous release. A failed first deployment stops the unverified containers.
Successful runs remove their staging directory and retain the newest three
mode-`0700` `.deploy-rollback-*` directories. Failed staging directories remain
for diagnosis and can contain generated runtime configuration, so they must stay
inside the protected deployment directory.

Two maintenance workflows reuse these checks without changing the running
containers:

- `Monitor On-Prem Runtime` checks both services and the steady H4 runtime every
  hour.
- `Backup On-Prem Runtime` creates and restore-drills a snapshot once per day.

Their schedules are inert until the repository-level GitHub Actions variable
`ONPREM_MAINTENANCE_ENABLED=true` is set. This gate cannot be environment-level
because GitHub evaluates a job condition before loading environment variables.
Manual dispatch remains available for an explicit smoke run. Deploy, monitoring,
and backup share one concurrency group, so they cannot inspect or copy state
midway through a restart.

These snapshots remain on the on-prem host. They protect against application or
deployment failure, but not loss of the host disk; an encrypted off-host copy is
still required for host-level disaster recovery.

## GitHub Actions Deployment

The on-prem GitHub Actions workflow first runs every Node research and deployment
contract test, then builds the backend and dashboard Docker images. The backend
Docker build also runs the complete Gradle test, lint, and build gate. It saves
both images as tarballs, connects to the private host through Twingate, uploads
the package with the SHA-pinned appleboy SCP Action, and restarts the containers
with the SHA-pinned appleboy SSH Action. Every workflow has read-only repository
permissions, and Dependabot proposes weekly updates for the pinned Action SHAs.

Required GitHub Environment secrets are documented in
`docs/backend/on-prem-github-actions-deploy.md`. Keep Bybit keys, alert tokens,
and `BOT_CONTROL_TOKEN` only in the ignored local `.env` file or GitHub
Environment secrets, not in the repository.

## Current Startup Sequence

1. Run the deployment workflow with `deploy=false` and confirm the dry-run.
2. After explicit host-change approval, rerun it with `deploy=true`.
3. Confirm the H4 status is `OBSERVING` and approval is
   `SHADOW_COLLECTING` or `READY_FOR_HUMAN_REVIEW`.
4. Manually run monitoring and backup once before enabling their schedules.
5. Do not create a TESTNET/LIVE deployment workflow until the continuous
   forward gate and explicit human approval are frozen.
