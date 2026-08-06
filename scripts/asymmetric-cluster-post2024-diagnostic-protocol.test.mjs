import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-post2024-diagnostic-v1.json"), "utf8"),
);
const source = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-absorption-development-v3-result.json"), "utf8"),
);

test("post-2024 diagnostic freezes the retained candidate without claiming independence", () => {
  assert.equal(protocol.candidate.id, source.diagnosticCandidate.id);
  assert.equal(protocol.independence, "NON_INDEPENDENT_PREVIOUSLY_INSPECTED_HISTORY");
  assert.equal(protocol.status, "PREDECLARED_DIAGNOSTIC");
  assert.equal(protocol.outcomePolicy.promotionAllowed, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});

test("diagnostic windows are contiguous, end before the fresh seal, and cover ten quarters", () => {
  assert.equal(protocol.windows.length, 10);
  for (let index = 1; index < protocol.windows.length; index += 1) {
    assert.equal(protocol.windows[index - 1].replayEndAt, protocol.windows[index].replayStartAt);
  }
  assert.equal(protocol.windows[0].replayStartAt, protocol.sourceData.diagnosticReplayStartsAt);
  assert.equal(protocol.windows.at(-1).replayEndAt, protocol.sourceData.diagnosticReplayEndsAt);
  assert.ok(Date.parse(protocol.sourceData.diagnosticReplayEndsAt) < Date.parse("2026-07-02T05:45:00Z"));
  assert.equal(protocol.reservedSealedWindowMayBeRead, false);
});

test("cost stress and diagnostic gates are fixed before replay", () => {
  assert.deepEqual(protocol.costMultipliers, [1, 1.5, 2]);
  assert.equal(protocol.diagnosticGate.minimumBaseTradeCount, 40);
  assert.equal(protocol.diagnosticGate.minimumPositiveWindowCount, 7);
  assert.equal(protocol.diagnosticGate.minimumBaseBootstrapLowerMeanNetR, 0);
  assert.equal(protocol.diagnosticGate.minimumTwoXCostMeanNetR, 0);
});
