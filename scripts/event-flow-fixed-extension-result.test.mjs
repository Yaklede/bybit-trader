import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-fixed-extension-result-v1.json"),
  "utf8",
));

test("fixed extension rejects the post-hoc development winner on untouched dates", () => {
  assert.equal(result.status, "REJECTED_FIXED_EXTENSION");
  assert.equal(result.metrics.observedDays, 96);
  assert.equal(result.metrics.tradeCount, 23);
  assert.equal(result.metrics.netReturnPct < 0, true);
  assert.equal(result.metrics.meanNetR < 0, true);
  assert.equal(result.metrics.profitFactor < 1, true);
  assert.equal(result.metrics.bootstrapLowerMeanNetR < 0, true);
  assert.equal(result.gate.passed, false);
});

test("fixed extension failure keeps all later evidence and execution locked", () => {
  assert.equal(result.trialAccounting.cumulativeCandidates, 141);
  assert.equal(result.trialAccounting.remainingCandidates, 51);
  assert.equal(result.nextStageBoundary.notAllowed.includes(
    "RETUNE_OR_PROMOTE_THE_REJECTED_FIXED_CANDIDATE",
  ), true);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
