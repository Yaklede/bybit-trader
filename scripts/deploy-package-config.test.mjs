import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const runtimeConfigFiles = [
  "volume-flow-composite-current.json",
  "volume-confirmed-trend-ensemble-v1.json",
  "volume-confirmed-trend-ensemble-v1-bootstrap.json",
  "volume-confirmed-trend-ensemble-v1-external-result.json",
  "volume-confirmed-trend-ensemble-v1-kotlin-parity-result.json",
  "volume-confirmed-trend-ensemble-v1-runtime-parity-result.json",
  "volume-confirmed-trend-ensemble-v1-forward-policy.json",
];

const workflow = fs.readFileSync(".github/workflows/deploy-onprem.yml", "utf8");
const dockerfile = fs.readFileSync("Dockerfile", "utf8");
const runtimeEnvScript = extractRuntimeEnvScript(workflow);

test("on-prem package preserves every runtime config hidden by the compose mount", () => {
  const packagingStep = workflow.match(/- name: Build Docker images[\s\S]*?- name: Build runtime env file/)?.[0];
  assert.ok(packagingStep, "Build Docker images step must exist");

  for (const file of runtimeConfigFiles) {
    assert.ok(fs.existsSync(`config/${file}`), `runtime config is missing: ${file}`);
    assert.match(packagingStep, new RegExp(`config/${escapeRegExp(file)}`));
    assert.match(dockerfile, new RegExp(`config/${escapeRegExp(file)}`));
  }

  assert.match(packagingStep, /deploy-package\/config\//);
});

test("on-prem deployment defaults cannot enable private execution", () => {
  assert.match(workflow, /BOT_MODE: \$\{\{ vars\.BOT_MODE \|\| 'PAPER' \}\}/);
  assert.match(workflow, /BOT_PRIVATE_EXECUTION_ENABLED: \$\{\{ vars\.BOT_PRIVATE_EXECUTION_ENABLED \|\| 'false' \}\}/);
  assert.match(workflow, /BOT_EXECUTION_LOOP_ENABLED: \$\{\{ vars\.BOT_EXECUTION_LOOP_ENABLED \|\| 'false' \}\}/);
  assert.match(workflow, /BOT_EXECUTION_RECONCILIATION_ENABLED: \$\{\{ vars\.BOT_EXECUTION_RECONCILIATION_ENABLED \|\| 'false' \}\}/);
  assert.match(workflow, /policy\.decision\?\.liveExecutionAllowed !== true/);
  assert.match(workflow, /Automatic execution is blocked/);
  assert.match(workflow, /process\.env\.BYBIT_API_KEY = ""/);
  assert.match(workflow, /Trend Shadow requires an isolated PAPER runtime/);
});

test("PAPER runtime generation strips private exchange credentials", () => {
  const result = runRuntimeEnvScript({
    BOT_MODE: "PAPER",
    BOT_CONTROL_TOKEN: "control-test-value",
    BYBIT_API_KEY: "must-not-survive",
    BYBIT_API_SECRET: "must-not-survive",
    BOT_PRIVATE_EXECUTION_ENABLED: "false",
    BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
    BOT_EXECUTION_LOOP_ENABLED: "false",
    BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.runtimeEnv, /^BOT_MODE=PAPER$/m);
  assert.match(result.runtimeEnv, /^BYBIT_API_KEY=$/m);
  assert.match(result.runtimeEnv, /^BYBIT_API_SECRET=$/m);
  assert.doesNotMatch(result.runtimeEnv, /must-not-survive/);
});

test("automatic execution fails while the frozen policy rejects live execution", () => {
  const result = runRuntimeEnvScript({
    BOT_MODE: "LIVE",
    BOT_CONTROL_TOKEN: "control-test-value",
    BYBIT_API_KEY: "api-key",
    BYBIT_API_SECRET: "api-secret",
    BOT_EXECUTION_LOOP_ENABLED: "true",
  });

  assert.equal(result.status, 1);
  assert.match(result.stderr, /Automatic execution is blocked/);
});

test("trend Shadow rejects mixed private and paper execution", () => {
  const result = runRuntimeEnvScript({
    BOT_MODE: "PAPER",
    BOT_CONTROL_TOKEN: "control-test-value",
    BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
    BOT_PAPER_LOOP_ENABLED: "true",
    BOT_PRIVATE_EXECUTION_ENABLED: "false",
    BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
    BOT_EXECUTION_LOOP_ENABLED: "false",
    BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
  });

  assert.equal(result.status, 1);
  assert.match(result.stderr, /Trend Shadow requires an isolated PAPER runtime/);
  assert.match(result.stderr, /BOT_PAPER_LOOP_ENABLED/);
});

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function extractRuntimeEnvScript(source) {
  const match = source.match(/node <<'NODE'\n([\s\S]*?)\n\s+NODE/);
  assert.ok(match, "runtime env generation script must exist");
  return match[1].replace(/^ {10}/gm, "");
}

function runRuntimeEnvScript(environment) {
  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "bybit-deploy-env-"));
  try {
    fs.mkdirSync(path.join(tempDirectory, "config"), { recursive: true });
    fs.mkdirSync(path.join(tempDirectory, "deploy-package", "env"), { recursive: true });
    fs.writeFileSync(
      path.join(tempDirectory, "config", "volume-confirmed-trend-ensemble-v1-forward-policy.json"),
      JSON.stringify({ decision: { liveExecutionAllowed: false } }),
    );
    const result = spawnSync(process.execPath, ["-e", runtimeEnvScript], {
      cwd: tempDirectory,
      encoding: "utf8",
      env: { ...process.env, ...environment },
    });
    const runtimeEnvPath = path.join(tempDirectory, "deploy-package", "env", "bybit-trader.env");
    return {
      status: result.status,
      stderr: result.stderr,
      runtimeEnv: fs.existsSync(runtimeEnvPath) ? fs.readFileSync(runtimeEnvPath, "utf8") : "",
    };
  } finally {
    fs.rmSync(tempDirectory, { recursive: true, force: true });
  }
}
