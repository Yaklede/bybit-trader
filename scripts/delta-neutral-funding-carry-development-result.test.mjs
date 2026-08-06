import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-development-v1.json",
));
const receiptBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-development-acquisition-receipt-v1.json",
));
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-development-result-v1.json",
)));

test("development result remains bound to the frozen protocol and acquisition evidence", () => {
  assert.equal(result.protocolSha256, sha256(protocolBytes));
  assert.equal(result.acquisitionReceiptSha256, sha256(receiptBytes));
  assert.match(result.developmentReportSha256, /^[a-f0-9]{64}$/);
  assert.match(result.rankedCandidatesSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.trialAccounting.evaluatedCandidates, 24);
  assert.equal(result.trialAccounting.cumulativeObservedCandidates, 311);
});

test("the simulator correction changes synchronization only and preserves the trial", () => {
  assert.equal(result.simulatorCorrection.invalidInitialReplayDiscarded, true);
  assert.equal(result.simulatorCorrection.candidateDefinitionChanged, false);
  assert.equal(result.simulatorCorrection.costContractChanged, false);
  assert.equal(result.simulatorCorrection.gateChanged, false);
  assert.equal(result.trialAccounting.passedCandidates, 1);
});

test("exactly one passing candidate is frozen without claiming live approval", () => {
  const selected = result.selectedCandidate;
  assert.equal(selected.candidateSha256, sha256(JSON.stringify(selected.candidate)));
  assert.equal(selected.allDevelopmentGateChecksPassed, true);
  assert.equal(selected.metrics.netReturnPct > 0, true);
  assert.equal(selected.metrics.costStressNetReturnPct > 0, true);
  assert.equal(selected.metrics.secondLegDelayStressNetReturnPct > 0, true);
  assert.equal(result.evidenceBoundary.internalValidation2024Read, false);
  assert.equal(result.internalValidation.acquisitionAllowed, true);
  assert.equal(result.internalValidation.candidateMayBeRetuned, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
