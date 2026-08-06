import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { expandCandidates } from "./lib/volume-impact-state-research.mjs";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-structure-development-v2.json"), "utf8"),
);
const parent = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-impact-state-development-result-v1.json"), "utf8"),
);

test("v2 is a bounded new hypothesis stage derived from the rejected v1 result", () => {
  assert.equal(parent.status, "REJECTED");
  assert.equal(protocol.parentResultId, parent.resultId);
  assert.equal(protocol.selectionPolicy.maximumStageCandidateCount, 24);
  assert.equal(protocol.selectionPolicy.dailyCompoundReturnIsSearchObjective, false);
  assert.equal(protocol.selectionPolicy.automaticPromotionAllowed, false);
  const counts = protocol.hypotheses.map((hypothesis) =>
    Object.values(hypothesis.grid).reduce((count, values) => count * values.length, 1),
  );
  assert.deepEqual(counts, [12, 12]);
  assert.equal(counts.reduce((sum, count) => sum + count, 0), 24);
  const candidates = expandCandidates(protocol);
  assert.equal(candidates.length, 24);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 24);
});

test("v2 never reads beyond pre-2024 development or opens the reserved seal", () => {
  assert.equal(protocol.sourceData.developmentReplayEndsAt, "2024-01-01T00:00:00Z");
  assert.equal(protocol.contaminationDisclosure.reservedSealedWindowMayBeReadDuringDevelopment, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
  for (const fold of protocol.nestedWalkForward.folds) {
    assert.equal(fold.trainEndAt, fold.validationStartAt);
    assert.ok(Date.parse(fold.validationEndAt) <= Date.parse(protocol.sourceData.developmentReplayEndsAt));
  }
});

test("retest and cluster mechanics are explicit rather than hidden grid dimensions", () => {
  const retest = protocol.hypotheses.find((item) => item.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION");
  const cluster = protocol.hypotheses.find((item) => item.family === "CLUSTERED_VOLUME_EXHAUSTION_REVERSAL");
  assert.deepEqual(retest.grid.m1RetestWindowBars, [15, 30]);
  assert.equal(retest.fixed.trailingActivationR, 1.5);
  assert.deepEqual(cluster.grid.minimumClusterRelativeVolume, [1.75, 2.25, 2.75]);
  assert.equal(cluster.fixed.reversalDirectionMustMatchClosedM15Regime, true);
});
