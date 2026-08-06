import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-result-v3.json"),
  "utf8",
));

test("v3 rejects the best-looking confirmed reversal because its selection sample is insufficient", () => {
  assert.equal(result.status, "REJECTED_INSUFFICIENT_SELECTION_SAMPLE");
  assert.equal(result.bestObservedCandidate.selectionEligible, false);
  assert.equal(result.bestObservedCandidate.selection2023.tradeCount, 6);
  assert.equal(result.bestObservedCandidate.selection2023.minimumRequiredTrades, 15);
  assert.equal(result.bestObservedCandidate.selection2023.bootstrapLowerMeanNetR < 0, true);
});

test("v3 result permits only a fixed-candidate non-promoting sample extension", () => {
  assert.equal(result.trialAccounting.cumulativeCandidates, 140);
  assert.equal(result.nextStageBoundary.allowed.includes("NON_PROMOTING_FIXED_CANDIDATE_SAMPLE_EXTENSION"), true);
  assert.equal(result.validationDataRead, false);
  assert.equal(result.externalDataRead, false);
  assert.equal(result.freshSealedDataRead, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});
