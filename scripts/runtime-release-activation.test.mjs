import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(import.meta.dirname, "..");
const activationScript = path.join(repoRoot, "deploy", "docker", "activate-runtime-release.sh");

test("staged release activates without mutating a nonexistent previous runtime", () => {
  const fixture = createFixture({ existingRuntime: false, verifyFails: false, curlFails: false });
  try {
    const result = runActivation(fixture);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /activated and verified/);
    assert.equal(read(path.join(fixture.deployRoot, ".env")), expectedComposeEnv(fixture.deployRoot));
    assert.equal(read(path.join(fixture.deployRoot, "env", "bybit-trader.env")), "profile=new\n");
    assert.equal(fs.existsSync(fixture.stagingDirectory), false);
    assert.doesNotMatch(read(fixture.dockerLog), / compose .* down/);
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("failed first deployment stops the unverified runtime", () => {
  const fixture = createFixture({ existingRuntime: false, verifyFails: false, curlFails: true });
  try {
    const result = runActivation(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /First deployment failed; the unverified runtime was stopped/);
    assert.match(read(fixture.dockerLog), /compose --env-file \.env -f compose\.yaml down/);
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("failed profile verification restores and restarts the previous runtime", () => {
  const fixture = createFixture({ existingRuntime: true, verifyFails: true, curlFails: false });
  try {
    const result = runActivation(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Previous runtime restored after failed deployment/);
    assert.equal(read(path.join(fixture.deployRoot, ".env")), "previous-compose-env\n");
    assert.equal(read(path.join(fixture.deployRoot, "compose.yaml")), "previous-compose\n");
    assert.equal(read(path.join(fixture.deployRoot, "env", "bybit-trader.env")), "profile=previous\n");
    assert.equal(fs.existsSync(path.join(fixture.deployRoot, "config", "new-only.json")), false);
    assert.equal(read(path.join(fixture.deployRoot, "config", "old-only.json")), "previous\n");
    assert.equal(read(path.join(fixture.deployRoot, "bin", "marker")), "previous\n");
    const dockerLog = read(fixture.dockerLog);
    assert.equal(
      dockerLog.match(/compose --env-file \.env -f compose\.yaml up -d --remove-orphans/g)?.length,
      2,
    );
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("failure immediately after the active switch restores the exact previous release", () => {
  const fixture = createFixture({
    existingRuntime: true,
    verifyFails: false,
    curlFails: false,
    firstComposeUpFails: true,
  });
  try {
    const result = runActivation(fixture);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Previous runtime restored after failed deployment/);
    assert.equal(read(path.join(fixture.deployRoot, ".env")), "previous-compose-env\n");
    assert.equal(read(path.join(fixture.deployRoot, "compose.yaml")), "previous-compose\n");
    assert.equal(fs.existsSync(path.join(fixture.deployRoot, "config", "new-only.json")), false);
    assert.equal(read(path.join(fixture.deployRoot, "config", "old-only.json")), "previous\n");
    assert.equal(read(path.join(fixture.deployRoot, "bin", "marker")), "previous\n");
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test("activation rejects an unrelated staging path and an invalid dashboard port", () => {
  const fixture = createFixture({ existingRuntime: false, verifyFails: false, curlFails: false });
  try {
    const wrongStaging = spawnSync(
      "sh",
      [activationScript, fixture.deployRoot, fixture.root, "127.0.0.1", "8080", "test-run"],
      { cwd: repoRoot, encoding: "utf8" },
    );
    assert.equal(wrongStaging.status, 1);
    assert.match(wrongStaging.stderr, /Staging directory must match the deployment id/);

    const invalidPort = spawnSync(
      "sh",
      [
        activationScript,
        fixture.deployRoot,
        fixture.stagingDirectory,
        "127.0.0.1",
        "65536",
        "test-run",
      ],
      { cwd: repoRoot, encoding: "utf8" },
    );
    assert.equal(invalidPort.status, 1);
    assert.match(invalidPort.stderr, /Dashboard port must be between 1 and 65535/);
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

function createFixture({ existingRuntime, verifyFails, curlFails, firstComposeUpFails = false }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "bybit-release-activation-"));
  const deployRoot = path.join(root, "deploy");
  const stagingDirectory = path.join(deployRoot, ".deploy-staging-test-run");
  const fakeBin = path.join(root, "fake-bin");
  const dockerLog = path.join(root, "docker.log");
  const dockerState = path.join(root, "docker.state");

  fs.mkdirSync(path.join(stagingDirectory, "bin"), { recursive: true });
  fs.mkdirSync(path.join(stagingDirectory, "config"), { recursive: true });
  fs.mkdirSync(path.join(stagingDirectory, "env"), { recursive: true });
  fs.mkdirSync(path.join(stagingDirectory, "images"), { recursive: true });
  fs.mkdirSync(fakeBin, { recursive: true });

  write(path.join(stagingDirectory, "release.env"), [
    "BOT_IMAGE=bybit-trader:new",
    "DASHBOARD_IMAGE=bybit-trader-dashboard:new",
    "BOT_IMAGE_TAR=bot.tar.gz",
    "DASHBOARD_IMAGE_TAR=dashboard.tar.gz",
    "",
  ].join("\n"));
  write(path.join(stagingDirectory, "compose.yaml"), "new-compose\n");
  write(path.join(stagingDirectory, "env", "bybit-trader.env"), "profile=new\n");
  write(path.join(stagingDirectory, "config", "profile.json"), "{}\n");
  write(path.join(stagingDirectory, "config", "new-only.json"), "new\n");
  write(path.join(stagingDirectory, "images", "bot.tar.gz"), "bot-image\n");
  write(path.join(stagingDirectory, "images", "dashboard.tar.gz"), "dashboard-image\n");
  writeExecutable(
    path.join(stagingDirectory, "bin", "backup-runtime-state.sh"),
    "#!/bin/sh\nprintf '%s\\n' NONE\n",
  );
  writeExecutable(path.join(stagingDirectory, "bin", "verify-runtime-backup.sh"), "#!/bin/sh\nexit 0\n");
  writeExecutable(
    path.join(stagingDirectory, "bin", "verify-runtime-profile.sh"),
    "#!/bin/sh\nif [ \"${FAKE_VERIFY_FAIL:-false}\" = true ]; then exit 1; fi\nexit 0\n",
  );

  writeExecutable(
    fakeBin + "/docker",
    [
      "#!/bin/sh",
      "printf '%s\\n' \"$*\" >> \"${FAKE_DOCKER_LOG}\"",
      "case \"$*\" in",
      "  *\" compose \"*\" up \"*|compose*\" up \"*)",
      "    if [ \"${FAKE_FIRST_COMPOSE_UP_FAIL:-false}\" = true ] && [ ! -f \"${FAKE_DOCKER_STATE}\" ]; then",
      "      : > \"${FAKE_DOCKER_STATE}\"",
      "      exit 1",
      "    fi",
      "    ;;",
      "esac",
      "exit 0",
      "",
    ].join("\n"),
  );
  writeExecutable(
    fakeBin + "/curl",
    "#!/bin/sh\nif [ \"${FAKE_CURL_FAIL:-false}\" = true ]; then exit 1; fi\nexit 0\n",
  );
  writeExecutable(fakeBin + "/sleep", "#!/bin/sh\nexit 0\n");

  if (existingRuntime) {
    fs.mkdirSync(path.join(deployRoot, "env"), { recursive: true });
    fs.mkdirSync(path.join(deployRoot, "config"), { recursive: true });
    fs.mkdirSync(path.join(deployRoot, "bin"), { recursive: true });
    write(path.join(deployRoot, ".env"), "previous-compose-env\n");
    write(path.join(deployRoot, "compose.yaml"), "previous-compose\n");
    write(path.join(deployRoot, "release.env"), "BOT_IMAGE=bybit-trader:previous\n");
    write(path.join(deployRoot, "env", "bybit-trader.env"), "profile=previous\n");
    write(path.join(deployRoot, "config", "profile.json"), "{\"previous\":true}\n");
    write(path.join(deployRoot, "config", "old-only.json"), "previous\n");
    write(path.join(deployRoot, "bin", "marker"), "previous\n");
  }

  write(dockerLog, "");
  return {
    root,
    deployRoot,
    stagingDirectory,
    fakeBin,
    dockerLog,
    dockerState,
    verifyFails,
    curlFails,
    firstComposeUpFails,
  };
}

function runActivation(fixture) {
  return spawnSync(
    "sh",
    [activationScript, fixture.deployRoot, fixture.stagingDirectory, "127.0.0.1", "8080", "test-run"],
    {
      cwd: repoRoot,
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: `${fixture.fakeBin}:${process.env.PATH}`,
        FAKE_DOCKER_LOG: fixture.dockerLog,
        FAKE_DOCKER_STATE: fixture.dockerState,
        FAKE_VERIFY_FAIL: String(fixture.verifyFails),
        FAKE_CURL_FAIL: String(fixture.curlFails),
        FAKE_FIRST_COMPOSE_UP_FAIL: String(fixture.firstComposeUpFails),
      },
    },
  );
}

function expectedComposeEnv(deployRoot) {
  return [
    "BOT_IMAGE=bybit-trader:new",
    "DASHBOARD_IMAGE=bybit-trader-dashboard:new",
    "DASHBOARD_BIND_HOST=127.0.0.1",
    "DASHBOARD_PORT=8080",
    `BOT_ENV_FILE=${deployRoot}/env/bybit-trader.env`,
    "",
  ].join("\n");
}

function write(file, content) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

function writeExecutable(file, content) {
  write(file, content);
  fs.chmodSync(file, 0o755);
}

function read(file) {
  return fs.readFileSync(file, "utf8");
}
