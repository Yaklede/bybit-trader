import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-result-v1.json"),
  "utf8",
));

test("event-flow v1 is rejected because every signal failed the frozen risk-distance contract", () => {
  assert.equal(result.status, "REJECTED_EXECUTION_INCOMPATIBLE_ZERO_TRADES");
  assert.equal(result.families.every((family) => family.candidateSignalCountRange.maximum > 0), true);
  assert.equal(result.families.every((family) => family.candidateExecutableTradeCountRange.maximum === 0), true);
  assert.equal(result.rootCause.representativeM1AtrStopRiskPct.maximum < result.rootCause.frozenMinimumInitialRiskPct, true);
  assert.equal(result.rootCause.interpretation.includes("PROFIT_EXPECTANCY_WAS_NOT_OBSERVED"), true);
});

test("v1 result permits only a new predeclared stop-floor hypothesis", () => {
  assert.equal(result.trialAccounting.cumulativeCandidates, 92);
  assert.equal(result.nextStageBoundary.allowed.includes("STOP_FLOOR"), true);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
