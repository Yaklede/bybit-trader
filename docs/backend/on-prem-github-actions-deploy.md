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

Required secrets:

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

Recommended optional secret:

- `ONPREM_SSH_FINGERPRINT`: SHA256 host key fingerprint for SSH host
  verification.

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
