import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  expandMultiAssetDeltaNeutralFundingCarryCandidates,
  loadMultiAssetDeltaNeutralFundingCarryProtocol,
  validateMultiAssetDeltaNeutralFundingCarryProtocol,
} from "./multi-asset-delta-neutral-funding-carry-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-development-v1.json",
);

test("multi-asset carry is frozen before bulk development acquisition", async () => {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryProtocol(protocolPath);
  assert.equal(loaded.protocol.parentResult.sha256, loaded.parentResultSha256);
  assert.deepEqual(loaded.protocol.sourceData.symbols, ["BTCUSDT", "ETHUSDT", "SOLUSDT"]);
  assert.equal(loaded.protocol.researchBoundary.bulkMultiAssetDevelopmentPayloadsReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.researchBoundary.internalValidation2024RemainsUnreadForMultiAssetFamily, true);
});

test("multi-asset grid contains exactly 24 fixed candidates", async () => {
  const { protocol } = await loadMultiAssetDeltaNeutralFundingCarryProtocol(protocolPath);
  const candidates = expandMultiAssetDeltaNeutralFundingCarryCandidates(protocol);
  assert.equal(candidates.length, 24);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 24);
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.minimumPositiveFundingStreak)), new Set([3, 6]));
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.minimumTrailingMedianFundingRate)),
    new Set([0.0001, 0.0002, 0.0003]));
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.maximumConcurrentPairs)), new Set([1, 2]));
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.exitConsecutiveNonPositiveFundingCount)),
    new Set([1, 2]));
});

test("portfolio capital and concentration gates cannot be loosened silently", async () => {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryProtocol(protocolPath);
  const { protocol } = loaded;
  assert.equal(protocol.executionContract.maximumTotalMatchedNotionalFractionOfEquity, 0.4);
  assert.equal(protocol.executionContract.absoluteMaximumConcurrentPairs, 2);
  assert.equal(protocol.developmentGate.maximumPositivePositionProfitConcentration, 0.25);
  assert.equal(protocol.developmentGate.maximumPositiveAssetProfitConcentration, 0.6);
  assert.equal(protocol.developmentGate.minimumTradedAssetCount, 3);

  const mutated = structuredClone(protocol);
  mutated.developmentGate.maximumPositivePositionProfitConcentration = 0.4;
  assert.throws(
    () => validateMultiAssetDeltaNeutralFundingCarryProtocol(
      mutated,
      { programStatus: "CLOSED_NO_APPROVABLE_DELTA_NEUTRAL_FUNDING_CARRY_V1" },
      loaded.parentResultSha256,
    ),
    /gate.*changed/i,
  );
});

test("all three symbols preserve exchange minimums and no-trade sizing", async () => {
  const { protocol } = await loadMultiAssetDeltaNeutralFundingCarryProtocol(protocolPath);
  assert.equal(protocol.observedInstrumentRules.BTCUSDT.perpetual.quantityStepBase, 0.001);
  assert.equal(protocol.observedInstrumentRules.ETHUSDT.perpetual.quantityStepBase, 0.01);
  assert.equal(protocol.observedInstrumentRules.SOLUSDT.perpetual.quantityStepBase, 0.1);
  assert.match(protocol.executionContract.minimumQuantityPolicy, /NO_TRADE/);
  assert.equal(protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
