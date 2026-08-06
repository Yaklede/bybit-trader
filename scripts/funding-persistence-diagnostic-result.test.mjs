import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(await readFile(resolve(
  repositoryRoot,
  "config/bybit-funding-persistence-diagnostic-result-v1.json",
)));

test("funding persistence result binds the frozen diagnostic", async () => {
  const protocolBytes = await readFile(resolve(repositoryRoot, result.protocol.path));
  assert.equal(result.programStatus, "CLOSED_NO_FUNDING_PERSISTENCE_FOR_CARRY_V4");
  assert.equal(result.protocol.sha256, sha256(protocolBytes));
  assert.equal(result.gate.passed, false);
  assert.deepEqual(result.gate.eligibleSymbols, []);
});

test("historical persistence cannot substitute for 2026 diagnostic evidence", () => {
  for (const symbol of Object.values(result.symbols)) {
    assert.equal(symbol.anchoredPearsonCorrelation > 0.15, true);
    assert.equal(symbol.developmentCostRecoveryRate, 1);
    assert.equal(symbol.diagnosticHighPersistenceObservationCount, 0);
    assert.equal(symbol.diagnosticCostRecoveryRate, 0);
  }
  assert.equal(result.decision.carryV4ResearchAllowed, false);
  assert.equal(result.decision.riskFilterMayBePresentedAsProfitEvidence, false);
});

test("closed carry research keeps execution disabled", () => {
  assert.equal(result.decision.makerShadowResearchIsNext, true);
  assert.equal(result.evidenceBoundary.future2026AfterH1Read, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
