import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-absorption-development-v3-result.json"), "utf8"),
);

test("v3 family gate remains rejected even with four positive folds", () => {
  assert.equal(result.status, "REJECTED_FAMILY_GATE");
  assert.equal(result.nestedWalkForward.positiveValidationFolds, 4);
  assert.ok(result.nestedWalkForward.pooledTradeCount < 60);
  assert.ok(result.nestedWalkForward.pooledBootstrapLowerMeanNetR < 0);
  assert.equal(result.automaticExecutionAllowed, false);
});

test("the zero-trade replay is explicitly invalidated", () => {
  assert.equal(result.invalidatedRun.status, "INVALIDATED_SIMULATOR_CONFIGURATION");
  assert.equal(result.invalidatedRun.observedTrades, 0);
});

test("the retained candidate is diagnostic-only despite positive development metrics", () => {
  const candidate = result.diagnosticCandidate;
  assert.equal(candidate.id, "acar_lrv2p1_smax2p5_cf4");
  assert.ok(candidate.tradeCount >= 60);
  assert.ok(candidate.bootstrapLowerMeanNetR > 0);
  assert.equal(candidate.selectionStatus, "FROZEN_DIAGNOSTIC_ONLY_NOT_PROMOTABLE");
  assert.equal(result.reservedSealedWindowOpened, false);
});
