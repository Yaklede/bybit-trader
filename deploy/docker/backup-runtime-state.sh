#!/bin/sh

set -eu

compose_env_file="${1:-.env}"
compose_file="${2:-compose.yaml}"
runtime_env_file="${3:-env/bybit-trader.env}"
backup_root="${4:-backups}"
validator_image="${5:-}"
retention_count="${6:-14}"

runtime_value() {
  key="$1"
  sed -n "s/^${key}=//p" "${runtime_env_file}" | tail -n 1
}

container_get() {
  endpoint="$1"
  docker exec "${container_id}" \
    sh -c 'curl -fsS -H "Authorization: Bearer ${BOT_CONTROL_TOKEN}" "http://127.0.0.1:8080$1"' \
    sh "${endpoint}"
}

case "${retention_count}" in
  ''|*[!0-9]*|0)
    printf '%s\n' "Runtime backup retention must be a positive integer." >&2
    exit 1
    ;;
esac

if [ -z "${validator_image}" ]; then
  printf '%s\n' "Runtime backup requires the validator image argument." >&2
  exit 1
fi

container_id="$(
  docker compose --env-file "${compose_env_file}" -f "${compose_file}" ps --status running -q bybit-trader
)"
if [ -z "${container_id}" ]; then
  printf '%s\n' "Runtime backup skipped: no running bybit-trader container." >&2
  printf '%s\n' "NONE"
  exit 0
fi

umask 077
mkdir -p "${backup_root}"
chmod 700 "${backup_root}"

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
snapshot_name="${timestamp}"
partial_directory="${backup_root}/.${snapshot_name}.partial"
snapshot_directory="${backup_root}/${snapshot_name}"
container_temporary_database="/tmp/bybit-trader-predeploy-${timestamp}.sqlite"
paused=false
completed=false

cleanup() {
  if [ "${paused}" = "true" ]; then
    docker unpause "${container_id}" >/dev/null 2>&1 || true
    paused=false
  fi
  docker exec "${container_id}" rm -f "${container_temporary_database}" >/dev/null 2>&1 || true
  if [ "${completed}" != "true" ]; then
    rm -rf "${partial_directory}"
  fi
}
trap cleanup EXIT HUP INT TERM

rm -rf "${partial_directory}"
mkdir -p "${partial_directory}"

database_path="$(
  docker exec "${container_id}" sh -c 'printf %s "${BOT_DATABASE_PATH:-/data/bybit-trader.sqlite}"'
)"
case "${database_path}" in
  /data/*) ;;
  *)
    printf '%s\n' "Runtime database path must remain under /data." >&2
    exit 1
    ;;
esac
case "${database_path}" in
  *[!A-Za-z0-9_./-]*)
    printf '%s\n' "Runtime database path contains unsupported characters." >&2
    exit 1
    ;;
esac

trend_shadow_enabled="$(runtime_value BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED)"
if [ "${trend_shadow_enabled}" = "true" ]; then
  container_get '/strategy/volume-confirmed-trend/shadow?limit=1' \
    > "${partial_directory}/shadow-before.json"
  container_get '/strategy/volume-confirmed-trend/approval' \
    > "${partial_directory}/approval-before.json"
fi

if docker exec "${container_id}" sh -c 'command -v sqlite3 >/dev/null 2>&1'; then
  docker exec "${container_id}" sh -c \
    'rm -f "$1" && sqlite3 "${BOT_DATABASE_PATH:-/data/bybit-trader.sqlite}" ".timeout 10000" ".backup $1"' \
    sh "${container_temporary_database}"
  docker cp \
    "${container_id}:${container_temporary_database}" \
    "${partial_directory}/source.sqlite" >/dev/null
else
  database_wal_present=false
  database_shm_present=false
  if docker exec "${container_id}" test -f "${database_path}-wal"; then
    database_wal_present=true
  fi
  if docker exec "${container_id}" test -f "${database_path}-shm"; then
    database_shm_present=true
  fi

  docker pause "${container_id}" >/dev/null
  paused=true
  docker cp "${container_id}:${database_path}" "${partial_directory}/source.sqlite" >/dev/null
  if [ "${database_wal_present}" = "true" ]; then
    docker cp "${container_id}:${database_path}-wal" "${partial_directory}/source.sqlite-wal" >/dev/null
  fi
  if [ "${database_shm_present}" = "true" ]; then
    docker cp "${container_id}:${database_path}-shm" "${partial_directory}/source.sqlite-shm" >/dev/null
  fi
  docker unpause "${container_id}" >/dev/null
  paused=false
fi

chmod u+rw "${partial_directory}"/source.sqlite*
absolute_partial_directory="$(cd "${partial_directory}" && pwd)"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --entrypoint sh \
  -v "${absolute_partial_directory}:/backup" \
  "${validator_image}" \
  -c 'set -eu
      rm -f /backup/bybit-trader.sqlite
      sqlite3 /backup/source.sqlite ".timeout 10000" ".backup /backup/bybit-trader.sqlite"
      test "$(sqlite3 /backup/bybit-trader.sqlite "PRAGMA quick_check;")" = "ok"'

rm -f "${partial_directory}"/source.sqlite*
database_sha256="$(sha256sum "${partial_directory}/bybit-trader.sqlite" | awk '{print $1}')"
shadow_session_id=""
if [ -f "${partial_directory}/shadow-before.json" ]; then
  shadow_session_id="$(
    sed -n 's/.*"sessionId":"\([A-Za-z0-9_-]*\)".*/\1/p' \
      "${partial_directory}/shadow-before.json" | head -n 1
  )"
fi

printf '%s\n' \
  "createdAt=${timestamp}" \
  "databasePath=${database_path}" \
  "databaseSha256=${database_sha256}" \
  "shadowSessionId=${shadow_session_id}" \
  > "${partial_directory}/manifest.env"
printf '%s  %s\n' "${database_sha256}" "bybit-trader.sqlite" \
  > "${partial_directory}/SHA256SUMS"

chmod 600 "${partial_directory}"/*
mv "${partial_directory}" "${snapshot_directory}"
completed=true

find "${backup_root}" -mindepth 1 -maxdepth 1 -type d -name '20??????T??????Z' -print \
  | sort -r \
  | awk -v keep="${retention_count}" 'NR > keep' \
  | while IFS= read -r expired_snapshot; do
      rm -rf "${expired_snapshot}"
    done

printf '%s\n' "Runtime backup completed: ${snapshot_directory}." >&2
printf '%s\n' "${snapshot_directory}"
