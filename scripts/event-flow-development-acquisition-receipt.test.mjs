import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-v1.json"),
  "utf8",
));
const receipt = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-acquisition-receipt-v1.json"),
  "utf8",
));

test("development acquisition receipt seals every predeclared primary block and source archive", () => {
  assert.equal(receipt.status, "COMPLETE");
  assert.deepEqual(
    receipt.completedBlocks.map((block) => block.id),
    protocol.stages.development.primaryBlocks.map((block) => block.id),
  );
  assert.equal(receipt.completedBlocks.flatMap((block) => block.orderBookArchiveSha256).length, 36);
  assert.equal(receipt.completedBlocks.flatMap((block) => block.tradeArchiveSha256).length, 36);
  for (const block of receipt.completedBlocks) {
    assert.equal(block.orderBookArchiveSha256.every(isSha256), true);
    assert.equal(block.tradeArchiveSha256.every(isSha256), true);
    assert.equal(isSha256(block.sourceFingerprintSha256), true);
  }
});

test("receipt proves full causal bar coverage without unlocking later stages", () => {
  assert.deepEqual(receipt.coverage, {
    sourceDays: 36,
    m1Candles: 51_840,
    m5Candles: 10_368,
    m15Candles: 3_456,
    orderBookEventFlowBars: 51_840,
    takerEventFlowBars: 51_840,
  });
  assert.equal(receipt.lockedDataAcquired, false);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}
