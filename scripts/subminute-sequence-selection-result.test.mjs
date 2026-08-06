import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-subminute-sequence-selection-result-v1.json"),
  "utf8",
));

test("subminute v1 closes without an eligible selection candidate", () => {
  assert.equal(result.status, "REJECTED_NO_SELECTION_CANDIDATE");
  assert.equal(result.programStatus, "CLOSED_NO_APPROVABLE_SUBMINUTE_SEQUENCE_STRATEGY_V1");
  assert.equal(result.trialAccounting.stageCandidates, 32);
  assert.equal(result.selection2023.eligibleCandidateCount, 0);
  assert.equal(result.selection2023.gateFailureCounts.minimumTrades, 32);
  assert.equal(result.selection2023.gateFailureCounts.minimumLongTrades, 32);
  assert.equal(result.selection2023.gateFailureCounts.minimumPositiveQuarterCount, 32);
});

test("the apparent absorption profit is isolated to the wide-spread Q1 scale", () => {
  const absorption = result.bestByFamily.find((row) => row.family === "SUBMINUTE_ABSORPTION_REVERSAL");
  const continuation = result.bestByFamily.find((row) => row.family === "SUBMINUTE_DEPLETION_CONTINUATION");
  assert.equal(absorption.positiveQuarterCount, 1);
  assert.equal(absorption.longTrades, 1);
  assert.equal(absorption.winnerProfitConcentration > 0.35, true);
  assert.equal(continuation.netReturnPct < 0, true);
  assert.equal(result.featureScaleDiagnostic[0].absoluteThresholdPassBucketCount > 26_000, true);
  assert.equal(result.featureScaleDiagnostic.slice(1).every((row) => row.absoluteThresholdPassBucketCount < 10), true);
});

test("rejected v1 cannot read 2024 or unlock automatic execution", () => {
  assert.equal(result.internalValidation2024Read, false);
  assert.equal(result.expansionRead, false);
  assert.equal(result.validation2025Read, false);
  assert.equal(result.external2026Read, false);
  assert.equal(result.freshSealedRead, false);
  assert.equal(result.internalValidationAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
  assert.equal(result.nextStageBoundary.notAllowed.includes("PROMOTE_THE_Q1_ONLY_ABSORPTION_ROW"), true);
});
