import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const deployWorkflow = fs.readFileSync(".github/workflows/deploy-onprem.yml", "utf8");
const monitorWorkflow = fs.readFileSync(".github/workflows/monitor-onprem.yml", "utf8");
const backupWorkflow = fs.readFileSync(".github/workflows/backup-onprem.yml", "utf8");

test("deploy, monitoring, and backup serialize mutations against one runtime", () => {
  for (const workflow of [deployWorkflow, monitorWorkflow, backupWorkflow]) {
    assert.match(workflow, /group: onprem-runtime-main/);
    assert.match(workflow, /cancel-in-progress: false/);
  }
});

test("scheduled maintenance remains opt-in while manual dispatch stays available", () => {
  for (const workflow of [monitorWorkflow, backupWorkflow]) {
    assert.match(workflow, /workflow_dispatch:/);
    assert.match(workflow, /schedule:/);
    assert.match(
      workflow,
      /github\.event_name == 'workflow_dispatch' \|\| vars\.ONPREM_MAINTENANCE_ENABLED == 'true'/,
    );
    assert.match(workflow, /environment: onprem-live/);
    assert.match(workflow, /job-level if runs before environment variables load/);
  }
});

test("external monitor checks both services and requires a steady frozen profile", () => {
  assert.match(monitorWorkflow, /twingate\/github-action@v1/);
  assert.match(monitorWorkflow, /appleboy\/ssh-action@v1/);
  assert.match(monitorWorkflow, /ps --status running -q bybit-trader\)/);
  assert.match(monitorWorkflow, /ps --status running -q bybit-trader-dashboard\)/);
  assert.match(monitorWorkflow, /\/api\/health/);
  assert.match(
    monitorWorkflow,
    /sh bin\/verify-runtime-profile\.sh[\s\S]*env\/bybit-trader\.env[\s\S]*steady/,
  );
});

test("scheduled backup performs a validated restore drill and reports failures", () => {
  assert.match(backupWorkflow, /RUNTIME_BACKUP_RETENTION_COUNT/);
  assert.match(backupWorkflow, /sh bin\/backup-runtime-state\.sh/);
  assert.match(backupWorkflow, /sh bin\/verify-runtime-backup\.sh/);
  assert.match(
    backupWorkflow,
    /sh bin\/verify-runtime-profile\.sh[\s\S]*env\/bybit-trader\.env[\s\S]*steady/,
  );
  for (const workflow of [monitorWorkflow, backupWorkflow]) {
    assert.match(workflow, /if: \$\{\{ failure\(\) \}\}/);
    assert.match(workflow, /DISCORD_WEBHOOK_URL: \$\{\{ secrets\.DISCORD_WEBHOOK_URL \}\}/);
    assert.doesNotMatch(workflow, /echo .*DISCORD_WEBHOOK_URL/);
  }
});
