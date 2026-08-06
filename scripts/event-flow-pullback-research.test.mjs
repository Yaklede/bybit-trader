import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { expandPullbackCandidates } from "./event-flow-pullback-protocol.mjs";
import {
  evaluatePullback,
  runPullbackReplay,
} from "./lib/event-flow-pullback-research.mjs";
import { simulateEventCandidateBlock } from "./lib/event-flow-development-research.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const readJson = async (name) => JSON.parse(await fs.readFile(path.join(repositoryRoot, "config", name), "utf8"));
const protocol = await readJson("bybit-event-flow-pullback-reacceleration-v1.json");
const primaryProtocol = await readJson("bybit-event-flow-development-v1.json");
const extensionProtocol = await readJson("bybit-event-flow-fixed-extension-v1.json");

test("pullback strategy requires shock, later pullback, later reacceleration, then next-minute fill", () => {
  const candidate = expandPullbackCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const rows = [
    signalRow({ openedAtMs: start, openTradePrice: 100, closeTradePrice: 101 }),
    signalRow({
      openedAtMs: start + 60_000,
      takerDirection: "SELL",
      takerImbalance: -0.2,
      closeTradePrice: 100.7,
    }),
    signalRow({
      openedAtMs: start + 120_000,
      takerDirection: "BUY",
      takerImbalance: 0.2,
      relativeTakerNotional: 1.2,
      endTop5Imbalance: 0.2,
      meanMicropriceEdgeBps: 0.1,
      closeTradePrice: 101.1,
    }),
    signalRow({
      openedAtMs: start + 180_000,
      open: 101.1,
      high: 101.2,
      low: 101,
      close: 101.1,
      takerDirection: "NEUTRAL",
      takerImbalance: 0,
    }),
  ];
  const result = simulateEventCandidateBlock(candidate, {
    id: "T01",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:04:00Z",
    rows,
  }, protocol.executionContract);
  assert.equal(result.trades.length, 1);
  assert.equal(result.trades[0].side, "BUY");
  assert.equal(result.trades[0].pullbackObservedAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].observedPullbackFraction, 0.3);
  assert.equal(result.trades[0].signalAt, "2024-01-02T00:03:00.000Z");
  assert.equal(result.trades[0].openedAt, "2024-01-02T00:03:00.000Z");
});

test("pullback strategy rejects reacceleration without an earlier pullback", () => {
  const candidate = expandPullbackCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const rows = [
    signalRow({ openedAtMs: start, openTradePrice: 100, closeTradePrice: 101 }),
    signalRow({ openedAtMs: start + 60_000, closeTradePrice: 101.1 }),
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

test("pullback strategy invalidates when price crosses the setup trade open", () => {
  const candidate = expandPullbackCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const rows = [
    signalRow({ openedAtMs: start, openTradePrice: 100, closeTradePrice: 101 }),
    signalRow({
      openedAtMs: start + 60_000,
      takerDirection: "SELL",
      takerImbalance: -0.2,
      closeTradePrice: 99.9,
    }),
    signalRow({ openedAtMs: start + 120_000, closeTradePrice: 101.2 }),
    signalRow({ openedAtMs: start + 180_000, takerDirection: "NEUTRAL", takerImbalance: 0 }),
  ];
  const result = simulateEventCandidateBlock(candidate, {
    id: "T03",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:04:00Z",
    rows,
  }, protocol.executionContract);
  assert.equal(result.trades.length, 0);
});

test("pullback evaluator selects on 2023 only and preserves the right-tail candidate", () => {
  const candidates = expandPullbackCandidates(protocol);
  const selectedId = candidates[0].id;
  const validationOnlyId = candidates[1].id;
  const replay = {
    candidateCount: 18,
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
  const result = evaluatePullback(
    replay,
    protocolBlocks(),
    protocol,
    primaryProtocol,
    extensionProtocol,
  );
  assert.equal(result.selectedCandidateId, selectedId);
  assert.equal(result.status, "CANDIDATE_FREEZE_REQUIRED");
  assert.equal(result.freezeRecommendation.targetR, 3.5);
  assert.equal(result.pooledValidation.tradeCount, 12);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

test("pullback replay cannot authorize execution", () => {
  const replay = runPullbackReplay({
    blocks: [],
    protocol,
    primaryProtocol,
    extensionProtocol,
  });
  assert.equal(replay.candidateCount, 18);
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
    directionalPriceImpactAtr: 5,
    directionalCloseLocation: 0.9,
    opposingSideDepletion: 0.5,
    consumedSideReplenishment: -0.5,
    m15Regime: "BUY",
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
