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
const ciWorkflow = fs.readFileSync(".github/workflows/ci.yml", "utf8");
const dockerfile = fs.readFileSync("Dockerfile", "utf8");
const activationScript = fs.readFileSync("deploy/docker/activate-runtime-release.sh", "utf8");
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

test("on-prem deployment packages and runs profile-specific post-deploy verification", () => {
  assert.ok(fs.existsSync("deploy/docker/backup-runtime-state.sh"));
  assert.ok(fs.existsSync("deploy/docker/activate-runtime-release.sh"));
  assert.ok(fs.existsSync("deploy/docker/verify-runtime-backup.sh"));
  assert.ok(fs.existsSync("deploy/docker/verify-runtime-profile.sh"));
  assert.match(
    workflow,
    /cp deploy\/docker\/backup-runtime-state\.sh deploy-package\/bin\/backup-runtime-state\.sh/,
  );
  assert.match(
    workflow,
    /cp deploy\/docker\/activate-runtime-release\.sh deploy-package\/bin\/activate-runtime-release\.sh/,
  );
  assert.match(
    workflow,
    /cp deploy\/docker\/verify-runtime-backup\.sh deploy-package\/bin\/verify-runtime-backup\.sh/,
  );
  assert.match(
    workflow,
    /cp deploy\/docker\/verify-runtime-profile\.sh deploy-package\/bin\/verify-runtime-profile\.sh/,
  );
  assert.match(workflow, /\.deploy-staging-\$\{\{ github\.run_id \}\}/);
  assert.match(workflow, /umask 077[\s\S]*chmod 700 "\$\{STAGING_DIR\}"/);
  assert.match(workflow, /sh '\$\{\{ secrets\.ONPREM_DEPLOY_DIR \}\}\/\.deploy-staging-/);
  assert.match(activationScript, /sh "\$\{staging_directory\}\/bin\/backup-runtime-state\.sh"/);
  assert.match(activationScript, /sh "\$\{staging_directory\}\/bin\/verify-runtime-backup\.sh"/);
  assert.match(activationScript, /runtime_backup_snapshot/);
  assert.match(activationScript, /continuity_snapshot/);
  assert.match(
    activationScript,
    /sh bin\/verify-runtime-profile\.sh[\s\S]*env\/bybit-trader\.env[\s\S]*"\$\{continuity_snapshot\}"/,
  );
});

test("on-prem activation backs up before mutation and rolls back a failed release", () => {
  const syntax = spawnSync("sh", ["-n", "deploy/docker/activate-runtime-release.sh"], {
    cwd: process.cwd(),
    encoding: "utf8",
  });
  assert.equal(syntax.status, 0, syntax.stderr);
  assert.ok(
    activationScript.indexOf("runtime_backup_snapshot=\"NONE\"") <
      activationScript.indexOf("activated=true"),
    "runtime backup must happen before activation",
  );
  assert.match(activationScript, /rollback_release\(\)/);
  assert.match(activationScript, /trap on_exit EXIT/);
  assert.match(activationScript, /cp -p "\$\{rollback_directory\}\/\.env" \.env/);
  assert.match(activationScript, /docker compose --env-file \.env -f compose\.yaml up -d --remove-orphans/);
  assert.match(activationScript, /First deployment failed; the unverified runtime was stopped/);
  assert.match(activationScript, /rm -rf "\$\{staging_directory\}"/);
});

test("on-prem deployment is a dry-run unless host deployment is explicitly selected", () => {
  assert.match(workflow, /deploy:[\s\S]*type: boolean[\s\S]*default: false/);
  assert.match(workflow, /name: Validate on-prem connection secrets\n\s+if: \$\{\{ inputs\.deploy == true \}\}/);
  assert.match(workflow, /name: Connect to Twingate\n\s+if: \$\{\{ inputs\.deploy == true \}\}/);
  assert.match(workflow, /name: Prepare remote Docker directories\n\s+if: \$\{\{ inputs\.deploy == true \}\}/);
  assert.match(workflow, /name: Upload Docker deployment package\n\s+if: \$\{\{ inputs\.deploy == true \}\}/);
  assert.match(workflow, /name: Load images and restart compose services\n\s+if: \$\{\{ inputs\.deploy == true \}\}/);
  assert.match(workflow, /name: Report dry-run result\n\s+if: \$\{\{ inputs\.deploy != true \}\}/);
});

test("on-prem deployment reports every failed dry-run, deploy, or rollback to Discord", () => {
  assert.match(workflow, /name: Notify Discord on deployment failure/);
  assert.match(workflow, /if: \$\{\{ failure\(\) \}\}/);
  assert.match(workflow, /DISCORD_WEBHOOK_URL: \$\{\{ secrets\.DISCORD_WEBHOOK_URL \}\}/);
  assert.match(workflow, /DEPLOY_REQUESTED: \$\{\{ inputs\.deploy \}\}/);
  assert.match(workflow, /호스트 배포 또는 자동 롤백/);
  assert.match(workflow, /배포 사전 검증/);
  assert.match(workflow, /GITHUB_SERVER_URL.*GITHUB_REPOSITORY.*GITHUB_RUN_ID/);
  assert.doesNotMatch(workflow, /echo .*DISCORD_WEBHOOK_URL/);
});

test("runtime image includes sqlite tooling for consistent deploy backups", () => {
  const dockerfile = fs.readFileSync("Dockerfile", "utf8");
  assert.match(dockerfile, /apt-get install -y --no-install-recommends curl ca-certificates sqlite3/);
  assert.match(ciWorkflow, /name: Verify runtime backup recovery/);
  assert.match(ciWorkflow, /sh deploy\/docker\/verify-runtime-backup\.sh/);
  assert.match(ciWorkflow, /docker exec "\$\{SOURCE_CONTAINER\}" cat \/tmp\/bybit-trader-ci\.sqlite/);
  assert.match(ciWorkflow, /--network none/);
  assert.match(ciWorkflow, /BOT_PRIVATE_EXECUTION_ENABLED=false/);
});

test("on-prem deployment is frozen to public-data-only trend Shadow", () => {
  const envBlock = workflow.match(/- name: Build runtime env file[\s\S]*?\n\s+run: \|/)?.[0];
  assert.ok(envBlock, "Build runtime env file step must exist");
  assert.match(workflow, /^name: Deploy Trend Shadow On-Prem$/m);
  assert.match(envBlock, /^\s+BOT_MODE: PAPER$/m);
  assert.match(envBlock, /^\s+BOT_SYMBOL: BTCUSDT$/m);
  assert.match(envBlock, /^\s+BOT_PAPER_LOOP_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_MAKER_SHADOW_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED: "true"$/m);
  assert.match(envBlock, /^\s+BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_PRIVATE_EXECUTION_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_PRIVATE_EXECUTION_STREAM_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_EXECUTION_LOOP_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_EXECUTION_RECONCILIATION_ENABLED: "false"$/m);
  assert.match(envBlock, /^\s+BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE: "false"$/m);
  assert.match(envBlock, /^\s+BOT_EXECUTION_USE_LIVE_EQUITY: "false"$/m);
  assert.match(envBlock, /^\s+BOT_EXECUTION_LEVERAGE: "1"$/m);
  assert.match(envBlock, /^\s+"BYBIT_API_KEY": ""$/m);
  assert.match(envBlock, /^\s+"BYBIT_API_SECRET": ""$/m);
  assert.doesNotMatch(
    envBlock,
    /vars\.(BOT_MODE|BOT_SYMBOL|BOT_PAPER_LOOP_ENABLED|BOT_MAKER_SHADOW_ENABLED|BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED|BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED|BOT_PRIVATE_EXECUTION_ENABLED|BOT_PRIVATE_EXECUTION_STREAM_ENABLED|BOT_EXECUTION_LOOP_ENABLED|BOT_EXECUTION_RECONCILIATION_ENABLED|BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE)/,
  );
  assert.doesNotMatch(envBlock, /secrets\.(BYBIT_API_KEY|BYBIT_API_SECRET)/);
  assert.match(workflow, /policy\.decision\?\.liveExecutionAllowed !== true/);
  assert.match(workflow, /Automatic execution is blocked/);
  assert.match(workflow, /packaged human approval receipt is incomplete or not approved/);
  assert.match(workflow, /process\.env\.BYBIT_API_KEY = ""/);
  assert.match(workflow, /Trend Shadow requires an isolated PAPER runtime/);
});

test("checked-in runtime examples default to the same non-ordering Shadow profile", () => {
  for (const file of [
    ".env.example",
    "deploy/docker/env/bybit-trader.env.example",
    "deploy/systemd/bybit-trader.env.example",
  ]) {
    const values = parseEnvFile(fs.readFileSync(file, "utf8"));
    assert.equal(values.BOT_MODE, "PAPER", file);
    assert.equal(values.BOT_PAPER_LOOP_ENABLED, "false", file);
    assert.equal(values.BOT_MAKER_SHADOW_ENABLED, "false", file);
    assert.equal(values.BOT_VOLUME_CONFIRMED_TREND_SHADOW_ENABLED, "true", file);
    assert.equal(values.BOT_VOLUME_CONFIRMED_TREND_LIVE_ENABLED, "false", file);
    assert.equal(values.BYBIT_API_KEY, "", file);
    assert.equal(values.BYBIT_API_SECRET, "", file);
    assert.equal(values.BOT_PRIVATE_EXECUTION_ENABLED, "false", file);
    assert.equal(values.BOT_PRIVATE_EXECUTION_STREAM_ENABLED, "false", file);
    assert.equal(values.BOT_EXECUTION_LOOP_ENABLED, "false", file);
    assert.equal(values.BOT_EXECUTION_RECONCILIATION_ENABLED, "false", file);
    assert.equal(values.BOT_EXECUTION_ALLOW_UNVERIFIED_PROFILE, "false", file);
    assert.equal(values.BOT_EXECUTION_USE_LIVE_EQUITY, "false", file);
    assert.equal(values.BOT_EXECUTION_LEVERAGE, "1", file);
  }
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

function parseEnvFile(source) {
  return Object.fromEntries(
    source
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#") && line.includes("="))
      .map((line) => {
        const separator = line.indexOf("=");
        return [line.slice(0, separator), line.slice(separator + 1)];
      }),
  );
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
