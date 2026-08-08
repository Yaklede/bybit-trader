import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const verifierScript = path.join(repoRoot, "deploy", "docker", "verify-runtime-backup.sh");
const protocolHash = "a".repeat(64);

test("restore drill validates H4 evidence and boots the application from a temporary volume", () => {
  const run = runRestoreDrill({
    shadowSessionId: "trend-shadow-existing",
    shadowRow:
      `volume-confirmed-trend-ensemble-v1|BTCUSDT|trend-shadow-existing|OBSERVING|${protocolHash}|5|1|0`,
  });
  try {
    assert.equal(run.result.status, 0, output(run.result));
    assert.match(run.result.stdout, /shadowSession=trend-shadow-existing/);
    const calls = readFileSync(run.logPath, "utf8");
    assert.match(calls, /volume create bybit-trader-restore-drill-/);
    assert.match(calls, /BOT_PRIVATE_EXECUTION_ENABLED=false/);
    assert.match(calls, /rm -f bybit-trader-restore-drill-/);
    assert.match(calls, /volume rm bybit-trader-restore-drill-/);
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

test("restore drill rejects incomplete H4 session-start evidence", () => {
  const run = runRestoreDrill({
    shadowSessionId: "trend-shadow-existing",
    shadowRow:
      `volume-confirmed-trend-ensemble-v1|BTCUSDT|trend-shadow-existing|OBSERVING|${protocolHash}|5|0|0`,
  });
  try {
    assert.equal(run.result.status, 1, output(run.result));
    assert.match(run.result.stderr, /incomplete start evidence/);
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

test("restore drill rejects a corrupted backup before using Docker", () => {
  const run = runRestoreDrill({ corruptChecksum: true });
  try {
    assert.equal(run.result.status, 1, output(run.result));
    assert.doesNotMatch(readFileSync(run.logPath, "utf8"), /volume create/);
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

test("restore drill removes its temporary resources when the recovered app cannot start", () => {
  const run = runRestoreDrill({ healthy: false });
  try {
    assert.equal(run.result.status, 1, output(run.result));
    assert.match(run.result.stderr, /failed to start the application/);
    const calls = readFileSync(run.logPath, "utf8");
    assert.match(calls, /logs --tail=120/);
    assert.match(calls, /rm -f bybit-trader-restore-drill-/);
    assert.match(calls, /volume rm bybit-trader-restore-drill-/);
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

function runRestoreDrill({
  shadowSessionId = "",
  shadowRow = "",
  corruptChecksum = false,
  healthy = true,
}) {
  const directory = mkdtempSync(path.join(tmpdir(), "bybit-restore-drill-"));
  const snapshotDirectory = path.join(directory, "snapshot");
  const logPath = path.join(directory, "docker.log");
  const dockerPath = path.join(directory, "docker");
  const database = "sqlite-backup-fixture";
  const databaseHash = createHash("sha256").update(database).digest("hex");
  const checksum = corruptChecksum ? "0".repeat(64) : databaseHash;
  mkdirSync(snapshotDirectory, { recursive: true });
  writeFileSync(path.join(snapshotDirectory, "bybit-trader.sqlite"), database);
  writeFileSync(
    path.join(snapshotDirectory, "manifest.env"),
    `databaseSha256=${databaseHash}\nshadowSessionId=${shadowSessionId}\n`,
  );
  writeFileSync(
    path.join(snapshotDirectory, "SHA256SUMS"),
    `${checksum}  bybit-trader.sqlite\n`,
  );
  writeFileSync(
    dockerPath,
    `#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$1" in
  run)
    case "$*" in
      *'--entrypoint sqlite3'*'PRAGMA quick_check;'*) printf '%s\\n' 'ok' ;;
      *'--entrypoint sqlite3'*volumeConfirmedTrendShadowStates*) printf '%s\\n' "$FAKE_SHADOW_ROW" ;;
      *' -d --name '*) printf '%s\\n' 'restore-container-id' ;;
      *) ;;
    esac
    ;;
  volume) ;;
  exec)
    if [ "$FAKE_HEALTHY" != "true" ]; then exit 1; fi
    ;;
  inspect) printf '%s\\n' 'false' ;;
  logs) printf '%s\\n' 'restore drill fixture failed' ;;
  rm) ;;
  *) exit 1 ;;
esac
`,
  );
  chmodSync(dockerPath, 0o755);
  writeFileSync(logPath, "");
  const result = spawnSync(
    "sh",
    [verifierScript, snapshotDirectory, "bybit-trader:test"],
    {
      cwd: directory,
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: `${directory}:${process.env.PATH}`,
        FAKE_DOCKER_LOG: logPath,
        FAKE_HEALTHY: String(healthy),
        FAKE_SHADOW_ROW: shadowRow,
      },
    },
  );
  return { directory, logPath, result };
}

function output(result) {
  return `${result.stdout || ""}\n${result.stderr || ""}`;
}
