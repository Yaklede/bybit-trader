import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-result-v2.json"),
  "utf8",
));

test("v2 rejects continuation and does not overstate the weak positive reversal mean", () => {
  assert.equal(result.status, "REJECTED_NO_STATISTICALLY_POSITIVE_FAMILY");
  const continuation = result.families.find((family) => family.family === "EVENT_DEPLETION_CONTINUATION");
  const reversal = result.families.find((family) => family.family === "EVENT_ABSORPTION_REVERSAL");
  assert.equal(continuation.positiveMeanCandidateCount, 0);
  assert.equal(reversal.bestObservedCandidate.meanNetR > 0, true);
  assert.equal(reversal.bestObservedCandidate.bootstrapLowerMeanNetR < 0, true);
  assert.equal(reversal.bestObservedCandidate.profitFactor < 1.1, true);
});

test("v2 result allows only a new confirmed-reversal state-machine stage", () => {
  assert.equal(result.trialAccounting.cumulativeCandidates, 124);
  assert.equal(result.nextStageBoundary.allowed.includes("CONFIRMATION_STATE_MACHINE"), true);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
