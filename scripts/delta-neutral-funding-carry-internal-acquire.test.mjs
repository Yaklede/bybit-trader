import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";

import {
  internalStageProtocol,
  parseArgs,
  validateExistingInternalImport,
} from "./delta-neutral-funding-carry-internal-acquire.mjs";

test("internal acquisition cannot override 2024 or open external evidence", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-delta-neutral-funding-carry-internal-v1.json",
    "--report=build/research/internal.json",
    "--request-delay-ms=200",
  ]);
  assert.match(parsed.protocol, /bybit-delta-neutral-funding-carry-internal-v1\.json$/);
  assert.equal(parsed.requestDelayMs, 200);
  assert.throws(() => parseArgs(["--stage=external"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2025-01-01"]), /Unsupported argument/);
});

test("internal stage adapter exposes only the fixed 2024 range to shared coverage code", () => {
  const stage = internalStageProtocol({
    sourceData: {
      spotSymbol: "BTCUSDT",
      perpetualSymbol: "BTCUSDT",
      stageStart: "2024-01-01T00:00:00Z",
      stageEndExclusive: "2025-01-01T00:00:00Z",
    },
    internalValidationBlocks: [{ id: "I01" }],
  });
  assert.equal(stage.sourceData.developmentStart, "2024-01-01T00:00:00Z");
  assert.equal(stage.sourceData.developmentEndExclusive, "2025-01-01T00:00:00Z");
  assert.deepEqual(stage.evidenceSchedule.developmentBlocks, [{ id: "I01" }]);
});

test("internal import receipt rejects normalized or raw-page mutation", () => {
  const rows = [{ timestamp: Date.parse("2024-01-01T00:00:00Z"), fundingRate: "0.0001" }];
  const pages = [rawPage(0, "/funding?page=0", "{}")];
  const summary = {
    dataset: "funding",
    sourceEndpoint: "/funding",
    rangeStart: "2024-01-01T00:00:00Z",
    rangeEndExclusive: "2025-01-01T00:00:00Z",
    pageCount: 1,
    rowCount: 1,
    firstTimestamp: "2024-01-01T00:00:00Z",
    lastTimestamp: "2024-01-01T00:00:00Z",
    responseChainSha256: responseChain(pages),
    normalizedContentSha256: hashRows(rows),
    importerVersion: "delta-neutral-funding-carry-internal-v1",
  };
  const expected = {
    endpoint: "/funding",
    rangeStart: "2024-01-01T00:00:00Z",
    rangeEndExclusive: "2025-01-01T00:00:00Z",
  };
  assert.doesNotThrow(() => validateExistingInternalImport(summary, rows, pages, expected));
  assert.throws(() => validateExistingInternalImport(
    summary,
    [{ ...rows[0], fundingRate: "0.0002" }],
    pages,
    expected,
  ), /immutable internal receipt/);
  assert.throws(() => validateExistingInternalImport(
    summary,
    rows,
    [{ ...pages[0], rawBody: "changed" }],
    expected,
  ), /raw pages differ/);
});

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
