import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/multi-horizon-m15-regime-development-v1.json", import.meta.url);

test("M15 regime experiment is bounded and requires completed higher-timeframe candles", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const grid = protocol.candidateGrid;

  assert.equal(protocol.status, "PREDECLARED_DEVELOPMENT");
  assert.equal(
    grid.m15SlopeLookbackCandles.length *
      grid.thresholdScale.length *
      grid.minimumConsensusVotes.length *
      grid.sideMode.length,
    24,
  );
  assert.equal(grid.maximumCandidateCount, 24);
  assert.match(protocol.executionRule, /after its close time/);
  assert.equal(protocol.decisionPolicy.post2024InspectionBeforeSelection, false);
});
