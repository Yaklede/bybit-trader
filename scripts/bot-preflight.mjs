#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const env = process.env;
const checks = [];

const mode = (env.BOT_MODE || "PAPER").toUpperCase();
const paperStrategy = (env.BOT_PAPER_STRATEGY || "volume-flow-aggressive").toLowerCase();
const paperLoopEnabled = parseBool(env.BOT_PAPER_LOOP_ENABLED);
const paperTimeframe = (env.BOT_PAPER_TIMEFRAME || (paperStrategy === "volume-flow-aggressive" ? "M5" : "M1")).toUpperCase();
const paperCandleLimit = Number(env.BOT_PAPER_CANDLE_LIMIT || (paperStrategy === "volume-flow-aggressive" ? "18000" : "200"));
const paperSyncLimit = Number(env.BOT_PAPER_SYNC_LIMIT || "1000");
const privateExecutionEnabled = parseBool(env.BOT_PRIVATE_EXECUTION_ENABLED);
const executionLoopEnabled = parseBool(env.BOT_EXECUTION_LOOP_ENABLED);
const executionTimeframe = (env.BOT_EXECUTION_TIMEFRAME || "M5").toUpperCase();
const executionCandleLimit = Number(env.BOT_EXECUTION_CANDLE_LIMIT || "18000");
const executionSyncLimit = Number(env.BOT_EXECUTION_SYNC_LIMIT || "1000");
const executionRiskFraction = Number(env.BOT_EXECUTION_RISK_FRACTION || "0.055");
const executionQtyStep = Number(env.BOT_EXECUTION_QTY_STEP || "0.001");
const executionMinQty = Number(env.BOT_EXECUTION_MIN_QTY || "0.001");
const executionMaxQty = env.BOT_EXECUTION_MAX_QTY ? Number(env.BOT_EXECUTION_MAX_QTY) : null;
const executionMaxNotional = env.BOT_EXECUTION_MAX_NOTIONAL ? Number(env.BOT_EXECUTION_MAX_NOTIONAL) : null;
const bybitPrivateBaseUrl =
  env.BYBIT_PRIVATE_BASE_URL || (mode === "LIVE" ? "https://api.bybit.com" : "https://api-testnet.bybit.com");
const symbol = env.BOT_SYMBOL || "BTCUSDT";
const databasePath = env.BOT_DATABASE_PATH || "data/bybit-trader.sqlite";
const compositeConfigPath = env.BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH || "config/volume-flow-composite-current.json";
const telegramEnabled = parseBool(env.TELEGRAM_ALERTS_ENABLED);
const discordEnabled = parseBool(env.DISCORD_ALERTS_ENABLED);

check("BOT_MODE is supported", ["PAPER", "TESTNET", "LIVE"].includes(mode), `mode=${mode}`);
check("BOT_CONTROL_TOKEN is set", isLongEnough(env.BOT_CONTROL_TOKEN, 16), "set a private operator token with 16+ chars");
check("paper strategy is supported", ["volume-flow-aggressive", "mean-reversion"].includes(paperStrategy), paperStrategy);
check("paper sync limit respects Bybit page limit", Number.isInteger(paperSyncLimit) && paperSyncLimit >= 1 && paperSyncLimit <= 1000, `paperSyncLimit=${paperSyncLimit}`);
check("composite config file exists", fs.existsSync(compositeConfigPath), compositeConfigPath);
checkWritableParent(databasePath);

if (mode === "PAPER") {
  check("paper loop is enabled", paperLoopEnabled, "set BOT_PAPER_LOOP_ENABLED=true for paper operation");
}

if (mode === "PAPER" && paperStrategy === "volume-flow-aggressive") {
  check("aggressive paper timeframe is M5", paperTimeframe === "M5", `timeframe=${paperTimeframe}`);
  check("aggressive paper candle limit covers 60d regime rules", Number.isInteger(paperCandleLimit) && paperCandleLimit >= 17281, `candleLimit=${paperCandleLimit}`);
  checkM5History(databasePath, paperCandleLimit);
} else if (mode === "PAPER") {
  check("mean-reversion paper candle limit is valid", Number.isInteger(paperCandleLimit) && paperCandleLimit >= 20, `candleLimit=${paperCandleLimit}`);
}

if (mode !== "PAPER") {
  check("BYBIT_API_KEY is set outside PAPER mode", isLongEnough(env.BYBIT_API_KEY, 8), "required for TESTNET/LIVE");
  check("BYBIT_API_SECRET is set outside PAPER mode", isLongEnough(env.BYBIT_API_SECRET, 16), "required for TESTNET/LIVE");
  check("private execution is enabled outside PAPER mode", privateExecutionEnabled, "set BOT_PRIVATE_EXECUTION_ENABLED=true");
  check("execution loop is enabled outside PAPER mode", executionLoopEnabled, "set BOT_EXECUTION_LOOP_ENABLED=true for unattended operation");
  check("execution timeframe is M5", executionTimeframe === "M5", `timeframe=${executionTimeframe}`);
  check("execution sync limit respects Bybit page limit", Number.isInteger(executionSyncLimit) && executionSyncLimit >= 1 && executionSyncLimit <= 1000, `executionSyncLimit=${executionSyncLimit}`);
  check("execution candle limit covers 60d regime rules", Number.isInteger(executionCandleLimit) && executionCandleLimit >= 17281, `candleLimit=${executionCandleLimit}`);
  check("execution risk fraction is within configured risk band", executionRiskFraction > 0 && executionRiskFraction <= 0.2, `riskFraction=${executionRiskFraction}`);
  check("execution quantity step is positive", executionQtyStep > 0, `qtyStep=${executionQtyStep}`);
  check("execution min quantity is positive", executionMinQty > 0, `minQty=${executionMinQty}`);
  check(
    "execution max quantity is empty or above min quantity",
    executionMaxQty === null || executionMaxQty >= executionMinQty,
    `maxQty=${executionMaxQty ?? ""}, minQty=${executionMinQty}`,
  );
  check(
    "execution max notional is empty or positive",
    executionMaxNotional === null || executionMaxNotional > 0,
    `maxNotional=${executionMaxNotional ?? ""}`,
  );
  if (mode === "TESTNET") {
    check("TESTNET uses Bybit testnet private base URL", bybitPrivateBaseUrl.includes("api-testnet.bybit.com"), bybitPrivateBaseUrl);
  }
  if (mode === "LIVE") {
    check("LIVE does not use Bybit testnet private base URL", !bybitPrivateBaseUrl.includes("testnet"), bybitPrivateBaseUrl);
  }
  checkM5History(databasePath, executionCandleLimit);
}

check("at least one alert sink is enabled", telegramEnabled || discordEnabled, "enable Telegram or Discord alerts");
if (telegramEnabled) {
  check("TELEGRAM_BOT_TOKEN is set", isLongEnough(env.TELEGRAM_BOT_TOKEN, 16), "required when Telegram alerts are enabled");
  check("TELEGRAM_CHAT_ID is set", isLongEnough(env.TELEGRAM_CHAT_ID, 1), "required when Telegram alerts are enabled");
}
if (discordEnabled) {
  check("DISCORD_WEBHOOK_URL is set", /^https:\/\/discord(app)?\.com\/api\/webhooks\//.test(env.DISCORD_WEBHOOK_URL || ""), "required when Discord alerts are enabled");
}

const failures = checks.filter((item) => !item.ok);
for (const item of checks) {
  const status = item.ok ? "PASS" : "FAIL";
  console.log(`${status} ${item.name}${item.detail ? ` - ${item.detail}` : ""}`);
}

if (failures.length > 0) {
  console.error(`Preflight failed: ${failures.length}/${checks.length} checks failed.`);
  process.exit(1);
}

console.log(`Preflight passed: ${checks.length}/${checks.length} checks passed.`);

function check(name, ok, detail = "") {
  checks.push({ name, ok: Boolean(ok), detail });
}

function isLongEnough(value, minLength) {
  return typeof value === "string" && value.trim().length >= minLength;
}

function parseBool(value) {
  return typeof value === "string" && value.toLowerCase() === "true";
}

function checkWritableParent(filePath) {
  const parent = path.dirname(path.resolve(filePath));
  try {
    fs.mkdirSync(parent, { recursive: true });
    fs.accessSync(parent, fs.constants.W_OK);
    check("database parent directory is writable", true, parent);
  } catch {
    check("database parent directory is writable", false, parent);
  }
}

function checkM5History(filePath, requiredCandles) {
  if (!fs.existsSync(filePath)) {
    check("database file exists for aggressive paper history", false, filePath);
    return;
  }
  try {
    const output = execFileSync(
      "sqlite3",
      [
        filePath,
        `select count(*) from marketCandles where symbol = '${symbol.replaceAll("'", "''")}' and timeframe = 'M5';`,
      ],
      { encoding: "utf8" },
    );
    const count = Number(output.trim());
    check("database has enough BTCUSDT M5 candles", count >= requiredCandles, `count=${count}, required=${requiredCandles}`);
  } catch {
    check("database has enough BTCUSDT M5 candles", false, "sqlite3 count query failed");
  }
}
