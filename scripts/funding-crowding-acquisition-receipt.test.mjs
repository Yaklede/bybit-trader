import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-funding-crowding-development-v1.json",
));
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-funding-crowding-development-acquisition-receipt-v1.json",
)));

test("funding crowding receipt binds the frozen development protocol and snapshot", () => {
  assert.equal(receipt.status, "COMPLETE_DEVELOPMENT_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "development");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.match(receipt.acquisitionReportSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedFeatureSha256, /^[a-f0-9]{64}$/);
  assert.equal(receipt.officialRestEvidence.fundingRowCount, 3034);
  assert.equal(receipt.officialRestEvidence.premiumRowCount, 97096);
});

test("official premium gaps remain explicit without invalidating usable settlement inputs", () => {
  assert.equal(receipt.coverage.premiumSourceGapCount, 1);
  assert.equal(receipt.coverage.premiumMissingIntervalCount, 13);
  assert.equal(receipt.coverage.missingDecisionInputCount, 0);
  assert.equal(receipt.integrity.premiumSourceGapRetainedWithoutInterpolation, true);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
});

test("development evidence cannot unlock validation or exchange execution", () => {
  assert.equal(Object.values(receipt.lockedEvidence).every((value) => value === false), true);
  assert.equal(receipt.developmentEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
