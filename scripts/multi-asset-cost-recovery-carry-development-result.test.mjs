import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-development-result-v3.json",
)));

test("cost-recovery candidate is immutable after the development replay", async () => {
  const protocolBytes = await readFile(resolve(repositoryRoot, result.protocol.path));
  const freezeBytes = await readFile(resolve(repositoryRoot, result.replayFreeze.path));

  assert.equal(result.programStatus, "PASSED_DEVELOPMENT_ONLY_FROZEN_FOR_2026_SEAL");
  assert.equal(result.protocol.sha256, sha256(protocolBytes));
  assert.equal(result.replayFreeze.sha256, sha256(freezeBytes));
  assert.equal(result.selectedCandidate.id, "multi_asset_cost_recovery_carry_018");
  assert.equal(result.selectedCandidate.sha256, "46bac80f02e00cdbcb4605e785a0f2dee6865d983c9082e326615381dd91dfcf");
  assert.equal(result.trialAccounting.evaluatedCandidates, 54);
  assert.equal(result.trialAccounting.passedCandidates, 4);
  assert.equal(result.trialAccounting.selectedCandidateCount, 1);
});

test("all development years pass economics and stress gates", () => {
  assert.equal(result.developmentGate.passed, true);
  assert.deepEqual(result.developmentGate.failedChecks, []);
  assert.equal(result.annualMetrics.length, 3);

  for (const annual of result.annualMetrics) {
    assert.equal(annual.netReturnPct > 0, true);
    assert.equal(annual.bootstrapLowerMeanDailyReturnPct > 0, true);
    assert.equal(annual.costStressNetReturnPct > 0, true);
    assert.equal(annual.secondLegDelayStressNetReturnPct > 0, true);
    assert.equal(annual.liquidationCount, 0);
  }
});

test("a development pass unlocks only the sealed test, never execution", () => {
  assert.equal(result.decision.candidateFrozenWithoutRetuning, true);
  assert.equal(result.decision.sealed2026AcquisitionAllowed, true);
  assert.equal(result.decision.sealed2026MayBeReadBeforeProtocolFreeze, false);
  assert.equal(result.decision.developmentPassGrantsExecution, false);
  assert.equal(result.evidenceBoundary.sealed2026Read, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
