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
const symbol = env.BOT_SYMBOL || "BTCUSDT";
const databasePath = env.BOT_DATABASE_PATH || "data/bybit-trader.sqlite";
const compositeConfigPath = env.BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH || "config/volume-flow-composite-current.json";
const telegramEnabled = parseBool(env.TELEGRAM_ALERTS_ENABLED);
const discordEnabled = parseBool(env.DISCORD_ALERTS_ENABLED);

check("BOT_MODE is supported", ["PAPER", "TESTNET", "LIVE"].includes(mode), `mode=${mode}`);
check("BOT_CONTROL_TOKEN is set", isLongEnough(env.BOT_CONTROL_TOKEN, 16), "set a private operator token with 16+ chars");
check("paper loop is enabled", paperLoopEnabled, "set BOT_PAPER_LOOP_ENABLED=true for operation");
check("paper strategy is supported", ["volume-flow-aggressive", "mean-reversion"].includes(paperStrategy), paperStrategy);
check("paper sync limit respects Bybit page limit", Number.isInteger(paperSyncLimit) && paperSyncLimit >= 1 && paperSyncLimit <= 1000, `syncLimit=${paperSyncLimit}`);
check("composite config file exists", fs.existsSync(compositeConfigPath), compositeConfigPath);
checkWritableParent(databasePath);

if (paperStrategy === "volume-flow-aggressive") {
  check("aggressive paper timeframe is M5", paperTimeframe === "M5", `timeframe=${paperTimeframe}`);
  check("aggressive paper candle limit covers 60d regime rules", Number.isInteger(paperCandleLimit) && paperCandleLimit >= 17281, `candleLimit=${paperCandleLimit}`);
  checkM5History(databasePath, paperCandleLimit);
} else {
  check("mean-reversion paper candle limit is valid", Number.isInteger(paperCandleLimit) && paperCandleLimit >= 20, `candleLimit=${paperCandleLimit}`);
}

if (mode !== "PAPER") {
  check("BYBIT_API_KEY is set outside PAPER mode", isLongEnough(env.BYBIT_API_KEY, 8), "required for TESTNET/LIVE");
  check("BYBIT_API_SECRET is set outside PAPER mode", isLongEnough(env.BYBIT_API_SECRET, 16), "required for TESTNET/LIVE");
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
