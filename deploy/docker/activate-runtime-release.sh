#!/bin/sh

set -eu

deploy_root="${1:?deploy root is required}"
staging_directory="${2:?staging directory is required}"
dashboard_bind_host="${3:?dashboard bind host is required}"
dashboard_port="${4:-8080}"
deployment_id="${5:?deployment id is required}"

case "${deploy_root}" in
  /)
    printf '%s\n' "Deployment root cannot be the filesystem root." >&2
    exit 1
    ;;
  /*/)
    printf '%s\n' "Deployment root must not end with a slash." >&2
    exit 1
    ;;
  /*) ;;
  *)
    printf '%s\n' "Deployment root must be an absolute path." >&2
    exit 1
    ;;
esac
case "${deployment_id}" in
  ''|*[!A-Za-z0-9_-]*)
    printf '%s\n' "Deployment id contains unsupported characters." >&2
    exit 1
    ;;
esac
expected_staging_directory="${deploy_root}/.deploy-staging-${deployment_id}"
if [ "${staging_directory}" != "${expected_staging_directory}" ]; then
  printf '%s\n' "Staging directory must match the deployment id under the deployment root." >&2
  exit 1
fi
case "${dashboard_port}" in
  ''|*[!0-9]*)
    printf '%s\n' "Dashboard port must be numeric." >&2
    exit 1
    ;;
esac
if [ "${dashboard_port}" -lt 1 ] || [ "${dashboard_port}" -gt 65535 ]; then
  printf '%s\n' "Dashboard port must be between 1 and 65535." >&2
  exit 1
fi
case "${dashboard_bind_host}" in
  ''|*[!A-Za-z0-9.:_-]*)
    printf '%s\n' "Dashboard bind host contains unsupported characters." >&2
    exit 1
    ;;
esac

for required_file in \
  release.env \
  compose.yaml \
  env/bybit-trader.env \
  bin/backup-runtime-state.sh \
  bin/verify-runtime-backup.sh \
  bin/verify-runtime-profile.sh; do
  test -f "${staging_directory}/${required_file}"
done

umask 077
mkdir -p "${deploy_root}"
cd "${deploy_root}"

# shellcheck disable=SC1091
. "${staging_directory}/release.env"
for required_value in \
  BOT_IMAGE \
  DASHBOARD_IMAGE \
  BOT_IMAGE_TAR \
  DASHBOARD_IMAGE_TAR; do
  eval "value=\${${required_value}:-}"
  if [ -z "${value}" ]; then
    printf '%s\n' "Staged release is missing ${required_value}." >&2
    exit 1
  fi
done

test -f "${staging_directory}/images/${BOT_IMAGE_TAR}"
test -f "${staging_directory}/images/${DASHBOARD_IMAGE_TAR}"

docker load -i "${staging_directory}/images/${BOT_IMAGE_TAR}"
docker load -i "${staging_directory}/images/${DASHBOARD_IMAGE_TAR}"

runtime_backup_snapshot="NONE"
if [ -f .env ] && [ -f compose.yaml ] && [ -f env/bybit-trader.env ]; then
  runtime_backup_snapshot="$(
    sh "${staging_directory}/bin/backup-runtime-state.sh" \
      .env \
      compose.yaml \
      env/bybit-trader.env \
      backups \
      "${BOT_IMAGE}"
  )"
  if [ "${runtime_backup_snapshot}" != "NONE" ]; then
    sh "${staging_directory}/bin/verify-runtime-backup.sh" \
      "${runtime_backup_snapshot}" \
      "${BOT_IMAGE}"
  fi
fi

rollback_directory="${deploy_root}/.deploy-rollback-${deployment_id}"
rollback_available=false
activated=false
completed=false

mkdir -p "${rollback_directory}"
chmod 700 "${rollback_directory}"
if [ -f .env ] && [ -f compose.yaml ] && [ -f env/bybit-trader.env ]; then
  cp -p .env compose.yaml "${rollback_directory}/"
  if [ -f release.env ]; then
    cp -p release.env "${rollback_directory}/"
  fi
  for directory in env config bin; do
    if [ -d "${directory}" ]; then
      mkdir -p "${rollback_directory}/${directory}"
      cp -pR "${directory}/." "${rollback_directory}/${directory}/"
    fi
  done
  rollback_available=true
fi

probe_runtime() {
  compose_env_file="$1"
  probe_host="$(sed -n 's/^DASHBOARD_BIND_HOST=//p' "${compose_env_file}" | tail -n 1)"
  probe_port="$(sed -n 's/^DASHBOARD_PORT=//p' "${compose_env_file}" | tail -n 1)"
  if [ "${probe_host}" = "0.0.0.0" ]; then
    probe_host="127.0.0.1"
  fi
  for _attempt in $(seq 1 30); do
    if curl -fsS --max-time 10 "http://${probe_host}:${probe_port}/" >/dev/null \
      && curl -fsS --max-time 10 "http://${probe_host}:${probe_port}/api/health" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

rollback_release() {
  trap - EXIT
  set +e
  printf '%s\n' "Deployment verification failed; rolling back ${deployment_id}." >&2
  if [ -f .env ] && [ -f compose.yaml ]; then
    docker compose --env-file .env -f compose.yaml logs --tail=120 \
      bybit-trader bybit-trader-dashboard >&2
  fi
  if [ "${rollback_available}" = "true" ]; then
    for directory in env config bin; do
      rm -rf "${directory}"
      if [ -d "${rollback_directory}/${directory}" ]; then
        mkdir -p "${directory}"
        cp -pR "${rollback_directory}/${directory}/." "${directory}/"
      fi
    done
    cp -p "${rollback_directory}/.env" .env
    cp -p "${rollback_directory}/compose.yaml" compose.yaml
    if [ -f "${rollback_directory}/release.env" ]; then
      cp -p "${rollback_directory}/release.env" release.env
    else
      rm -f release.env
    fi
    rollback_healthy=false
    if docker compose --env-file .env -f compose.yaml up -d --remove-orphans \
      && probe_runtime .env; then
      rollback_healthy=true
    fi
    if [ "${rollback_healthy}" != "true" ]; then
      printf '%s\n' "Rollback started but the previous runtime did not become healthy." >&2
    else
      printf '%s\n' "Previous runtime restored after failed deployment." >&2
    fi
  else
    if [ -f .env ] && [ -f compose.yaml ]; then
      docker compose --env-file .env -f compose.yaml down
    fi
    printf '%s\n' "First deployment failed; the unverified runtime was stopped." >&2
  fi
}

on_exit() {
  status="$?"
  if [ "${status}" -ne 0 ] && [ "${activated}" = "true" ] && [ "${completed}" != "true" ]; then
    rollback_release
  fi
  exit "${status}"
}
trap on_exit EXIT

candidate_directory="${staging_directory}/.runtime-candidate"
mkdir -p \
  "${candidate_directory}/bin" \
  "${candidate_directory}/config" \
  "${candidate_directory}/env"
cp -pR "${staging_directory}/bin/." "${candidate_directory}/bin/"
cp -pR "${staging_directory}/config/." "${candidate_directory}/config/"
install -m 600 "${staging_directory}/env/bybit-trader.env" \
  "${candidate_directory}/env/bybit-trader.env"
install -m 644 "${staging_directory}/compose.yaml" "${candidate_directory}/compose.yaml"
install -m 600 "${staging_directory}/release.env" "${candidate_directory}/release.env"
printf '%s\n' \
  "BOT_IMAGE=${BOT_IMAGE}" \
  "DASHBOARD_IMAGE=${DASHBOARD_IMAGE}" \
  "DASHBOARD_BIND_HOST=${dashboard_bind_host}" \
  "DASHBOARD_PORT=${dashboard_port}" \
  "BOT_ENV_FILE=${deploy_root}/env/bybit-trader.env" > "${candidate_directory}/.env"
chmod 600 "${candidate_directory}/.env"

# Every active path has a complete candidate before the first runtime mutation.
activated=true
for directory in bin config env; do
  rm -rf "${directory}"
  mv "${candidate_directory}/${directory}" "${directory}"
done
mv "${candidate_directory}/compose.yaml" compose.yaml
mv "${candidate_directory}/release.env" release.env
mv "${candidate_directory}/.env" .env
mkdir -p images
cp -p "${staging_directory}/images/${BOT_IMAGE_TAR}" images/
cp -p "${staging_directory}/images/${DASHBOARD_IMAGE_TAR}" images/

docker compose --env-file .env -f compose.yaml up -d --remove-orphans
docker compose --env-file .env -f compose.yaml ps
if ! probe_runtime .env; then
  printf '%s\n' "New runtime did not become healthy before the deployment deadline." >&2
  exit 1
fi

continuity_snapshot=""
if [ "${runtime_backup_snapshot}" != "NONE" ] \
  && [ -f "${runtime_backup_snapshot}/shadow-before.json" ]; then
  continuity_snapshot="${runtime_backup_snapshot}/shadow-before.json"
fi
sh bin/verify-runtime-profile.sh \
  .env \
  compose.yaml \
  env/bybit-trader.env \
  "${continuity_snapshot}"

completed=true
trap - EXIT

find images -maxdepth 1 -type f -name 'bybit-trader-v*.tar.gz' -exec ls -1t {} + 2>/dev/null \
  | tail -n +4 \
  | while IFS= read -r archive; do rm -f "${archive}"; done
find images -maxdepth 1 -type f -name 'bybit-trader-dashboard-v*.tar.gz' -exec ls -1t {} + 2>/dev/null \
  | tail -n +4 \
  | while IFS= read -r archive; do rm -f "${archive}"; done
find "${deploy_root}" -mindepth 1 -maxdepth 1 -type d -name '.deploy-rollback-*' \
  -exec ls -1dt {} + 2>/dev/null \
  | awk 'NR > 3' \
  | while IFS= read -r old_rollback; do rm -rf "${old_rollback}"; done
rm -rf "${staging_directory}"

printf '%s\n' "Deployment ${deployment_id} activated and verified."
