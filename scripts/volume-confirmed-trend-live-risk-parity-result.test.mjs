import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";

const protocolBytes = readFileSync("config/volume-confirmed-trend-ensemble-v1.json");
const externalResultBytes = readFileSync("config/volume-confirmed-trend-ensemble-v1-external-result.json");
const riskParityBytes = readFileSync("config/volume-confirmed-trend-ensemble-v1-live-risk-parity-result.json");
const protocol = JSON.parse(protocolBytes);
const externalResult = JSON.parse(externalResultBytes);
const riskParity = JSON.parse(riskParityBytes);

test("Live risk parity result is immutable and bound to frozen external evidence", () => {
  assert.equal(sha256(riskParityBytes), "367f6579566ebea561854e39bb2465b43fcd421aeb42af361e479abcea584767");
  assert.equal(riskParity.protocol.id, protocol.protocolId);
  assert.equal(riskParity.protocol.candidateId, protocol.candidateId);
  assert.equal(riskParity.protocol.sha256, sha256(protocolBytes));
  assert.equal(riskParity.sourceEvidence.externalResultSha256, sha256(externalResultBytes));
  assert.equal(
    riskParity.sourceEvidence.databaseSha256,
    externalResult.acquisitionEvidence.databaseSha256,
  );
  assert.deepEqual(riskParity.canonicalBaseline, {
    startingEquityUsdt: externalResult.canonicalMetrics.startingEquityUsdt,
    endingEquityUsdt: externalResult.canonicalMetrics.baseline.endingEquityUsdt,
    netReturnPct: externalResult.canonicalMetrics.baseline.netReturnPct,
    closedTradeCount: externalResult.canonicalMetrics.baseline.closedTradeCount,
  });
  assert.deepEqual(riskParity.runtimeRiskPolicy, {
    maximumDailyLossFraction: 0.03,
    maximumAccountDrawdownFraction: 0.35,
    maximumConsecutiveLosses: 3,
    riskStateMaximumAgeSeconds: 600,
    walletReconciliationMaximumAgeSeconds: 600,
    walletReconciliationConfirmedMismatchCount: 2,
  });
});

test("Live risk replay is cross-runtime deterministic and exposes the permanent stop", () => {
  assert.equal(riskParity.policyReplay.simulationKind, "H4_DECISION_BOUNDARY_RISK_POLICY_REPLAY");
  assert.equal(riskParity.policyReplay.livePathSimulation, false);
  assert.deepEqual(riskParity.policyReplay.crossRuntimeParity, {
    status: "PASS",
    nodeResultSha256: "4a7d08bc440fb7cd4ffa5624899479014181601751840b326467244489497dbd",
    kotlinResultSha256: "bf2554937963ae0b4abe1dc1829833b4bfa78c61736971a88c5dde9a201923a8",
    numericTolerance: 1e-8,
    commandCount: 165,
    runCount: 9,
    tradeCount: 27,
  });
  assert.deepEqual(riskParity.policyReplay.canonical, {
    startingEquityUsdt: 660,
    costMultiplier: 1,
    endingEquityUsdt: 574.39010661,
    netReturnPct: -12.97119597,
    compoundDailyReturnPct: -0.00600375,
    maximumConservativeIntrabarDrawdownPct: 15.8881403,
    closedTradeCount: 3,
    blockedEntryCount: 162,
    blockedEntryReasonCounts: {
      CONSECUTIVE_LOSS_LIMIT_REACHED: 161,
      DAILY_EQUITY_LOSS_LIMIT_REACHED: 1,
    },
    firstBlockedEntry: {
      executionAt: "2020-04-06T16:00:00.000Z",
      side: 1,
      equityUsdt: 608.61493309,
      dayStartEquityUsdt: 633.81434539,
      peakEquityUsdt: 675.84766172,
      consecutiveLosses: 1,
      reasonCodes: ["DAILY_EQUITY_LOSS_LIMIT_REACHED"],
    },
    maximumObservedConsecutiveLosses: 3,
    finalConsecutiveLosses: 3,
  });
  assert.equal(riskParity.policyReplay.stressMatrix.length, 9);
  assert.ok(riskParity.policyReplay.stressMatrix.every((run) => run.netReturnPct < 0));
  assert.ok(riskParity.policyReplay.stressMatrix.every((run) => run.closedTradeCount === 3));
  assert.ok(riskParity.policyReplay.stressMatrix.every((run) => run.blockedEntryCount === 162));
});

test("unresolved Live risk parity can never authorize execution", () => {
  assert.equal(riskParity.status, "FAIL");
  assert.equal(riskParity.audit.livePathSimulation, false);
  assert.equal(riskParity.audit.frozenPathReproducible, false);
  assert.equal(riskParity.decision.riskPolicyParityPassed, false);
  assert.equal(riskParity.decision.automaticExecutionAllowed, false);
  assert.equal(riskParity.decision.liveExecutionAllowed, false);
  assert.deepEqual(riskParity.decision.reasonCodes, riskParity.audit.reasonCodes);
  assert.ok(riskParity.decision.reasonCodes.length > 0);
});

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}
