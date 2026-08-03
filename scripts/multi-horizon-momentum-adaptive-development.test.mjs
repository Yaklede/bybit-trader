import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/multi-horizon-momentum-adaptive-development-v1.json", import.meta.url);

test("adaptive momentum development grid is exactly the declared bounded experiment", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const grid = protocol.candidateGrid;

  assert.equal(protocol.status, "PREDECLARED_DEVELOPMENT");
  assert.equal(grid.horizonSets.length * grid.thresholdScale.length * grid.minimumConsensusVotes.length * grid.sideMode.length, 24);
  assert.equal(grid.maximumCandidateCount, 24);
  assert.equal(protocol.decisionPolicy.post2024InspectionBeforeSelection, false);
  assert.equal(protocol.decisionPolicy.automaticPromotionAllowed, false);
});
