import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-pullback-result-v1.json"),
  "utf8",
));

test("pullback reacceleration closes the current event-flow program without an approved candidate", () => {
  assert.equal(result.status, "REJECTED_PULLBACK_REACCELERATION");
  assert.equal(result.programStatus, "CLOSED_NO_APPROVABLE_EVENT_FLOW_STRATEGY");
  assert.equal(result.selection2023.eligibleCandidateCount, 0);
  assert.equal(result.selection2023.candidateMeanNetRRange.maximum < 0, true);
  assert.equal(result.selection2023.bestRankedIneligibleCandidate.bootstrapUpperMeanNetR < 0, true);
  assert.equal(result.bestRankedCandidateBreakdown.grossMeanRAfterSlippageBeforeFees < 0, true);
  assert.equal(result.gate.passed, false);
});

test("the remaining single trial cannot retune rejected families or unlock execution", () => {
  assert.equal(result.trialAccounting.cumulativeCandidates, 191);
  assert.equal(result.trialAccounting.remainingCandidates, 1);
  assert.equal(result.trialAccounting.remainingCandidateCanStartNewFamily, false);
  assert.equal(result.nextStageBoundary.notAllowed.includes(
    "USE_THE_ONE_REMAINING_TRIAL_TO_RETUNE_A_REJECTED_FAMILY",
  ), true);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
