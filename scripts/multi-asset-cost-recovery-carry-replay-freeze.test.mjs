import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadCostRecoveryReplayFreeze,
} from "./multi-asset-cost-recovery-carry-replay.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const freezePath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-replay-freeze-v3.json",
);

test("v3 code and grid are frozen before development outcomes", async () => {
  const loaded = await loadCostRecoveryReplayFreeze(freezePath);
  assert.equal(loaded.manifest.status, "FROZEN_BEFORE_COST_RECOVERY_DEVELOPMENT_REPLAY");
  assert.equal(loaded.manifest.outcomeBoundary.developmentGridMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.outcomeBoundary.sealed2026Read, false);
  assert.equal(loaded.manifest.trialAccounting.frozenCandidatesToEvaluate, 54);
  assert.equal(loaded.manifest.selectionPolicy.maximumSelectedCandidateCount, 1);
  assert.equal(loaded.manifest.selectionPolicy.candidateMayBeRetunedAfterOutcome, false);
  assert.equal(loaded.manifest.automaticExecutionAllowed, false);
  assert.equal(loaded.manifest.liveExecutionAllowed, false);
});
