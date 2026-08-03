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
    assert.equal(Date.parse(breakoutTrade.openedAt) - Date.parse(breakoutTrade.signalAt), 300_000);
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
      ["multi-horizon-momentum", "MULTI_HORIZON_MOMENTUM"],
      ["macro-pullback-recovery", "MACRO_PULLBACK_RECOVERY"],
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

test("multi-horizon momentum profile stays inside its predeclared candidate boundary", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-momentum-boundary-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outDirectory = path.join(directory, "out");
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
        "multi-horizon-momentum",
        "--maxCandidates",
        "200",
        "--quiet",
        "true",
      ],
      { encoding: "utf8" },
    );

    const ranked = JSON.parse(await fs.readFile(path.join(outDirectory, "ranked.json"), "utf8"));
    assert.equal(ranked.length, 108);
    assert.equal(new Set(ranked.map((result) => result.id)).size, 108);
    assert.ok(ranked.every((result) => result.family === "MULTI_HORIZON_MOMENTUM"));
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("feature discovery accepts sealed validation windows without renaming them to folds", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-validation-windows-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outDirectory = path.join(directory, "out");
    createFixtureDatabase(databasePath);
    await fs.writeFile(
      windowsPath,
      JSON.stringify({
        status: "SEALED_BEFORE_REPLAY",
        windows: [
          {
            id: "V1",
            replayStartAt: "2024-01-01T14:00:00Z",
            replayEndAt: "2024-01-02T00:45:00Z",
          },
        ],
      }),
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
        "multi-horizon-momentum",
        "--maxCandidates",
        "1",
        "--quiet",
        "true",
      ],
      { encoding: "utf8" },
    );

    const ranked = JSON.parse(await fs.readFile(path.join(outDirectory, "ranked.json"), "utf8"));
    assert.equal(ranked.length, 1);
    assert.equal(ranked[0].reports.length, 1);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("cost stress scales candidate fees and slippage without expanding the candidate grid", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-momentum-cost-stress-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outDirectory = path.join(directory, "out");
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
        "multi-horizon-momentum",
        "--candidateId",
        "multi_momentum_scale0.75_votes3_stop8_trail16_long_only",
        "--costMultiplier",
        "1.5",
        "--quiet",
        "true",
      ],
      { encoding: "utf8" },
    );

    const ranked = JSON.parse(await fs.readFile(path.join(outDirectory, "ranked.json"), "utf8"));
    assert.equal(ranked.length, 1);
    assert.equal(ranked[0].candidate.costMultiplier, 1.5);
    assert.ok(Math.abs(ranked[0].candidate.feeRate - 0.0009) < 1e-12);
    assert.ok(Math.abs(ranked[0].candidate.entrySlippageRate - 0.0003) < 1e-12);
    assert.ok(Math.abs(ranked[0].candidate.exitSlippageRate - 0.0003) < 1e-12);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("macro pullback recovery profile stays inside its predeclared candidate boundary", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-macro-recovery-boundary-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outDirectory = path.join(directory, "out");
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
        "macro-pullback-recovery",
        "--maxCandidates",
        "200",
        "--quiet",
        "true",
      ],
      { encoding: "utf8" },
    );

    const ranked = JSON.parse(await fs.readFile(path.join(outDirectory, "ranked.json"), "utf8"));
    assert.equal(ranked.length, 48);
    assert.equal(new Set(ranked.map((result) => result.id)).size, 48);
    assert.ok(ranked.every((result) => result.family === "MACRO_PULLBACK_RECOVERY"));
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("macro pullback recovery cost stress scales one fixed candidate", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-macro-recovery-cost-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outDirectory = path.join(directory, "out");
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
        "macro-pullback-recovery",
        "--candidateId",
        "macro_recovery_regime5_counter3_stop8_trail16_both",
        "--costMultiplier",
        "2",
        "--quiet",
        "true",
      ],
      { encoding: "utf8" },
    );

    const ranked = JSON.parse(await fs.readFile(path.join(outDirectory, "ranked.json"), "utf8"));
    assert.equal(ranked.length, 1);
    assert.equal(ranked[0].candidate.costMultiplier, 2);
    assert.ok(Math.abs(ranked[0].candidate.feeRate - 0.0012) < 1e-12);
    assert.ok(Math.abs(ranked[0].candidate.entrySlippageRate - 0.0004) < 1e-12);
    assert.ok(Math.abs(ranked[0].candidate.exitSlippageRate - 0.0004) < 1e-12);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});

test("macro pullback recovery fills only after the completed recovery signal", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "bybit-macro-recovery-causal-"));
  try {
    const databasePath = path.join(directory, "candles.sqlite");
    const windowsPath = path.join(directory, "windows.json");
    const outputPath = path.join(directory, "trace.json");
    const outDirectory = path.join(directory, "out");
    const timestamps = createMacroRecoveryFixtureDatabase(databasePath);
    await fs.writeFile(
      windowsPath,
      JSON.stringify([
        {
          id: "D1",
          replayStartAt: timestamps[8_400],
          replayEndAt: timestamps[12_999],
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
        "macro-pullback-recovery",
        "--traceCandidateId",
        "macro_recovery_regime5_counter3_stop8_trail16_both",
        "--traceWindowId",
        "D1",
        "--traceOut",
        outputPath,
        "--quiet",
        "true",
      ],
      { encoding: "utf8" },
    );

    const trace = JSON.parse(await fs.readFile(outputPath, "utf8"));
    const recoveryTrade = trace.reports[0].trades[0];
    assert.ok(
      recoveryTrade,
      `fixture should produce a macro pullback recovery trade: ${JSON.stringify(trace.reports[0])}`,
    );
    assert.equal(recoveryTrade.side, "BUY");
    assert.equal(Date.parse(recoveryTrade.openedAt) - Date.parse(recoveryTrade.signalAt), 300_000);
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

function createMacroRecoveryFixtureDatabase(databasePath) {
  const database = new DatabaseSync(databasePath);
  const timestamps = [];
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
      VALUES ('BTCUSDT', 'M5', ?, ?, ?, ?, ?, '10', ?)
    `);
    const startedAt = Date.parse("2020-01-01T00:00:00Z");
    database.exec("BEGIN");
    for (let index = 0; index < 13_000; index += 1) {
      const openedAt = new Date(startedAt + index * 300_000).toISOString().replace(".000Z", "Z");
      const close = macroRecoveryFixtureClose(index);
      const open = index === 0 ? close : macroRecoveryFixtureClose(index - 1);
      timestamps.push(openedAt);
      insert.run(
        openedAt,
        open.toString(),
        (Math.max(open, close) + 0.1).toString(),
        (Math.min(open, close) - 0.1).toString(),
        close.toString(),
        openedAt,
      );
    }
    database.exec("COMMIT");
  } catch (error) {
    if (database.isTransaction) database.exec("ROLLBACK");
    throw error;
  } finally {
    database.close();
  }
  return timestamps;
}

function macroRecoveryFixtureClose(index) {
  if (index < 7_800) return 100;
  if (index < 7_840) return 100 + (50 * (index - 7_800)) / 40;
  if (index < 8_500) return 150 + (30 * (index - 7_840)) / 660;
  if (index < 8_560) return 180 - (40 * (index - 8_500)) / 60;
  if (index < 8_704) return 140 + (1.8 * (index - 8_560)) / 144;
  return 141.8;
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
