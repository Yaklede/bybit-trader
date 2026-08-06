import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const result = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-structure-development-v2-result.json"), "utf8"),
);

test("v2 rejects both families and preserves the reserved seal", () => {
  assert.equal(result.status, "REJECTED");
  assert.equal(result.reservedSealedWindowOpened, false);
  assert.equal(result.automaticExecutionAllowed, false);
  assert.ok(result.families.every((family) => family.status.startsWith("REJECTED")));
});

test("retest continuation is rejected on a wholly negative interval", () => {
  const family = result.families.find((item) => item.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION");
  assert.ok(family.bestDiagnosticCandidate.bootstrapUpperMeanNetR < 0);
  assert.ok(family.bestDiagnosticCandidate.tradeCount >= 100);
});

test("clustered reversal remains insufficient despite four positive diagnostic folds", () => {
  const family = result.families.find((item) => item.family === "CLUSTERED_VOLUME_EXHAUSTION_REVERSAL");
  assert.equal(family.bestDiagnosticCandidate.positiveValidationFolds, 4);
  assert.ok(family.bestDiagnosticCandidate.meanNetR > 0);
  assert.ok(family.bestDiagnosticCandidate.bootstrapLowerMeanNetR < 0);
  assert.ok(family.bestDiagnosticCandidate.longNetR > 0);
  assert.ok(family.bestDiagnosticCandidate.shortNetR < 0);
});
