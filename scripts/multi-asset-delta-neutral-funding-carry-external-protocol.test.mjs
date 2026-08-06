import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadMultiAssetDeltaNeutralFundingCarryExternalProtocol,
  validateMultiAssetDeltaNeutralFundingCarryExternalProtocol,
} from "./multi-asset-delta-neutral-funding-carry-external-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-external-v2.json",
);

test("external v2 freezes the unchanged candidate before 2025 evidence", async () => {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryExternalProtocol(protocolPath);
  assert.equal(loaded.protocol.status, "PREDECLARED_BEFORE_EXTERNAL_2025_EVIDENCE_ACQUISITION");
  assert.equal(loaded.protocol.selectedCandidate.id, "multi_asset_delta_neutral_carry_04");
  assert.equal(loaded.protocol.researchBoundary.external2025OfficialPayloadsReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.researchBoundary.external2025PortfolioOutcomeReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.researchBoundary.candidateRetunedFrom2024Outcome, false);
  assert.equal(loaded.protocol.trialAccounting.newProtocolDecisionCount, 1);
});

test("external and sealed annualized sample gates are fixed separately", async () => {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryExternalProtocol(protocolPath);
  assert.equal(loaded.protocol.externalValidationGate.minimumClosedPositions, 12);
  assert.equal(loaded.protocol.externalValidationGate.minimumCapturedFundingSettlements, 365);
  assert.equal(loaded.protocol.externalValidationGate.maximumDrawdownPct, 5);
  assert.equal(loaded.protocol.sealedValidationContract.gate.minimumClosedPositions, 6);
  assert.equal(loaded.protocol.sealedValidationContract.gate.minimumCapturedFundingSettlements, 180);
  assert.equal(loaded.protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(loaded.protocol.outcomePolicy.liveExecutionAllowed, false);
});

test("external protocol rejects a post-outcome candidate or gate rewrite", async () => {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryExternalProtocol(protocolPath);
  const context = await contextFromLoaded(loaded);
  assert.throws(() => validateMultiAssetDeltaNeutralFundingCarryExternalProtocol({
    ...context,
    protocol: {
      ...context.protocol,
      externalValidationGate: {
        ...context.protocol.externalValidationGate,
        minimumClosedPositions: 1,
      },
    },
  }), /annual gate changed/);
});

async function contextFromLoaded(loaded) {
  const fs = await import("node:fs/promises");
  const protocol = loaded.protocol;
  const readJson = async (relativePath) => JSON.parse(await fs.readFile(path.join(repositoryRoot, relativePath)));
  return {
    protocol,
    parentResult: await readJson(protocol.parentInternalResult.path),
    parentSha256: loaded.parentResultSha256,
    developmentResult: await readJson(protocol.developmentResult.path),
    developmentResultSha256: loaded.developmentResultSha256,
    developmentProtocol: await readJson(protocol.developmentProtocol.path),
    developmentProtocolSha256: loaded.developmentProtocolSha256,
    simulatorSha256: loaded.simulatorSha256,
  };
}
