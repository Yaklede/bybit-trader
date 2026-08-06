import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(repositoryRoot, "config/bybit-funding-crowding-development-v1.json"));
const receiptBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-funding-crowding-development-acquisition-receipt-v1.json",
));
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-funding-crowding-development-result-v1.json",
)));

test("funding crowding closure remains bound to protocol and acquisition evidence", () => {
  assert.equal(result.protocolSha256, sha256(protocolBytes));
  assert.equal(result.acquisitionReceiptSha256, sha256(receiptBytes));
  assert.match(result.developmentReportSha256, /^[a-f0-9]{64}$/);
  assert.match(result.rankedCandidatesSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.trialAccounting.evaluatedCandidates, 32);
  assert.equal(result.trialAccounting.cumulativeObservedCandidates, 287);
});

test("positive development return cannot bypass the predeclared statistical gate", () => {
  assert.equal(result.bestCandidate.netReturnPct > 0, true);
  assert.equal(result.bestCandidate.bootstrapLowerMeanNetR < 0, true);
  assert.deepEqual(result.bestCandidate.failedGateChecks, ["minimumBootstrapLowerMeanNetR"]);
  assert.equal(result.bootstrapSensitivity.allLowerBoundsNegative, true);
  assert.equal(result.trialAccounting.passedCandidates, 0);
  assert.deepEqual(result.selectedCandidateIds, []);
});

test("rejected funding program leaves every later evidence and execution gate locked", () => {
  assert.equal(result.programStatus, "CLOSED_NO_APPROVABLE_FUNDING_CROWDING_STRATEGY_V1");
  assert.equal(Object.entries(result.evidenceBoundary)
    .filter(([name]) => name !== "development2020Through2022Read")
    .every(([, value]) => value === false), true);
  assert.equal(result.internalValidationAcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
