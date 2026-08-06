import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  expandMultiAssetCostRecoveryCarryCandidates,
  loadMultiAssetCostRecoveryCarryProtocol,
} from "./multi-asset-cost-recovery-carry-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-development-v3.json",
);

test("v3 freezes 54 cost-recovery candidates before 2026 evidence", async () => {
  const loaded = await loadMultiAssetCostRecoveryCarryProtocol(protocolPath);
  const candidates = expandMultiAssetCostRecoveryCarryCandidates(loaded.protocol);
  assert.equal(candidates.length, 54);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 54);
  assert.equal(candidates.every((candidate) => candidate.minimumProjectedNetCarryScore === 0.001), true);
  assert.equal(candidates.every((candidate) => candidate.minimumEntryBasisPct === -0.001), true);
  assert.equal(loaded.protocol.researchBoundary.sealed2026OfficialPayloadsReadBeforeDeclaration, false);
});

test("v3 candidate horizon scales with its maximum holding period", async () => {
  const loaded = await loadMultiAssetCostRecoveryCarryProtocol(protocolPath);
  const candidates = expandMultiAssetCostRecoveryCarryCandidates(loaded.protocol);
  for (const candidate of candidates) {
    assert.equal(candidate.projectedCarryHorizonSettlements, Math.round(candidate.maximumHoldingDays * 1.5));
  }
  assert.deepEqual(
    [...new Set(candidates.map((candidate) => candidate.projectedCarryHorizonSettlements))],
    [45, 90, 135],
  );
});

test("v3 selection requires positive economics in every disclosed year", async () => {
  const loaded = await loadMultiAssetCostRecoveryCarryProtocol(protocolPath);
  const gate = loaded.protocol.developmentGate;
  assert.equal(gate.minimumNetReturnPctPerYear, 0);
  assert.equal(gate.minimumBootstrapLowerMeanDailyReturnPctPerYear, 0);
  assert.equal(gate.minimumCostStressNetReturnPctPerYear, 0);
  assert.equal(gate.minimumSecondLegDelayStressNetReturnPctPerYear, 0);
  assert.equal(loaded.protocol.selectionPolicy.candidateMayBeRetunedAfterOutcome, false);
  assert.equal(loaded.protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(loaded.protocol.outcomePolicy.liveExecutionAllowed, false);
});
