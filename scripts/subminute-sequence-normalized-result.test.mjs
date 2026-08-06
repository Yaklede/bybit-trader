import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-subminute-sequence-normalized-v2-result.json"),
  "utf8",
));

test("normalized v2 closes after every candidate loses after costs", () => {
  assert.equal(result.status, "REJECTED_NO_NORMALIZED_SELECTION_CANDIDATE");
  assert.equal(result.programStatus, "CLOSED_NO_APPROVABLE_SUBMINUTE_SEQUENCE_STRATEGY_V2");
  assert.equal(result.selection2023.eligibleCandidateCount, 0);
  assert.equal(result.selection2023.profitableCandidateCount, 0);
  assert.equal(result.selection2023.candidateNetReturnPctRange.maximum < 0, true);
  assert.equal(result.selection2023.gateFailureCounts.minimumProfitFactor, 32);
  assert.equal(result.selection2023.gateFailureCounts.minimumMeanNetR, 32);
});

test("normalized v2 failure is expectancy rather than missing coverage", () => {
  assert.equal(result.selection2023.candidateTradeCountRange.minimum >= 24, true);
  assert.equal(result.bestNetCandidate.longTrades >= 8, true);
  assert.equal(result.bestNetCandidate.shortTrades >= 8, true);
  assert.equal(result.bestNetCandidate.bootstrapUpperMeanNetR < 0, true);
  assert.equal(
    result.bestGrossBeforeFeeCandidate.grossMeanRAfterSlippageBeforeFees <
      result.bestGrossBeforeFeeCandidate.meanFeeCostR,
    true,
  );
  assert.equal(result.bestGrossBeforeFeeCandidate.positiveGrossQuarterCount < 3, true);
});

test("normalized v2 cannot open 2024 or execution", () => {
  assert.equal(result.internalValidation2024Read, false);
  assert.equal(result.expansionRead, false);
  assert.equal(result.validation2025Read, false);
  assert.equal(result.external2026Read, false);
  assert.equal(result.freshSealedRead, false);
  assert.equal(result.internalValidationAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
