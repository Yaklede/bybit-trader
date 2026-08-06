import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import {
  auditFundingCrowdingCoverage,
  bindFundingCrowdingDatabase,
  ensureFundingCrowdingSchema,
  fetchReversePages,
  normalizeFundingRows,
  normalizePremiumRows,
  normalizedFundingFeatureFingerprint,
  parseArgs,
  validateExistingImport,
  verifyIntervalCoverage,
} from "./funding-crowding-acquire.mjs";

test("funding acquisition arguments cannot open validation or override evidence", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-funding-crowding-development-v1.json",
    "--report=build/research/report.json",
    "--request-delay-ms=200",
  ]);
  assert.match(parsed.protocol, /bybit-funding-crowding-development-v1\.json$/);
  assert.equal(parsed.requestDelayMs, 200);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2023-01-01"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--request-delay-ms=-1"]), /non-negative integer/);
});

test("funding and premium normalization are sorted, canonical, and conflict-safe", () => {
  const start = Date.parse("2020-01-01T00:00:00Z");
  const end = start + 24 * 60 * 60 * 1_000;
  const funding = normalizeFundingRows([
    { fundingRateTimestamp: String(start + 8 * 60 * 60 * 1_000), fundingRate: "0.000300000" },
    { fundingRateTimestamp: String(start), fundingRate: "-0.0005000" },
    { fundingRateTimestamp: String(start), fundingRate: "-0.0005" },
  ], start, end);
  assert.deepEqual(funding, [
    { timestamp: start, fundingRate: "-0.0005" },
    { timestamp: start + 8 * 60 * 60 * 1_000, fundingRate: "0.0003" },
  ]);
  assert.throws(() => normalizeFundingRows([
    { fundingRateTimestamp: String(start), fundingRate: "0.1" },
    { fundingRateTimestamp: String(start), fundingRate: "0.2" },
  ], start, end), /conflicting rows/);

  const premium = normalizePremiumRows([
    [String(start), "0.00010", "0.0002", "-0.0001", "0.00005"],
  ], start, end);
  assert.deepEqual(premium[0], {
    timestamp: start,
    open: "0.0001",
    high: "0.0002",
    low: "-0.0001",
    close: "0.00005",
  });
});

test("interval coverage rejects silent gaps and stale tails", () => {
  const start = Date.parse("2020-01-01T00:00:00Z");
  const interval = 8 * 60 * 60 * 1_000;
  const complete = verifyIntervalCoverage(
    Array.from({ length: 4 }, (_, index) => ({ timestamp: start + index * interval })),
    interval,
    "funding",
    { minimumRows: 4, requiredLastAtOrAfter: start + 3 * interval },
  );
  assert.equal(complete.complete, true);
  const gap = verifyIntervalCoverage([
    { timestamp: start },
    { timestamp: start + 2 * interval },
  ], interval, "funding", { requiredLastAtOrAfter: start + 3 * interval });
  assert.equal(gap.complete, false);
  assert.equal(gap.failures.some((failure) => failure.includes("gap")), true);
  assert.equal(gap.failures.some((failure) => failure.includes("required boundary")), true);
});

test("reverse pagination retains auditable raw responses including the terminal page", async () => {
  const start = Date.parse("2020-01-01T00:00:00Z");
  const responses = [
    [{ fundingRateTimestamp: String(start + 8 * 60 * 60 * 1_000), fundingRate: "0.1" }],
    [{ fundingRateTimestamp: String(start + 60 * 60 * 1_000), fundingRate: "0.2" }],
    [],
  ];
  let requestIndex = 0;
  const result = await fetchReversePages({
    endpoint: "/funding",
    params: { startTime: start, limit: 1 },
    endExclusiveMillis: start + 16 * 60 * 60 * 1_000,
    request: async (endpoint, params) => {
      const list = responses[requestIndex];
      const rawBody = JSON.stringify({ retCode: 0, result: { list } });
      const response = {
        payload: JSON.parse(rawBody),
        rawBody,
        canonicalRequest: `${endpoint}?endTime=${params.endTime}`,
      };
      requestIndex += 1;
      return response;
    },
    rows: (value) => value.list,
    timestamp: (row) => Number(row.fundingRateTimestamp),
  });
  assert.equal(result.rows.length, 2);
  assert.equal(result.pageCount, 3);
  assert.equal(result.rawPages.at(-1).rawBody.includes('"list":[]'), true);
  assert.equal(result.responseChainSha256, responseChain(result.rawPages));
});

test("database binding and normalized import receipts are immutable", () => {
  const db = new DatabaseSync(":memory:");
  ensureFundingCrowdingSchema(db);
  const binding = {
    protocolId: "protocol",
    protocolSha256: "a".repeat(64),
    parentResultSha256: "b".repeat(64),
    candleDatabaseSha256: "c".repeat(64),
    boundAt: "2020-01-01T00:00:00Z",
  };
  bindFundingCrowdingDatabase(db, binding);
  bindFundingCrowdingDatabase(db, { ...binding, boundAt: "2021-01-01T00:00:00Z" });
  assert.throws(() => bindFundingCrowdingDatabase(db, {
    ...binding,
    protocolSha256: "d".repeat(64),
  }), /different evidence/);

  db.prepare("INSERT INTO fundingRates VALUES ('BTCUSDT','2020-01-01T00:00:00Z','0.0001')").run();
  db.prepare("INSERT INTO premiumIndexBars VALUES ('BTCUSDT','M15','2020-01-01T00:00:00Z','0','0','0','0')").run();
  const first = normalizedFundingFeatureFingerprint(db, "BTCUSDT");
  const second = normalizedFundingFeatureFingerprint(db, "BTCUSDT");
  assert.equal(first, second);

  const rows = [{ timestamp: Date.parse("2020-01-01T00:00:00Z"), fundingRate: "0.0001" }];
  const contentHash = createHash("sha256").update(`${JSON.stringify(rows[0])}\n`).digest("hex");
  const summary = {
    dataset: "funding",
    sourceEndpoint: "/funding",
    rangeStart: "2020-01-01T00:00:00Z",
    rangeEndExclusive: "2020-01-02T00:00:00Z",
    pageCount: 1,
    rowCount: 1,
    firstTimestamp: "2020-01-01T00:00:00Z",
    lastTimestamp: "2020-01-01T00:00:00Z",
    normalizedContentSha256: contentHash,
    responseChainSha256: responseChain([rawPage(0, "/funding?page=0", "{}")]),
    importerVersion: "funding-crowding-development-v1",
  };
  const pages = [rawPage(0, "/funding?page=0", "{}")];
  assert.doesNotThrow(() => validateExistingImport(summary, rows, pages, {
    endpoint: "/funding",
    rangeStart: "2020-01-01T00:00:00Z",
    rangeEndExclusive: "2020-01-02T00:00:00Z",
  }));
  assert.throws(() => validateExistingImport(summary, [{ ...rows[0], fundingRate: "0.0002" }]), /immutable import receipt/);
  assert.throws(() => validateExistingImport(summary, rows, [{ ...pages[0], rawBody: "changed" }]), /content hash/);
  db.close();
});

test("coverage audit requires funding warmup, continuous premium, and all candle frames", () => {
  const db = new DatabaseSync(":memory:");
  ensureFundingCrowdingSchema(db);
  const start = Date.parse("2020-01-01T00:00:00Z");
  const end = Date.parse("2020-02-15T00:00:00Z");
  const fundingInsert = db.prepare("INSERT INTO fundingRates VALUES ('BTCUSDT',?,?)");
  for (let timestamp = start; timestamp < end; timestamp += 8 * 60 * 60 * 1_000) {
    fundingInsert.run(instant(timestamp), "0.0001");
  }
  const premiumInsert = db.prepare("INSERT INTO premiumIndexBars VALUES ('BTCUSDT','M15',?,'0','0','0','0')");
  for (let timestamp = start; timestamp < end; timestamp += 15 * 60 * 1_000) {
    premiumInsert.run(instant(timestamp));
  }
  const candleInsert = db.prepare(`
    INSERT INTO marketCandles(symbol,timeframe,opened_at,open,high,low,close,volume,source_timestamp)
    VALUES ('BTCUSDT',?,?,'1','1','1','1','1',?)
  `);
  db.exec("BEGIN");
  for (const [timeframe, interval] of [["M1", 60_000], ["M5", 300_000], ["M15", 900_000]]) {
    for (let timestamp = start; timestamp < end; timestamp += interval) {
      candleInsert.run(timeframe, instant(timestamp), instant(timestamp));
    }
  }
  db.exec("COMMIT");
  const protocol = {
    sourceData: {
      symbol: "BTCUSDT",
      developmentStart: instant(start),
      developmentEndExclusive: instant(end),
    },
    evidenceSchedule: {
      developmentBlocks: [{ startAt: "2020-02-01T00:00:00Z" }],
    },
  };
  const audit = auditFundingCrowdingCoverage(db, protocol);
  assert.equal(audit.complete, true);
  assert.equal(audit.funding.warmupBeforeFirstBlock >= 90, true);
  assert.deepEqual(Object.keys(audit.candleCounts).sort(), ["M1", "M15", "M5"]);
  assert.equal(audit.candleCoverage.M1.rowCount, audit.candleCoverage.M1.expectedRows);
  db.prepare("DELETE FROM premiumIndexBars WHERE opened_at='2020-01-10T00:00:00Z'").run();
  assert.equal(auditFundingCrowdingCoverage(db, protocol).complete, false);
  db.close();
});

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
