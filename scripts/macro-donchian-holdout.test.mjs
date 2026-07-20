import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

import { verifyContract } from "./aggressive-runtime-replay-audit.mjs";
import { verifyProtocol } from "./volume-flow-sealed-evaluate.mjs";

const protocolPath = new URL("../config/macro-donchian-sealed-windows-v1.json", import.meta.url);
const contractPath = new URL("../config/aggressive-macro-donchian-candidate-v1.json", import.meta.url);

test("macro Donchian candidate is frozen before its post-2024 sealed protocol", async () => {
  const protocol = verifyProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));
  const contract = verifyContract(JSON.parse(await fs.readFile(contractPath, "utf8")));

  assert.equal(protocol.generation.tuningAllowed, false);
  assert.equal(protocol.generation.candidateContract, "config/aggressive-macro-donchian-candidate-v1.json");
  assert.equal(protocol.windows.length, 40);
  assert.equal(protocol.windows.every((window) => Date.parse(window.replayStartAt) >= Date.parse("2024-01-01T00:00:00Z")), true);
  assert.equal(contract.runtimeProfile.signalMode, "MACRO_DONCHIAN");
  assert.equal(contract.runtimeProfile.riskFraction, 0.055);
  assert.equal(contract.gates.requiredRuntimeSignalProfileMatched, false);
  assert.equal(contract.gates.minCompoundDailyReturnPct, 0.2);
});
