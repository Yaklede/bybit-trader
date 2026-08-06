import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-internal-result-v1.json",
)));

test("2024 internal result rejects v1 without rewriting its gate", async () => {
  const protocolBytes = await readFile(resolve(repositoryRoot, result.protocol.path));
  const freezeBytes = await readFile(resolve(repositoryRoot, result.replayFreeze.path));
  assert.equal(result.programStatus, "REJECTED_MULTI_ASSET_INTERNAL_VALIDATION");
  assert.equal(result.protocol.sha256, sha256(protocolBytes));
  assert.equal(result.replayFreeze.sha256, sha256(freezeBytes));
  assert.equal(result.gate.passed, false);
  assert.deepEqual(result.gate.failedChecks, ["minimumClosedPositions"]);
  assert.equal(result.gate.requiredClosedPositions, 20);
  assert.equal(result.gate.observedClosedPositions, 17);
  assert.equal(result.gate.allOtherChecksPassed, true);
});

test("positive economics do not unlock external evidence or execution", () => {
  assert.equal(result.metrics.netReturnPct > 0, true);
  assert.equal(result.metrics.costStressNetReturnPct > 0, true);
  assert.equal(result.metrics.secondLegDelayStressNetReturnPct > 0, true);
  assert.equal(result.decision.v1CandidateRejected, true);
  assert.equal(result.decision.v1GateMayBeRelaxedAfterOutcome, false);
  assert.equal(result.decision.v1CandidateMayBeRetunedAfterOutcome, false);
  assert.equal(result.decision.v1External2025AcquisitionAllowed, false);
  assert.equal(result.evidenceBoundary.external2025Read, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
