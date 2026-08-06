import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const scriptPath = path.join(repoRoot, "scripts", "bot-preflight.mjs");

test("paper preflight defaults to the causal M5 candidate", () => {
  const run = runPaperPreflight();

  assert.equal(run.status, 0, runOutput(run));
  assert.match(run.stdout, /PASS paper strategy is supported - multi-horizon-momentum/);
  assert.match(run.stdout, /PASS multi-horizon paper timeframe is M5/);
  assert.match(run.stdout, /PASS multi-horizon paper candle limit covers causal warmup/);
});

test("paper preflight rejects a timeframe that changes the validated horizons", () => {
  const run = runPaperPreflight({ BOT_PAPER_TIMEFRAME: "M15" });

  assert.equal(run.status, 1, runOutput(run));
  assert.match(run.stdout, /FAIL multi-horizon paper timeframe is M5 - timeframe=M15/);
});

function runPaperPreflight(overrides = {}) {
  const temporaryDirectory = mkdtempSync(path.join(tmpdir(), "bybit-paper-preflight-"));
  const env = {
    ...process.env,
    BOT_MODE: "PAPER",
    BOT_CONTROL_TOKEN: "0123456789abcdef",
    BOT_PAPER_LOOP_ENABLED: "true",
    BOT_PRIVATE_EXECUTION_ENABLED: "false",
    BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
    BOT_EXECUTION_LOOP_ENABLED: "false",
    BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
    DISCORD_ALERTS_ENABLED: "true",
    DISCORD_WEBHOOK_URL: "https://discord.com/api/webhooks/test/test",
    BOT_DATABASE_PATH: path.join(temporaryDirectory, "paper.sqlite"),
    BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH: path.join(repoRoot, "config", "volume-flow-composite-current.json"),
    ...overrides,
  };
  delete env.BOT_PAPER_STRATEGY;
  delete env.BOT_PAPER_CANDLE_LIMIT;
  if (!("BOT_PAPER_TIMEFRAME" in overrides)) delete env.BOT_PAPER_TIMEFRAME;

  try {
    return spawnSync(process.execPath, [scriptPath], {
      cwd: repoRoot,
      env,
      encoding: "utf8",
    });
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

function runOutput(run) {
  return `${run.stdout || ""}\n${run.stderr || ""}`;
}
