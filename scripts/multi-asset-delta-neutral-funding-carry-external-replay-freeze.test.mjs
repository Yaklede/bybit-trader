import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadExternalReplayFreeze,
} from "./multi-asset-delta-neutral-funding-carry-external-replay.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const freezePath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-external-replay-freeze-v2.json",
);

test("external replay implementation and evidence are frozen before 2025 outcome", async () => {
  const loaded = await loadExternalReplayFreeze(freezePath);
  assert.equal(loaded.manifest.status, "FROZEN_BEFORE_MULTI_ASSET_EXTERNAL_OUTCOME_REPLAY");
  assert.equal(loaded.manifest.outcomeBoundary.externalPortfolioMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.outcomeBoundary.sealed2026Read, false);
  assert.equal(loaded.manifest.candidatePolicy.candidateId, "multi_asset_delta_neutral_carry_04");
  assert.equal(loaded.manifest.candidatePolicy.evaluatedCandidateCount, 1);
  assert.equal(loaded.manifest.gatePolicy.allExternalGatesMustPassBeforeSealedAcquisition, true);
  assert.equal(loaded.manifest.automaticExecutionAllowed, false);
  assert.equal(loaded.manifest.liveExecutionAllowed, false);
});
