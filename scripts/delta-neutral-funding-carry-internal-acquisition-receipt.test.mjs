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
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-internal-acquisition-receipt-v1.json",
)));

test("internal receipt binds the frozen 2024 protocol, candidate, and simulator", () => {
  assert.equal(receipt.status, "COMPLETE_DELTA_NEUTRAL_INTERNAL_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "internal");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.equal(receipt.frozenCandidateSha256, "eba52739bfa5d266de0e3b21f49a71637c9080e44cfd3821f601b3d2ef6be509");
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedEvidenceSha256, /^[a-f0-9]{64}$/);
});

test("2024 leap-year evidence has one exact two-leg timeline", () => {
  assert.equal(receipt.officialRestEvidence.spotLast.rowCount, 105408);
  assert.equal(receipt.officialRestEvidence.perpetualLast.rowCount, 105408);
  assert.equal(receipt.officialRestEvidence.perpetualMark.rowCount, 105408);
  assert.equal(receipt.officialRestEvidence.perpetualIndex.rowCount, 105408);
  assert.equal(receipt.officialRestEvidence.funding.rowCount, 1098);
  assert.equal(receipt.coverage.matchingM5Rows, receipt.coverage.expectedM5RowsPerSeries);
  assert.equal(receipt.coverage.missingDecisionInputCount, 0);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
});

test("internal evidence cannot unlock external or live execution before evaluation", () => {
  assert.equal(Object.values(receipt.lockedEvidence).every((value) => value === false), true);
  assert.equal(receipt.internalEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
