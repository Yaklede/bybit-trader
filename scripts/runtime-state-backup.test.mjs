import assert from "node:assert/strict";
import {
  chmodSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backupScript = path.join(repoRoot, "deploy", "docker", "backup-runtime-state.sh");

test("runtime backup creates a validated SQLite snapshot and Shadow continuity evidence", () => {
  const run = runBackup({ sqliteAvailable: true, shadowEnabled: true });
  try {
    assert.equal(run.result.status, 0, output(run.result));
    const snapshotDirectory = run.result.stdout.trim();
    assert.ok(existsSync(path.join(snapshotDirectory, "bybit-trader.sqlite")));
    assert.ok(existsSync(path.join(snapshotDirectory, "shadow-before.json")));
    assert.ok(existsSync(path.join(snapshotDirectory, "approval-before.json")));
    assert.match(
      readFileSync(path.join(snapshotDirectory, "manifest.env"), "utf8"),
      /shadowSessionId=trend-shadow-existing/,
    );
    assert.equal(
      readdirSync(snapshotDirectory).some((name) => name.startsWith("source.sqlite")),
      false,
    );
    assert.doesNotMatch(readFileSync(run.logPath, "utf8"), /pause container-123/);
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

test("runtime backup pauses an older container and preserves its WAL before validation", () => {
  const run = runBackup({ sqliteAvailable: false, shadowEnabled: false, walPresent: true });
  try {
    assert.equal(run.result.status, 0, output(run.result));
    const calls = readFileSync(run.logPath, "utf8");
    assert.match(calls, /pause container-123/);
    assert.match(calls, /bybit-trader\.sqlite-wal/);
    assert.match(calls, /unpause container-123/);
    assert.ok(existsSync(path.join(run.result.stdout.trim(), "bybit-trader.sqlite")));
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

test("runtime backup skips cleanly on the first deployment", () => {
  const run = runBackup({ running: false, shadowEnabled: true });
  try {
    assert.equal(run.result.status, 0, output(run.result));
    assert.equal(run.result.stdout.trim(), "NONE");
    assert.match(run.result.stderr, /no running bybit-trader container/);
  } finally {
    rmSync(run.directory, { recursive: true, force: true });
  }
});

function runBackup({
  running = true,
  sqliteAvailable = true,
  shadowEnabled = false,
  walPresent = false,
}) {
  const directory = mkdtempSync(path.join(tmpdir(), "bybit-runtime-backup-"));
  const runtimeEnvPath = path.join(directory, "runtime.env");
  const backupRoot = path.join(directory, "backups");
  const dockerPath = path.join(directory, "docker");
  const logPath = path.join(directory, "docker.log");
  writeFileSync(
    runtimeEnvPath,
    `BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED=${shadowEnabled}\n`,
  );
  writeFileSync(
    dockerPath,
    `#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
case "$1" in
  compose)
    if [ "$FAKE_RUNNING" = "true" ]; then printf '%s' 'container-123'; fi
    ;;
  exec)
    case "$*" in
      *'printf %s'*BOT_DATABASE_PATH*) printf '%s' '/data/bybit-trader.sqlite' ;;
      *volume-confirmed-trend/shadow*)
        printf '%s' '{"enabled":true,"state":{"sessionId":"trend-shadow-existing"}}'
        ;;
      *volume-confirmed-trend/approval*)
        printf '%s' '{"available":true,"status":"COLLECTING"}'
        ;;
      *'command -v sqlite3'*)
        if [ "$FAKE_SQLITE_AVAILABLE" != "true" ]; then exit 1; fi
        ;;
      *bybit-trader.sqlite-wal*)
        if [ "$FAKE_WAL_PRESENT" != "true" ]; then exit 1; fi
        ;;
      *bybit-trader.sqlite-shm*) exit 1 ;;
      *) ;;
    esac
    ;;
  cp)
    destination=''
    for argument in "$@"; do destination="$argument"; done
    mkdir -p "$(dirname "$destination")"
    printf '%s' 'sqlite-source' > "$destination"
    ;;
  run)
    mount=''
    previous=''
    for argument in "$@"; do
      if [ "$previous" = '-v' ]; then mount="$argument"; break; fi
      previous="$argument"
    done
    host_directory="\${mount%%:*}"
    cp "$host_directory/source.sqlite" "$host_directory/bybit-trader.sqlite"
    ;;
  pause|unpause) ;;
  *) exit 1 ;;
esac
`,
  );
  chmodSync(dockerPath, 0o755);
  const result = spawnSync(
    "sh",
    [
      backupScript,
      "compose.env",
      "compose.yaml",
      runtimeEnvPath,
      backupRoot,
      "bybit-trader:test",
      "2",
    ],
    {
      cwd: directory,
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: `${directory}:${process.env.PATH}`,
        FAKE_DOCKER_LOG: logPath,
        FAKE_RUNNING: String(running),
        FAKE_SQLITE_AVAILABLE: String(sqliteAvailable),
        FAKE_WAL_PRESENT: String(walPresent),
      },
    },
  );
  return { directory, logPath, result };
}

function output(result) {
  return `${result.stdout || ""}\n${result.stderr || ""}`;
}
