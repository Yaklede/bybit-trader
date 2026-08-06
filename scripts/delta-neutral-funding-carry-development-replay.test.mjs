import assert from "node:assert/strict";
import test from "node:test";

import {
  parseArgs,
  rankCandidateEvaluations,
  validateAcquisitionReceipt,
} from "./delta-neutral-funding-carry-development-replay.mjs";

test("development replay arguments cannot select a candidate or open later evidence", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-delta-neutral-funding-carry-development-v1.json",
    "--receipt=config/receipt.json",
    "--report=build/result.json",
  ]);
  assert.match(parsed.protocol, /bybit-delta-neutral-funding-carry-development-v1\.json$/);
  assert.throws(() => parseArgs(["--candidate=best"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
});

test("receipt validator requires exact development coverage and locked later evidence", () => {
  const receipt = {
    status: "COMPLETE_DELTA_NEUTRAL_DEVELOPMENT_EVIDENCE_SEALED",
    stage: "development",
    protocolSha256: "a".repeat(64),
    stageSnapshotSha256: "b".repeat(64),
    normalizedEvidenceSha256: "c".repeat(64),
    coverage: { matchingM5Rows: 105120, fundingRows: 1095, missingDecisionInputCount: 0 },
    integrity: { timeline: true, raw: true },
    lockedEvidence: { internal: false, external: false, sealed: false },
    developmentEvaluationAllowed: true,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  assert.equal(validateAcquisitionReceipt(receipt, "a".repeat(64)), receipt);
  assert.throws(() => validateAcquisitionReceipt({
    ...receipt,
    lockedEvidence: { ...receipt.lockedEvidence, internal: true },
  }, "a".repeat(64)), /not eligible/);
  assert.throws(() => validateAcquisitionReceipt({
    ...receipt,
    coverage: { ...receipt.coverage, matchingM5Rows: 105119 },
  }, "a".repeat(64)), /not eligible/);
});

test("ranking prefers passed gate, fewer failures, and predeclared gate margin", () => {
  const evaluations = [
    evaluation("failed-one", false, ["a"], 0.2, 10, 0.1),
    evaluation("passed-low", true, [], 0.1, 2, 0.01),
    evaluation("failed-two", false, ["a", "b"], 0.9, 20, 0.2),
    evaluation("passed-high", true, [], 0.3, 1, 0.001),
  ];
  const ranked = rankCandidateEvaluations(evaluations);
  assert.deepEqual(ranked.map((row) => row.candidate.id), [
    "passed-high",
    "passed-low",
    "failed-one",
    "failed-two",
  ]);
});

function evaluation(id, passed, failedChecks, minimumGateMargin, costStressNetReturnPct, bootstrap) {
  return {
    candidate: { id },
    gate: { passed, failedChecks, minimumGateMargin },
    metrics: {
      costStressNetReturnPct,
      bootstrapLowerMeanDailyReturnPct: bootstrap,
    },
  };
}
