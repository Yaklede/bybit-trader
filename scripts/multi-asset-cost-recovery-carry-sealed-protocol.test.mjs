import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadCostRecoveryCarrySealedProtocol,
  validateCostRecoveryCarrySealedProtocol,
} from "./multi-asset-cost-recovery-carry-sealed-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-cost-recovery-carry-sealed-2026-h1-v3.json",
);

test("2026 H1 seal freezes candidate 018 before official evidence", async () => {
  const loaded = await loadCostRecoveryCarrySealedProtocol(protocolPath);
  assert.equal(loaded.protocol.selectedCandidate.id, "multi_asset_cost_recovery_carry_018");
  assert.equal(loaded.protocol.researchBoundary.sealed2026OfficialPayloadsReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.researchBoundary.sealed2026PortfolioOutcomeReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.trialAccounting.evaluatedCandidateCount, 1);
});

test("sealed gate and period cannot be changed after declaration", async () => {
  const loaded = await loadCostRecoveryCarrySealedProtocol(protocolPath);
  const context = contextFromLoaded(loaded);
  assert.throws(() => validateCostRecoveryCarrySealedProtocol({
    ...context,
    protocol: {
      ...context.protocol,
      sealedValidationGate: {
        ...context.protocol.sealedValidationGate,
        minimumClosedPositions: 1,
      },
    },
  }), /validation gate changed/);
  assert.throws(() => validateCostRecoveryCarrySealedProtocol({
    ...context,
    protocol: {
      ...context.protocol,
      sourceData: {
        ...context.protocol.sourceData,
        stageStart: "2026-02-01T00:00:00Z",
      },
    },
  }), /source contract changed/);
});

test("a sealed pass can only unlock forward shadow and paper", async () => {
  const loaded = await loadCostRecoveryCarrySealedProtocol(protocolPath);
  assert.equal(loaded.protocol.outcomePolicy.passedCandidateRequiresFreshForwardShadow, true);
  assert.equal(loaded.protocol.outcomePolicy.passedCandidateRequiresPaperExecutionParity, true);
  assert.equal(loaded.protocol.outcomePolicy.sealedPassGrantsLiveExecution, false);
  assert.equal(loaded.protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(loaded.protocol.outcomePolicy.liveExecutionAllowed, false);
});

function contextFromLoaded(loaded) {
  return {
    protocol: loaded.protocol,
    developmentResult: loaded.developmentResult,
    developmentResultSha256: loaded.developmentResultSha256,
    developmentProtocol: loaded.developmentProtocol,
    developmentProtocolSha256: loaded.developmentProtocolSha256,
    simulatorSha256: loaded.simulatorSha256,
  };
}
