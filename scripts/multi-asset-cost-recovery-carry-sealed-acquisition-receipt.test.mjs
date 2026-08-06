import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-sealed-2026-h1-v3.json",
));
const protocol = JSON.parse(protocolBytes);
const simulatorBytes = await readFile(resolve(
  repositoryRoot,
  "scripts/lib/multi-asset-delta-neutral-funding-carry-research.mjs",
));
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-sealed-acquisition-receipt-v3.json",
)));

test("sealed receipt binds protocol, candidate, simulator, and snapshot", () => {
  assert.equal(receipt.status, "COMPLETE_MULTI_ASSET_SEALED_2026_H1_EVIDENCE");
  assert.equal(receipt.stage, "sealed-2026-h1");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.equal(receipt.selectedCandidateSha256, protocol.selectedCandidateSha256);
  assert.equal(receipt.simulatorSha256, sha256(simulatorBytes));
  assert.match(receipt.acquisitionReportSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedEvidenceSha256, /^[a-f0-9]{64}$/);
});

test("all 15 official 2026 H1 datasets have exact causal coverage", () => {
  assert.deepEqual(Object.keys(receipt.officialRestEvidence), ["BTCUSDT", "ETHUSDT", "SOLUSDT"]);
  for (const evidence of Object.values(receipt.officialRestEvidence)) {
    assert.equal(Object.keys(evidence).length, 5);
    for (const [name, dataset] of Object.entries(evidence)) {
      assert.equal(dataset.rowCount, name === "funding" ? 543 : 52128);
      assert.match(dataset.responseChainSha256, /^[a-f0-9]{64}$/);
      assert.match(dataset.contentSha256, /^[a-f0-9]{64}$/);
    }
  }
  assert.equal(receipt.coverage.totalMatchingM5Rows, 156384);
  assert.equal(receipt.coverage.totalFundingRows, 1629);
  assert.equal(receipt.coverage.missingDecisionInputCount, 0);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
});

test("sealed outcome remains unread while execution stays blocked", () => {
  assert.equal(receipt.outcomeBoundary.sealed2026H1PortfolioMetricsReadBeforeReceipt, false);
  assert.equal(receipt.outcomeBoundary.freshForwardSealRead, false);
  assert.equal(receipt.sealedEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
