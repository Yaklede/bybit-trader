import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";
import {
  buildRuntimeRequest,
  evaluateRuntimeProtocol,
  evaluateRuntimeWindow,
  parseArgs,
  verifyContract,
} from "./aggressive-runtime-replay-audit.mjs";
import { verifyProtocol } from "./volume-flow-sealed-evaluate.mjs";

const protocolPath = new URL("../config/volume-flow-sealed-windows-v1.json", import.meta.url);
const contractPath = new URL("../config/aggressive-runtime-replay-contract-v1.json", import.meta.url);

test("runtime replay contract identifies the actual aggressive profile and its execution limits", async () => {
  const contract = verifyContract(JSON.parse(await fs.readFile(contractPath, "utf8")));

  assert.equal(contract.runtimeProfile.profileId, "absa_final_us_v1");
  assert.equal(contract.runtimeProfile.maxNotional, 100);
  assert.equal(contract.gates.requiredValidationStatus, "VERIFIED");
});

test("runtime audit requires a verified profile, causal fill, return, risk, and coverage gates", () => {
  const contract = {
    runtimeProfile: { profileId: "profile" },
    gates: {
      requiredFillModelVersion: "causal-m1-path-v2",
      requiredValidationStatus: "VERIFIED",
      minCompoundDailyReturnPct: 0.8,
      maxDrawdownPct: 40,
      maxLiquidationCount: 0,
      minTradeCount: 3,
      minActiveDayCoveragePct: 2,
    },
  };
  const window = { id: "S01", durationMonths: 1, replayStartAt: "2024-01-01T00:00:00Z", replayEndAt: "2024-02-01T00:00:00Z" };
  const response = {
    profileId: "profile",
    fillModelVersion: "causal-m1-path-v2",
    validationStatus: "VERIFIED",
    startAt: window.replayStartAt,
    endAt: window.replayEndAt,
    compoundDailyReturnPct: 0.9,
    maxDrawdownPct: 20,
    liquidationCount: 0,
    tradeCount: 3,
    activeDayCoveragePct: 2,
    netReturnPct: 20,
    finalEquity: 120,
  };

  assert.equal(evaluateRuntimeWindow(window, { ok: true, response }, contract.gates, "profile").passed, true);
  const failed = evaluateRuntimeWindow(
    window,
    { ok: true, response: { ...response, validationStatus: "UNVERIFIED", liquidationCount: 1 } },
    contract.gates,
    "profile",
  );
  assert.deepEqual(failed.reasons, ["PROFILE_UNVERIFIED", "LIQUIDATION_DETECTED"]);
});

test("runtime audit continues after a request error and does not mutate execution settings", async () => {
  const protocol = verifyProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));
  const contract = verifyContract(JSON.parse(await fs.readFile(contractPath, "utf8")));
  const original = structuredClone(contract);
  const request = buildRuntimeRequest(contract, protocol.windows[0]);
  assert.equal(request.maxNotional, 100);
  assert.equal("profileId" in request, false);
  assert.equal("endpoint" in request, false);
  assert.deepEqual(contract, original);

  const report = await evaluateRuntimeProtocol(
    { ...protocol, windows: protocol.windows.slice(0, 2) },
    contract,
    async (requestBody) => {
      if (requestBody.replayStartAt === protocol.windows[0].replayStartAt) throw new Error("warmup unavailable");
      return {
        profileId: contract.runtimeProfile.profileId,
        fillModelVersion: "causal-m1-path-v2",
        validationStatus: "UNVERIFIED",
        startAt: requestBody.replayStartAt,
        endAt: requestBody.replayEndAt,
        compoundDailyReturnPct: 1,
        maxDrawdownPct: 1,
        liquidationCount: 0,
        tradeCount: 5,
        activeDayCoveragePct: 3,
      };
    },
  );

  assert.equal(report.failedWindowCount, 2);
  assert.match(report.results[0].reasons[0], /^REQUEST_FAILED:.*warmup unavailable/);
  assert.deepEqual(contract, original);
});

test("runtime audit accepts the shared evaluator options but rejects a composite strategy argument", () => {
  assert.equal(parseArgs(["--concurrency=2"]).concurrency, 2);
  assert.throws(() => parseArgs(["--strategy=config/volume-flow-composite-current.json"]));
});
