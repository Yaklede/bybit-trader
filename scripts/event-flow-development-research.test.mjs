import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  buildEventCandidates,
  detectEventSignal,
  evaluateEventDevelopment,
  prepareEventBlock,
  runEventDevelopmentReplay,
  simulateEventCandidateBlock,
} from "./lib/event-flow-development-research.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-v1.json"),
  "utf8",
));
const analysisContract = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-analysis-v1.json"),
  "utf8",
));
const analysisContractV2 = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-analysis-v2.json"),
  "utf8",
));
const analysisContractV3 = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-development-analysis-v3.json"),
  "utf8",
));

test("frozen event-flow candidate IDs expand to exactly 32 unique trials", () => {
  const candidates = buildEventCandidates(protocol);
  assert.equal(candidates.length, 32);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 32);
  assert.match(candidates[0].id, /^edc_/);
  assert.match(candidates.at(-1).id, /^ear_/);
});

test("causal features exclude the current minute and never attach an unclosed M15 bar", () => {
  const m1Rows = Array.from({ length: 800 }, (_, index) => m1Row(index, index === 60 ? 1_000 : 100));
  m1Rows[60].high = 150;
  const m15Rows = Array.from({ length: 54 }, (_, index) => m15Row(index, index === 50 ? 50 : 100 + index));
  const block = prepareEventBlock({
    id: "T01",
    era: "TEST",
    sourceStartDate: "2024-01-01",
    sourceEndDate: "2024-01-01",
    replayStartAt: "2024-01-01T00:00:00Z",
    replayEndAt: "2024-01-02T00:00:00Z",
    m1Rows,
    m15Rows,
  });
  assert.equal(block.rows[60].relativeTakerNotional, 10);
  assert.equal(block.rows[60].atr, 2);
  assert.equal(block.rows[749].m15SourceCloseTimeMs, Date.parse("2024-01-01T12:30:00Z"));
  assert.equal(block.rows[749].m15Regime, "BUY");
  assert.ok(block.rows[749].m15SourceCloseTimeMs <= block.rows[749].closeTimeMs);
});

test("depletion continuation and absorption reversal use opposite frozen order-flow hypotheses", () => {
  const [continuation] = buildEventCandidates(protocol);
  const reversal = buildEventCandidates(protocol).find((candidate) => candidate.family === "EVENT_ABSORPTION_REVERSAL");
  const base = signalRow({
    takerDirection: "BUY",
    takerImbalance: 0.5,
    relativeTakerNotional: 4,
    alignedEndTop5Imbalance: 0.2,
    alignedMicropriceEdgeBps: 0.1,
    opposingSideDepletion: 0.5,
    consumedSideReplenishment: -0.5,
    directionalPriceImpactBps: 1,
    m15Regime: "BUY",
  });
  assert.equal(detectEventSignal(continuation, base).orderSide, "BUY");
  assert.equal(detectEventSignal(reversal, {
    ...base,
    alignedEndTop5Imbalance: -0.2,
    opposingSideDepletion: -0.5,
    consumedSideReplenishment: 0.5,
    m15Regime: "SELL",
  }).orderSide, "SELL");
});

test("signal fills only at the next M1 open and a same-minute stop wins over target", () => {
  const candidate = buildEventCandidates(protocol)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const signal = signalRow({ openedAtMs: start, closeTimeMs: start + 60_000 });
  const conflict = signalRow({
    openedAtMs: start + 60_000,
    closeTimeMs: start + 120_000,
    open: 100,
    high: 104,
    low: 98,
    close: 101,
    takerDirection: "NEUTRAL",
    takerImbalance: 0,
  });
  const result = simulateEventCandidateBlock(candidate, {
    id: "T02",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:02:00Z",
    rows: [signal, conflict],
  }, protocol.executionContract);
  assert.equal(result.trades.length, 1);
  assert.equal(result.trades[0].openedAt, "2024-01-02T00:01:00.000Z");
  assert.equal(result.trades[0].exitReason, "STOP");
  assert.ok(result.trades[0].netR < 0);
});

test("v2 widens a sub-floor stop to 0.4 percent and resizes instead of forcing the v1 raw ATR risk", () => {
  const v1Candidate = buildEventCandidates(protocol, analysisContract)[0];
  const v2Candidate = buildEventCandidates(protocol, analysisContractV2)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const signal = signalRow({ openedAtMs: start, closeTimeMs: start + 60_000, atr: 0.01 });
  const next = signalRow({
    openedAtMs: start + 60_000,
    closeTimeMs: start + 120_000,
    open: 100,
    high: 101,
    low: 99,
    close: 100,
    takerDirection: "NEUTRAL",
    takerImbalance: 0,
  });
  const block = {
    id: "T03",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:02:00Z",
    rows: [signal, next],
  };
  assert.equal(simulateEventCandidateBlock(v1Candidate, block, protocol.executionContract).trades.length, 0);
  const v2 = simulateEventCandidateBlock(v2Candidate, block, protocol.executionContract);
  assert.match(v2Candidate.id, /^sf04_/);
  assert.equal(v2.trades.length, 1);
  assert.equal(v2.trades[0].rawTriggerRiskPct < 0.004, true);
  assert.equal(v2.trades[0].effectiveTriggerRiskPct, 0.004);
});

test("v3 expands 16 confirmed-reversal candidates with unique causal state-machine IDs", () => {
  const candidates = buildEventCandidates(protocol, analysisContractV3);
  assert.equal(candidates.length, 16);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 16);
  assert.equal(candidates.every((candidate) => candidate.family === "CONFIRMED_ABSORPTION_REVERSAL"), true);
  assert.match(candidates[0].id, /^car_/);
});

test("v3 arms on absorption, confirms on a later opposite flow minute, and fills one minute later", () => {
  const candidate = buildEventCandidates(protocol, analysisContractV3)[0];
  const start = Date.parse("2024-01-02T00:00:00Z");
  const setup = signalRow({
    openedAtMs: start,
    closeTimeMs: start + 60_000,
    closeTradePrice: 100,
    consumedSideReplenishment: 0.5,
    opposingSideDepletion: -0.5,
    alignedEndTop5Imbalance: -0.2,
    m15Regime: "SELL",
  });
  const confirmation = signalRow({
    openedAtMs: start + 60_000,
    closeTimeMs: start + 120_000,
    takerDirection: "SELL",
    takerImbalance: -0.3,
    relativeTakerNotional: 1.5,
    endTop5Imbalance: -0.2,
    closeTradePrice: 99.8,
    m15Regime: "SELL",
  });
  const entry = signalRow({
    openedAtMs: start + 120_000,
    closeTimeMs: start + 180_000,
    open: 99.8,
    high: 100,
    low: 99.5,
    close: 99.7,
    takerDirection: "NEUTRAL",
    takerImbalance: 0,
  });
  const result = simulateEventCandidateBlock(candidate, {
    id: "T04",
    era: "TEST",
    replayStartAt: "2024-01-02T00:00:00Z",
    replayEndAt: "2024-01-02T00:03:00Z",
    rows: [setup, confirmation, entry],
  }, protocol.executionContract);
  assert.equal(result.trades.length, 1);
  assert.equal(result.trades[0].side, "SELL");
  assert.equal(result.trades[0].signalAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].openedAt, "2024-01-02T00:02:00.000Z");
  assert.equal(result.trades[0].confirmationTakerImbalance, -0.3);
});

test("v3 selects once on 2023 and never reselects from better-looking 2024 outcomes", () => {
  const candidates = buildEventCandidates(protocol, analysisContractV3);
  const selectedId = candidates[0].id;
  const validationOnlyId = candidates[1].id;
  const replay = {
    candidateCount: candidates.length,
    candidates: candidates.map((candidate) => {
      let trades = [];
      if (candidate.id === selectedId) {
        trades = [
          ...Array.from({ length: 15 }, (_, index) => syntheticTrade("D01", index, 0.2)),
          ...Array.from({ length: 8 }, (_, index) => syntheticTrade("D07", 100 + index, -0.2)),
          ...Array.from({ length: 8 }, (_, index) => syntheticTrade("D10", 200 + index, -0.2)),
        ];
      } else if (candidate.id === validationOnlyId) {
        trades = [
          ...Array.from({ length: 20 }, (_, index) => syntheticTrade("D07", 300 + index, 0.5)),
          ...Array.from({ length: 20 }, (_, index) => syntheticTrade("D10", 400 + index, 0.5)),
        ];
      }
      return { id: candidate.id, family: candidate.family, trades };
    }),
  };
  const evaluation = evaluateEventDevelopment(replay, protocol, analysisContractV3);
  const report = evaluation.familyReports[0];
  assert.equal(report.selectedCandidateId, selectedId);
  assert.equal(report.eraValidations.every((era) => era.metrics.netReturnPct < 0), true);
  assert.equal(evaluation.status, "REJECTED");
  assert.equal(evaluation.freezeRecommendation, null);
  assert.equal(evaluation.validationDataAcquisitionAllowed, false);
});

test("an empty frozen replay rejects both families and cannot unlock later data", () => {
  const candidates = buildEventCandidates(protocol);
  const replay = {
    candidateCount: candidates.length,
    candidates: candidates.map((candidate) => ({ ...candidate, id: candidate.id, family: candidate.family, trades: [] })),
  };
  const evaluation = evaluateEventDevelopment(replay, protocol, analysisContract);
  assert.equal(evaluation.status, "REJECTED");
  assert.equal(evaluation.validationDataAcquisitionAllowed, false);
  assert.equal(evaluation.externalDataAcquisitionAllowed, false);
  assert.equal(evaluation.liveExecutionAllowed, false);
});

test("full replay never authorizes execution even before evaluation", () => {
  const candidates = buildEventCandidates(protocol);
  const replay = runEventDevelopmentReplay({ blocks: [], candidates, protocol, analysisContract });
  assert.equal(replay.candidateCount, 32);
  assert.equal(replay.automaticExecutionAllowed, false);
});

function m1Row(index, totalNotional) {
  const openedAtMs = Date.parse("2024-01-01T00:00:00Z") + index * 60_000;
  return {
    openedAt: instant(openedAtMs),
    openedAtMs,
    open: 100,
    high: 101,
    low: 99,
    close: 100,
    volume: 10,
    takerBuyNotional: totalNotional * 0.6,
    takerSellNotional: totalNotional * 0.4,
    tradeCount: 10,
    openTradePrice: 100,
    highTradePrice: 101,
    lowTradePrice: 99,
    closeTradePrice: 100.1,
    endTop5Imbalance: 0.2,
    meanMicropriceEdgeBps: 0.1,
    bidAddedTop5Notional: 10,
    bidRemovedTop5Notional: 20,
    askAddedTop5Notional: 10,
    askRemovedTop5Notional: 20,
  };
}

function m15Row(index, close) {
  const openedAtMs = Date.parse("2024-01-01T00:00:00Z") + index * 15 * 60_000;
  return {
    openedAt: instant(openedAtMs),
    openedAtMs,
    open: close,
    high: close + 1,
    low: close - 1,
    close,
    volume: 100,
  };
}

function signalRow(overrides = {}) {
  const openedAtMs = overrides.openedAtMs ?? Date.parse("2024-01-02T00:00:00Z");
  return {
    openedAt: instant(openedAtMs),
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
    directionalPriceImpactBps: 1,
    opposingSideDepletion: 0.5,
    consumedSideReplenishment: -0.5,
    m15Regime: "BUY",
    ...overrides,
  };
}

function syntheticTrade(blockId, index, netR) {
  return {
    blockId,
    closedAtMs: Date.parse("2024-01-01T00:00:00Z") + index * 60_000,
    netR,
    maeR: Math.min(netR, -0.1),
    exitReason: netR > 0 ? "TARGET" : "STOP",
  };
}

function instant(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}
