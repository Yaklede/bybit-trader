import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import {
  auditDeltaNeutralFundingCarryCoverage,
  bindDeltaNeutralFundingCarryDatabase,
  ensureDeltaNeutralFundingCarrySchema,
  fetchReversePages,
  normalizeFundingRows,
  normalizeKlineRows,
  normalizedDeltaNeutralEvidenceFingerprint,
  parseArgs,
  validateExistingImport,
  verifyExactIntervalCoverage,
} from "./delta-neutral-funding-carry-acquire.mjs";

test("acquisition arguments cannot open 2024 or override the frozen range", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-delta-neutral-funding-carry-development-v1.json",
    "--report=build/research/delta-neutral-report.json",
    "--request-delay-ms=200",
  ]);
  assert.match(parsed.protocol, /bybit-delta-neutral-funding-carry-development-v1\.json$/);
  assert.equal(parsed.requestDelayMs, 200);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2024-01-01"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--request-delay-ms=-1"]), /non-negative integer/);
});

test("bar and funding normalization are sorted, canonical, and conflict-safe", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const end = start + 10 * 60 * 1_000;
  const bars = normalizeKlineRows([
    [String(start + 5 * 60 * 1_000), "2.00", "3", "1", "2.5", "10.0", "20.0"],
    [String(start), "1.0", "2.0", "0.5", "1.5"],
    [String(start), "1", "2", "0.5", "1.5"],
  ], start, end, "spot");
  assert.deepEqual(bars, [
    { timestamp: start, open: "1", high: "2", low: "0.5", close: "1.5", volume: null, turnover: null },
    { timestamp: start + 5 * 60 * 1_000, open: "2", high: "3", low: "1", close: "2.5", volume: "10", turnover: "20" },
  ]);
  assert.throws(() => normalizeKlineRows([
    [String(start), "1", "2", "0.5", "1.5"],
    [String(start), "1", "2", "0.5", "1.6"],
  ], start, end, "spot"), /conflicting rows/);
  const funding = normalizeFundingRows([
    { fundingRateTimestamp: String(start), fundingRate: "0.0001000" },
  ], start, end);
  assert.deepEqual(funding, [{ timestamp: start, fundingRate: "0.0001" }]);
});

test("exact coverage rejects any missing interval or boundary", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const interval = 5 * 60 * 1_000;
  const rows = Array.from({ length: 3 }, (_, index) => ({ timestamp: start + index * interval }));
  assert.equal(verifyExactIntervalCoverage(rows, interval, start, start + 3 * interval, "bars").complete, true);
  const missing = verifyExactIntervalCoverage([rows[0], rows[2]], interval, start, start + 3 * interval, "bars");
  assert.equal(missing.complete, false);
  assert.equal(missing.failures.some((failure) => failure.includes("gap")), true);
});

test("reverse pagination retains every raw response including terminal page", async () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const responses = [
    [[String(start + 5 * 60 * 1_000), "1", "1", "1", "1"]],
    [[String(start + 1), "1", "1", "1", "1"]],
    [],
  ];
  let requestIndex = 0;
  const result = await fetchReversePages({
    endpoint: "/v5/market/kline",
    params: { start, limit: 1 },
    endExclusiveMillis: start + 10 * 60 * 1_000,
    request: async (endpoint, params) => {
      const list = responses[requestIndex];
      const rawBody = JSON.stringify({ retCode: 0, result: { list } });
      requestIndex += 1;
      return {
        payload: JSON.parse(rawBody),
        rawBody,
        canonicalRequest: `${endpoint}?end=${params.end}`,
      };
    },
    rows: (value) => value.list,
    timestamp: (row) => Number(row[0]),
  });
  assert.equal(result.rows.length, 2);
  assert.equal(result.pageCount, 3);
  assert.equal(result.rawPages.at(-1).rawBody.includes('"list":[]'), true);
  assert.equal(result.responseChainSha256, responseChain(result.rawPages));
});

test("database binding, evidence fingerprint, and import receipts are immutable", () => {
  const db = new DatabaseSync(":memory:");
  ensureDeltaNeutralFundingCarrySchema(db);
  const binding = {
    protocolId: "protocol",
    protocolSha256: "a".repeat(64),
    parentResultSha256: "b".repeat(64),
    boundAt: "2023-01-01T00:00:00Z",
  };
  bindDeltaNeutralFundingCarryDatabase(db, binding);
  bindDeltaNeutralFundingCarryDatabase(db, { ...binding, boundAt: "2024-01-01T00:00:00Z" });
  assert.throws(() => bindDeltaNeutralFundingCarryDatabase(db, {
    ...binding,
    protocolSha256: "c".repeat(64),
  }), /different evidence/);
  for (const series of ["SPOT_LAST", "PERPETUAL_LAST", "PERPETUAL_MARK", "PERPETUAL_INDEX"]) {
    db.prepare("INSERT INTO marketBars VALUES (?, '2023-01-01T00:00:00Z', '1','1','1','1',NULL,NULL)")
      .run(series);
  }
  db.prepare("INSERT INTO fundingRates VALUES ('BTCUSDT','2023-01-01T00:00:00Z','0.0001')").run();
  const protocol = { sourceData: { perpetualSymbol: "BTCUSDT" } };
  assert.equal(normalizedDeltaNeutralEvidenceFingerprint(db, protocol),
    normalizedDeltaNeutralEvidenceFingerprint(db, protocol));

  const rows = [{ timestamp: Date.parse("2023-01-01T00:00:00Z"), fundingRate: "0.0001" }];
  const pages = [rawPage(0, "/funding?page=0", "{}")];
  const summary = {
    dataset: "funding",
    sourceEndpoint: "/funding",
    rangeStart: "2023-01-01T00:00:00Z",
    rangeEndExclusive: "2023-01-02T00:00:00Z",
    pageCount: 1,
    rowCount: 1,
    firstTimestamp: "2023-01-01T00:00:00Z",
    lastTimestamp: "2023-01-01T00:00:00Z",
    responseChainSha256: responseChain(pages),
    normalizedContentSha256: hashRows(rows),
    importerVersion: "delta-neutral-funding-carry-development-v1",
  };
  assert.doesNotThrow(() => validateExistingImport(summary, rows, pages, {
    endpoint: "/funding",
    rangeStart: "2023-01-01T00:00:00Z",
    rangeEndExclusive: "2023-01-02T00:00:00Z",
  }));
  assert.throws(() => validateExistingImport(summary, [{ ...rows[0], fundingRate: "0.0002" }], pages, {
    endpoint: "/funding",
    rangeStart: "2023-01-01T00:00:00Z",
    rangeEndExclusive: "2023-01-02T00:00:00Z",
  }), /immutable import receipt/);
  db.close();
});

test("coverage requires one exact timeline and usable funding decision bars", () => {
  const db = new DatabaseSync(":memory:");
  ensureDeltaNeutralFundingCarrySchema(db);
  const start = Date.parse("2023-01-01T00:00:00Z");
  const end = start + 24 * 60 * 60 * 1_000;
  const insertBar = db.prepare("INSERT INTO marketBars VALUES (?,?,?,?,?,?,NULL,NULL)");
  db.exec("BEGIN");
  for (const series of ["SPOT_LAST", "PERPETUAL_LAST", "PERPETUAL_MARK", "PERPETUAL_INDEX"]) {
    for (let timestamp = start; timestamp < end; timestamp += 5 * 60 * 1_000) {
      insertBar.run(series, instant(timestamp), "1", "1", "1", "1");
    }
  }
  const insertFunding = db.prepare("INSERT INTO fundingRates VALUES ('BTCUSDT',?,?)");
  for (let timestamp = start; timestamp < end; timestamp += 8 * 60 * 60 * 1_000) {
    insertFunding.run(instant(timestamp), "0.0001");
  }
  db.exec("COMMIT");
  const protocol = {
    sourceData: {
      perpetualSymbol: "BTCUSDT",
      developmentStart: instant(start),
      developmentEndExclusive: instant(end),
    },
  };
  assert.equal(auditDeltaNeutralFundingCarryCoverage(db, protocol).complete, true);
  db.prepare("DELETE FROM marketBars WHERE series='PERPETUAL_MARK' AND opened_at=?").run(instant(start + 5 * 60 * 1_000));
  const broken = auditDeltaNeutralFundingCarryCoverage(db, protocol);
  assert.equal(broken.complete, false);
  assert.equal(broken.matchingBarCount < broken.expectedMatchingBarCount, true);
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

function hashRows(rows) {
  const hash = createHash("sha256");
  for (const row of rows) hash.update(`${JSON.stringify(row)}\n`);
  return hash.digest("hex");
}
