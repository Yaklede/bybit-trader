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
  "volume-confirmed-trend-live-approval.json",
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
  assert.match(workflow, /BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: \$\{\{ vars\.BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED \|\| 'false' \}\}/);
  assert.match(workflow, /policy\.decision\?\.liveExecutionAllowed !== true/);
  assert.match(workflow, /Automatic execution is blocked/);
  assert.match(workflow, /packaged human approval receipt is incomplete or not approved/);
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

test("read-only TESTNET contract probe preserves credentials with every order path disabled", () => {
  const result = runRuntimeEnvScript({
    BOT_MODE: "TESTNET",
    BOT_CONTROL_TOKEN: "control-test-value",
    BYBIT_API_KEY: "api-key",
    BYBIT_API_SECRET: "api-secret",
    BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "false",
    BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false",
    BOT_PRIVATE_EXECUTION_ENABLED: "false",
    BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
    BOT_EXECUTION_LOOP_ENABLED: "false",
    BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.runtimeEnv, /^BOT_MODE=TESTNET$/m);
  assert.match(result.runtimeEnv, /^BYBIT_API_KEY=api-key$/m);
  assert.match(result.runtimeEnv, /^BOT_PRIVATE_EXECUTION_ENABLED=false$/m);
  assert.match(result.runtimeEnv, /^BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED=false$/m);
});

test("trend live execution rejects the repository's default unapproved receipt", () => {
  const result = runRuntimeEnvScript({
    BOT_MODE: "TESTNET",
    BOT_CONTROL_TOKEN: "control-test-value",
    BYBIT_API_KEY: "api-key",
    BYBIT_API_SECRET: "api-secret",
    BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
    BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "true",
    BOT_VOLUME_CONFIRMED_TREND_LIVE_APPROVAL_PATH:
      "/opt/bybit-trader/config/volume-confirmed-trend-live-approval.json",
    BOT_VOLUME_CONFIRMED_TREND_SHADOW_EVIDENCE_PATH:
      "/data/trend-approval/session/shadow-evidence.json",
    BOT_VOLUME_CONFIRMED_TREND_APPROVAL_REPORT_PATH:
      "/data/trend-approval/session/approval-report.json",
  });

  assert.equal(result.status, 1);
  assert.match(result.stderr, /packaged human approval receipt is incomplete or not approved/);
});

test("trend live execution preserves an explicitly approved isolated runtime", () => {
  const result = runRuntimeEnvScript(
    {
      BOT_MODE: "TESTNET",
      BOT_CONTROL_TOKEN: "control-test-value",
      BYBIT_API_KEY: "api-key",
      BYBIT_API_SECRET: "api-secret",
      BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true",
      BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "true",
      BOT_VOLUME_CONFIRMED_TREND_LIVE_APPROVAL_PATH:
        "/opt/bybit-trader/config/volume-confirmed-trend-live-approval.json",
      BOT_VOLUME_CONFIRMED_TREND_SHADOW_EVIDENCE_PATH:
        "/data/trend-approval/session/shadow-evidence.json",
      BOT_VOLUME_CONFIRMED_TREND_APPROVAL_REPORT_PATH:
        "/data/trend-approval/session/approval-report.json",
      BOT_PRIVATE_EXECUTION_ENABLED: "false",
      BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false",
      BOT_EXECUTION_LOOP_ENABLED: "false",
      BOT_EXECUTION_RECONCILIATION_ENABLED: "false",
    },
    approvedReceipt(),
  );

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.runtimeEnv, /^BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED=true$/m);
  assert.match(
    result.runtimeEnv,
    /^BOT_VOLUME_CONFIRMED_TREND_SHADOW_EVIDENCE_PATH=\/data\/trend-approval\/session\/shadow-evidence\.json$/m,
  );
});

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function extractRuntimeEnvScript(source) {
  const match = source.match(/node <<'NODE'\n([\s\S]*?)\n\s+NODE/);
  assert.ok(match, "runtime env generation script must exist");
  return match[1].replace(/^ {10}/gm, "");
}

function runRuntimeEnvScript(environment, approvalReceipt = defaultApprovalReceipt()) {
  const tempDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "bybit-deploy-env-"));
  try {
    fs.mkdirSync(path.join(tempDirectory, "config"), { recursive: true });
    fs.mkdirSync(path.join(tempDirectory, "deploy-package", "env"), { recursive: true });
    fs.writeFileSync(
      path.join(tempDirectory, "config", "volume-confirmed-trend-ensemble-v1-forward-policy.json"),
      JSON.stringify({ decision: { liveExecutionAllowed: false } }),
    );
    fs.writeFileSync(
      path.join(tempDirectory, "config", "volume-confirmed-trend-live-approval.json"),
      JSON.stringify(approvalReceipt),
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

function defaultApprovalReceipt() {
  return {
    status: "NOT_APPROVED",
    liveExecutionAllowed: false,
  };
}

function approvedReceipt() {
  const sha256 = "a".repeat(64);
  return {
    status: "APPROVED",
    approvalId: "approval-001",
    protocol: { sha256 },
    forwardPolicy: { sha256 },
    shadowSessionId: "shadow-session-001",
    shadowEvidenceSha256: sha256,
    approvalReportSha256: sha256,
    approvedAt: "2026-08-06T00:00:00Z",
    approvedBy: "human-owner",
    liveExecutionAllowed: true,
  };
}
