import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/volume-flow-development-folds-v3.json", import.meta.url);

test("0.2 percent development protocol is immutable and cannot promote a strategy", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const actualHash = createHash("sha256").update(JSON.stringify(protocol.folds)).digest("hex");

  assert.equal(protocol.status, "DEVELOPMENT");
  assert.equal(protocol.selectionPolicy.parameterSelectionAllowed, true);
  assert.equal(protocol.selectionPolicy.promotionAllowed, false);
  assert.equal(protocol.gates.minCompoundDailyReturnPct, 0.2);
  assert.equal(protocol.gates.maxDrawdownPct, 40);
  assert.equal(protocol.gates.requiredFillModelVersion, "causal-m1-path-v2");
  assert.equal(protocol.folds.length, 4);
  assert.equal(actualHash, protocol.foldsSha256);
  assert.equal(protocol.folds.at(-1).replayEndAt, protocol.sourceData.developmentDataEndAt);
});
