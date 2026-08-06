import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-external-result-v2.json",
)));

test("2025 external result rejects v2 and binds its frozen artifacts", async () => {
  const protocolBytes = await readFile(resolve(repositoryRoot, result.protocol.path));
  const freezeBytes = await readFile(resolve(repositoryRoot, result.replayFreeze.path));
  assert.equal(result.programStatus, "REJECTED_MULTI_ASSET_EXTERNAL_VALIDATION");
  assert.equal(result.protocol.sha256, sha256(protocolBytes));
  assert.equal(result.replayFreeze.sha256, sha256(freezeBytes));
  assert.equal(result.gate.passed, false);
  assert.equal(result.gate.failedChecks.length, 12);
});

test("negative net economics keep 2026 and execution locked", () => {
  assert.equal(result.metrics.netReturnPct < 0, true);
  assert.equal(result.metrics.bootstrapLowerMeanDailyReturnPct < 0, true);
  assert.equal(result.metrics.costStressNetReturnPct < 0, true);
  assert.equal(result.metrics.secondLegDelayStressNetReturnPct < 0, true);
  assert.equal(result.diagnosis.fundingDidNotRecoverRoundTripCosts, true);
  assert.equal(result.diagnosis.gateRelaxationWouldNotMakeCostsPositive, true);
  assert.equal(result.decision.v2Sealed2026AcquisitionAllowed, false);
  assert.equal(result.evidenceBoundary.sealed2026Read, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
