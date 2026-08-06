import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-internal-v1.json",
));
const protocol = JSON.parse(protocolBytes);
const simulatorBytes = await readFile(resolve(
  repositoryRoot,
  "scripts/lib/multi-asset-delta-neutral-funding-carry-research.mjs",
));
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-internal-acquisition-receipt-v1.json",
)));

test("internal receipt binds frozen protocol, candidate, simulator, and evidence", () => {
  assert.equal(receipt.status, "COMPLETE_MULTI_ASSET_INTERNAL_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "internal");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.equal(receipt.selectedCandidateSha256, protocol.selectedCandidateSha256);
  assert.equal(receipt.simulatorSha256, sha256(simulatorBytes));
  assert.match(receipt.acquisitionReportSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedEvidenceSha256, /^[a-f0-9]{64}$/);
});

test("all 15 official 2024 datasets have exact causal coverage", () => {
  assert.deepEqual(Object.keys(receipt.officialRestEvidence), ["BTCUSDT", "ETHUSDT", "SOLUSDT"]);
  for (const evidence of Object.values(receipt.officialRestEvidence)) {
    assert.equal(Object.keys(evidence).length, 5);
    for (const [name, dataset] of Object.entries(evidence)) {
      assert.equal(dataset.rowCount, name === "funding" ? 1098 : 105408);
      assert.match(dataset.responseChainSha256, /^[a-f0-9]{64}$/);
      assert.match(dataset.contentSha256, /^[a-f0-9]{64}$/);
    }
  }
  assert.equal(receipt.coverage.totalMatchingM5Rows, 316224);
  assert.equal(receipt.coverage.totalFundingRows, 3294);
  assert.equal(receipt.coverage.missingDecisionInputCount, 0);
  assert.equal(Object.values(receipt.integrity).every(Boolean), true);
});

test("receipt preserves the disclosed internal boundary and blocks execution", () => {
  assert.equal(receipt.priorObservationDisclosure.BTCUSDT2024SingleAssetMetricsRead, true);
  assert.equal(receipt.priorObservationDisclosure.ETHUSDT2024SingleAssetMetricsRead, false);
  assert.equal(receipt.priorObservationDisclosure.SOLUSDT2024SingleAssetMetricsRead, false);
  assert.equal(receipt.priorObservationDisclosure.multiAssetPortfolio2024MetricsReadBeforeFreeze, false);
  assert.equal(Object.values(receipt.lockedEvidence).every((value) => value === false), true);
  assert.equal(receipt.internalEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
