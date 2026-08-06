import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadSealedReplayFreeze,
} from "./multi-asset-cost-recovery-carry-sealed-replay.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const freezePath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-sealed-replay-freeze-v3.json",
);

test("sealed replay code and evidence are frozen before outcome", async () => {
  const loaded = await loadSealedReplayFreeze(freezePath);
  assert.equal(loaded.manifest.status, "FROZEN_BEFORE_SEALED_2026_H1_OUTCOME_REPLAY");
  assert.equal(loaded.manifest.outcomeBoundary.sealedPortfolioMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.outcomeBoundary.freshForwardSealRead, false);
  assert.equal(loaded.manifest.candidatePolicy.evaluatedCandidateCount, 1);
  assert.equal(loaded.manifest.candidatePolicy.candidateMayBeRetunedAfterOutcome, false);
  assert.equal(loaded.manifest.gatePolicy.allSealedGatesMustPass, true);
  assert.equal(loaded.manifest.gatePolicy.gateMayBeChangedAfterOutcome, false);
  assert.equal(loaded.manifest.automaticExecutionAllowed, false);
  assert.equal(loaded.manifest.liveExecutionAllowed, false);
});
