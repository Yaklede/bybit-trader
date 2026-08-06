import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { expandAcceptedContinuationCandidates } from "./event-flow-accepted-continuation-protocol.mjs";
import {
  evaluateAcceptedContinuation,
  runAcceptedContinuationReplay,
} from "./lib/event-flow-accepted-continuation-research.mjs";
import { simulateEventCandidateBlock } from "./lib/event-flow-development-research.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const readJson = async (name) => JSON.parse(await fs.readFile(path.join(repositoryRoot, "config", name), "utf8"));
const protocol = await readJson("bybit-event-flow-accepted-continuation-v1.json");
const primaryProtocol = await readJson("bybit-event-flow-development-v1.json");
const extensionProtocol = await readJson("bybit-event-flow-fixed-extension-v1.json");

test("accepted continuation confirms after the setup and fills at the next contiguous minute", () => {
  const candidate = expandAcceptedContinuationCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const setup = signalRow({
    openedAtMs: start,
    closeTimeMs: start + 60_000,
    highTradePrice: 100.2,
    lowTradePrice: 99.8,
  });
  const confirmation = signalRow({
    openedAtMs: start + 60_000,
    closeTimeMs: start + 120_000,
    closeTradePrice: 100.3,
    highTradePrice: 100.35,
    lowTradePrice: 100,
    takerImbalance: 0.3,
    relativeTakerNotional: 1.2,
    endTop5Imbalance: 0.2,
    meanMicropriceEdgeBps: 0.1,
  });
  const entry = signalRow({
    openedAtMs: start + 120_000,
    closeTimeMs: start + 180_000,
    open: 100.3,
    high: 100.4,
    low: 100.1,
    close: 100.2,
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
  assert.equal(result.trades[0].side, "BUY");
  assert.equal(result.trades[0].signalAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].openedAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].confirmationTakerImbalance, 0.3);
});

test("accepted continuation does not enter when price fails to clear the setup extreme", () => {
  const candidate = expandAcceptedContinuationCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const rows = [
    signalRow({ openedAtMs: start, highTradePrice: 100.2, lowTradePrice: 99.8 }),
    signalRow({
      openedAtMs: start + 60_000,
      closeTradePrice: 100.1,
      takerImbalance: 0.3,
      relativeTakerNotional: 1.2,
      endTop5Imbalance: 0.2,
      meanMicropriceEdgeBps: 0.1,
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

test("accepted continuation selects once on 2023 and applies the frozen choice to 2024", () => {
  const candidates = expandAcceptedContinuationCandidates(protocol);
  const selectedId = candidates[0].id;
  const validationOnlyId = candidates[1].id;
  const blocks = protocolBlocks();
  const replay = {
    candidateCount: 16,
    candidates: candidates.map((candidate) => {
      let trades = [];
      if (candidate.id === selectedId) {
        trades = [
          ...Array.from({ length: 20 }, (_, index) => syntheticTrade("D01", index, 0.4, index)),
          ...Array.from({ length: 8 }, (_, index) => syntheticTrade("D07", 100 + index, 0.3, index)),
          ...Array.from({ length: 8 }, (_, index) => syntheticTrade("D10", 200 + index, 0.3, index)),
        ];
      } else if (candidate.id === validationOnlyId) {
        trades = [
          ...Array.from({ length: 8 }, (_, index) => syntheticTrade("D07", 300 + index, 1, index)),
          ...Array.from({ length: 8 }, (_, index) => syntheticTrade("D10", 400 + index, 1, index)),
        ];
      }
      return { id: candidate.id, family: candidate.family, candidate, trades };
    }),
  };
  const result = evaluateAcceptedContinuation(
    replay,
    blocks,
    protocol,
    primaryProtocol,
    extensionProtocol,
  );
  assert.equal(result.selectedCandidateId, selectedId);
  assert.equal(result.status, "CANDIDATE_FREEZE_REQUIRED");
  assert.equal(result.pooledValidation.tradeCount, 16);
  assert.equal(result.longTrades, 8);
  assert.equal(result.shortTrades, 8);
  assert.equal(result.validationDataAcquisitionAllowed, false);
  assert.equal(result.liveExecutionAllowed, false);
});

test("accepted continuation replay always keeps execution disabled", () => {
  const replay = runAcceptedContinuationReplay({
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
  return [
    ...primaryProtocol.stages.development.primaryBlocks,
    ...extensionProtocol.blocks,
  ];
}

function signalRow(overrides = {}) {
  const openedAtMs = overrides.openedAtMs ?? Date.parse("2024-01-02T00:00:00Z");
  return {
    openedAt: new Date(openedAtMs).toISOString(),
    openedAtMs,
    closeTimeMs: openedAtMs + 60_000,
    open: 100,
    high: 100.5,
    low: 99.5,
    close: 100,
    atr: 1,
    takerDirection: "BUY",
    takerImbalance: 0.5,
    relativeTakerNotional: 4,
    alignedEndTop5Imbalance: 0.2,
    alignedMicropriceEdgeBps: 0.1,
    directionalPriceImpactBps: 4,
    opposingSideDepletion: 0.5,
    consumedSideReplenishment: -0.5,
    m15Regime: "BUY",
    endTop5Imbalance: 0.2,
    meanMicropriceEdgeBps: 0.1,
    closeTradePrice: 100.1,
    highTradePrice: 100.2,
    lowTradePrice: 99.8,
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
