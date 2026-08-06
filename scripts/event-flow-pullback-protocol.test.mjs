import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  expandPullbackCandidates,
  validatePullbackProtocol,
} from "./event-flow-pullback-protocol.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const readJson = async (name) => JSON.parse(await fs.readFile(path.join(repositoryRoot, "config", name), "utf8"));
const protocol = await readJson("bybit-event-flow-pullback-reacceleration-v1.json");
const primaryProtocol = await readJson("bybit-event-flow-development-v1.json");
const extensionProtocol = await readJson("bybit-event-flow-fixed-extension-v1.json");

test("pullback reacceleration freezes exactly 18 final development candidates", () => {
  const validated = validatePullbackProtocol(protocol, primaryProtocol, extensionProtocol);
  const candidates = expandPullbackCandidates(validated);
  assert.equal(candidates.length, 18);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 18);
  assert.equal(candidates.every((candidate) =>
    candidate.family === "SHOCK_PULLBACK_REACCELERATION_CONTINUATION"), true);
  assert.equal(validated.trialAccounting.cumulativeCandidates, 191);
  assert.equal(validated.trialAccounting.remainingCandidatesAfterStage, 1);
});

test("pullback reacceleration fixes a larger right-tail exit before replay", () => {
  const validated = validatePullbackProtocol(protocol, primaryProtocol, extensionProtocol);
  assert.equal(validated.hypothesis.fixed.targetR, 3.5);
  assert.equal(validated.hypothesis.fixed.maximumHoldingMinutes, 360);
  assert.equal(validated.setupFrequencyAudit.usedReturnsOrExitOutcomes, false);
});

test("pullback reacceleration rejects hypothesis, exit, and gate mutations", () => {
  const hypothesisMutation = structuredClone(protocol);
  hypothesisMutation.hypothesis.grid.minimumPullbackFraction[0] = 0.1;
  assert.throws(() => validatePullbackProtocol(
    hypothesisMutation,
    primaryProtocol,
    extensionProtocol,
  ), /hypothesis changed/);

  const exitMutation = structuredClone(protocol);
  exitMutation.hypothesis.fixed.targetR = 2;
  exitMutation.hypothesisSha256 = "invalid";
  assert.throws(() => validatePullbackProtocol(
    exitMutation,
    primaryProtocol,
    extensionProtocol,
  ), /hypothesis changed/);

  const gateMutation = structuredClone(protocol);
  gateMutation.chronologicalDevelopment.validationGate.minimumTrades = 1;
  assert.throws(() => validatePullbackProtocol(
    gateMutation,
    primaryProtocol,
    extensionProtocol,
  ), /validation gate/);
});

test("pullback development cannot open evidence or execution directly", () => {
  const validated = validatePullbackProtocol(protocol, primaryProtocol, extensionProtocol);
  assert.equal(validated.outcomePolicy.validationDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.externalDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.freshSealedDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(validated.outcomePolicy.liveExecutionAllowed, false);
});
