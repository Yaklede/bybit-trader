import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-external-v2.json",
));
const protocol = JSON.parse(protocolBytes);
const simulatorBytes = await readFile(resolve(
  repositoryRoot,
  "scripts/lib/multi-asset-delta-neutral-funding-carry-research.mjs",
));
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-external-acquisition-receipt-v2.json",
)));

test("external receipt binds frozen protocol, candidate, simulator, and snapshot", () => {
  assert.equal(receipt.status, "COMPLETE_MULTI_ASSET_EXTERNAL_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "external");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.equal(receipt.selectedCandidateSha256, protocol.selectedCandidateSha256);
  assert.equal(receipt.simulatorSha256, sha256(simulatorBytes));
  assert.match(receipt.acquisitionReportSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedEvidenceSha256, /^[a-f0-9]{64}$/);
});

test("all 15 official 2025 datasets have exact causal coverage", () => {
  assert.deepEqual(Object.keys(receipt.officialRestEvidence), ["BTCUSDT", "ETHUSDT", "SOLUSDT"]);
  for (const evidence of Object.values(receipt.officialRestEvidence)) {
    assert.equal(Object.keys(evidence).length, 5);
    for (const [name, dataset] of Object.entries(evidence)) {
      assert.equal(dataset.rowCount, name === "funding" ? 1095 : 105120);
      assert.match(dataset.responseChainSha256, /^[a-f0-9]{64}$/);
      assert.match(dataset.contentSha256, /^[a-f0-9]{64}$/);
    }
  }
  assert.equal(receipt.coverage.totalMatchingM5Rows, 315360);
  assert.equal(receipt.coverage.totalFundingRows, 3285);
  assert.equal(receipt.coverage.missingDecisionInputCount, 0);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
});

test("external outcome and later evidence remain unread while execution stays blocked", () => {
  assert.equal(receipt.outcomeBoundary.external2025PortfolioMetricsReadBeforeReceipt, false);
  assert.equal(receipt.outcomeBoundary.sealed2026Read, false);
  assert.equal(receipt.outcomeBoundary.freshForwardSealRead, false);
  assert.equal(receipt.externalEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
