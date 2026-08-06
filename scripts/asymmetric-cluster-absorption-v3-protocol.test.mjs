import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { expandCandidates } from "./lib/volume-impact-state-research.mjs";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-absorption-development-v3.json"), "utf8"),
);
const parent = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-structure-development-v2-result.json"), "utf8"),
);

test("v3 freezes one asymmetric family with exactly 12 candidates", () => {
  assert.equal(protocol.parentResultId, parent.resultId);
  assert.equal(protocol.hypotheses.length, 1);
  assert.equal(protocol.hypotheses[0].family, "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL");
  const count = Object.values(protocol.hypotheses[0].grid).reduce((total, values) => total * values.length, 1);
  assert.equal(count, 12);
  assert.equal(protocol.selectionPolicy.maximumStageCandidateCount, 12);
  const candidates = expandCandidates(protocol);
  assert.equal(candidates.length, 12);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 12);
});

test("active-month methodology does not relax the pooled family gate", () => {
  assert.equal(protocol.trainingEligibility.minimumActiveMonthPositiveRatio, 0.5);
  assert.equal(protocol.familyDevelopmentGate.minimumPooledValidationTrades, 60);
  assert.equal(protocol.familyDevelopmentGate.minimumPositiveValidationFolds, 4);
  assert.equal(protocol.familyDevelopmentGate.minimumBootstrapLowerMeanNetR, 0);
  assert.equal(protocol.selectionPolicy.dailyCompoundReturnIsSearchObjective, false);
});

test("v3 keeps the fresh seal closed and live execution disabled", () => {
  assert.equal(protocol.sourceData.developmentReplayEndsAt, "2024-01-01T00:00:00Z");
  assert.equal(protocol.contaminationDisclosure.reservedSealedWindowMayBeReadDuringDevelopment, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
