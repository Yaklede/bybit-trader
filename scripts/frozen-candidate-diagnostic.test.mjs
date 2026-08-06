import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  evaluateFrozenDiagnosticRuns,
  scaledCostContract,
  validateFrozenDiagnosticProtocol,
} from "./lib/volume-impact-state-research.mjs";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-post2024-diagnostic-v1.json"), "utf8"),
);

test("frozen diagnostic validator rejects promotion and undeclared cost changes", () => {
  assert.equal(validateFrozenDiagnosticProtocol(protocol), protocol);
  assert.throws(
    () => validateFrozenDiagnosticProtocol({
      ...protocol,
      outcomePolicy: { ...protocol.outcomePolicy, liveExecutionAllowed: true },
    }),
    /cannot enable promotion or live execution/,
  );
  assert.throws(() => scaledCostContract(protocol, 1.25), /Undeclared cost multiplier/);
});

test("cost scaling changes every declared fee and slippage rate but no risk control", () => {
  const scaled = scaledCostContract(protocol, 2);
  const base = protocol.executionContract;
  assert.equal(scaled.entryFeeRate, base.entryFeeRate * 2);
  assert.equal(scaled.exitFeeRate, base.exitFeeRate * 2);
  assert.equal(scaled.entrySlippageRate, base.entrySlippageRate * 2);
  assert.equal(scaled.exitSlippageRate, base.exitSlippageRate * 2);
  assert.equal(scaled.riskFraction, base.riskFraction);
  assert.equal(scaled.maximumTradesPerUtcDay, base.maximumTradesPerUtcDay);
});

test("a no-trade diagnostic rejects the candidate and never opens the fresh seal", () => {
  const runs = protocol.costMultipliers.map((costMultiplier) => ({
    costMultiplier,
    candidateResult: { trades: [], dataGapCount: 0 },
  }));
  const result = evaluateFrozenDiagnosticRuns(runs, protocol);
  assert.equal(result.status, "REJECTED");
  assert.equal(result.gate.checks.minimumBaseTradeCount, false);
  assert.equal(result.reservedSealedWindowOpened, false);
  assert.equal(result.automaticExecutionAllowed, false);
});
