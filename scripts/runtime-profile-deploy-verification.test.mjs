import assert from "node:assert/strict";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const verifierPath = path.join(repoRoot, "deploy", "docker", "verify-runtime-profile.sh");

test("isolated H4 Shadow deployment verifies its provider and immutable order boundary", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "PAPER",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
      },
      {
        shadow:
          '{"enabled":true,"protocolId":"volume-confirmed-trend-ensemble-v1","state":null,"recentEvents":[]}',
        approval:
          '{"available":true,"status":"SHADOW_NOT_STARTED","automaticExecutionAllowed":false,"liveExecutionAllowed":false}',
      },
    );

  assert.equal(run.status, 0, output(run));
  assert.match(run.stdout, /trendShadow=true/);
  assert.match(run.stdout, /readOnlyTestnet=false/);
});

test("H4 Shadow deployment fails when the configured provider is not active", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "PAPER",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
      },
      {
        shadow: '{"enabled":false,"state":null,"recentEvents":[]}',
        approval:
          '{"available":true,"status":"SHADOW_DISABLED","automaticExecutionAllowed":false,"liveExecutionAllowed":false}',
      },
    );

  assert.equal(run.status, 1, output(run));
  assert.match(run.stderr, /H4 Shadow provider is disabled/);
});

test("H4 Shadow deployment rejects a changed session after restart", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "PAPER",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
      },
      {
        shadow:
          '{"enabled":true,"protocolId":"volume-confirmed-trend-ensemble-v1","state":{"sessionId":"trend-shadow-new"},"recentEvents":[]}',
        approval:
          '{"available":true,"status":"COLLECTING","automaticExecutionAllowed":false,"liveExecutionAllowed":false}',
      },
      0,
      '{"enabled":true,"state":{"sessionId":"trend-shadow-existing"}}',
    );

  assert.equal(run.status, 1, output(run));
  assert.match(run.stderr, /H4 Shadow session changed across deployment/);
});

test("H4 Shadow deployment accepts the persisted session after restart", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "PAPER",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
      },
      {
        shadow:
          '{"enabled":true,"protocolId":"volume-confirmed-trend-ensemble-v1","state":{"sessionId":"trend-shadow-existing"},"recentEvents":[]}',
        approval:
          '{"available":true,"status":"COLLECTING","automaticExecutionAllowed":false,"liveExecutionAllowed":false}',
      },
      0,
      '{"enabled":true,"state":{"sessionId":"trend-shadow-existing"}}',
    );

  assert.equal(run.status, 0, output(run));
});

test("read-only TESTNET deployment fails on an incompatible exchange contract", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "TESTNET",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "false",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
        BOT_PRIVATE_EXECUTION_ENABLED: "false",
        BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
        BOT_EXECUTION_LOOP_ENABLED: "false",
        BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
      },
      {
        contract:
          '{"available":true,"valid":false,"failures":["BUY_LEVERAGE_NOT_ONE","SELL_LEVERAGE_NOT_ONE"]}',
      },
    );

  assert.equal(run.status, 1, output(run));
  assert.match(run.stderr, /exchange contract does not match the frozen H4 strategy/);
  assert.match(run.stderr, /BUY_LEVERAGE_NOT_ONE/);
});

test("read-only TESTNET deployment passes only with a valid exchange contract", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "TESTNET",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "false",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
        BOT_PRIVATE_EXECUTION_ENABLED: "false",
        BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
        BOT_EXECUTION_LOOP_ENABLED: "false",
        BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
      },
      { contract: '{"available":true,"valid":true,"failures":[]}' },
    );

  assert.equal(run.status, 0, output(run));
  assert.match(run.stdout, /readOnlyTestnet=true/);
});

test("legacy runtime skips H4 profile verification", () => {
  const run =
    runVerifier(
      {
        BOT_MODE: "LIVE",
        BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "false",
        BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
        BOT_PRIVATE_EXECUTION_ENABLED: "true",
      },
      {},
      99,
    );

  assert.equal(run.status, 0, output(run));
  assert.match(run.stdout, /trendShadow=false/);
});

function runVerifier(
  runtimeEnvironment,
  responses,
  unexpectedDockerExit = 0,
  continuitySnapshot = null,
) {
  const directory = mkdtempSync(path.join(tmpdir(), "bybit-runtime-profile-"));
  try {
    const runtimeEnvPath = path.join(directory, "runtime.env");
    writeFileSync(
      runtimeEnvPath,
      `${Object.entries(runtimeEnvironment)
        .map(([key, value]) => `${key}=${value}`)
        .join("\n")}\n`,
    );
    const dockerPath = path.join(directory, "docker");
    writeFileSync(
      dockerPath,
      `#!/bin/sh
case "$*" in
  *volume-confirmed-trend/shadow*) printf '%s' "$FAKE_SHADOW_RESPONSE" ;;
  *volume-confirmed-trend/approval*) printf '%s' "$FAKE_APPROVAL_RESPONSE" ;;
  *volume-confirmed-trend/exchange-contract*) printf '%s' "$FAKE_CONTRACT_RESPONSE" ;;
  *) exit ${unexpectedDockerExit} ;;
esac
`,
    );
    chmodSync(dockerPath, 0o755);
    const continuitySnapshotPath = path.join(directory, "shadow-before.json");
    if (continuitySnapshot != null) {
      writeFileSync(continuitySnapshotPath, continuitySnapshot);
    }
    return spawnSync(
      "sh",
      [
        verifierPath,
        "compose.env",
        "compose.yaml",
        runtimeEnvPath,
        continuitySnapshot == null ? "" : continuitySnapshotPath,
      ],
      {
        cwd: directory,
        encoding: "utf8",
        env: {
          ...process.env,
          PATH: `${directory}:${process.env.PATH}`,
          FAKE_SHADOW_RESPONSE: responses.shadow || "",
          FAKE_APPROVAL_RESPONSE: responses.approval || "",
          FAKE_CONTRACT_RESPONSE: responses.contract || "",
        },
      },
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function output(run) {
  return `${run.stdout || ""}\n${run.stderr || ""}`;
}
