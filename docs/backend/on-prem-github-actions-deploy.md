# On-Prem GitHub Actions Deployment

The deployment workflow is `.github/workflows/deploy-onprem.yml`.

It uses the official `twingate/github-action@v1` action to connect a GitHub
runner to the Twingate-protected on-prem host, uploads the Gradle application
distribution over SSH, flips the `current` symlink, and restarts a systemd
service.

## Required GitHub Environment

Create a GitHub Environment named `onprem-testnet`. Add required reviewers if
you want every deploy to need manual approval.

Required secrets:

- `TWINGATE_SERVICE_KEY`: Twingate Service key with access only to the on-prem
  deploy host resource.
- `ONPREM_SSH_HOST`: private host name or IP reachable through Twingate.
- `ONPREM_SSH_USER`: deploy user on the host.
- `ONPREM_SSH_PRIVATE_KEY`: private key for the deploy user.
- `ONPREM_DEPLOY_DIR`: deploy root, for example `/opt/bybit-trader`.
- `ONPREM_SYSTEMD_SERVICE`: optional, defaults to `bybit-trader.service`.

## Host Assumptions

The host should already have:

- A locked-down deploy user with SSH access.
- A systemd unit that runs
  `/opt/bybit-trader/current/bin/bot-app`.
- Environment variables supplied by systemd `EnvironmentFile`, a local secret
  manager, or another on-prem secret mechanism.
- `BOT_CONTROL_TOKEN`, alert tokens, and Bybit tokens stored only on the host,
  not in the repository.

Templates:

- `deploy/systemd/bybit-trader.service`
- `deploy/systemd/bybit-trader.env.example`

## Trigger

The workflow is currently `workflow_dispatch` only so it will not fail on pushes
before Twingate and SSH secrets are installed. After the deployment secrets are
configured and a testnet deploy succeeds, add a guarded `push` trigger if fully
automatic main-branch deployment is desired.
