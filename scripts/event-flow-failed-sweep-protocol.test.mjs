import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  expandFailedSweepCandidates,
  validateFailedSweepProtocol,
} from "./event-flow-failed-sweep-protocol.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const readJson = async (name) => JSON.parse(await fs.readFile(path.join(repositoryRoot, "config", name), "utf8"));
const protocol = await readJson("bybit-event-flow-failed-sweep-reversal-v1.json");
const primaryProtocol = await readJson("bybit-event-flow-development-v1.json");
const extensionProtocol = await readJson("bybit-event-flow-fixed-extension-v1.json");

test("failed-sweep reversal freezes exactly 16 distinct candidates", () => {
  const validated = validateFailedSweepProtocol(protocol, primaryProtocol, extensionProtocol);
  const candidates = expandFailedSweepCandidates(validated);
  assert.equal(candidates.length, 16);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 16);
  assert.equal(candidates.every((candidate) => candidate.family === "FAILED_LIQUIDITY_SWEEP_REVERSAL"), true);
  assert.equal(validated.trialAccounting.cumulativeCandidates, 173);
});

test("failed-sweep reversal binds a return-free setup frequency audit", () => {
  const validated = validateFailedSweepProtocol(protocol, primaryProtocol, extensionProtocol);
  assert.equal(validated.setupFrequencyAudit.usedReturnsOrExitOutcomes, false);
  assert.equal(validated.setupFrequencyAudit.selection2023RawSetups, 484);
  assert.equal(validated.setupFrequencyAudit.validation2024RawSetups, 154);
});

test("failed-sweep reversal rejects hypothesis, schedule, and gate mutations", () => {
  const hypothesisMutation = structuredClone(protocol);
  hypothesisMutation.hypothesis.fixed.minimumDirectionalImpactAtr = 0.1;
  assert.throws(() => validateFailedSweepProtocol(
    hypothesisMutation,
    primaryProtocol,
    extensionProtocol,
  ), /hypothesis changed/);

  const scheduleMutation = structuredClone(protocol);
  scheduleMutation.chronologicalDevelopment.validationEras[0].blockIds.reverse();
  assert.throws(() => validateFailedSweepProtocol(
    scheduleMutation,
    primaryProtocol,
    extensionProtocol,
  ), /validation schedule/);

  const gateMutation = structuredClone(protocol);
  gateMutation.chronologicalDevelopment.validationGate.minimumTrades = 1;
  assert.throws(() => validateFailedSweepProtocol(
    gateMutation,
    primaryProtocol,
    extensionProtocol,
  ), /validation gate/);
});

test("failed-sweep development cannot unlock evidence or execution", () => {
  const validated = validateFailedSweepProtocol(protocol, primaryProtocol, extensionProtocol);
  assert.equal(validated.outcomePolicy.validationDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.externalDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.freshSealedDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(validated.outcomePolicy.liveExecutionAllowed, false);
});
