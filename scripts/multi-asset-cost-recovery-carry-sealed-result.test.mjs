import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-sealed-result-v3.json",
)));

test("candidate 018 is rejected by the immutable 2026 H1 result", async () => {
  const protocolBytes = await readFile(resolve(repositoryRoot, result.protocol.path));
  const freezeBytes = await readFile(resolve(repositoryRoot, result.replayFreeze.path));
  const receiptBytes = await readFile(resolve(repositoryRoot, result.acquisitionReceipt.path));
  assert.equal(result.programStatus, "REJECTED_SEALED_2026_H1_VALIDATION");
  assert.equal(result.protocol.sha256, sha256(protocolBytes));
  assert.equal(result.replayFreeze.sha256, sha256(freezeBytes));
  assert.equal(result.acquisitionReceipt.sha256, sha256(receiptBytes));
  assert.equal(result.candidate.id, "multi_asset_cost_recovery_carry_018");
  assert.equal(result.gate.passed, false);
  assert.equal(result.gate.failedChecks.length, 10);
});

test("negative net economics cannot be overridden by low drawdown", () => {
  assert.equal(result.metrics.netReturnPct < 0, true);
  assert.equal(result.metrics.bootstrapLowerMeanDailyReturnPct < 0, true);
  assert.equal(result.metrics.costStressNetReturnPct < 0, true);
  assert.equal(result.metrics.secondLegDelayStressNetReturnPct < 0, true);
  assert.equal(result.metrics.maximumDrawdownPct < 1, true);
  assert.equal(result.failureAttribution.fundingDidNotCoverBaseExecutionCosts, true);
  assert.equal(result.failureAttribution.positiveFundingStreakDidNotPredictLongHorizonCarry, true);
});

test("rejected sealed result keeps all execution paths closed", () => {
  assert.equal(result.decision.candidateRejected, true);
  assert.equal(result.decision.candidateMayBeRetunedOn2026H1, false);
  assert.equal(result.decision.sealedGateMayBeRelaxed, false);
  assert.equal(result.decision.forwardShadowAndPaperAllowed, false);
  assert.equal(result.evidenceBoundary.future2026AfterH1Read, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
