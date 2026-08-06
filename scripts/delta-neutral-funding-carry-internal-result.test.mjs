import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-internal-v1.json",
));
const receiptBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-internal-acquisition-receipt-v1.json",
));
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-internal-result-v1.json",
)));

test("internal result remains bound to the frozen candidate and 2024 evidence", () => {
  assert.equal(result.protocolSha256, sha256(protocolBytes));
  assert.equal(result.acquisitionReceiptSha256, sha256(receiptBytes));
  assert.match(result.internalReportSha256, /^[a-f0-9]{64}$/);
  assert.match(result.internalTradesSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.trialAccounting.newCandidateCount, 0);
  assert.equal(result.trialAccounting.cumulativeObservedCandidates, 311);
});

test("profitable internal replay cannot bypass the frozen concentration gate", () => {
  assert.equal(result.development2023.passed, true);
  assert.equal(result.internal2024.netReturnPct > 0, true);
  assert.equal(result.internal2024.bootstrapLowerMeanDailyReturnPct > 0, true);
  assert.equal(result.internal2024.costStressNetReturnPct > 0, true);
  assert.equal(result.internal2024.positivePositionProfitConcentration > 0.35, true);
  assert.deepEqual(result.internal2024.failedGateChecks, ["maximumPositivePositionProfitConcentration"]);
  assert.equal(result.internal2024.passed, false);
});

test("rejected carry candidate leaves external, sealed, and execution gates locked", () => {
  assert.equal(result.programStatus, "CLOSED_NO_APPROVABLE_DELTA_NEUTRAL_FUNDING_CARRY_V1");
  assert.equal(result.evidenceBoundary.external2025Read, false);
  assert.equal(result.evidenceBoundary.sealed2026Read, false);
  assert.equal(result.external2025AcquisitionAllowed, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
