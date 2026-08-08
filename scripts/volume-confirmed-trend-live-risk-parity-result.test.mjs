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
  assert.equal(sha256(riskParityBytes), "3073eadc11f7fd05eb351897185879b6735276a420c11e55fd00d5f56c7fbe2f");
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
