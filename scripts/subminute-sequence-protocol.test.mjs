import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  expandSubminuteCandidates,
  validateSubminuteSequenceProtocol,
} from "./subminute-sequence-protocol.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-subminute-sequence-development-v1.json"),
  "utf8",
));

test("subminute sequence protocol freezes chronological evidence before raw replay", () => {
  const validated = validateSubminuteSequenceProtocol(protocol);
  assert.equal(validated.acquisition.selectionBlocks.every((block) => block.era.startsWith("2023")), true);
  assert.equal(validated.acquisition.internalValidationBlocks.every((block) => block.era.startsWith("2024")), true);
  assert.equal(validated.researchBoundary.subminuteSourcePayloadsReadBeforeDeclaration, false);
  assert.equal(validated.outcomePolicy.validation2025MayBeAcquiredOnlyAfterExpansionPass, true);
});

test("subminute candidate budget is exactly 16 per independent family", () => {
  const candidates = expandSubminuteCandidates(protocol);
  const counts = Map.groupBy(candidates, (candidate) => candidate.family);
  assert.equal(candidates.length, 32);
  assert.equal(counts.get("SUBMINUTE_ABSORPTION_REVERSAL").length, 16);
  assert.equal(counts.get("SUBMINUTE_DEPLETION_CONTINUATION").length, 16);
});

test("locked evidence and automatic execution remain unavailable", () => {
  const validated = validateSubminuteSequenceProtocol(protocol);
  assert.equal(validated.featureContract.liquidationFeature.historicalSourceStatus, "ABSENT_NOT_ZERO");
  assert.equal(validated.featureContract.liquidationFeature.candidateUseAllowed, false);
  assert.equal(validated.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(validated.outcomePolicy.liveExecutionAllowed, false);
});
