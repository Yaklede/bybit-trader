import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocolPath = path.join(repoRoot, "config/volume-impact-state-development-v1.json");
const sealedPath = path.join(repoRoot, "config/fresh-sealed-validation-2026-08-v1.json");

test("volume-impact development protocol freezes exactly 24 bounded candidates", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  assert.equal(protocol.status, "PREDECLARED_DEVELOPMENT");
  assert.equal(protocol.protocolRevision, 2);
  assert.equal(protocol.selectionPolicy.dailyCompoundReturnIsSearchObjective, false);
  assert.equal(protocol.selectionPolicy.automaticPromotionAllowed, false);
  assert.equal(protocol.hypotheses.length, 2);

  const counts = protocol.hypotheses.map((hypothesis) =>
    Object.values(hypothesis.grid).reduce((count, values) => count * values.length, 1),
  );
  assert.deepEqual(counts, [12, 12]);
  assert.equal(counts.reduce((total, count) => total + count, 0), 24);
  assert.equal(protocol.selectionPolicy.maximumStageCandidateCount, 24);
  assert.ok(protocol.hypotheses.every((hypothesis) => hypothesis.candidateCount === 12));
});

test("walk-forward folds are causal, contiguous, and stop before the reserved seal", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const sealed = JSON.parse(await fs.readFile(sealedPath, "utf8"));
  const folds = protocol.nestedWalkForward.folds;
  assert.equal(folds.length, 6);

  for (let index = 0; index < folds.length; index += 1) {
    const fold = folds[index];
    assert.ok(Date.parse(fold.trainStartAt) < Date.parse(fold.trainEndAt));
    assert.equal(fold.trainEndAt, fold.validationStartAt);
    assert.ok(Date.parse(fold.validationStartAt) < Date.parse(fold.validationEndAt));
    assert.ok(Date.parse(fold.validationEndAt) <= Date.parse(protocol.sourceData.developmentReplayEndsAt));
    if (index > 0) assert.equal(folds[index - 1].validationEndAt, fold.validationStartAt);
  }

  const sealedStart = Math.min(...sealed.windows.map((window) => Date.parse(window.replayStartAt)));
  assert.ok(Date.parse(protocol.sourceData.developmentReplayEndsAt) < sealedStart);
  assert.equal(protocol.contaminationDisclosure.reservedSealedWindowMayBeReadDuringDevelopment, false);
});

test("execution contract requires closed multi-timeframe inputs and adverse causal fills", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const contract = protocol.executionContract;
  assert.equal(contract.decisionClock, "M1_CLOSE");
  assert.equal(contract.m15Input, "CLOSED_BARS_ONLY");
  assert.equal(contract.m5SetupInput, "CLOSED_BARS_ONLY");
  assert.equal(contract.m1ConfirmationMustBeStrictlyAfterM5Close, true);
  assert.equal(contract.entry, "NEXT_CONTIGUOUS_M1_OPEN");
  assert.equal(contract.sameBarConflict, "STOP_FIRST");
  assert.equal(contract.discontinuousEntry, "NO_TRADE");
  assert.ok(contract.entryFeeRate > 0);
  assert.ok(contract.exitFeeRate > 0);
  assert.ok(contract.entrySlippageRate > 0);
  assert.ok(contract.exitSlippageRate > 0);
  assert.equal(contract.researchLeverage, 15);
  assert.equal(contract.maintenanceMarginRate, 0.005);
  assert.equal(
    protocol.hypotheses.find((hypothesis) => hypothesis.family === "VOLUME_EXHAUSTION_REVERSAL")
      .fixed.reversalDirectionMustMatchClosedM15Regime,
    true,
  );
  assert.equal(protocol.outcomePolicy.liveExecutionAllowed, false);
});
