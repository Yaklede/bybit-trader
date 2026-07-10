import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import test from "node:test";
import {
  buildRequest,
  evaluateProtocol,
  evaluateWindow,
  parseArgs,
  verifyProtocol,
} from "./volume-flow-sealed-evaluate.mjs";

const protocolPath = new URL("../config/volume-flow-sealed-windows-v1.json", import.meta.url);

test("sealed protocol has a valid hash and the required 40 windows", async () => {
  const protocol = verifyProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));

  assert.equal(protocol.windows.length, 40);
  assert.equal(protocol.actualHash, protocol.windowsSha256);
  assert.equal(protocol.gates.minCompoundDailyReturnPct, 0.8);
});

test("sealed protocol rejects a modified random window", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  protocol.windows[0].replayEndAt = "2024-05-12T00:00:00Z";

  assert.throws(() => verifyProtocol(protocol), /hash mismatch/);
});

test("sealed protocol rejects a window outside the recorded source data even when its hash is updated", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  protocol.windows[0].replayStartAt = "2020-01-01T00:00:00Z";
  protocol.windowsSha256 = createHash(protocol.windows);

  assert.throws(() => verifyProtocol(protocol), /outside the sealed source-data range/);
});

test("sealed evaluator requires causal fill, return, risk, liquidation, and coverage gates", () => {
  const gates = {
    minCompoundDailyReturnPct: 0.8,
    maxMarkToMarketDrawdownPct: 40,
    maxLiquidationCount: 0,
    minTradeCount: 3,
    minActiveDayCoveragePct: 2,
    requiredFillModelVersion: "causal-next-m1-open-v2",
  };
  const window = { id: "S01", durationMonths: 1, replayStartAt: "2024-01-01T00:00:00Z", replayEndAt: "2024-02-01T00:00:00Z" };
  const response = {
    engineVersion: "2.0.0",
    fillModelVersion: "causal-next-m1-open-v2",
    commonReplayWindow: { startAt: "2024-01-01T00:00:00Z", endAt: "2024-02-01T00:00:00Z" },
    compoundDailyReturnPct: 0.9,
    maxDrawdownPct: 20,
    markToMarketMaxDrawdownPct: 30,
    liquidationCount: 0,
    tradeCount: 4,
    activeDayCoveragePct: 3,
    netReturnPct: 20,
    finalEquity: 120,
  };

  assert.equal(evaluateWindow(window, response, gates).passed, true);
  const failed = evaluateWindow(window, { ...response, liquidationCount: 1, activeDayCoveragePct: 1 }, gates);
  assert.deepEqual(failed.reasons, ["LIQUIDATION_DETECTED", "ACTIVE_DAY_COVERAGE_INSUFFICIENT"]);
});

test("sealed evaluator rejects an incomplete forward capture replay window", () => {
  const gates = {
    minCompoundDailyReturnPct: 0.8,
    maxMarkToMarketDrawdownPct: 40,
    maxLiquidationCount: 0,
    minTradeCount: 3,
    minActiveDayCoveragePct: 2,
    requiredFillModelVersion: "causal-next-m1-open-v2",
  };
  const window = { id: "S01", durationMonths: 1, replayStartAt: "2024-01-01T00:00:00Z", replayEndAt: "2024-02-01T00:00:00Z" };
  const response = {
    engineVersion: "2.0.0",
    fillModelVersion: "causal-next-m1-open-v2",
    commonReplayWindow: { startAt: "2024-01-01T00:00:00Z", endAt: "2024-02-01T00:00:00Z" },
    forwardCaptureReplayCoverage: {
      orderBookRequired: true,
      takerFlowRequired: true,
      requestedM5BucketCount: 100,
      completeRequiredM5BucketCount: 99,
      requiredCoveragePct: 99,
    },
    compoundDailyReturnPct: 0.9,
    maxDrawdownPct: 20,
    markToMarketMaxDrawdownPct: 30,
    liquidationCount: 0,
    tradeCount: 4,
    activeDayCoveragePct: 3,
    netReturnPct: 20,
    finalEquity: 120,
  };

  const result = evaluateWindow(window, response, gates);
  assert.equal(result.passed, false);
  assert.deepEqual(result.reasons, ["FORWARD_CAPTURE_COVERAGE_INSUFFICIENT"]);
  assert.equal(result.forwardCaptureReplayCoverage.requiredCoveragePct, 99);
});

test("sealed evaluator rejects a malformed forward capture coverage report", () => {
  const gates = {
    minCompoundDailyReturnPct: 0.8,
    maxMarkToMarketDrawdownPct: 40,
    maxLiquidationCount: 0,
    minTradeCount: 3,
    minActiveDayCoveragePct: 2,
    requiredFillModelVersion: "causal-next-m1-open-v2",
  };
  const window = { id: "S01", durationMonths: 1, replayStartAt: "2024-01-01T00:00:00Z", replayEndAt: "2024-02-01T00:00:00Z" };
  const response = {
    engineVersion: "2.0.0",
    fillModelVersion: "causal-next-m1-open-v2",
    commonReplayWindow: { startAt: "2024-01-01T00:00:00Z", endAt: "2024-02-01T00:00:00Z" },
    forwardCaptureReplayCoverage: {
      orderBookRequired: true,
      takerFlowRequired: false,
      requestedM5BucketCount: 100,
      completeRequiredM5BucketCount: 99,
      requiredCoveragePct: 100,
    },
    compoundDailyReturnPct: 0.9,
    maxDrawdownPct: 20,
    markToMarketMaxDrawdownPct: 30,
    liquidationCount: 0,
    tradeCount: 4,
    activeDayCoveragePct: 3,
    netReturnPct: 20,
    finalEquity: 120,
  };

  assert.deepEqual(evaluateWindow(window, response, gates).reasons, ["FORWARD_CAPTURE_COVERAGE_INSUFFICIENT"]);
});

test("sealed evaluator does not mutate a strategy config and fails the whole protocol on one failed window", async () => {
  const protocol = verifyProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));
  const strategy = { symbol: "BTCUSDT", legs: [{ id: "fixed" }] };
  const original = structuredClone(strategy);
  const request = buildRequest(strategy, protocol.windows[0]);
  assert.deepEqual(strategy, original);
  assert.equal(request.m1Limit, 6_000_000);

  const report = await evaluateProtocol(
    { ...protocol, windows: protocol.windows.slice(0, 2) },
    strategy,
    async requestBody => ({
      engineVersion: "2.0.0",
      fillModelVersion: "causal-next-m1-open-v2",
      commonReplayWindow: { startAt: requestBody.replayStartAt, endAt: requestBody.replayEndAt },
      compoundDailyReturnPct: requestBody.replayStartAt === protocol.windows[0].replayStartAt ? 0.9 : 0.1,
      maxDrawdownPct: 10,
      markToMarketMaxDrawdownPct: 10,
      liquidationCount: 0,
      tradeCount: 5,
      activeDayCoveragePct: 3,
      netReturnPct: 10,
      finalEquity: 110,
    }),
  );

  assert.equal(report.passed, false);
  assert.equal(report.failedWindowCount, 1);
  assert.deepEqual(strategy, original);
});

test("parseArgs does not accept a bare argument or excessive parallelism", () => {
  assert.throws(() => parseArgs(["--api"]));
  assert.throws(() => parseArgs(["--concurrency=5"]));
});

function createHash(value) {
  return crypto.createHash("sha256").update(JSON.stringify(value)).digest("hex");
}
