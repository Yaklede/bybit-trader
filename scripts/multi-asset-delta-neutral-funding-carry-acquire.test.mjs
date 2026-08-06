import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import {
  auditMultiAssetDeltaNeutralFundingCarryCoverage,
  bindMultiAssetDeltaNeutralFundingCarryDatabase,
  ensureMultiAssetDeltaNeutralFundingCarrySchema,
  multiAssetDatasetDefinitions,
  normalizedMultiAssetEvidenceFingerprint,
  parseArgs,
  validateExistingImport,
} from "./multi-asset-delta-neutral-funding-carry-acquire.mjs";

test("acquisition arguments cannot override the frozen date range", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-multi-asset-delta-neutral-funding-carry-development-v1.json",
    "--report=build/research/multi-asset-acquisition.json",
    "--request-delay-ms=200",
  ]);
  assert.match(parsed.protocol, /multi-asset-delta-neutral-funding-carry-development-v1\.json$/);
  assert.equal(parsed.requestDelayMs, 200);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2024-01-01"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--request-delay-ms=-1"]), /non-negative integer/);
});

test("dataset plan has five immutable datasets for each of three symbols", () => {
  const protocol = { sourceData: sourceData(0, 1) };
  const definitions = multiAssetDatasetDefinitions(protocol);
  assert.equal(definitions.length, 15);
  assert.equal(new Set(definitions.map((definition) => definition.dataset)).size, 15);
  for (const symbol of protocol.sourceData.symbols) {
    assert.equal(definitions.filter((definition) => definition.symbol === symbol).length, 5);
  }
});

test("database binding and normalized evidence are symbol aware", () => {
  const db = new DatabaseSync(":memory:");
  ensureMultiAssetDeltaNeutralFundingCarrySchema(db);
  const binding = {
    protocolId: "protocol",
    protocolSha256: "a".repeat(64),
    parentResultSha256: "b".repeat(64),
    boundAt: "2023-01-01T00:00:00Z",
  };
  bindMultiAssetDeltaNeutralFundingCarryDatabase(db, binding);
  bindMultiAssetDeltaNeutralFundingCarryDatabase(db, { ...binding, boundAt: "2024-01-01T00:00:00Z" });
  assert.throws(() => bindMultiAssetDeltaNeutralFundingCarryDatabase(db, {
    ...binding,
    protocolSha256: "c".repeat(64),
  }), /different evidence/);

  const start = Date.parse("2023-01-01T00:00:00Z");
  const insertBar = db.prepare("INSERT INTO marketBars VALUES (?,?,?,?,?,?,?,NULL,NULL)");
  const insertFunding = db.prepare("INSERT INTO fundingRates VALUES (?,?,?)");
  for (const symbol of ["BTCUSDT", "ETHUSDT", "SOLUSDT"]) {
    for (const series of ["SPOT_LAST", "PERPETUAL_LAST", "PERPETUAL_MARK", "PERPETUAL_INDEX"]) {
      insertBar.run(symbol, series, instant(start), "1", "1", "1", "1");
    }
    insertFunding.run(symbol, instant(start), "0.0001");
  }
  const protocol = { sourceData: { symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"] } };
  const before = normalizedMultiAssetEvidenceFingerprint(db, protocol);
  db.prepare(`
    UPDATE marketBars SET close='1.1'
    WHERE symbol='ETHUSDT' AND series='SPOT_LAST' AND opened_at=?
  `).run(instant(start));
  assert.notEqual(normalizedMultiAssetEvidenceFingerprint(db, protocol), before);
  db.close();
});

test("coverage requires exact M5 and funding timelines for every symbol", () => {
  const db = new DatabaseSync(":memory:");
  ensureMultiAssetDeltaNeutralFundingCarrySchema(db);
  const start = Date.parse("2023-01-01T00:00:00Z");
  const end = start + 24 * 60 * 60 * 1_000;
  const insertBar = db.prepare("INSERT INTO marketBars VALUES (?,?,?,?,?,?,?,NULL,NULL)");
  const insertFunding = db.prepare("INSERT INTO fundingRates VALUES (?,?,?)");
  db.exec("BEGIN");
  for (const symbol of ["BTCUSDT", "ETHUSDT", "SOLUSDT"]) {
    for (const series of ["SPOT_LAST", "PERPETUAL_LAST", "PERPETUAL_MARK", "PERPETUAL_INDEX"]) {
      for (let timestamp = start; timestamp < end; timestamp += 5 * 60 * 1_000) {
        insertBar.run(symbol, series, instant(timestamp), "1", "1", "1", "1");
      }
    }
    for (let timestamp = start; timestamp < end; timestamp += 8 * 60 * 60 * 1_000) {
      insertFunding.run(symbol, instant(timestamp), "0.0001");
    }
  }
  db.exec("COMMIT");
  const protocol = { sourceData: sourceData(start, end) };
  const complete = auditMultiAssetDeltaNeutralFundingCarryCoverage(db, protocol);
  assert.equal(complete.complete, true);
  assert.equal(complete.totalMatchingM5Rows, 3 * 288);
  assert.equal(complete.totalFundingRows, 9);
  db.prepare(`
    DELETE FROM marketBars WHERE symbol='SOLUSDT' AND series='PERPETUAL_MARK' AND opened_at=?
  `).run(instant(start + 5 * 60 * 1_000));
  const broken = auditMultiAssetDeltaNeutralFundingCarryCoverage(db, protocol);
  assert.equal(broken.complete, false);
  assert.equal(broken.symbols.SOLUSDT.matchingM5Rows, 287);
  db.close();
});

test("persisted imports bind symbol, series, rows, and raw responses", () => {
  const timestamp = Date.parse("2023-01-01T00:00:00Z");
  const rows = [{ timestamp, fundingRate: "0.0001" }];
  const pages = [rawPage(0, "/funding?symbol=ETHUSDT", "{}")];
  const summary = {
    dataset: "ethusdt_funding",
    symbol: "ETHUSDT",
    series: null,
    sourceEndpoint: "/funding",
    rangeStart: instant(timestamp),
    rangeEndExclusive: instant(timestamp + 8 * 60 * 60 * 1_000),
    pageCount: 1,
    rowCount: 1,
    firstTimestamp: instant(timestamp),
    lastTimestamp: instant(timestamp),
    responseChainSha256: responseChain(pages),
    normalizedContentSha256: hashRows(rows),
    importerVersion: "multi-asset-delta-neutral-funding-carry-development-v1",
  };
  const expected = {
    symbol: "ETHUSDT",
    series: null,
    endpoint: "/funding",
    rangeStart: summary.rangeStart,
    rangeEndExclusive: summary.rangeEndExclusive,
  };
  assert.doesNotThrow(() => validateExistingImport(summary, rows, pages, expected));
  assert.throws(
    () => validateExistingImport({ ...summary, symbol: "BTCUSDT" }, rows, pages, expected),
    /source contract/,
  );
  assert.throws(
    () => validateExistingImport(summary, [{ ...rows[0], fundingRate: "0.0002" }], pages, expected),
    /immutable import receipt/,
  );
});

function sourceData(start, end) {
  return {
    symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
    spotKlineEndpoint: "/v5/market/kline?category=spot",
    perpetualKlineEndpoint: "/v5/market/kline?category=linear",
    markKlineEndpoint: "/v5/market/mark-price-kline?category=linear",
    indexKlineEndpoint: "/v5/market/index-price-kline?category=linear",
    fundingEndpoint: "/v5/market/funding/history?category=linear",
    developmentStart: instant(start),
    developmentEndExclusive: instant(end),
  };
}

function instant(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}

function rawPage(pageIndex, canonicalRequest, rawBody) {
  return {
    pageIndex,
    canonicalRequest,
    rawBody,
    responseSha256: createHash("sha256").update(rawBody).digest("hex"),
  };
}

function responseChain(pages) {
  const hash = createHash("sha256");
  for (const page of pages) {
    hash.update(page.canonicalRequest);
    hash.update("\0");
    hash.update(page.rawBody);
    hash.update("\0");
  }
  return hash.digest("hex");
}

function hashRows(rows) {
  const hash = createHash("sha256");
  for (const row of rows) hash.update(`${JSON.stringify(row)}\n`);
  return hash.digest("hex");
}
