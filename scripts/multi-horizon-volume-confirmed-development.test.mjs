import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/multi-horizon-volume-confirmed-development-v1.json", import.meta.url);

test("volume-confirmed momentum experiment has one bounded OHLCV filter axis", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const grid = protocol.candidateGrid;

  assert.equal(protocol.status, "PREDECLARED_DEVELOPMENT");
  assert.equal(grid.relativeVolumeMin.length * grid.minBodyRatio.length * grid.sideMode.length, 24);
  assert.equal(grid.maximumCandidateCount, 24);
  assert.equal(protocol.parentHypothesis, "multi-horizon-momentum-adaptive-development-v1");
  assert.equal(protocol.decisionPolicy.post2024InspectionBeforeSelection, false);
});
