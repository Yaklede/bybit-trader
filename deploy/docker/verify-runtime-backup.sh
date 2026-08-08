#!/bin/sh

set -eu

snapshot_directory="${1:-}"
validator_image="${2:-}"

if [ -z "${snapshot_directory}" ] || [ ! -d "${snapshot_directory}" ]; then
  printf '%s\n' "Runtime backup verification requires an existing snapshot directory." >&2
  exit 1
fi
if [ -z "${validator_image}" ]; then
  printf '%s\n' "Runtime backup verification requires the validator image." >&2
  exit 1
fi

database_file="${snapshot_directory}/bybit-trader.sqlite"
manifest_file="${snapshot_directory}/manifest.env"
checksum_file="${snapshot_directory}/SHA256SUMS"
test -f "${database_file}"
test -f "${manifest_file}"
test -f "${checksum_file}"

manifest_value() {
  key="$1"
  sed -n "s/^${key}=//p" "${manifest_file}" | tail -n 1
}

checksum_line_count="$(wc -l < "${checksum_file}" | tr -d '[:space:]')"
expected_database_sha256="$(
  awk 'NF == 2 && $2 == "bybit-trader.sqlite" { print $1 }' "${checksum_file}"
)"
manifest_database_sha256="$(manifest_value databaseSha256)"
actual_database_sha256="$(sha256sum "${database_file}" | awk '{print $1}')"
case "${expected_database_sha256}" in
  ''|*[!0-9a-f]*)
    printf '%s\n' "Runtime backup checksum manifest is invalid." >&2
    exit 1
    ;;
esac
if [ "${checksum_line_count}" != "1" ] || \
  [ "${#expected_database_sha256}" -ne 64 ] || \
  [ "${manifest_database_sha256}" != "${expected_database_sha256}" ] || \
  [ "${actual_database_sha256}" != "${expected_database_sha256}" ]; then
  printf '%s\n' "Runtime backup database checksum does not match its evidence." >&2
  exit 1
fi

shadow_session_id="$(manifest_value shadowSessionId)"
case "${shadow_session_id}" in
  *[!A-Za-z0-9_-]*)
    printf '%s\n' "Runtime backup Shadow session ID is invalid." >&2
    exit 1
    ;;
esac

absolute_snapshot_directory="$(cd "${snapshot_directory}" && pwd)"
sqlite_query() {
  query="$1"
  docker run --rm \
    --network none \
    --user root \
    --entrypoint sqlite3 \
    -v "${absolute_snapshot_directory}:/backup:ro" \
    "${validator_image}" \
    -readonly -noheader -separator '|' /backup/bybit-trader.sqlite "${query}"
}

quick_check="$(sqlite_query 'PRAGMA quick_check;')"
if [ "${quick_check}" != "ok" ]; then
  printf '%s\n' "Runtime backup SQLite quick check failed: ${quick_check:-NO_RESULT}." >&2
  exit 1
fi

if [ -n "${shadow_session_id}" ]; then
  shadow_row="$(sqlite_query "
    SELECT
      protocol_id || '|' || symbol || '|' || session_id || '|' || status || '|' || protocol_sha256 || '|' ||
      (SELECT COUNT(*) FROM volumeConfirmedTrendShadowEvents WHERE session_id = '${shadow_session_id}') || '|' ||
      (SELECT COUNT(*) FROM volumeConfirmedTrendShadowEvents WHERE session_id = '${shadow_session_id}' AND event_type = 'SESSION_STARTED') || '|' ||
      (SELECT COUNT(*) FROM volumeConfirmedTrendShadowEvents WHERE session_id = '${shadow_session_id}' AND event_type = 'SESSION_INVALIDATED')
    FROM volumeConfirmedTrendShadowStates
    WHERE session_id = '${shadow_session_id}';
  ")"
  old_ifs="${IFS}"
  IFS='|'
  set -- ${shadow_row}
  IFS="${old_ifs}"
  protocol_id="${1:-}"
  symbol="${2:-}"
  stored_session_id="${3:-}"
  shadow_status="${4:-}"
  protocol_sha256="${5:-}"
  event_count="${6:-}"
  session_started_count="${7:-}"
  session_invalidated_count="${8:-}"

  if [ "${protocol_id}" != "volume-confirmed-trend-ensemble-v1" ] || \
    [ "${symbol}" != "BTCUSDT" ] || \
    [ "${stored_session_id}" != "${shadow_session_id}" ]; then
    printf '%s\n' "Runtime backup does not contain the frozen H4 Shadow checkpoint." >&2
    exit 1
  fi
  case "${protocol_sha256}" in
    ''|*[!0-9a-f]*)
      printf '%s\n' "Runtime backup H4 protocol hash is invalid." >&2
      exit 1
      ;;
  esac
  if [ "${#protocol_sha256}" -ne 64 ]; then
    printf '%s\n' "Runtime backup H4 protocol hash is invalid." >&2
    exit 1
  fi
  case "${event_count}:${session_started_count}:${session_invalidated_count}" in
    *[!0-9:]*|'')
      printf '%s\n' "Runtime backup H4 event counts are invalid." >&2
      exit 1
      ;;
  esac
  if [ "${session_invalidated_count}" != "0" ]; then
    printf '%s\n' "Runtime backup current H4 session is already invalidated." >&2
    exit 1
  fi
  case "${shadow_status}" in
    BOOTSTRAPPING)
      if [ "${event_count}" != "0" ] || [ "${session_started_count}" != "0" ]; then
        printf '%s\n' "Runtime backup bootstrapping H4 session has unexpected events." >&2
        exit 1
      fi
      ;;
    OBSERVING)
      if [ "${event_count}" -lt 1 ] || [ "${session_started_count}" != "1" ]; then
        printf '%s\n' "Runtime backup observing H4 session has incomplete start evidence." >&2
        exit 1
      fi
      ;;
    *)
      printf '%s\n' "Runtime backup H4 status is invalid: ${shadow_status:-NO_STATUS}." >&2
      exit 1
      ;;
  esac
fi

drill_suffix="$(date -u '+%Y%m%d%H%M%S')-$$"
drill_volume="bybit-trader-restore-drill-${drill_suffix}"
drill_container="bybit-trader-restore-drill-${drill_suffix}"
drill_control_credential="$(printf '%s' "${drill_suffix}" | sha256sum | awk '{print $1}')"
volume_created=false
container_created=false

cleanup() {
  if [ "${container_created}" = "true" ]; then
    docker rm -f "${drill_container}" >/dev/null 2>&1 || true
  fi
  if [ "${volume_created}" = "true" ]; then
    docker volume rm "${drill_volume}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT HUP INT TERM

docker volume create "${drill_volume}" >/dev/null
volume_created=true
docker run --rm \
  --network none \
  --user root \
  --entrypoint sh \
  -v "${absolute_snapshot_directory}:/backup:ro" \
  -v "${drill_volume}:/data" \
  "${validator_image}" \
  -c 'set -eu
      cp /backup/bybit-trader.sqlite /data/bybit-trader.sqlite
      chown bybit-trader:bybit-trader /data/bybit-trader.sqlite
      chmod 600 /data/bybit-trader.sqlite'

docker run -d \
  --name "${drill_container}" \
  --network none \
  --read-only \
  --tmpfs /tmp:exec,mode=1777 \
  -v "${drill_volume}:/data" \
  -e BOT_MODE=PAPER \
  -e BOT_API_HOST=0.0.0.0 \
  -e BOT_API_PORT=8080 \
  -e "BOT_CONTROL_TOKEN=${drill_control_credential}" \
  -e BOT_DATABASE_PATH=/data/bybit-trader.sqlite \
  -e BOT_PAPER_LOOP_ENABLED=false \
  -e BOT_FORWARD_MARKET_CAPTURE_ENABLED=false \
  -e BOT_MAKER_SHADOW_ENABLED=false \
  -e BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED=false \
  -e BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED=false \
  -e BOT_PRIVATE_EXECUTION_ENABLED=false \
  -e BOT_PRIVATE_EXECUTION_STREAM_ENABLED=false \
  -e BOT_EXECUTION_LOOP_ENABLED=false \
  -e BOT_EXECUTION_RECONCILIATION_ENABLED=false \
  "${validator_image}" >/dev/null
container_created=true

healthy=false
for _ in $(seq 1 30); do
  if docker exec "${drill_container}" curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
    healthy=true
    break
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "${drill_container}" 2>/dev/null || true)" != "true" ]; then
    break
  fi
  sleep 1
done

if [ "${healthy}" != "true" ]; then
  printf '%s\n' "Runtime backup restore drill failed to start the application." >&2
  docker logs --tail=120 "${drill_container}" >&2 || true
  exit 1
fi

printf '%s\n' \
  "Runtime backup verification passed: snapshot=${snapshot_directory} shadowSession=${shadow_session_id:-NONE}."
