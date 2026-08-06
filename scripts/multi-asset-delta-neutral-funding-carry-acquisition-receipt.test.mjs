import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-development-v1.json",
));
const receipt = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-development-acquisition-receipt-v1.json",
)));

test("multi-asset receipt binds the frozen protocol and official snapshot", () => {
  assert.equal(receipt.status, "COMPLETE_MULTI_ASSET_DEVELOPMENT_EVIDENCE_SEALED");
  assert.equal(receipt.stage, "development");
  assert.equal(receipt.protocolSha256, sha256(protocolBytes));
  assert.match(receipt.acquisitionReportSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.stageSnapshotSha256, /^[a-f0-9]{64}$/);
  assert.match(receipt.normalizedEvidenceSha256, /^[a-f0-9]{64}$/);
});

test("all 15 official datasets have exact causal coverage", () => {
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

test("development evidence cannot unlock later evidence or execution", () => {
  assert.equal(Object.values(receipt.lockedEvidence).every((value) => value === false), true);
  assert.equal(receipt.developmentEvaluationAllowed, true);
  assert.equal(receipt.automaticExecutionAllowed, false);
  assert.equal(receipt.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
