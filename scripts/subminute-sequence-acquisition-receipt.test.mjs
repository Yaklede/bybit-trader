import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const receipt = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-subminute-sequence-selection-acquisition-receipt-v1.json"),
  "utf8",
));

test("selection acquisition receipt binds a complete immutable five-second snapshot", () => {
  assert.equal(receipt.status, "COMPLETE_SELECTION_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "selection");
  assert.equal(receipt.sourceYear, 2023);
  assert.equal(receipt.sourceDateCount, 28);
  assert.equal(receipt.evaluationDayCount, 24);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedFeatureSha256, /^[a-f0-9]{64}$/);
  assert.equal(receipt.coverage.orderBookSliceCount, 28 * 17_280);
  assert.equal(receipt.coverage.tradeSliceCount, 28 * 17_280);
});

test("selection source integrity does not convert missing events into favorable evidence", () => {
  assert.equal(receipt.coverage.carriedForwardOrderBookSliceCount, 0);
  assert.equal(receipt.coverage.zeroTradeSliceCount > 0, true);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
  assert.equal(Object.values(receipt.lockedEvidence).every((value) => value === false), true);
});

test("sealed selection data can select research candidates but cannot unlock execution", () => {
  assert.equal(receipt.selectionAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});
