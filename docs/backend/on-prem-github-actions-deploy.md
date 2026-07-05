# On-Prem GitHub Actions Deployment

The deployment workflow is `.github/workflows/deploy-onprem.yml`.

It uses the official `twingate/github-action@v1` action to connect a GitHub
runner to the Twingate-protected on-prem host, builds a Docker image, uploads
the image tarball and Compose files over SSH, runs `docker load`, and restarts
the service with Docker Compose.

## Required GitHub Environment

Create a GitHub Environment named `onprem-live`. Add required reviewers if
you want every deploy to need manual approval.

Required secrets:

- `TWINGATE_SERVICE_KEY`: Twingate Service key with access only to the on-prem
  deploy host resource.
- `ONPREM_SSH_HOST`: private host name or IP reachable through Twingate.
- `ONPREM_SSH_USER`: deploy user on the host.
- `ONPREM_SSH_PRIVATE_KEY`: private key for the deploy user.
- `ONPREM_DEPLOY_DIR`: deploy root, for example `/opt/bybit-trader`.
- `ONPREM_DOCKER_BIND_HOST`: optional, defaults to `127.0.0.1`. Use
  `0.0.0.0` only when Twingate or a private reverse proxy must reach the host
  port directly.

## Host Assumptions

The host should already have:

- A locked-down deploy user with SSH access.
- Docker Engine and the Docker Compose plugin.
- `/opt/bybit-trader/env/bybit-trader.env` created on the host from
  `deploy/docker/env/bybit-trader.env.example`.
- `BOT_CONTROL_TOKEN`, alert tokens, and Bybit tokens stored only on the host,
  not in the repository.

Templates:

- `Dockerfile`
- `compose.yaml`
- `deploy/docker/env/bybit-trader.env.example`

## Trigger

The workflow is currently `workflow_dispatch` only so it will not fail on pushes
before Twingate and SSH secrets are installed. After the deployment secrets are
configured and a Docker deploy succeeds, add a guarded `push` trigger if fully
automatic main-branch deployment is desired.
