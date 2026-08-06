import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-development-result-v1.json",
)));

test("development result freezes one passing candidate without execution permission", () => {
  assert.equal(result.programStatus, "DEVELOPMENT_CANDIDATE_FROZEN_FOR_INTERNAL_VALIDATION");
  assert.equal(result.trialAccounting.evaluatedCandidates, 24);
  assert.equal(result.trialAccounting.passedCandidates, 2);
  assert.equal(result.trialAccounting.selectedCandidates, 1);
  assert.equal(result.development2023.passed, true);
  assert.deepEqual(result.development2023.failedGateChecks, []);
  assert.equal(result.internalValidation.acquisitionAllowed, true);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

test("selected candidate hash and diversification metrics are immutable", () => {
  assert.equal(
    sha256(JSON.stringify(result.selectedCandidate.candidate)),
    result.selectedCandidate.candidateSha256,
  );
  assert.equal(result.selectedCandidate.candidate.id, "multi_asset_delta_neutral_carry_04");
  assert.deepEqual(result.selectedCandidate.candidate.symbols, ["BTCUSDT", "ETHUSDT", "SOLUSDT"]);
  assert.equal(result.development2023.tradedAssetCount, 3);
  assert.equal(result.development2023.positiveAssetCount, 3);
  assert.equal(result.development2023.positivePositionProfitConcentration <= 0.25, true);
  assert.equal(result.development2023.positiveAssetProfitConcentration <= 0.6, true);
});

test("development artifacts and unread evidence remain explicitly bound", () => {
  for (const value of Object.values(result.developmentArtifacts)) {
    assert.match(value, /^[a-f0-9]{64}$/);
  }
  assert.equal(result.evidenceBoundary.internalValidation2024Read, false);
  assert.equal(result.evidenceBoundary.external2025Read, false);
  assert.equal(result.evidenceBoundary.sealed2026Read, false);
  assert.equal(result.internalValidation.candidateMayBeRetuned, false);
  assert.equal(result.internalValidation.gateMayBeChanged, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
