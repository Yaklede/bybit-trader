import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import {
  assertCompleteSliceDay,
  bindResearchDatabase,
  completeTradeSliceDay,
  ensureSubminuteSchema,
  expectedArchiveDay,
  parseArgs,
  verifyOrderBookMinuteParity,
  verifyTradeMinuteParity,
} from "./subminute-sequence-acquire.mjs";

test("subminute acquisition arguments expose only declared chronological stages", () => {
  assert.equal(parseArgs(["--stage=selection"]).stage, "selection");
  assert.equal(parseArgs(["--stage=internal-validation"]).stage, "internal-validation");
  assert.throws(() => parseArgs(["--stage=external"]), /stage must be one of/);
  assert.throws(() => parseArgs(["--unknown=value"]), /Unsupported argument/);
});

test("subminute schema and protocol binding are idempotent but immutable", () => {
  const db = new DatabaseSync(":memory:");
  ensureSubminuteSchema(db);
  ensureSubminuteSchema(db);
  const binding = {
    protocolId: "protocol",
    protocolSha256: "protocol-hash",
    candleDatabaseSha256: "candle-hash",
    aggregateDatabaseSha256: "aggregate-hash",
    bucketMillis: 5000,
  };
  assert.doesNotThrow(() => bindResearchDatabase(db, binding));
  assert.doesNotThrow(() => bindResearchDatabase(db, binding));
  assert.throws(
    () => bindResearchDatabase(db, { ...binding, bucketMillis: 1000 }),
    /metadata mismatch for bucketMillis/,
  );
  db.close();
});

test("trade slices explicitly represent no-trade intervals without future prices", () => {
  const date = "2024-01-01";
  const start = Date.parse(`${date}T00:00:00Z`);
  const bars = new Map([[start + 5_000, {
    buyNotional: 100,
    sellNotional: 0,
    buyCount: 1,
    sellCount: 0,
    openPrice: 100,
    highPrice: 100,
    lowPrice: 100,
    closePrice: 100,
    firstTradeAt: start + 5_100,
    lastTradeAt: start + 5_100,
  }]]);
  const slices = completeTradeSliceDay(bars, date, 5_000);
  assert.equal(slices.length, 17_280);
  assert.equal(slices[0].tradeCount, 0);
  assert.equal(slices[0].openPrice, null);
  assert.equal(slices[1].tradeCount, 1);
  assert.equal(slices[1].firstTradeAt, start + 5_100);
  assert.doesNotThrow(() => assertCompleteSliceDay(slices, date, 5_000));
});

test("bound aggregate manifests provide immutable order-book and trade provenance", () => {
  const db = aggregateDatabase();
  db.prepare(`INSERT INTO historicalOrderBookImports VALUES ('bybit','orderbook','BTCUSDT','2024-01-01','https://book','book.zip',10,'book-hash',20,'2024-01-01T00:00:00.001Z','2024-01-01T23:59:59.999Z')`).run();
  db.prepare(`INSERT INTO historicalTradeImports VALUES ('bybit','public-trades','BTCUSDT','2024-01-01','https://trade',30,'trade-hash',40,'2024-01-01T00:00:00.001Z','2024-01-01T23:59:59.999Z')`).run();
  const expected = expectedArchiveDay(db, "BTCUSDT", "2024-01-01");
  assert.equal(expected.orderBook.archiveSha256, "book-hash");
  assert.equal(expected.orderBook.archiveFilename, "book.zip");
  assert.equal(expected.trade.archiveSha256, "trade-hash");
  db.close();
});

test("five-second features aggregate back to the previously bound minute evidence", () => {
  const db = aggregateDatabase();
  db.exec(`
    CREATE TABLE orderBookEventFlowBars(
      symbol TEXT, opened_at TEXT, message_count INTEGER, snapshot_count INTEGER,
      mean_top5_imbalance TEXT, mean_microprice_edge_bps TEXT,
      bid_added_top5_notional TEXT, bid_removed_top5_notional TEXT,
      ask_added_top5_notional TEXT, ask_removed_top5_notional TEXT,
      open_mid_price TEXT, high_mid_price TEXT, low_mid_price TEXT, close_mid_price TEXT
    );
    CREATE TABLE takerFlowBars(
      symbol TEXT, opened_at TEXT, taker_buy_notional TEXT, taker_sell_notional TEXT,
      buy_trade_count INTEGER, sell_trade_count INTEGER
    );
    CREATE TABLE takerEventFlowBars(
      symbol TEXT, opened_at TEXT, open_trade_price TEXT, high_trade_price TEXT,
      low_trade_price TEXT, close_trade_price TEXT
    );
  `);
  const insertBook = db.prepare("INSERT INTO orderBookEventFlowBars VALUES ('BTCUSDT', ?, 12, 0, '0.2', '0.1', '12', '24', '36', '48', '100', '101', '99', '100')");
  const insertFlow = db.prepare("INSERT INTO takerFlowBars VALUES ('BTCUSDT', ?, '10', '0', 1, 0)");
  const insertTrade = db.prepare("INSERT INTO takerEventFlowBars VALUES ('BTCUSDT', ?, '100', '100', '100', '100')");
  const dayStart = Date.parse("2024-01-01T00:00:00Z");
  db.exec("BEGIN");
  for (let minute = 0; minute < 1_440; minute += 1) {
    const openedAt = new Date(dayStart + minute * 60_000).toISOString().replace(".000Z", "Z");
    insertBook.run(openedAt);
    insertFlow.run(openedAt);
    insertTrade.run(openedAt);
  }
  db.exec("COMMIT");

  const bookSlices = [];
  const tradeBars = new Map();
  for (let index = 0; index < 17_280; index += 1) {
    const openedAt = dayStart + index * 5_000;
    bookSlices.push({
      openedAt,
      messageCount: 1,
      snapshotCount: 0,
      meanTop5Imbalance: 0.2,
      meanMicropriceEdgeBps: 0.1,
      bidAddedTop5Notional: 1,
      bidRemovedTop5Notional: 2,
      askAddedTop5Notional: 3,
      askRemovedTop5Notional: 4,
      openMidPrice: 100,
      highMidPrice: 101,
      lowMidPrice: 99,
      closeMidPrice: 100,
    });
    if (index % 12 === 0) {
      tradeBars.set(openedAt, {
        buyNotional: 10,
        sellNotional: 0,
        buyCount: 1,
        sellCount: 0,
        openPrice: 100,
        highPrice: 100,
        lowPrice: 100,
        closePrice: 100,
        firstTradeAt: openedAt + 1,
        lastTradeAt: openedAt + 1,
      });
    }
  }
  const tradeSlices = completeTradeSliceDay(tradeBars, "2024-01-01", 5_000);
  assert.doesNotThrow(() => verifyOrderBookMinuteParity(bookSlices, db, "BTCUSDT", "2024-01-01"));
  assert.doesNotThrow(() => verifyTradeMinuteParity(tradeSlices, db, "BTCUSDT", "2024-01-01"));
  db.close();
});

function aggregateDatabase() {
  const db = new DatabaseSync(":memory:");
  db.exec(`
    CREATE TABLE historicalOrderBookImports(
      provider TEXT, dataset TEXT, symbol TEXT, source_date TEXT, source_url TEXT,
      archive_filename TEXT, archive_size_bytes INTEGER, archive_sha256 TEXT,
      event_count INTEGER, first_event_at TEXT, last_event_at TEXT
    );
    CREATE TABLE historicalTradeImports(
      provider TEXT, dataset TEXT, symbol TEXT, source_date TEXT, source_url TEXT,
      archive_size_bytes INTEGER, archive_sha256 TEXT, event_count INTEGER,
      first_event_at TEXT, last_event_at TEXT
    );
  `);
  return db;
}
