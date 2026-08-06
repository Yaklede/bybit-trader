import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { validateAnalysisContract } from "./event-flow-development-analysis-contract.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const contract = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-analysis-v1.json"),
  "utf8",
));

test("analysis contract freezes causal feature and adverse execution clocks before replay", () => {
  assert.equal(validateAnalysisContract(contract), contract);
  assert.equal(contract.causalFeatureDefinitions.relativeTakerNotional.includes("CURRENT_MINUTE_EXCLUDED"), true);
  assert.equal(contract.causalFeatureDefinitions.atr.includes("CURRENT_DECISION_CANDLE_EXCLUDED"), true);
  assert.deepEqual(contract.positionContract.sameBarPriority.slice(0, 3), [
    "GAP_LIQUIDATION",
    "GAP_STOP",
    "INTRABAR_STOP",
  ]);
});

test("analysis contract cannot unlock hidden data or execution", () => {
  assert.equal(contract.acquisitionProtocol.validationExternalAndFreshDataAllowed, false);
  assert.equal(contract.outcomePolicy.automaticExecutionAllowed, false);
  assert.equal(contract.outcomePolicy.liveExecutionAllowed, false);
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      acquisitionProtocol: { ...contract.acquisitionProtocol, validationExternalAndFreshDataAllowed: true },
    }),
    /cannot access/,
  );
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      outcomePolicy: { ...contract.outcomePolicy, liveExecutionAllowed: true },
    }),
    /cannot mutate itself or authorize execution/,
  );
});

test("analysis contract remains bound to the committed acquisition protocol hash", () => {
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      acquisitionProtocol: { ...contract.acquisitionProtocol, protocolSha256: "0".repeat(64) },
    }),
    /frozen acquisition protocol hash/,
  );
});
