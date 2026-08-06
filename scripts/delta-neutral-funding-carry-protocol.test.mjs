import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  expandDeltaNeutralFundingCarryCandidates,
  loadDeltaNeutralFundingCarryProtocol,
} from "./delta-neutral-funding-carry-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(repositoryRoot, "config/bybit-delta-neutral-funding-carry-development-v1.json");

test("delta-neutral funding carry is frozen before bulk development acquisition", async () => {
  const loaded = await loadDeltaNeutralFundingCarryProtocol(protocolPath);
  assert.equal(loaded.protocol.parentResult.sha256, loaded.parentResultSha256);
  assert.equal(loaded.protocol.researchBoundary.availabilityProbeReadBeforeDeclaration, true);
  assert.equal(loaded.protocol.researchBoundary.bulkDevelopmentPayloadsReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.researchBoundary.internalValidation2024RemainsUnread, true);
});

test("delta-neutral grid contains exactly 24 economically distinct candidates", async () => {
  const { protocol } = await loadDeltaNeutralFundingCarryProtocol(protocolPath);
  const candidates = expandDeltaNeutralFundingCarryCandidates(protocol);
  assert.equal(candidates.length, 24);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 24);
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.minimumPositiveFundingStreak)), new Set([3, 6]));
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.minimumTrailingMedianFundingRate)),
    new Set([0.0001, 0.0002, 0.0003]));
  assert.equal(candidates.every((candidate) => candidate.maximumHoldingDays === 30), true);
});

test("execution contract includes four-leg costs, basis risk, and atomicity failure", async () => {
  const { protocol } = await loadDeltaNeutralFundingCarryProtocol(protocolPath);
  const execution = protocol.executionContract;
  assert.equal(execution.baseRoundTripCostRateOnMatchedNotional, 0.0041);
  assert.equal(execution.maximumMatchedNotionalFractionOfEquity, 0.4);
  assert.equal(execution.perpetualLeverage, 1);
  assert.equal(execution.secondLegFailure.startsWith("IMMEDIATE_REDUCE_ONLY"), true);
  assert.equal(protocol.developmentGate.costStressMinimumNetReturnPct, 0);
  assert.equal(protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
