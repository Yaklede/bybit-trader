import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  loadMultiAssetDeltaNeutralFundingCarryInternalProtocol,
} from "./multi-asset-delta-neutral-funding-carry-internal-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(
  repositoryRoot,
  "config/bybit-multi-asset-delta-neutral-funding-carry-internal-v1.json",
);

test("internal protocol binds the selected candidate and unchanged simulator", async () => {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryInternalProtocol(protocolPath);
  assert.equal(loaded.protocol.parentDevelopmentResult.sha256, loaded.parentResultSha256);
  assert.equal(loaded.protocol.developmentProtocol.sha256, loaded.developmentProtocolSha256);
  assert.equal(loaded.protocol.implementationBinding.simulatorSha256, loaded.simulatorSha256);
  assert.equal(loaded.protocol.selectedCandidate.id, "multi_asset_delta_neutral_carry_04");
  assert.equal(loaded.protocol.trialAccounting.newCandidateBudget, 0);
});

test("2024 boundary discloses prior BTC observation and keeps ETH SOL unread", async () => {
  const { protocol } = await loadMultiAssetDeltaNeutralFundingCarryInternalProtocol(protocolPath);
  assert.equal(protocol.researchBoundary.btc2024SingleAssetPayloadAndOutcomePreviouslyObserved, true);
  assert.equal(protocol.researchBoundary.ethAndSol2024OfficialPayloadsReadBeforeDeclaration, false);
  assert.equal(protocol.researchBoundary.multiAsset2024PortfolioOutcomeReadBeforeDeclaration, false);
  assert.equal(protocol.researchBoundary.external2025RemainsLocked, true);
  assert.equal(protocol.outcomePolicy.candidateMayBeRetuned, false);
  assert.equal(protocol.outcomePolicy.gateMayBeChanged, false);
  assert.equal(protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
