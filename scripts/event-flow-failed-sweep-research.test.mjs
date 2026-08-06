import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { expandFailedSweepCandidates } from "./event-flow-failed-sweep-protocol.mjs";
import {
  evaluateFailedSweep,
  runFailedSweepReplay,
} from "./lib/event-flow-failed-sweep-research.mjs";
import { simulateEventCandidateBlock } from "./lib/event-flow-development-research.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const readJson = async (name) => JSON.parse(await fs.readFile(path.join(repositoryRoot, "config", name), "utf8"));
const protocol = await readJson("bybit-event-flow-failed-sweep-reversal-v1.json");
const primaryProtocol = await readJson("bybit-event-flow-development-v1.json");
const extensionProtocol = await readJson("bybit-event-flow-fixed-extension-v1.json");

test("failed sweep waits for opposite flow and a full setup-open rejection", () => {
  const candidate = expandFailedSweepCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const setup = signalRow({
    openedAtMs: start,
    closeTimeMs: start + 60_000,
    takerDirection: "BUY",
    m15Regime: "SELL",
    openTradePrice: 100,
    closeTradePrice: 101,
    directionalPriceImpactAtr: 0.5,
    directionalCloseLocation: 0.9,
  });
  const confirmation = signalRow({
    openedAtMs: start + 60_000,
    closeTimeMs: start + 120_000,
    takerDirection: "SELL",
    takerImbalance: -0.3,
    relativeTakerNotional: 1.2,
    endTop5Imbalance: -0.2,
    meanMicropriceEdgeBps: -0.1,
    closeTradePrice: 99.9,
    m15Regime: "SELL",
  });
  const entry = signalRow({
    openedAtMs: start + 120_000,
    closeTimeMs: start + 180_000,
    open: 99.9,
    high: 100,
    low: 99.7,
    close: 99.8,
    takerDirection: "NEUTRAL",
    takerImbalance: 0,
  });
  const result = simulateEventCandidateBlock(candidate, {
    id: "T01",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:03:00Z",
    rows: [setup, confirmation, entry],
  }, protocol.executionContract);
  assert.equal(result.trades.length, 1);
  assert.equal(result.trades[0].side, "SELL");
  assert.equal(result.trades[0].signalAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].openedAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].directionalPriceImpactAtr, 0.5);
});

test("failed sweep does not enter before price crosses the setup trade open", () => {
  const candidate = expandFailedSweepCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const rows = [
    signalRow({
      openedAtMs: start,
      m15Regime: "SELL",
      openTradePrice: 100,
      closeTradePrice: 101,
      directionalPriceImpactAtr: 0.5,
      directionalCloseLocation: 0.9,
    }),
    signalRow({
      openedAtMs: start + 60_000,
      takerDirection: "SELL",
      takerImbalance: -0.3,
      relativeTakerNotional: 1.2,
      endTop5Imbalance: -0.2,
      meanMicropriceEdgeBps: -0.1,
      closeTradePrice: 100.1,
      m15Regime: "SELL",
    }),
    signalRow({ openedAtMs: start + 120_000, takerDirection: "NEUTRAL", takerImbalance: 0 }),
  ];
  const result = simulateEventCandidateBlock(candidate, {
    id: "T02",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:03:00Z",
    rows,
  }, protocol.executionContract);
  assert.equal(result.trades.length, 0);
});

test("failed sweep selects once on 2023 and never reselects from 2024", () => {
  const candidates = expandFailedSweepCandidates(protocol);
  const selectedId = candidates[0].id;
  const validationOnlyId = candidates[1].id;
  const replay = {
    candidateCount: 16,
    candidates: candidates.map((candidate) => {
      let trades = [];
      if (candidate.id === selectedId) {
        trades = [
          ...Array.from({ length: 16 }, (_, index) => syntheticTrade("D01", index, 0.4, index)),
          ...Array.from({ length: 6 }, (_, index) => syntheticTrade("D07", 100 + index, 0.3, index)),
          ...Array.from({ length: 6 }, (_, index) => syntheticTrade("D10", 200 + index, 0.3, index)),
        ];
      } else if (candidate.id === validationOnlyId) {
        trades = [
          ...Array.from({ length: 6 }, (_, index) => syntheticTrade("D07", 300 + index, 1, index)),
          ...Array.from({ length: 6 }, (_, index) => syntheticTrade("D10", 400 + index, 1, index)),
        ];
      }
      return { id: candidate.id, family: candidate.family, candidate, trades };
    }),
  };
  const result = evaluateFailedSweep(
    replay,
    protocolBlocks(),
    protocol,
    primaryProtocol,
    extensionProtocol,
  );
  assert.equal(result.selectedCandidateId, selectedId);
  assert.equal(result.status, "CANDIDATE_FREEZE_REQUIRED");
  assert.equal(result.pooledValidation.tradeCount, 12);
  assert.equal(result.longTrades, 6);
  assert.equal(result.shortTrades, 6);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

test("failed-sweep replay cannot authorize execution", () => {
  const replay = runFailedSweepReplay({
    blocks: [],
    protocol,
    primaryProtocol,
    extensionProtocol,
  });
  assert.equal(replay.candidateCount, 16);
  assert.equal(replay.automaticExecutionAllowed, false);
  assert.equal(replay.liveExecutionAllowed, false);
});

function protocolBlocks() {
  return [...primaryProtocol.stages.development.primaryBlocks, ...extensionProtocol.blocks];
}

function signalRow(overrides = {}) {
  const openedAtMs = overrides.openedAtMs ?? Date.parse("2024-01-02T00:00:00Z");
  return {
    openedAt: new Date(openedAtMs).toISOString(),
    openedAtMs,
    closeTimeMs: openedAtMs + 60_000,
    open: 100,
    high: 101.5,
    low: 99.5,
    close: 101,
    atr: 0.2,
    takerDirection: "BUY",
    takerImbalance: 0.5,
    relativeTakerNotional: 4,
    alignedEndTop5Imbalance: 0.2,
    alignedMicropriceEdgeBps: 0.1,
    directionalPriceImpactBps: 100,
    directionalPriceImpactAtr: 0.5,
    directionalCloseLocation: 0.9,
    opposingSideDepletion: 0.5,
    consumedSideReplenishment: -0.5,
    m15Regime: "SELL",
    endTop5Imbalance: 0.2,
    meanMicropriceEdgeBps: 0.1,
    openTradePrice: 100,
    closeTradePrice: 101,
    highTradePrice: 101.1,
    lowTradePrice: 99.9,
    ...overrides,
  };
}

function syntheticTrade(blockId, index, netR, sideIndex) {
  return {
    blockId,
    closedAtMs: Date.parse("2024-01-01T00:00:00Z") + index * 60_000,
    netR,
    maeR: -0.1,
    side: sideIndex % 2 === 0 ? "BUY" : "SELL",
    exitReason: "TARGET",
  };
}
