import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { validateFixedExtensionProtocol } from "./event-flow-fixed-extension-protocol.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-fixed-extension-v1.json"),
  "utf8",
));
const parentProtocol = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-v1.json"),
  "utf8",
));

test("fixed extension freezes one disclosed candidate over 96 untouched evaluation days", () => {
  const validated = validateFixedExtensionProtocol(structuredClone(protocol), parentProtocol);
  assert.equal(validated.fixedCandidate.id, "car_ti0p4_cw5_ci0p1_ob0p05");
  assert.equal(validated.trialAccounting.cumulativeCandidates, 141);
  assert.equal(validated.blocks.length, 16);
  assert.equal(validated.blockContract.totalEvaluationDays, 96);
});

test("fixed extension rejects candidate, date, cost, and statistical-gate mutations", () => {
  const candidateMutation = structuredClone(protocol);
  candidateMutation.fixedCandidate.confirmationWindowMinutes = 10;
  assert.throws(() => validateFixedExtensionProtocol(candidateMutation, parentProtocol), /candidate fingerprint/);

  const dateMutation = structuredClone(protocol);
  dateMutation.blocks[0].sourceStartDate = "2023-02-06";
  assert.throws(() => validateFixedExtensionProtocol(dateMutation, parentProtocol), /date blocks/);

  const costMutation = structuredClone(protocol);
  costMutation.executionContract.entryFeeRate = 0;
  assert.throws(() => validateFixedExtensionProtocol(costMutation, parentProtocol), /execution and statistics/);

  const gateMutation = structuredClone(protocol);
  gateMutation.extensionGate.minimumTrades = 5;
  assert.throws(() => validateFixedExtensionProtocol(gateMutation, parentProtocol), /statistical gate/);
});

test("fixed extension cannot unlock validation, fresh evidence, or execution", () => {
  const validated = validateFixedExtensionProtocol(structuredClone(protocol), parentProtocol);
  assert.equal(validated.outcomePolicy.validationDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.externalDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.freshSealedDataMayBeAcquiredDirectly, false);
  assert.equal(validated.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(validated.outcomePolicy.liveExecutionAllowed, false);
});
