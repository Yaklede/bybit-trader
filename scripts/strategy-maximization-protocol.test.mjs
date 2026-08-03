import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/strategy-maximization-protocol-v1.json", import.meta.url);

test("strategy maximization protocol treats 0.2 percent as an evaluation gate", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));

  assert.equal(protocol.status, "DEVELOPMENT");
  assert.equal(protocol.selectionPolicy.dailyCompoundReturnIsSearchObjective, false);
  assert.equal(protocol.selectionPolicy.automaticPromotionAllowed, false);
  assert.equal(protocol.baseline.validationStatus, "UNVERIFIED");
  assert.equal(protocol.baseline.automaticExecutionAllowed, false);
  assert.equal(protocol.approvalGates.minimumExternalPositiveFoldRatio, 0.75);
  assert.equal(protocol.approvalGates.maximumLiquidationCount, 0);
});

test("causal execution contract is fail-closed for ambiguous or missing data", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const execution = protocol.executionContract;

  assert.equal(execution.decisionUsesClosedCandlesOnly, true);
  assert.equal(execution.entryFill, "NEXT_CONTIGUOUS_CANDLE_OPEN_WITH_ADVERSE_SLIPPAGE");
  assert.equal(execution.sameCandleStopAndTarget, "STOP_FIRST");
  assert.equal(execution.missingEntryCandle, "NO_TRADE");
  assert.equal(execution.runtimeAndBacktestContractMustMatch, true);
});

test("new experiments have a bounded search budget and a fresh sealed window", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));

  assert.ok(protocol.selectionPolicy.maximumNewCandidatesPerStage <= 24);
  assert.ok(protocol.selectionPolicy.maximumSequentialStages <= 4);
  assert.equal(protocol.selectionPolicy.parameterExpansionAfterFailedStage, false);
  assert.equal(protocol.selectionPolicy.sealedWindowMayBeInspectedBeforeApproval, false);
  assert.equal(protocol.promotion.requiredRuntimeFingerprintMatch, true);
});
