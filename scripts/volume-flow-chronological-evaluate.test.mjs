import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";
import {
  evaluateDevelopmentProtocol,
  verifyDevelopmentProtocol,
} from "./volume-flow-chronological-evaluate.mjs";

const protocolPath = new URL("../config/volume-flow-development-folds-v2.json", import.meta.url);

test("chronological development protocol has four ordered pre-2024 folds", async () => {
  const protocol = verifyDevelopmentProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));

  assert.equal(protocol.folds.length, 4);
  assert.equal(protocol.actualHash, protocol.foldsSha256);
  assert.equal(protocol.selectionPolicy.promotionAllowed, false);
});

test("chronological development protocol rejects a changed fold even when the fold list is still ordered", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  protocol.folds[0].replayEndAt = "2021-06-02T00:00:00Z";

  assert.throws(() => verifyDevelopmentProtocol(protocol), /hash mismatch/);
});

test("development evaluator uses fixed folds and never promotes a candidate", async () => {
  const protocol = verifyDevelopmentProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));
  const strategy = { symbol: "BTCUSDT", legs: [{ id: "candidate" }] };
  const report = await evaluateDevelopmentProtocol(
    protocol,
    strategy,
    async (request) => ({
      fillModelVersion: "causal-next-m1-open-v2",
      commonReplayWindow: { startAt: request.replayStartAt, endAt: request.replayEndAt },
      compoundDailyReturnPct: 0.9,
      maxDrawdownPct: 10,
      markToMarketMaxDrawdownPct: 10,
      liquidationCount: 0,
      tradeCount: 5,
      activeDayCoveragePct: 3,
      netReturnPct: 10,
      finalEquity: 110,
    }),
  );

  assert.equal(report.developmentOnly, true);
  assert.equal(report.passed, true);
  assert.equal(report.foldCount, 4);
});
