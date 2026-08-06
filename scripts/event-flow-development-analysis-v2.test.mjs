import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { validateAnalysisContract } from "./event-flow-development-analysis-contract.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const contract = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-analysis-v2.json"),
  "utf8",
));

test("v2 consumes exactly 32 new trials while preserving all signal and selection rules", () => {
  assert.equal(validateAnalysisContract(contract), contract);
  assert.deepEqual(contract.trialAccounting, {
    priorEvidenceContractCandidates: 92,
    stageCandidates: 32,
    cumulativeCandidates: 124,
    maximumCumulativeCandidates: 192,
  });
  assert.equal(contract.inheritsAnalysisContract.causalFeaturesUnchanged, true);
  assert.equal(contract.inheritsAnalysisContract.signalDefinitionsUnchanged, true);
  assert.equal(contract.inheritsAnalysisContract.nestedSelectionAndGateUnchanged, true);
});

test("v2 can only widen sub-floor stops and cannot inspect locked evidence", () => {
  assert.equal(contract.executionRepair.minimumStopFloorPct, 0.004);
  assert.equal(contract.executionRepair.belowFloorPolicy, "WIDEN_STOP_TO_FLOOR_AND_RESIZE_QUANTITY");
  assert.equal(contract.executionRepair.aboveMaximumInitialRiskPolicy, "NO_TRADE");
  assert.equal(contract.acquisitionProtocol.validationExternalAndFreshDataAllowed, false);
  assert.equal(contract.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(contract.outcomePolicy.liveExecutionAllowed, false);
});

test("v2 rejects a floor or signal-policy mutation", () => {
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      executionRepair: { ...contract.executionRepair, minimumStopFloorPct: 0.002 },
    }),
    /only apply the frozen 0.4 percent/,
  );
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      candidateSet: { ...contract.candidateSet, signalParametersMayChange: true },
    }),
    /cannot change signal parameters/,
  );
});
