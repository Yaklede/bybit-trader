import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  expandFundingCandidates,
  loadFundingCrowdingProtocol,
} from "./funding-crowding-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(repositoryRoot, "config/bybit-funding-crowding-development-v1.json");

test("funding crowding research is frozen before development payload acquisition", async () => {
  const loaded = await loadFundingCrowdingProtocol(protocolPath);
  assert.equal(loaded.protocol.parentResult.sha256, loaded.parentResultSha256);
  assert.equal(loaded.protocol.researchBoundary.fundingPremiumDevelopmentPayloadsReadBeforeDeclaration, false);
  assert.equal(loaded.protocol.researchBoundary.internalValidation2023Through2024RemainsUnread, true);
  assert.equal(loaded.protocol.researchBoundary.external2025And2026RemainLocked, true);
});

test("funding crowding grid contains exactly 32 low-turnover candidates", async () => {
  const { protocol } = await loadFundingCrowdingProtocol(protocolPath);
  const candidates = expandFundingCandidates(protocol);
  assert.equal(candidates.length, 32);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 32);
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.fundingLookbackSettlements)), new Set([30, 90]));
  assert.deepEqual(new Set(candidates.map((candidate) => candidate.maximumHoldingHours)), new Set([24, 48]));
  assert.equal(candidates.every((candidate) => candidate.targetR === 2 && candidate.minimumStopPct === 0.02), true);
});

test("funding crowding execution remains capital-aware and cannot unlock live trading", async () => {
  const { protocol } = await loadFundingCrowdingProtocol(protocolPath);
  assert.equal(protocol.executionContract.startingEquityUsdt, 660);
  assert.equal(protocol.executionContract.maximumNotionalUsdt, 660);
  assert.equal(protocol.executionContract.oneHundredUsdtPolicy.startsWith("NO_TRADE"), true);
  assert.equal(protocol.outcomePolicy.internalValidationMayBeAcquiredOnlyAfterDevelopmentPass, true);
  assert.equal(protocol.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
