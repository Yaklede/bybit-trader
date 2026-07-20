import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";

const scriptPath = path.resolve("scripts/volume-flow-feature-discovery.mjs");

test("absorption breakout fills after its confirmation candle closes", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-feature-discovery-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outputPath = path.join(directory, "trace.json");
    const outDirectory = path.join(directory, "out");
    createFixtureDatabase(databasePath);
    await fs.writeFile(
      windowsPath,
      JSON.stringify([
        {
          id: "W1",
          replayStartAt: "2024-01-01T14:00:00Z",
          replayEndAt: "2024-01-02T00:45:00Z",
        },
      ]),
    );

    execFileSync(
      process.execPath,
      [
        scriptPath,
        "--db",
        databasePath,
        "--windows",
        windowsPath,
        "--out",
        outDirectory,
        "--profile",
        "absorption-adaptive-regime-final",
        "--traceCandidateId",
        "absa_final_us_v1",
        "--traceWindowId",
        "W1",
        "--traceOut",
        outputPath,
      ],
      { encoding: "utf8" },
    );

    const trace = JSON.parse(await fs.readFile(outputPath, "utf8"));
    const breakoutTrade = trace.reports[0].trades.find((trade) => trade.side === "BUY");
    assert.ok(breakoutTrade, "fixture should produce a confirmed long breakout");
    assert.equal(breakoutTrade.openedAt, "2024-01-01T19:10:00Z");
    assert.equal(breakoutTrade.entryPrice, 101.0202);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("technical-analysis discovery profiles are explicit research candidates", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-technical-discovery-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    createFixtureDatabase(databasePath);
    await fs.writeFile(
      windowsPath,
      JSON.stringify([
        {
          id: "D1",
          replayStartAt: "2024-01-01T14:00:00Z",
          replayEndAt: "2024-01-02T00:45:00Z",
        },
      ]),
    );

    const profiles = [
      ["trend-pullback-acceptance", "TREND_PULLBACK_ACCEPTANCE"],
      ["macro-trend-breakout", "MACRO_TREND_BREAKOUT"],
    ];
    for (const [profile, family] of profiles) {
      const outDirectory = path.join(directory, profile);
      execFileSync(
        process.execPath,
        [
          scriptPath,
          "--db",
          databasePath,
          "--windows",
          windowsPath,
          "--out",
          outDirectory,
          "--profile",
          profile,
          "--maxCandidates",
          "1",
          "--quiet",
          "true",
        ],
        { encoding: "utf8" },
      );
      const ranked = JSON.parse(await fs.readFile(path.join(outDirectory, "ranked.json"), "utf8"));
      assert.equal(ranked.length, 1);
      assert.equal(ranked[0].family, family);
    }
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

function createFixtureDatabase(databasePath) {
  const database = new DatabaseSync(databasePath);
  try {
    database.exec(`
      CREATE TABLE marketCandles (
        id INTEGER PRIMARY KEY,
        symbol TEXT NOT NULL,
        timeframe TEXT NOT NULL,
        opened_at TEXT NOT NULL,
        open TEXT NOT NULL,
        high TEXT NOT NULL,
        low TEXT NOT NULL,
        close TEXT NOT NULL,
        volume TEXT NOT NULL,
        source_timestamp TEXT NOT NULL
      );
    `);
    const insert = database.prepare(`
      INSERT INTO marketCandles(symbol, timeframe, opened_at, open, high, low, close, volume, source_timestamp)
      VALUES (?, 'M5', ?, ?, ?, ?, ?, ?, ?)
    `);
    const startedAt = Date.parse("2024-01-01T14:00:00Z");
    for (let index = 0; index < 130; index += 1) {
      const openedAt = new Date(startedAt + index * 300_000).toISOString().replace(".000Z", "Z");
      const candle = fixtureCandle(index);
      insert.run(
        "BTCUSDT",
        openedAt,
        candle.open.toString(),
        candle.high.toString(),
        candle.low.toString(),
        candle.close.toString(),
        candle.volume.toString(),
        openedAt,
      );
    }
  } finally {
    database.close();
  }
}

function fixtureCandle(index) {
  if (index === 59 || index === 60) {
    return { open: 100, high: 101, low: 99, close: 100, volume: 30 };
  }
  if (index === 61) {
    return { open: 100, high: 103, low: 99.5, close: 102, volume: 10 };
  }
  if (index === 62) {
    return { open: 101, high: 102, low: 100, close: 101.2, volume: 10 };
  }
  if (index === 63) {
    return { open: 101.2, high: 106, low: 101, close: 105, volume: 10 };
  }
  return { open: 100, high: 101, low: 99, close: 100, volume: 10 };
}
