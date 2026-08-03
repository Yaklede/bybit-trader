import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/multi-horizon-exit-ablation-development-v1.json", import.meta.url);

test("exit ablation changes only declared exit axes", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const grid = protocol.candidateGrid;

  assert.equal(protocol.status, "PREDECLARED_DEVELOPMENT");
  assert.equal(grid.stopAtr.length * grid.trailAtr.length * grid.maxHoldCandles.length, 18);
  assert.equal(grid.maximumCandidateCount, 18);
  assert.equal(grid.fixedSignal.sideMode, "LONG_ONLY");
  assert.equal(protocol.decisionPolicy.post2024InspectionBeforeSelection, false);
});
