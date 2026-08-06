import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-post2024-diagnostic-result-v1.json"), "utf8"),
);

test("post-2024 diagnostic permanently rejects the OHLCV cluster candidate", () => {
  assert.equal(result.status, "REJECTED");
  assert.ok(result.baseCost.meanNetR < 0);
  assert.ok(result.baseCost.profitFactor < 1);
  assert.ok(result.baseCost.bootstrapUpperMeanNetR < 0);
  assert.ok(result.twoXCost.meanNetR < result.baseCost.meanNetR);
});

test("rejected diagnostic cannot open the fresh seal or enable execution", () => {
  assert.equal(result.reservedSealedWindowOpened, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.nextAction, "REJECT_OHLCV_CLUSTER_CANDIDATE_AND_MOVE_TO_EVENT_FLOW_HYPOTHESES");
});
