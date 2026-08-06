import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const receipt = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-fixed-extension-acquisition-receipt-v1.json"),
  "utf8",
));

test("fixed extension receipt seals every declared block and causal event row", () => {
  assert.equal(receipt.status, "COMPLETE_FIXED_EXTENSION_EVENT_DATA_ACQUISITION");
  assert.equal(receipt.blockFingerprints.length, 16);
  assert.deepEqual(receipt.blockFingerprints.map((block) => block.id),
    Array.from({ length: 16 }, (_, index) => `X${String(index + 1).padStart(2, "0")}`));
  assert.equal(receipt.coverage.sourceDayCount, 112);
  assert.equal(receipt.coverage.m1Candles, 112 * 1_440);
  assert.equal(receipt.coverage.orderBookEventFlowBars, receipt.coverage.m1Candles);
  assert.equal(receipt.coverage.takerEventFlowBars, receipt.coverage.m1Candles);
});

test("fixed extension acquisition did not replay the candidate or unlock later evidence", () => {
  assert.equal(receipt.candidateReplayPerformed, false);
  assert.equal(receipt.validationDataRead, false);
  assert.equal(receipt.externalDataRead, false);
  assert.equal(receipt.freshSealedDataRead, false);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});
