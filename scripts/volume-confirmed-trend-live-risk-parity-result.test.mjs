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
  assert.equal(riskParity.schemaVersion, 2);
  assert.equal(sha256(riskParityBytes), "f5ce32043a0017199fa156b6ab6bc13e8a1a3a115c087b867677bc2fad592887");
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
    maximumDailyLossFraction: null,
    maximumAccountDrawdownFraction: 0.35,
    maximumConsecutiveLosses: null,
    riskStateMaximumAgeSeconds: 600,
    walletReconciliationMaximumAgeSeconds: 600,
    walletReconciliationConfirmedMismatchCount: 2,
  });
});

test("Live risk replay is cross-runtime deterministic and reproduces the frozen path", () => {
  assert.equal(riskParity.policyReplay.simulationKind, "H4_DECISION_BOUNDARY_RISK_POLICY_REPLAY");
  assert.equal(riskParity.policyReplay.livePathSimulation, false);
  assert.deepEqual(riskParity.policyReplay.crossRuntimeParity, {
    status: "PASS",
    nodeResultSha256: "cb9ab9301b4a04b600162e12af48b4ee213a443c251bcc22dc98d5fbcbb1a1f5",
    kotlinResultSha256: "28b74d309fd094e389ecef44677c450204ec44b8980437060cbbed03c0e36a83",
    numericTolerance: 1e-8,
    commandCount: 165,
    runCount: 9,
    tradeCount: 1485,
  });
  assert.deepEqual(riskParity.policyReplay.canonical, {
    startingEquityUsdt: 660,
    costMultiplier: 1,
    endingEquityUsdt: 3605.34525093,
    netReturnPct: 446.26443196,
    compoundDailyReturnPct: 0.07340346,
    maximumConservativeIntrabarDrawdownPct: 30.58189901,
    closedTradeCount: 165,
    blockedEntryCount: 0,
    blockedEntryReasonCounts: {},
    firstBlockedEntry: null,
    maximumObservedConsecutiveLosses: 11,
    finalConsecutiveLosses: 0,
  });
  assert.equal(riskParity.policyReplay.stressMatrix.length, 9);
  assert.ok(riskParity.policyReplay.stressMatrix.every((run) => run.netReturnPct > 0));
  assert.ok(riskParity.policyReplay.stressMatrix.every((run) => run.closedTradeCount === 165));
  assert.ok(riskParity.policyReplay.stressMatrix.every((run) => run.blockedEntryCount === 0));
});

test("passing Live risk parity still cannot authorize execution", () => {
  assert.equal(riskParity.status, "PASS");
  assert.equal(riskParity.audit.livePathSimulation, false);
  assert.equal(riskParity.audit.frozenPathReproducible, true);
  assert.equal(riskParity.audit.maximumObservedAccountDrawdownPct, 30.58189901);
  assert.equal(riskParity.audit.firstAccountDrawdownBreach, null);
  assert.equal(riskParity.decision.riskPolicyParityPassed, true);
  assert.equal(riskParity.decision.automaticExecutionAllowed, false);
  assert.equal(riskParity.decision.liveExecutionAllowed, false);
  assert.deepEqual(riskParity.decision.reasonCodes, riskParity.audit.reasonCodes);
  assert.deepEqual(riskParity.decision.reasonCodes, []);
});

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}
