import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-impact-state-development-result-v1.json"), "utf8"),
);
const registry = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/research-sealed-registry-v1.json"), "utf8"),
);

test("failed volume-impact development cannot promote or consume the fresh seal", () => {
  assert.equal(result.status, "REJECTED");
  assert.equal(result.automaticExecutionAllowed, false);
  assert.equal(result.reservedSealedWindowOpened, false);
  assert.ok(result.families.every((family) => family.status.startsWith("REJECTED")));
  const reserved = registry.protocols.find((protocol) => protocol.protocolId === result.reservedSealedProtocolId);
  assert.equal(reserved.status, "AVAILABLE");
});

test("continuation is rejected on a wholly negative bootstrap interval", () => {
  const continuation = result.families.find((family) => family.family === "VOLUME_IMPACT_CONTINUATION");
  assert.ok(continuation.bestDiagnosticCandidate.bootstrapUpperMeanNetR < 0);
  assert.ok(continuation.bestDiagnosticCandidate.tradeCount >= 600);
});

test("positive reversal diagnostic remains rejected when its bootstrap crosses zero", () => {
  const reversal = result.families.find((family) => family.family === "VOLUME_EXHAUSTION_REVERSAL");
  assert.ok(reversal.bestDiagnosticCandidate.meanNetR > 0);
  assert.ok(reversal.bestDiagnosticCandidate.bootstrapLowerMeanNetR < 0);
  assert.ok(reversal.bestDiagnosticCandidate.bootstrapUpperMeanNetR > 0);
  assert.equal(reversal.status, "REJECTED_INSUFFICIENT_EVIDENCE");
});
