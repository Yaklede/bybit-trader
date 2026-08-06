import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { validateAnalysisContract } from "./event-flow-development-analysis-contract.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const contract = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-analysis-v3.json"),
  "utf8",
));

test("v3 freezes 16 confirmed-reversal candidates and cumulative trial count 140", () => {
  assert.equal(validateAnalysisContract(contract), contract);
  assert.deepEqual(contract.trialAccounting, {
    priorEvidenceContractCandidates: 124,
    stageCandidates: 16,
    cumulativeCandidates: 140,
    maximumCumulativeCandidates: 192,
  });
  assert.equal(contract.hypothesis.candidateCount, 16);
});

test("v3 requires post-setup taker, book, and price confirmation before next-minute entry", () => {
  assert.equal(contract.stateMachine.confirmationMustOccurAfterSetupClose, true);
  assert.equal(contract.stateMachine.preReplaySetupAllowed, false);
  assert.equal(contract.stateMachine.confirmationConditions.length, 5);
  assert.equal(contract.stateMachine.confirmedToPending, "NEXT_CONTIGUOUS_M1_OPEN_AFTER_CONFIRMATION_CLOSE");
});

test("v3 keeps one 2023-selected candidate fixed across both 2024 validation eras", () => {
  assert.deepEqual(contract.chronologicalDevelopment.selectionEras, ["2023H1", "2023H2"]);
  assert.deepEqual(contract.chronologicalDevelopment.validationEras, ["2024H1", "2024H2"]);
  assert.equal(contract.chronologicalDevelopment.selectedCandidateMayChangeBetweenValidationEras, false);
  assert.equal(contract.chronologicalDevelopment.validationGate.minimumPositiveEraCount, 2);
  assert.equal(contract.acquisitionProtocol.validationExternalAndFreshDataAllowed, false);
});

test("v3 rejects candidate-count and chronological-gate mutations", () => {
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      trialAccounting: { ...contract.trialAccounting, stageCandidates: 15 },
    }),
    /trial accounting/,
  );
  assert.throws(
    () => validateAnalysisContract({
      ...contract,
      chronologicalDevelopment: {
        ...contract.chronologicalDevelopment,
        selectedCandidateMayChangeBetweenValidationEras: true,
      },
    }),
    /chronological selection/,
  );
});
