import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadReplayFreeze,
} from "./multi-asset-delta-neutral-funding-carry-development-replay.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const freezePath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-development-replay-freeze-v1.json",
);

test("development replay code and evidence are frozen before official outcomes", async () => {
  const loaded = await loadReplayFreeze(freezePath);
  assert.equal(
    loaded.manifest.status,
    "FROZEN_BEFORE_MULTI_ASSET_DEVELOPMENT_OUTCOME_REPLAY",
  );
  assert.equal(loaded.manifest.outcomeBoundary.developmentCandidateMetricsReadBeforeFreeze, false);
  assert.equal(loaded.manifest.trialAccounting.frozenCandidatesToEvaluate, 24);
  assert.equal(loaded.manifest.trialAccounting.cumulativeObservedCandidatesAfterReplay, 335);
  assert.equal(loaded.manifest.selectionPolicy.maximumSelectedCandidateCount, 1);
  assert.equal(loaded.manifest.automaticExecutionAllowed, false);
  assert.equal(loaded.manifest.liveExecutionAllowed, false);
});
