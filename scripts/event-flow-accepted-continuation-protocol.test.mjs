import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  expandAcceptedContinuationCandidates,
  validateAcceptedContinuationProtocol,
} from "./event-flow-accepted-continuation-protocol.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const readJson = async (name) => JSON.parse(await fs.readFile(path.join(repositoryRoot, "config", name), "utf8"));
const protocol = await readJson("bybit-event-flow-accepted-continuation-v1.json");
const primaryProtocol = await readJson("bybit-event-flow-development-v1.json");
const extensionProtocol = await readJson("bybit-event-flow-fixed-extension-v1.json");

test("accepted continuation freezes 16 candidates before replay", () => {
  const validated = validateAcceptedContinuationProtocol(
    structuredClone(protocol),
    primaryProtocol,
    extensionProtocol,
  );
  const candidates = expandAcceptedContinuationCandidates(validated);
  assert.equal(candidates.length, 16);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 16);
  assert.equal(candidates.every((candidate) => candidate.family === "ACCEPTED_DEPLETION_CONTINUATION"), true);
  assert.equal(validated.trialAccounting.cumulativeCandidates, 157);
});

test("accepted continuation selects on 2023 before two fixed 2024 eras", () => {
  const validated = validateAcceptedContinuationProtocol(protocol, primaryProtocol, extensionProtocol);
  assert.equal(validated.chronologicalDevelopment.candidateSelection.evaluationDays, 60);
  assert.deepEqual(validated.chronologicalDevelopment.validationEras.map((era) => era.evaluationDays), [30, 30]);
  assert.equal(validated.chronologicalDevelopment.candidateSelection.selectCandidateOnce, true);
  assert.equal(validated.outcomePolicy.retuneFrom2024Validation, false);
});

test("accepted continuation rejects hypothesis, source, and gate mutations", () => {
  const hypothesisMutation = structuredClone(protocol);
  hypothesisMutation.hypothesis.grid.confirmationWindowMinutes[0] = 2;
  assert.throws(() => validateAcceptedContinuationProtocol(
    hypothesisMutation,
    primaryProtocol,
    extensionProtocol,
  ), /hypothesis changed/);

  const sourceMutation = structuredClone(protocol);
  sourceMutation.sourceEvidence[1].databaseSha256 = "0".repeat(64);
  assert.throws(() => validateAcceptedContinuationProtocol(
    sourceMutation,
    primaryProtocol,
    extensionProtocol,
  ), /source evidence/);

  const gateMutation = structuredClone(protocol);
  gateMutation.chronologicalDevelopment.validationGate.minimumTrades = 1;
  assert.throws(() => validateAcceptedContinuationProtocol(
    gateMutation,
    primaryProtocol,
    extensionProtocol,
  ), /validation gate/);
});

test("accepted continuation development cannot unlock evidence or execution", () => {
  const validated = validateAcceptedContinuationProtocol(protocol, primaryProtocol, extensionProtocol);
  assert.equal(validated.outcomePolicy.validationDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.externalDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.freshSealedDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(validated.outcomePolicy.liveExecutionAllowed, false);
});
