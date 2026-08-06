import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  parseArgs,
} from "./multi-asset-delta-neutral-funding-carry-internal-acquire.mjs";
import {
  stageProtocolFromInternal,
  validateExistingStageImport,
} from "./lib/multi-asset-evidence-stage.mjs";

test("internal acquisition cannot override its frozen range or candidate", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-multi-asset-delta-neutral-funding-carry-internal-v1.json",
    "--request-delay-ms=200",
  ]);
  assert.match(parsed.protocol, /multi-asset-delta-neutral-funding-carry-internal-v1\.json$/);
  assert.equal(parsed.requestDelayMs, 200);
  assert.throws(() => parseArgs(["--start=2025-01-01"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
});

test("internal stage maps only the predeclared 2024 range", () => {
  const protocol = {
    sourceData: {
      symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
      stageStart: "2024-01-01T00:00:00Z",
      stageEndExclusive: "2025-01-01T00:00:00Z",
    },
    internalValidationBlocks: [{ id: "I01" }],
  };
  const stage = stageProtocolFromInternal(protocol);
  assert.equal(stage.sourceData.developmentStart, protocol.sourceData.stageStart);
  assert.equal(stage.sourceData.developmentEndExclusive, protocol.sourceData.stageEndExclusive);
  assert.deepEqual(stage.evidenceSchedule.developmentBlocks, protocol.internalValidationBlocks);
});

test("stage import receipt binds symbol, series, rows, and raw pages", () => {
  const timestamp = Date.parse("2024-01-01T00:00:00Z");
  const rows = [{ timestamp, fundingRate: "0.0001" }];
  const pages = [rawPage("{}")];
  const importerVersion = "multi-asset-delta-neutral-funding-carry-internal-v1";
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
    importerVersion,
  };
  const expected = {
    symbol: "ETHUSDT",
    series: null,
    endpoint: "/funding",
    rangeStart: summary.rangeStart,
    rangeEndExclusive: summary.rangeEndExclusive,
  };
  assert.doesNotThrow(() => validateExistingStageImport(
    summary,
    rows,
    pages,
    expected,
    importerVersion,
  ));
  assert.throws(() => validateExistingStageImport(
    { ...summary, importerVersion: "other" },
    rows,
    pages,
    expected,
    importerVersion,
  ), /source contract/);
});

function rawPage(rawBody) {
  return {
    pageIndex: 0,
    canonicalRequest: "/funding?symbol=ETHUSDT",
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

function instant(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}
