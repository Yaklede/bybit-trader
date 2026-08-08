#!/bin/sh

set -eu

compose_env_file="${1:-.env}"
compose_file="${2:-compose.yaml}"
runtime_env_file="${3:-env/bybit-trader.env}"

runtime_value() {
  key="$1"
  sed -n "s/^${key}=//p" "${runtime_env_file}" | tail -n 1
}

container_get() {
  endpoint="$1"
  docker compose --env-file "${compose_env_file}" -f "${compose_file}" exec -T bybit-trader \
    sh -c 'curl -fsS -H "Authorization: Bearer ${BOT_CONTROL_TOKEN}" "http://127.0.0.1:8080$1"' \
    sh "${endpoint}"
}

require_fragment() {
  payload="$1"
  fragment="$2"
  label="$3"
  case "${payload}" in
    *"${fragment}"*) ;;
    *)
      printf '%s\n' "Runtime profile verification failed: ${label}." >&2
      printf '%s\n' "${payload}" >&2
      exit 1
      ;;
  esac
}

mode="$(runtime_value BOT_MODE)"
trend_shadow_enabled="$(runtime_value BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED)"
trend_live_enabled="$(runtime_value BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED)"
private_execution_enabled="$(runtime_value BOT_PRIVATE_EXECUTION_ENABLED)"
private_stream_enabled="$(runtime_value BOT_PRIVATE_EXECUTION_STREAM_ENABLED)"
execution_loop_enabled="$(runtime_value BOT_EXECUTION_LOOP_ENABLED)"
execution_reconciliation_enabled="$(runtime_value BOT_EXECUTION_RECONCILIATION_ENABLED)"

if [ "${trend_shadow_enabled}" = "true" ]; then
  shadow_payload="$(container_get '/strategy/volume-confirmed-trend/shadow?limit=1')"
  require_fragment "${shadow_payload}" '"enabled":true' "H4 Shadow provider is disabled"
  require_fragment \
    "${shadow_payload}" \
    '"protocolId":"volume-confirmed-trend-ensemble-v1"' \
    "H4 Shadow protocol identity is unexpected"

  approval_payload="$(container_get '/strategy/volume-confirmed-trend/approval')"
  require_fragment "${approval_payload}" '"available":true' "H4 approval report is unavailable"
  require_fragment \
    "${approval_payload}" \
    '"automaticExecutionAllowed":false' \
    "H4 approval report unexpectedly allows automatic execution"
  require_fragment \
    "${approval_payload}" \
    '"liveExecutionAllowed":false' \
    "H4 approval report unexpectedly allows live execution"
fi

read_only_testnet_probe=false
if [ "${mode}" = "TESTNET" ] && \
  [ "${trend_shadow_enabled}" != "true" ] && \
  [ "${trend_live_enabled}" != "true" ] && \
  [ "${private_execution_enabled}" != "true" ] && \
  [ "${private_stream_enabled}" != "true" ] && \
  [ "${execution_loop_enabled}" != "true" ] && \
  [ "${execution_reconciliation_enabled}" != "true" ]; then
  read_only_testnet_probe=true
fi

if [ "${trend_live_enabled}" = "true" ] || [ "${read_only_testnet_probe}" = "true" ]; then
  contract_payload="$(container_get '/strategy/volume-confirmed-trend/exchange-contract')"
  require_fragment "${contract_payload}" '"available":true' "Bybit exchange contract is unavailable"
  require_fragment "${contract_payload}" '"valid":true' "Bybit exchange contract does not match the frozen H4 strategy"
fi

printf '%s\n' \
  "Runtime profile verification passed: mode=${mode:-UNSET} trendShadow=${trend_shadow_enabled:-false} trendLive=${trend_live_enabled:-false} readOnlyTestnet=${read_only_testnet_probe}."
