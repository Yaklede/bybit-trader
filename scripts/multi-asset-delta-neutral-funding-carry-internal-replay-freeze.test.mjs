import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadInternalReplayFreeze,
} from "./multi-asset-delta-neutral-funding-carry-internal-replay.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const freezePath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-internal-replay-freeze-v1.json",
);

test("internal replay implementation and evidence are frozen before portfolio outcome", async () => {
  const loaded = await loadInternalReplayFreeze(freezePath);
  assert.equal(loaded.manifest.status, "FROZEN_BEFORE_MULTI_ASSET_INTERNAL_OUTCOME_REPLAY");
  assert.equal(loaded.manifest.outcomeBoundary.BTCUSDT2024SingleAssetMetricsPreviouslyRead, true);
  assert.equal(loaded.manifest.outcomeBoundary.ETHUSDT2024SingleAssetMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.outcomeBoundary.SOLUSDT2024SingleAssetMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.outcomeBoundary.internalPortfolioMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.candidatePolicy.candidateId, "multi_asset_delta_neutral_carry_04");
  assert.equal(loaded.manifest.candidatePolicy.evaluatedCandidateCount, 1);
  assert.equal(loaded.manifest.gatePolicy.allInternalGatesMustPassBeforeExternalAcquisition, true);
  assert.equal(loaded.manifest.automaticExecutionAllowed, false);
  assert.equal(loaded.manifest.liveExecutionAllowed, false);
});
