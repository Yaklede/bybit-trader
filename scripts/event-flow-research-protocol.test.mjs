import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  acquisitionBlocks,
  expandEventFlowCandidates,
  validateEventFlowProtocol,
} from "./event-flow-research-protocol.mjs";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(await fs.readFile(path.join(repoRoot, "config/bybit-event-flow-development-v1.json"), "utf8"));

test("event-flow protocol freezes 32 new and 92 cumulative trials", () => {
  assert.equal(validateEventFlowProtocol(protocol), protocol);
  assert.equal(expandEventFlowCandidates(protocol).length, 32);
  assert.deepEqual(
    { ...Object.groupBy(expandEventFlowCandidates(protocol), (candidate) => candidate.family) },
    {
      EVENT_DEPLETION_CONTINUATION: expandEventFlowCandidates(protocol).slice(0, 16),
      EVENT_ABSORPTION_REVERSAL: expandEventFlowCandidates(protocol).slice(16),
    },
  );
});

test("only development acquisition is open before a candidate is frozen", () => {
  assert.equal(acquisitionBlocks(protocol, "development").length, 12);
  assert.throws(() => acquisitionBlocks(protocol, "validation"), /locked until its predecessor evidence/);
  assert.throws(() => acquisitionBlocks(protocol, "external"), /locked until its predecessor evidence/);
  assert.equal(protocol.stages.freshSealed.eventDataMayBeAcquiredBeforeCandidateFingerprint, false);
});

test("proof and known-gap dates are excluded from every declared block", () => {
  const blocks = ["development", "validation", "external"].flatMap((stage) => [
    ...protocol.stages[stage].primaryBlocks,
    ...protocol.stages[stage].reserveBlocks,
  ]);
  for (const block of blocks) {
    assert.equal(inBlock("2026-06-01", block), false);
    assert.equal(inBlock("2025-08-21", block), false);
  }
});

test("a protocol mutation cannot unlock sealed acquisition or alter trial counts", () => {
  assert.throws(
    () => validateEventFlowProtocol({ ...protocol, trials: { ...protocol.trials, cumulativeCount: 91 } }),
    /trial accounting/,
  );
  assert.throws(
    () => validateEventFlowProtocol({
      ...protocol,
      stages: {
        ...protocol.stages,
        freshSealed: { ...protocol.stages.freshSealed, eventDataMayBeAcquiredBeforeCandidateFingerprint: true },
      },
    }),
    /cannot be acquired/,
  );
});

function inBlock(date, block) {
  return date >= block.sourceStartDate && date <= block.sourceEndDate;
}
