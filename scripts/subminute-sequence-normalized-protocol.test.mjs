import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import { expandSubminuteCandidates } from "./subminute-sequence-protocol.mjs";
import { loadNormalizedSubminuteProtocol } from "./subminute-sequence-normalized-protocol.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(repositoryRoot, "config/bybit-subminute-sequence-normalized-v2.json");

test("normalized v2 is frozen against the closed v1 result before outcome replay", async () => {
  const loaded = await loadNormalizedSubminuteProtocol(protocolPath);
  assert.equal(loaded.declaration.parentEvidence.resultSha256, loaded.parentResultSha256);
  assert.equal(loaded.declaration.parentEvidence.protocolSha256, loaded.parentProtocolSha256);
  assert.equal(loaded.declaration.parentEvidence.acquisitionReceiptSha256, loaded.acquisitionReceiptSha256);
  assert.equal(loaded.declaration.researchBoundary.normalizedCandidateOutcomesReadBeforeDeclaration, false);
  assert.equal(loaded.declaration.researchBoundary.internalValidation2024RemainsUnread, true);
});

test("normalized v2 adds exactly 32 trials and changes only microprice scale", async () => {
  const loaded = await loadNormalizedSubminuteProtocol(protocolPath);
  const candidates = expandSubminuteCandidates(loaded.effectiveProtocol);
  assert.equal(candidates.length, 32);
  assert.equal(loaded.declaration.trialAccounting.priorObservedCandidates, 223);
  assert.equal(loaded.declaration.trialAccounting.cumulativeCandidateCountAfterReplay, 255);
  assert.equal(candidates.every((candidate) =>
    candidate.micropriceConfirmationMode === "CLOSE_SPREAD_NORMALIZED_CLAMPED" &&
    candidate.minimumNormalizedMicropriceEdge === 0.2), true);
  assert.equal(loaded.effectiveProtocol.executionContract.targetR, 3);
  assert.equal(loaded.effectiveProtocol.executionContract.entryFeeRate, 0.0006);
  assert.equal(loaded.effectiveProtocol.executionContract.maximumHoldingMinutes, 60);
  assert.equal(loaded.effectiveProtocol.acquisition.selectionBlocks.every((block) => block.era.startsWith("2023")), true);
});

test("normalized v2 cannot unlock later evidence or execution", async () => {
  const loaded = await loadNormalizedSubminuteProtocol(protocolPath);
  assert.equal(loaded.declaration.outcomePolicy.internalValidation2024MayBeAcquiredOnlyAfterSelectionPass, true);
  assert.equal(loaded.declaration.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(loaded.declaration.outcomePolicy.liveExecutionAllowed, false);
});
