import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-failed-sweep-result-v1.json"),
  "utf8",
));

test("failed-sweep reversal rejects all candidates on wholly negative 2023 evidence", () => {
  assert.equal(result.status, "REJECTED_FAILED_SWEEP_REVERSAL");
  assert.equal(result.selection2023.eligibleCandidateCount, 0);
  assert.equal(result.selection2023.candidateMeanNetRRange.maximum < 0, true);
  assert.equal(result.selection2023.bestRankedIneligibleCandidate.bootstrapUpperMeanNetR < 0, true);
  assert.equal(result.bestRankedCandidateBreakdown.grossMeanRAfterSlippageBeforeFees < 0, true);
  assert.equal(result.gate.passed, false);
});

test("failed-sweep failure leaves one bounded final development family only", () => {
  assert.equal(result.trialAccounting.cumulativeCandidates, 173);
  assert.equal(result.trialAccounting.remainingCandidates, 19);
  assert.equal(result.nextStageBoundary.notAllowed.includes(
    "RETUNE_OR_DIRECTION_FILTER_THE_REJECTED_FAILED_SWEEP_CANDIDATES",
  ), true);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
