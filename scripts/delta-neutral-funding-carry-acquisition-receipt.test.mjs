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
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-delta-neutral-funding-carry-development-acquisition-receipt-v1.json",
)));

test("delta-neutral receipt binds the frozen protocol and official development snapshot", () => {
  assert.equal(receipt.status, "COMPLETE_DELTA_NEUTRAL_DEVELOPMENT_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "development");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.match(receipt.acquisitionReportSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedEvidenceSha256, /^[a-f0-9]{64}$/);
});

test("all two-leg price series and funding have exact causal coverage", () => {
  const evidence = receipt.officialRestEvidence;
  assert.equal(evidence.spotLast.rowCount, 105120);
  assert.equal(evidence.perpetualLast.rowCount, 105120);
  assert.equal(evidence.perpetualMark.rowCount, 105120);
  assert.equal(evidence.perpetualIndex.rowCount, 105120);
  assert.equal(evidence.funding.rowCount, 1095);
  assert.equal(receipt.coverage.matchingM5Rows, receipt.coverage.expectedM5RowsPerSeries);
  assert.equal(receipt.coverage.missingDecisionInputCount, 0);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
});

test("development evidence cannot unlock later evidence or live execution", () => {
  assert.equal(Object.values(receipt.lockedEvidence).every((value) => value === false), true);
  assert.equal(receipt.developmentEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
