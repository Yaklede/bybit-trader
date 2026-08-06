import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import { loadDeltaNeutralFundingCarryInternalProtocol } from "./delta-neutral-funding-carry-internal-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(repositoryRoot, "config/bybit-delta-neutral-funding-carry-internal-v1.json");

test("internal protocol binds the one frozen candidate and unchanged simulator", async () => {
  const { protocol, researchLibrarySha256 } = await loadDeltaNeutralFundingCarryInternalProtocol(protocolPath);
  assert.equal(protocol.selectedCandidate.id, "delta_neutral_funding_carry_02");
  assert.equal(protocol.selectedCandidateSha256, protocol.parentDevelopmentResult == null
    ? null
    : "eba52739bfa5d266de0e3b21f49a71637c9080e44cfd3821f601b3d2ef6be509");
  assert.equal(protocol.implementationBinding.researchLibrarySha256, researchLibrarySha256);
  assert.equal(protocol.trialAccounting.newCandidateBudget, 0);
});

test("2024 is internal only and all later evidence remains locked", async () => {
  const { protocol } = await loadDeltaNeutralFundingCarryInternalProtocol(protocolPath);
  assert.equal(protocol.sourceData.stageStart, "2024-01-01T00:00:00Z");
  assert.equal(protocol.sourceData.stageEndExclusive, "2025-01-01T00:00:00Z");
  assert.equal(protocol.researchBoundary.external2025RemainsLocked, true);
  assert.equal(protocol.researchBoundary.sealed2026RemainsLocked, true);
  assert.equal(protocol.outcomePolicy.candidateMayBeRetuned, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
