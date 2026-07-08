#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const checks = [];
const deployDir = process.env.ONPREM_DEPLOY_DIR || process.cwd();
const envFile = process.env.BOT_ENV_FILE || path.join(deployDir, "env", "bybit-trader.env");
const composeFile = process.env.BOT_COMPOSE_FILE || path.join(deployDir, "compose.yaml");

check("Dockerfile exists", fs.existsSync(path.join(process.cwd(), "Dockerfile")), "Dockerfile");
check("compose file exists", fs.existsSync(composeFile), composeFile);
check("application env file exists", fs.existsSync(envFile), envFile);
check("config file exists", fs.existsSync(path.join(deployDir, "config", "volume-flow-composite-current.json")), "config/volume-flow-composite-current.json");
checkCommand("docker is installed", "docker", ["--version"]);
checkCommand("docker compose is installed", "docker", ["compose", "version"]);

if (fs.existsSync(composeFile)) {
  try {
    execFileSync("docker", ["compose", "-f", composeFile, "config"], {
      cwd: deployDir,
      stdio: "pipe",
      env: {
        ...process.env,
        BOT_ENV_FILE: envFile,
      },
    });
    check("compose config is valid", true, composeFile);
  } catch (error) {
    check("compose config is valid", false, error.message);
  }
}

if (fs.existsSync(envFile)) {
  const content = fs.readFileSync(envFile, "utf8");
  check("BOT_MODE is LIVE or TESTNET", /^BOT_MODE=(LIVE|TESTNET)$/m.test(content), "set BOT_MODE=LIVE or TESTNET");
  check("BOT_CONTROL_TOKEN is configured", envLineMatches(content, ["BOT_CONTROL_", "TOKEN"], ".{16,}"), "set 16+ chars");
  check("BYBIT_API_KEY is configured", envLineMatches(content, ["BYBIT_API_", "KEY"], ".{8,}"), "set Bybit key");
  check("BYBIT_API_SECRET is configured", envLineMatches(content, ["BYBIT_API_", "SECRET"], ".{16,}"), "set Bybit secret");
  check("private execution is enabled", /^BOT_PRIVATE_EXECUTION_ENABLED=true$/m.test(content), "set BOT_PRIVATE_EXECUTION_ENABLED=true");
  check("live account equity is enabled", /^BOT_EXECUTION_USE_LIVE_EQUITY=true$/m.test(content), "set BOT_EXECUTION_USE_LIVE_EQUITY=true");
  const executionMaxNotional = numericEnvValue(content, "BOT_EXECUTION_MAX_NOTIONAL");
  const executionLeverage = numericEnvValue(content, "BOT_EXECUTION_LEVERAGE");
  check(
    "execution has a compounding notional limiter",
    (executionLeverage !== null && executionLeverage > 1) || (executionMaxNotional !== null && executionMaxNotional > 0),
    "set BOT_EXECUTION_LEVERAGE or BOT_EXECUTION_MAX_NOTIONAL",
  );
}

for (const item of checks) {
  const status = item.ok ? "PASS" : "FAIL";
  console.log(`${status} ${item.name}${item.detail ? ` - ${item.detail}` : ""}`);
}

const failures = checks.filter((item) => !item.ok);
if (failures.length > 0) {
  console.error(`Docker preflight failed: ${failures.length}/${checks.length} checks failed.`);
  process.exit(1);
}

console.log(`Docker preflight passed: ${checks.length}/${checks.length} checks passed.`);

function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail });
}

function checkCommand(name, command, args) {
  try {
    const output = execFileSync(command, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
    check(name, true, output);
  } catch {
    check(name, false, `${command} ${args.join(" ")}`);
  }
}

function envLineMatches(content, nameParts, valuePattern) {
  return new RegExp(`^${nameParts.join("")}=${valuePattern}$`, "m").test(content);
}

function numericEnvValue(content, key) {
  const match = content.match(new RegExp(`^${key}=([^\\n]*)$`, "m"));
  if (!match || !match[1].trim()) return null;
  const value = Number(match[1].trim());
  return Number.isFinite(value) ? value : null;
}
