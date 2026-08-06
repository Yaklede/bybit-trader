import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-accepted-continuation-result-v1.json"),
  "utf8",
));

test("accepted continuation rejects all 16 candidates before 2024 selection", () => {
  assert.equal(result.status, "REJECTED_ACCEPTED_CONTINUATION");
  assert.equal(result.selection2023.eligibleCandidateCount, 0);
  assert.equal(result.selection2023.candidateNetReturnPctRange.maximum < 0, true);
  assert.equal(result.selection2023.candidateMeanNetRRange.maximum < 0, true);
  assert.equal(result.selection2023.candidateProfitFactorRange.maximum < 1, true);
  assert.equal(result.gate.passed, false);
});

test("positive small 2024 observations cannot promote a failed 2023 candidate", () => {
  assert.equal(result.observedButNotSelectable2024.candidateMeanNetRWasPositiveCount, 16);
  assert.equal(result.nextStageBoundary.notAllowed.includes(
    "SELECT_A_CANDIDATE_FROM_2024_AFTER_2023_SELECTION_FAILED",
  ), true);
  assert.equal(result.trialAccounting.cumulativeCandidates, 157);
  assert.equal(result.trialAccounting.remainingCandidates, 35);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
