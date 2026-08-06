import { execFileSync, spawn } from "node:child_process";
import readline from "node:readline";

import { movingBlockBootstrap } from "./research-evidence.mjs";

export const MINUTE_MS = 60_000;
export const TIMEFRAME_MS = Object.freeze({ M1: MINUTE_MS, M5: 5 * MINUTE_MS, M15: 15 * MINUTE_MS });

export function validateDevelopmentProtocol(protocol) {
  if (protocol?.status !== "PREDECLARED_DEVELOPMENT") {
    throw new Error("Development protocol must be PREDECLARED_DEVELOPMENT.");
  }
  if (protocol.selectionPolicy?.dailyCompoundReturnIsSearchObjective !== false) {
    throw new Error("Compound-daily return cannot be a candidate search objective.");
  }
  if (protocol.selectionPolicy?.automaticPromotionAllowed !== false || protocol.outcomePolicy?.liveExecutionAllowed !== false) {
    throw new Error("Development research cannot allow automatic or live promotion.");
  }
  const start = Date.parse(protocol.sourceData?.developmentReplayStartsAt);
  const end = Date.parse(protocol.sourceData?.developmentReplayEndsAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) {
    throw new Error("Development replay bounds are invalid.");
  }
  if (end > Date.parse(protocol.contaminationDisclosure?.developmentSelectionEndsAt)) {
    throw new Error("Development replay cannot cross the predeclared selection boundary.");
  }
  const candidates = expandCandidates(protocol);
  if (candidates.length !== protocol.selectionPolicy.maximumStageCandidateCount) {
    throw new Error(`Candidate count mismatch: expected=${protocol.selectionPolicy.maximumStageCandidateCount} actual=${candidates.length}`);
  }
  return protocol;
}

export function expandCandidates(protocol) {
  const candidates = [];
  for (const hypothesis of protocol.hypotheses ?? []) {
    const gridEntries = Object.entries(hypothesis.grid ?? {});
    for (const parameters of cartesianGrid(gridEntries)) {
      const prefix = candidatePrefix(hypothesis.family);
      const lookback = parameters.m5BreakoutLookbackBars ?? parameters.m5FailedBreakoutLookbackBars;
      const volume = parameters.minimumM5RelativeVolume ?? parameters.minimumClusterRelativeVolume;
      const confirmation = parameters.m1ConfirmationWindowBars ?? parameters.m1RetestWindowBars;
      const id = hypothesis.family === "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL"
        ? [
          prefix,
          `lrv${numberId(parameters.minimumLongClusterRelativeVolume)}`,
          `smax${numberId(parameters.maximumShortClusterRelativeVolumeExclusive)}`,
          `cf${confirmation}`,
        ].join("_")
        : [
          prefix,
          `rv${numberId(volume)}`,
          `lb${lookback}`,
          `${hypothesis.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION" ? "rt" : "cf"}${confirmation}`,
        ].join("_");
      candidates.push({
        id,
        family: hypothesis.family,
        ...hypothesis.fixed,
        ...parameters,
      });
    }
  }
  if (new Set(candidates.map((candidate) => candidate.id)).size !== candidates.length) {
    throw new Error("Expanded candidate IDs must be unique.");
  }
  return candidates;
}

function candidatePrefix(family) {
  switch (family) {
    case "VOLUME_IMPACT_CONTINUATION": return "vic";
    case "VOLUME_EXHAUSTION_REVERSAL": return "ver";
    case "VOLUME_BREAKOUT_RETEST_CONTINUATION": return "vbr";
    case "CLUSTERED_VOLUME_EXHAUSTION_REVERSAL": return "cve";
    case "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL": return "acar";
    default: throw new Error(`Unsupported candidate family: ${family}`);
  }
}

function cartesianGrid(entries, index = 0, current = {}) {
  if (index >= entries.length) return [{ ...current }];
  const [key, values] = entries[index];
  if (!Array.isArray(values) || values.length === 0) throw new Error(`Candidate grid ${key} must be non-empty.`);
  return values.flatMap((value) => cartesianGrid(entries, index + 1, { ...current, [key]: value }));
}

function numberId(value) {
  return String(value).replace(".", "p");
}

export function loadCandlesFromSqlite({ dbPath, symbol = "BTCUSDT", timeframe, startAt, endAt }) {
  assertTimeframe(timeframe);
  const sql = candleSql({ symbol, timeframe, startAt, endAt });
  const output = execFileSync("sqlite3", ["-tabs", "-noheader", dbPath, sql], {
    encoding: "utf8",
    maxBuffer: 512 * 1024 * 1024,
  });
  return output.trim().split("\n").filter(Boolean).map((line) => parseCandleLine(line, timeframe));
}

export async function* streamCandlesFromSqlite({ dbPath, symbol = "BTCUSDT", timeframe = "M1", startAt, endAt }) {
  assertTimeframe(timeframe);
  const sql = candleSql({ symbol, timeframe, startAt, endAt });
  const child = spawn("sqlite3", ["-tabs", "-noheader", dbPath, sql], { stdio: ["ignore", "pipe", "pipe"] });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const completion = new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code) => resolve(code));
  });
  const lines = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
  for await (const line of lines) {
    if (line.length > 0) yield parseCandleLine(line, timeframe);
  }
  const code = await completion;
  if (code !== 0) throw new Error(`sqlite3 candle stream failed code=${code}: ${stderr.trim()}`);
}

function candleSql({ symbol, timeframe, startAt, endAt }) {
  if (!/^[A-Z0-9]+$/.test(symbol)) throw new Error(`Invalid symbol: ${symbol}`);
  const start = new Date(startAt).toISOString();
  const end = new Date(endAt).toISOString();
  return [
    "select opened_at, open, high, low, close, volume",
    "from marketCandles",
    `where symbol = '${symbol}'`,
    `and timeframe = '${timeframe}'`,
    `and opened_at >= '${start}'`,
    `and opened_at < '${end}'`,
    "order by opened_at;",
  ].join(" ");
}

function assertTimeframe(timeframe) {
  if (!(timeframe in TIMEFRAME_MS)) throw new Error(`Unsupported timeframe: ${timeframe}`);
}

function parseCandleLine(line, timeframe) {
  const [openedAt, open, high, low, close, volume] = line.split("\t");
  return normalizeCandle({ openedAt, open, high, low, close, volume }, TIMEFRAME_MS[timeframe]);
}

export function normalizeCandle(candle, durationMs) {
  const openedAtMs = candle.openedAtMs ?? Date.parse(candle.openedAt);
  const normalized = {
    openedAt: candle.openedAt ?? new Date(openedAtMs).toISOString(),
    openedAtMs,
    closeTimeMs: openedAtMs + durationMs,
    open: Number(candle.open),
    high: Number(candle.high),
    low: Number(candle.low),
    close: Number(candle.close),
    volume: Number(candle.volume),
  };
  if (
    !Number.isFinite(normalized.openedAtMs) ||
    [normalized.open, normalized.high, normalized.low, normalized.close, normalized.volume].some((value) => !Number.isFinite(value)) ||
    normalized.open <= 0 || normalized.high < normalized.low || normalized.volume < 0 ||
    normalized.high < Math.max(normalized.open, normalized.close) ||
    normalized.low > Math.min(normalized.open, normalized.close)
  ) {
    throw new Error(`Invalid candle at ${candle.openedAt ?? candle.openedAtMs}`);
  }
  return normalized;
}

export function prepareHigherTimeframeCandles(rawCandles, durationMs, { volumeLookback = 48, atrLookback = 20 } = {}) {
  const candles = rawCandles.map((candle) => normalizeCandle(candle, durationMs));
  const volumeQueue = [];
  const trueRangeQueue = [];
  let volumeSum = 0;
  let trueRangeSum = 0;
  let ema20 = null;
  let ema50 = null;
  for (let index = 0; index < candles.length; index += 1) {
    const candle = candles[index];
    if (index > 0 && candle.openedAtMs !== candles[index - 1].openedAtMs + durationMs) {
      throw new Error(
        `Higher-timeframe candles must be contiguous: previous=${candles[index - 1].openedAt} current=${candle.openedAt}`,
      );
    }
    const previousClose = index > 0 ? candles[index - 1].close : candle.close;
    const trueRange = Math.max(
      candle.high - candle.low,
      Math.abs(candle.high - previousClose),
      Math.abs(candle.low - previousClose),
    );
    candle.relativeVolume = volumeQueue.length === volumeLookback && volumeSum > 0
      ? candle.volume / (volumeSum / volumeLookback)
      : null;
    candle.atr = trueRangeQueue.length === atrLookback ? trueRangeSum / atrLookback : null;
    candle.bodyRatio = candle.high > candle.low ? Math.abs(candle.close - candle.open) / (candle.high - candle.low) : 0;
    candle.closeLocation = candle.high > candle.low ? (candle.close - candle.low) / (candle.high - candle.low) : 0.5;
    candle.upperWickRatio = candle.high > candle.low
      ? (candle.high - Math.max(candle.open, candle.close)) / (candle.high - candle.low)
      : 0;
    candle.lowerWickRatio = candle.high > candle.low
      ? (Math.min(candle.open, candle.close) - candle.low) / (candle.high - candle.low)
      : 0;
    candle.displacementAtr = candle.atr != null && candle.atr > 0 ? Math.abs(candle.close - candle.open) / candle.atr : null;
    ema20 = nextEma(ema20, candle.close, 20);
    ema50 = nextEma(ema50, candle.close, 50);
    candle.ema20 = index >= 19 ? ema20 : null;
    candle.ema50 = index >= 49 ? ema50 : null;
    candle.index = index;

    volumeQueue.push(candle.volume);
    volumeSum += candle.volume;
    if (volumeQueue.length > volumeLookback) volumeSum -= volumeQueue.shift();
    trueRangeQueue.push(trueRange);
    trueRangeSum += trueRange;
    if (trueRangeQueue.length > atrLookback) trueRangeSum -= trueRangeQueue.shift();
  }
  return candles;
}

function nextEma(previous, value, period) {
  if (previous == null) return value;
  return previous + (value - previous) * (2 / (period + 1));
}

export function attachClosedM15Regimes(m5Candles, m15Candles, slopeLookbackBars = 4) {
  let m15Index = -1;
  for (const m5 of m5Candles) {
    while (m15Index + 1 < m15Candles.length && m15Candles[m15Index + 1].closeTimeMs <= m5.closeTimeMs) {
      m15Index += 1;
    }
    const current = m15Candles[m15Index];
    const slope = m15Candles[m15Index - slopeLookbackBars];
    let direction = "NEUTRAL";
    if (current?.ema20 != null && current.ema50 != null && slope?.ema20 != null) {
      if (current.ema20 > current.ema50 && current.ema20 > slope.ema20) direction = "BUY";
      if (current.ema20 < current.ema50 && current.ema20 < slope.ema20) direction = "SELL";
    }
    m5.m15Regime = {
      direction,
      sourceIndex: m15Index,
      sourceCloseTimeMs: current?.closeTimeMs ?? null,
    };
  }
  return m5Candles;
}

export function detectM5Setup(candidate, candles, index) {
  const candle = candles[index];
  if (
    candle == null || candle.atr == null || candle.atr <= 0 ||
    candle.relativeVolume == null || candle.relativeVolume < candidate.minimumM5RelativeVolume
  ) return null;
  if (candidate.family === "VOLUME_IMPACT_CONTINUATION") {
    return continuationSetup(candidate, candles, index);
  }
  if (candidate.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION") {
    const setup = continuationSetup(candidate, candles, index);
    if (setup != null) setup.breakoutLevel = setup.side === "BUY" ? setup.priorHigh : setup.priorLow;
    return setup;
  }
  if (candidate.family === "VOLUME_EXHAUSTION_REVERSAL") {
    return reversalSetup(candidate, candles, index);
  }
  if (candidate.family === "CLUSTERED_VOLUME_EXHAUSTION_REVERSAL") {
    return clusteredReversalSetup(candidate, candles, index);
  }
  if (candidate.family === "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL") {
    return clusteredReversalSetup(candidate, candles, index);
  }
  throw new Error(`Unsupported candidate family: ${candidate.family}`);
}

function clusteredReversalSetup(candidate, candles, index) {
  const current = candles[index];
  const previous = candles[index - 1];
  const range = priorRange(candles, index - 1, candidate.m5FailedBreakoutLookbackBars);
  if (previous == null || range == null || current.m15Regime?.direction === "NEUTRAL") return null;
  const baselineVolume = current.relativeVolume != null && current.relativeVolume > 0
    ? current.volume / current.relativeVolume
    : null;
  if (baselineVolume == null || baselineVolume <= 0 || current.atr == null || current.atr <= 0) return null;
  const clusterRelativeVolume = (previous.volume + current.volume) / (2 * baselineVolume);
  if (
    candidate.family !== "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL" &&
    clusterRelativeVolume < candidate.minimumClusterRelativeVolume
  ) return null;
  const high = Math.max(previous.high, current.high);
  const low = Math.min(previous.low, current.low);
  const clusterRange = high - low;
  if (clusterRange <= 0) return null;
  const displacementAtr = Math.abs(current.close - previous.open) / current.atr;
  if (displacementAtr > candidate.maximumClusterDisplacementAtr) return null;
  const closeLocation = (current.close - low) / clusterRange;
  const upperRejection = (high - Math.max(previous.open, current.close)) / clusterRange;
  const lowerRejection = (Math.min(previous.open, current.close) - low) / clusterRange;
  const shortVolumeAllowed = candidate.family === "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL"
    ? clusterRelativeVolume >= candidate.minimumShortClusterRelativeVolume &&
      clusterRelativeVolume < candidate.maximumShortClusterRelativeVolumeExclusive
    : true;
  const longVolumeAllowed = candidate.family === "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL"
    ? clusterRelativeVolume >= candidate.minimumLongClusterRelativeVolume
    : true;
  const sell =
    shortVolumeAllowed &&
    current.m15Regime.direction === "SELL" &&
    high > range.high &&
    current.close < range.high &&
    upperRejection >= candidate.minimumClusterRejectionRatio &&
    closeLocation <= candidate.maximumDirectionalCloseLocation;
  const buy =
    longVolumeAllowed &&
    current.m15Regime.direction === "BUY" &&
    low < range.low &&
    current.close > range.low &&
    lowerRejection >= candidate.minimumClusterRejectionRatio &&
    closeLocation >= 1 - candidate.maximumDirectionalCloseLocation;
  if (!sell && !buy) return null;
  return {
    candidateId: candidate.id,
    family: candidate.family,
    side: buy ? "BUY" : "SELL",
    setupOpenedAtMs: previous.openedAtMs,
    setupCloseTimeMs: current.closeTimeMs,
    high,
    low,
    close: current.close,
    atr: current.atr,
    relativeVolume: clusterRelativeVolume,
    displacementAtr,
    bodyRatio: Math.abs(current.close - previous.open) / clusterRange,
    closeLocation,
    priorHigh: range.high,
    priorLow: range.low,
    m15Regime: current.m15Regime,
  };
}

function continuationSetup(candidate, candles, index) {
  const candle = candles[index];
  const range = priorRange(candles, index, candidate.m5BreakoutLookbackBars);
  if (range == null || candle.m15Regime?.direction === "NEUTRAL") return null;
  if (candle.bodyRatio < candidate.minimumBodyRatio || candle.displacementAtr < candidate.minimumDisplacementAtr) return null;
  const long =
    candle.m15Regime.direction === "BUY" &&
    candle.close > range.high &&
    candle.close > candle.open &&
    candle.closeLocation >= candidate.minimumDirectionalCloseLocation;
  const short =
    candle.m15Regime.direction === "SELL" &&
    candle.close < range.low &&
    candle.close < candle.open &&
    candle.closeLocation <= 1 - candidate.minimumDirectionalCloseLocation;
  if (!long && !short) return null;
  return setupRecord(candidate, candle, long ? "BUY" : "SELL", range);
}

function reversalSetup(candidate, candles, index) {
  const candle = candles[index];
  const range = priorRange(candles, index, candidate.m5FailedBreakoutLookbackBars);
  if (range == null || candle.m15Regime?.direction === "NEUTRAL") return null;
  if (candle.bodyRatio > candidate.maximumBodyRatio || candle.displacementAtr > candidate.maximumDisplacementAtr) return null;
  const sell =
    candle.m15Regime.direction === "SELL" &&
    candle.high > range.high &&
    candle.close < range.high &&
    candle.upperWickRatio >= candidate.minimumRejectionWickRatio &&
    candle.closeLocation <= candidate.maximumDirectionalCloseLocation;
  const buy =
    candle.m15Regime.direction === "BUY" &&
    candle.low < range.low &&
    candle.close > range.low &&
    candle.lowerWickRatio >= candidate.minimumRejectionWickRatio &&
    candle.closeLocation >= 1 - candidate.maximumDirectionalCloseLocation;
  if (!sell && !buy) return null;
  return setupRecord(candidate, candle, buy ? "BUY" : "SELL", range);
}

function setupRecord(candidate, candle, side, prior) {
  return {
    candidateId: candidate.id,
    family: candidate.family,
    side,
    setupOpenedAtMs: candle.openedAtMs,
    setupCloseTimeMs: candle.closeTimeMs,
    high: candle.high,
    low: candle.low,
    close: candle.close,
    atr: candle.atr,
    relativeVolume: candle.relativeVolume,
    displacementAtr: candle.displacementAtr,
    bodyRatio: candle.bodyRatio,
    closeLocation: candle.closeLocation,
    priorHigh: prior.high,
    priorLow: prior.low,
    m15Regime: candle.m15Regime,
  };
}

function priorRange(candles, index, lookback) {
  const start = index - lookback;
  if (start < 0) return null;
  let high = -Infinity;
  let low = Infinity;
  for (let cursor = start; cursor < index; cursor += 1) {
    high = Math.max(high, candles[cursor].high);
    low = Math.min(low, candles[cursor].low);
  }
  return { high, low };
}

export async function runCandidateBatch({ m1Candles, m5Candles, m15Candles, candidates, protocol }) {
  validateDevelopmentProtocol(protocol);
  const contract = protocol.executionContract;
  const replayStartMs = Date.parse(protocol.sourceData.developmentReplayStartsAt);
  const replayEndMs = Date.parse(protocol.sourceData.developmentReplayEndsAt);
  const preparedM5 = prepareHigherTimeframeCandles(m5Candles, TIMEFRAME_MS.M5);
  const preparedM15 = prepareHigherTimeframeCandles(m15Candles, TIMEFRAME_MS.M15);
  const slopeLookback = Math.max(...candidates.map((candidate) => candidate.m15SlopeLookbackBars ?? 0));
  attachClosedM15Regimes(preparedM5, preparedM15, slopeLookback);
  const states = new Map(candidates.map((candidate) => [candidate.id, initialState(candidate)]));
  const m1Indicators = new RollingM1Indicators();
  let m5Index = -1;
  let lastM1 = null;
  let observedM1 = 0;

  for await (const rawCandle of toAsyncIterable(m1Candles)) {
    const candle = m1Indicators.update(normalizeCandle(rawCandle, TIMEFRAME_MS.M1));
    if (lastM1 != null && candle.openedAtMs !== lastM1.openedAtMs + MINUTE_MS) {
      handleM1Gap(states, lastM1);
    }
    while (m5Index + 1 < preparedM5.length && preparedM5[m5Index + 1].closeTimeMs <= candle.closeTimeMs) {
      m5Index += 1;
    }
    if (candle.closeTimeMs <= replayStartMs) {
      lastM1 = candle;
      continue;
    }
    if (candle.openedAtMs >= replayEndMs) break;
    observedM1 += 1;

    for (const state of states.values()) {
      fillPendingEntry(state, candle, contract);
      processPositionCandle(state, candle, contract);
    }

    const newlyClosedM5 = [];
    let cursor = m5Index;
    while (cursor >= 0 && preparedM5[cursor].closeTimeMs > (lastM1?.closeTimeMs ?? -Infinity)) {
      newlyClosedM5.push(preparedM5[cursor]);
      cursor -= 1;
    }
    newlyClosedM5.reverse();
    for (const state of states.values()) {
      expireArm(state, candle.closeTimeMs);
      if (state.position == null && state.pending == null) {
        for (const m5 of newlyClosedM5) {
          const setup = detectM5Setup(state.candidate, preparedM5, m5.index);
          if (setup != null) state.arm = setup;
        }
        confirmArm(state, candle);
      }
      updateMarkToMarket(state, candle, contract);
    }
    lastM1 = candle;
  }

  if (lastM1 != null) {
    for (const state of states.values()) {
      if (state.position != null) closePosition(state, lastM1.close, lastM1.closeTimeMs, "REPLAY_END", contract);
    }
  }
  return {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    replayStartAt: new Date(replayStartMs).toISOString(),
    replayEndAt: new Date(replayEndMs).toISOString(),
    observedM1,
    candidateCount: candidates.length,
    candidates: [...states.values()].map((state) => ({
      id: state.candidate.id,
      family: state.candidate.family,
      candidate: state.candidate,
      trades: state.trades,
      dataGapCount: state.dataGapCount,
      rejectedDiscontinuousEntries: state.rejectedDiscontinuousEntries,
      fullReplayMaxDrawdownPct: round(state.maxDrawdownPct),
    })),
    automaticExecutionAllowed: false,
  };
}

async function* toAsyncIterable(source) {
  for await (const item of source) yield item;
}

function initialState(candidate) {
  return {
    candidate,
    arm: null,
    pending: null,
    position: null,
    equity: 1,
    peakEquity: 1,
    maxDrawdownPct: 0,
    trades: [],
    tradesByDay: new Map(),
    cooldownUntilMs: -Infinity,
    dataGapCount: 0,
    rejectedDiscontinuousEntries: 0,
  };
}

class RollingM1Indicators {
  constructor() {
    this.volumes = [];
    this.volumeSum = 0;
  }

  update(candle) {
    candle.relativeVolume = this.volumes.length === 20 && this.volumeSum > 0
      ? candle.volume / (this.volumeSum / 20)
      : null;
    candle.closeLocation = candle.high > candle.low ? (candle.close - candle.low) / (candle.high - candle.low) : 0.5;
    this.volumes.push(candle.volume);
    this.volumeSum += candle.volume;
    if (this.volumes.length > 20) this.volumeSum -= this.volumes.shift();
    return candle;
  }
}

function handleM1Gap(states, lastM1) {
  for (const state of states.values()) {
    state.dataGapCount += 1;
    state.arm = null;
    if (state.pending != null) state.rejectedDiscontinuousEntries += 1;
    state.pending = null;
    if (state.position != null) {
      closePosition(state, lastM1.close, lastM1.closeTimeMs, "DATA_GAP", defaultContractForGap(state));
    }
  }
}

function defaultContractForGap(state) {
  return state.position.contract;
}

function expireArm(state, decisionTimeMs) {
  if (state.arm == null) return;
  const windowBars = state.candidate.m1ConfirmationWindowBars ?? state.candidate.m1RetestWindowBars;
  const expiresAt = state.arm.setupCloseTimeMs + windowBars * MINUTE_MS;
  if (decisionTimeMs > expiresAt) state.arm = null;
}

function confirmArm(state, candle) {
  const setup = state.arm;
  if (setup == null || candle.closeTimeMs <= setup.setupCloseTimeMs) return;
  if (state.candidate.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION") {
    confirmRetestArm(state, candle);
    return;
  }
  const relativeVolume = candle.relativeVolume ?? 0;
  if (relativeVolume < state.candidate.m1MinimumRelativeVolume) return;
  const directionalLocation = setup.side === "BUY" ? candle.closeLocation : 1 - candle.closeLocation;
  if (directionalLocation < state.candidate.m1MinimumDirectionalCloseLocation) return;
  const directionalBody = setup.side === "BUY" ? candle.close > candle.open : candle.close < candle.open;
  if (!directionalBody) return;
  const confirmed = state.candidate.family === "VOLUME_IMPACT_CONTINUATION"
    ? setup.side === "BUY" ? candle.close > setup.high : candle.close < setup.low
    : setup.side === "BUY" ? candle.close > setup.close : candle.close < setup.close;
  if (!confirmed) return;
  state.pending = {
    setup,
    confirmationAtMs: candle.closeTimeMs,
    expectedEntryAtMs: candle.openedAtMs + MINUTE_MS,
    confirmationHigh: candle.high,
    confirmationLow: candle.low,
  };
  state.arm = null;
}

function confirmRetestArm(state, candle) {
  const setup = state.arm;
  const candidate = state.candidate;
  const invalidated = setup.side === "BUY" ? candle.close < setup.low : candle.close > setup.high;
  if (invalidated) {
    state.arm = null;
    return;
  }
  const tolerance = setup.atr * candidate.retestToleranceAtr;
  const touched = setup.side === "BUY"
    ? candle.low <= setup.breakoutLevel + tolerance
    : candle.high >= setup.breakoutLevel - tolerance;
  setup.retestTouched = setup.retestTouched === true || touched;
  if (!setup.retestTouched || candle.relativeVolume == null || candle.relativeVolume > candidate.m1MaximumRelativeVolume) return;
  const directionalLocation = setup.side === "BUY" ? candle.closeLocation : 1 - candle.closeLocation;
  const directionalBody = setup.side === "BUY" ? candle.close > candle.open : candle.close < candle.open;
  const accepted = setup.side === "BUY" ? candle.close > setup.breakoutLevel : candle.close < setup.breakoutLevel;
  if (!directionalBody || !accepted || directionalLocation < candidate.m1MinimumDirectionalCloseLocation) return;
  state.pending = {
    setup,
    confirmationAtMs: candle.closeTimeMs,
    expectedEntryAtMs: candle.openedAtMs + MINUTE_MS,
    confirmationHigh: candle.high,
    confirmationLow: candle.low,
  };
  state.arm = null;
}

function fillPendingEntry(state, candle, contract) {
  const pending = state.pending;
  if (pending == null) return;
  if (candle.openedAtMs !== pending.expectedEntryAtMs) {
    state.rejectedDiscontinuousEntries += 1;
    state.pending = null;
    return;
  }
  const day = new Date(candle.openedAtMs).toISOString().slice(0, 10);
  const dayTrades = state.tradesByDay.get(day) ?? 0;
  if (dayTrades >= contract.maximumTradesPerUtcDay || candle.openedAtMs < state.cooldownUntilMs) {
    state.pending = null;
    return;
  }
  const position = buildPosition(state, pending, candle, contract);
  state.pending = null;
  if (position == null) return;
  state.position = position;
  state.tradesByDay.set(day, dayTrades + 1);
}

function buildPosition(state, pending, candle, contract) {
  const { setup } = pending;
  const side = setup.side;
  const entryPrice = adverseEntryFill(candle.open, side, contract.entrySlippageRate);
  const stopPrice = state.candidate.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION"
    ? side === "BUY"
      ? pending.confirmationLow - setup.atr * state.candidate.confirmationStopBufferAtr
      : pending.confirmationHigh + setup.atr * state.candidate.confirmationStopBufferAtr
    : state.candidate.family === "VOLUME_IMPACT_CONTINUATION"
    ? side === "BUY"
      ? Math.min(setup.low, entryPrice - setup.atr * state.candidate.initialStopAtr)
      : Math.max(setup.high, entryPrice + setup.atr * state.candidate.initialStopAtr)
    : side === "BUY"
      ? setup.low - setup.atr * state.candidate.initialStopBufferAtr
      : setup.high + setup.atr * state.candidate.initialStopBufferAtr;
  const triggerRiskPerUnit = Math.abs(entryPrice - stopPrice);
  const triggerRiskPct = triggerRiskPerUnit / entryPrice;
  if (triggerRiskPct < contract.minimumInitialRiskPct || triggerRiskPct > contract.maximumInitialRiskPct) return null;
  const stopFill = adverseExitFill(stopPrice, side, contract.exitSlippageRate);
  const stopGrossLossPerUnit = side === "BUY" ? entryPrice - stopFill : stopFill - entryPrice;
  const stopCostPerUnit = entryPrice * contract.entryFeeRate + stopFill * contract.exitFeeRate;
  const netRiskPerUnit = stopGrossLossPerUnit + stopCostPerUnit;
  if (!Number.isFinite(netRiskPerUnit) || netRiskPerUnit <= 0) return null;
  const riskAmount = state.equity * contract.riskFraction;
  const quantity = riskAmount / netRiskPerUnit;
  const targetPrice = [
    "VOLUME_EXHAUSTION_REVERSAL",
    "CLUSTERED_VOLUME_EXHAUSTION_REVERSAL",
    "ASYMMETRIC_CLUSTER_ABSORPTION_REVERSAL",
  ].includes(state.candidate.family)
    ? side === "BUY"
      ? entryPrice + triggerRiskPerUnit * state.candidate.targetR
      : entryPrice - triggerRiskPerUnit * state.candidate.targetR
    : null;
  const liquidationDistance = (1 / contract.researchLeverage) - contract.maintenanceMarginRate;
  const liquidationPrice = side === "BUY"
    ? entryPrice * (1 - liquidationDistance)
    : entryPrice * (1 + liquidationDistance);
  return {
    contract,
    candidateId: state.candidate.id,
    family: state.candidate.family,
    side,
    setup,
    confirmationAtMs: pending.confirmationAtMs,
    openedAtMs: candle.openedAtMs,
    entryPrice,
    quantity,
    riskAmount,
    triggerRiskPerUnit,
    stopPrice,
    targetPrice,
    liquidationPrice,
    maxCloseTimeMs: candle.openedAtMs + state.candidate.maximumHoldingMinutes * MINUTE_MS,
    bestHigh: entryPrice,
    bestLow: entryPrice,
    maeR: 0,
    mfeR: 0,
  };
}

export function resolveExitOnCandle(position, candle) {
  const long = position.side === "BUY";
  const gapLiquidation = long ? candle.open <= position.liquidationPrice : candle.open >= position.liquidationPrice;
  if (gapLiquidation) return { price: position.liquidationPrice, reason: "LIQUIDATION" };
  const gapStop = long ? candle.open <= position.stopPrice : candle.open >= position.stopPrice;
  if (gapStop) return { price: candle.open, reason: position.trailingMoved ? "TRAILING_STOP" : "STOP" };
  const stopHit = long ? candle.low <= position.stopPrice : candle.high >= position.stopPrice;
  if (stopHit) return { price: position.stopPrice, reason: position.trailingMoved ? "TRAILING_STOP" : "STOP" };
  const liquidationHit = long ? candle.low <= position.liquidationPrice : candle.high >= position.liquidationPrice;
  if (liquidationHit) return { price: position.liquidationPrice, reason: "LIQUIDATION" };
  if (position.targetPrice != null) {
    const targetHit = long ? candle.high >= position.targetPrice : candle.low <= position.targetPrice;
    if (targetHit) return { price: position.targetPrice, reason: "TARGET" };
  }
  if (candle.closeTimeMs >= position.maxCloseTimeMs) return { price: candle.close, reason: "TIME" };
  return null;
}

function processPositionCandle(state, candle, contract) {
  const position = state.position;
  if (position == null) return;
  updateExcursions(position, candle, contract);
  const exit = resolveExitOnCandle(position, candle);
  if (exit != null) {
    closePosition(state, exit.price, candle.closeTimeMs, exit.reason, contract);
    return;
  }
  if (position.family === "VOLUME_IMPACT_CONTINUATION") {
    if (position.side === "BUY") {
      position.bestHigh = Math.max(position.bestHigh, candle.high);
      const nextStop = Math.max(position.stopPrice, position.bestHigh - position.setup.atr * state.candidate.trailingStopAtr);
      position.trailingMoved = position.trailingMoved === true || nextStop > position.stopPrice;
      position.stopPrice = nextStop;
    } else {
      position.bestLow = Math.min(position.bestLow, candle.low);
      const nextStop = Math.min(position.stopPrice, position.bestLow + position.setup.atr * state.candidate.trailingStopAtr);
      position.trailingMoved = position.trailingMoved === true || nextStop < position.stopPrice;
      position.stopPrice = nextStop;
    }
  }
  if (position.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION") {
    updateRetestTrailingStop(state, position, candle);
  }
}

function updateRetestTrailingStop(state, position, candle) {
  const candidate = state.candidate;
  const previousStop = position.stopPrice;
  if (position.side === "BUY") {
    position.bestHigh = Math.max(position.bestHigh, candle.high);
    const favorableR = (position.bestHigh - position.entryPrice) / position.triggerRiskPerUnit;
    if (favorableR >= candidate.breakEvenTriggerR) {
      position.stopPrice = Math.max(position.stopPrice, position.entryPrice + position.triggerRiskPerUnit * candidate.breakEvenLockR);
    }
    if (favorableR >= candidate.trailingActivationR) {
      position.stopPrice = Math.max(position.stopPrice, position.bestHigh - position.setup.atr * candidate.trailingStopAtr);
    }
  } else {
    position.bestLow = Math.min(position.bestLow, candle.low);
    const favorableR = (position.entryPrice - position.bestLow) / position.triggerRiskPerUnit;
    if (favorableR >= candidate.breakEvenTriggerR) {
      position.stopPrice = Math.min(position.stopPrice, position.entryPrice - position.triggerRiskPerUnit * candidate.breakEvenLockR);
    }
    if (favorableR >= candidate.trailingActivationR) {
      position.stopPrice = Math.min(position.stopPrice, position.bestLow + position.setup.atr * candidate.trailingStopAtr);
    }
  }
  position.trailingMoved = position.trailingMoved === true || position.stopPrice !== previousStop;
}

function updateExcursions(position, candle, contract) {
  const favorableTrigger = position.side === "BUY" ? candle.high : candle.low;
  const adverseTrigger = position.side === "BUY" ? candle.low : candle.high;
  const favorablePnl = netPnlAt(position, favorableTrigger, contract);
  const adversePnl = netPnlAt(position, adverseTrigger, contract);
  position.mfeR = Math.max(position.mfeR, favorablePnl / position.riskAmount);
  position.maeR = Math.min(position.maeR, adversePnl / position.riskAmount);
}

function closePosition(state, exitTriggerPrice, closedAtMs, reason, contract) {
  const position = state.position;
  if (position == null) return;
  const exitPrice = reason === "LIQUIDATION"
    ? exitTriggerPrice
    : adverseExitFill(exitTriggerPrice, position.side, contract.exitSlippageRate);
  const pnl = netPnlAt(position, exitPrice, contract, { priceAlreadyFilled: true });
  const equityBefore = state.equity;
  state.equity += pnl;
  const netR = pnl / position.riskAmount;
  state.trades.push({
    candidateId: position.candidateId,
    family: position.family,
    side: position.side,
    setupAt: new Date(position.setup.setupCloseTimeMs).toISOString(),
    confirmationAt: new Date(position.confirmationAtMs).toISOString(),
    openedAt: new Date(position.openedAtMs).toISOString(),
    closedAt: new Date(closedAtMs).toISOString(),
    openedAtMs: position.openedAtMs,
    closedAtMs,
    exitReason: reason,
    entryPrice: round(position.entryPrice),
    exitPrice: round(exitPrice),
    stopPrice: round(position.stopPrice),
    targetPrice: position.targetPrice == null ? null : round(position.targetPrice),
    liquidationPrice: round(position.liquidationPrice),
    quantity: round(position.quantity),
    riskAmount: round(position.riskAmount),
    netR: round(netR),
    maeR: round(Math.min(position.maeR, netR)),
    mfeR: round(Math.max(position.mfeR, netR)),
    returnPct: round((pnl / equityBefore) * 100),
    equityAfter: round(state.equity),
    setupRelativeVolume: round(position.setup.relativeVolume),
    setupDisplacementAtr: round(position.setup.displacementAtr),
    m15Regime: position.setup.m15Regime.direction,
  });
  state.cooldownUntilMs = closedAtMs + contract.cooldownMinutes * MINUTE_MS;
  state.position = null;
  updateEquityDrawdown(state, state.equity);
}

function netPnlAt(position, exitTriggerPrice, contract, { priceAlreadyFilled = false } = {}) {
  const exitPrice = priceAlreadyFilled
    ? exitTriggerPrice
    : adverseExitFill(exitTriggerPrice, position.side, contract.exitSlippageRate);
  const gross = position.side === "BUY"
    ? (exitPrice - position.entryPrice) * position.quantity
    : (position.entryPrice - exitPrice) * position.quantity;
  const fees = (position.entryPrice * position.quantity * contract.entryFeeRate) +
    (exitPrice * position.quantity * contract.exitFeeRate);
  return gross - fees;
}

function adverseEntryFill(price, side, slippageRate) {
  return side === "BUY" ? price * (1 + slippageRate) : price * (1 - slippageRate);
}

function adverseExitFill(price, side, slippageRate) {
  return side === "BUY" ? price * (1 - slippageRate) : price * (1 + slippageRate);
}

function updateMarkToMarket(state, candle, contract) {
  const marked = state.position == null
    ? state.equity
    : state.equity + netPnlAt(state.position, candle.close, contract);
  updateEquityDrawdown(state, marked);
}

function updateEquityDrawdown(state, equity) {
  state.peakEquity = Math.max(state.peakEquity, equity);
  if (state.peakEquity > 0) {
    state.maxDrawdownPct = Math.max(state.maxDrawdownPct, ((state.peakEquity - equity) / state.peakEquity) * 100);
  }
}

export function evaluateNestedWalkForward(batch, protocol) {
  validateDevelopmentProtocol(protocol);
  const byId = new Map(batch.candidates.map((result) => [result.id, result]));
  const families = [...new Set(batch.candidates.map((result) => result.family))];
  const familyReports = [];

  for (const family of families) {
    const familyCandidates = batch.candidates.filter((result) => result.family === family);
    const folds = [];
    const pooledValidationTrades = [];
    for (const fold of protocol.nestedWalkForward.folds) {
      const rankedTraining = familyCandidates
        .map((result) => ({
          candidateId: result.id,
          metrics: metricsForTrades(result.trades, fold.trainStartAt, fold.trainEndAt, protocol),
          dataGapCount: result.dataGapCount,
        }))
        .filter((item) => trainingEligible(item, protocol))
        .sort(compareRankedMetrics);
      const selected = rankedTraining[0] ?? null;
      const selectedResult = selected == null ? null : byId.get(selected.candidateId);
      const validation = selectedResult == null
        ? emptyMetrics(fold.validationStartAt, fold.validationEndAt)
        : metricsForTrades(selectedResult.trades, fold.validationStartAt, fold.validationEndAt, protocol);
      if (selectedResult != null) pooledValidationTrades.push(...filterTrades(selectedResult.trades, fold.validationStartAt, fold.validationEndAt));
      folds.push({
        id: fold.id,
        selectedCandidateId: selected?.candidateId ?? null,
        training: selected?.metrics ?? null,
        validation,
      });
    }
    pooledValidationTrades.sort((left, right) => left.closedAtMs - right.closedAtMs);
    const firstFold = protocol.nestedWalkForward.folds[0];
    const lastFold = protocol.nestedWalkForward.folds.at(-1);
    const pooled = metricsForTrades(
      pooledValidationTrades,
      firstFold.validationStartAt,
      lastFold.validationEndAt,
      protocol,
      { alreadyFiltered: true },
    );
    const positiveFolds = folds.filter((fold) => fold.validation.netReturnPct > 0).length;
    const gate = developmentGate({ pooled, positiveFolds, folds, protocol });
    const fullRanking = familyCandidates
      .map((result) => ({
        candidateId: result.id,
        metrics: metricsForTrades(
          result.trades,
          protocol.sourceData.developmentReplayStartsAt,
          protocol.sourceData.developmentReplayEndsAt,
          protocol,
        ),
        dataGapCount: result.dataGapCount,
      }))
      .filter((item) => trainingEligible(item, protocol))
      .sort(compareRankedMetrics);
    familyReports.push({
      family,
      status: gate.passed ? "DEVELOPMENT_PASSED" : "REJECTED",
      gate,
      folds,
      pooledValidation: pooled,
      positiveValidationFolds: positiveFolds,
      frozenCandidateId: gate.passed ? fullRanking[0]?.candidateId ?? null : null,
      fullDevelopmentRanking: fullRanking.slice(0, 12),
    });
  }
  const passing = familyReports.filter((report) => report.status === "DEVELOPMENT_PASSED");
  return {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    status: passing.length > 0 ? "CANDIDATE_FREEZE_REQUIRED" : "REJECTED",
    stageCandidateCount: batch.candidateCount,
    familyReports,
    candidateFreezeRequired: passing.length > 0,
    reservedSealedWindowOpened: false,
    automaticExecutionAllowed: false,
  };
}

function trainingEligible(item, protocol) {
  const gate = protocol.trainingEligibility;
  const metrics = item.metrics;
  const monthStability = gate.minimumActiveMonthPositiveRatio == null
    ? metrics.positiveMonthRatio >= gate.minimumPositiveMonthRatio
    : metrics.activeMonthPositiveRatio >= gate.minimumActiveMonthPositiveRatio;
  return item.dataGapCount === 0 &&
    metrics.tradeCount >= gate.minimumTrades &&
    monthStability &&
    metrics.profitFactor >= gate.minimumProfitFactor &&
    metrics.maxDrawdownPct <= gate.maximumDrawdownPct;
}

function compareRankedMetrics(left, right) {
  const lowerDifference = (right.metrics.bootstrap?.lowerBound ?? -Infinity) - (left.metrics.bootstrap?.lowerBound ?? -Infinity);
  if (lowerDifference !== 0) return lowerDifference;
  const activeMonthDifference = right.metrics.activeMonthPositiveRatio - left.metrics.activeMonthPositiveRatio;
  if (activeMonthDifference !== 0) return activeMonthDifference;
  const medianDifference = right.metrics.medianMonthlyReturnPct - left.metrics.medianMonthlyReturnPct;
  if (medianDifference !== 0) return medianDifference;
  const profitFactorDifference = right.metrics.profitFactor - left.metrics.profitFactor;
  if (profitFactorDifference !== 0) return profitFactorDifference;
  return left.candidateId.localeCompare(right.candidateId);
}

function developmentGate({ pooled, positiveFolds, folds, protocol }) {
  const required = protocol.familyDevelopmentGate;
  const checks = {
    allFoldsSelectedCandidate: folds.every((fold) => fold.selectedCandidateId != null),
    minimumPooledValidationTrades: pooled.tradeCount >= required.minimumPooledValidationTrades,
    minimumPositiveValidationFolds: positiveFolds >= required.minimumPositiveValidationFolds,
    minimumPooledProfitFactor: pooled.profitFactor >= required.minimumPooledProfitFactor,
    minimumPooledMeanNetR: pooled.meanNetR > required.minimumPooledMeanNetR,
    minimumBootstrapLowerMeanNetR: (pooled.bootstrap?.lowerBound ?? -Infinity) > required.minimumBootstrapLowerMeanNetR,
    maximumPooledDrawdownPct: pooled.maxDrawdownPct <= required.maximumPooledDrawdownPct,
    maximumLiquidationCount: pooled.liquidationCount <= required.maximumLiquidationCount,
  };
  return { passed: Object.values(checks).every(Boolean), checks };
}

export function metricsForTrades(trades, startAt, endAt, protocol, { alreadyFiltered = false } = {}) {
  const selected = alreadyFiltered ? [...trades] : filterTrades(trades, startAt, endAt);
  selected.sort((left, right) => left.closedAtMs - right.closedAtMs);
  const riskFraction = protocol.executionContract.riskFraction;
  let equity = 1;
  let peak = 1;
  let maxDrawdownPct = 0;
  let grossProfitR = 0;
  let grossLossR = 0;
  let wins = 0;
  let liquidationCount = 0;
  const activeDays = new Set();
  const monthFactors = new Map(monthKeys(startAt, endAt).map((month) => [month, 1]));
  const activeMonths = new Set();
  for (const trade of selected) {
    const equityBefore = equity;
    const adverseEquity = equityBefore * (1 + riskFraction * trade.maeR);
    maxDrawdownPct = Math.max(maxDrawdownPct, ((peak - adverseEquity) / peak) * 100);
    equity *= Math.max(0, 1 + riskFraction * trade.netR);
    peak = Math.max(peak, equity);
    maxDrawdownPct = Math.max(maxDrawdownPct, ((peak - equity) / peak) * 100);
    if (trade.netR > 0) {
      wins += 1;
      grossProfitR += trade.netR;
    } else {
      grossLossR += Math.abs(trade.netR);
    }
    if (trade.exitReason === "LIQUIDATION") liquidationCount += 1;
    activeDays.add(trade.openedAt.slice(0, 10));
    const month = trade.closedAt.slice(0, 7);
    activeMonths.add(month);
    monthFactors.set(month, (monthFactors.get(month) ?? 1) * Math.max(0, 1 + riskFraction * trade.netR));
  }
  const returnsR = selected.map((trade) => trade.netR);
  const monthlyReturns = [...monthFactors.values()].map((factor) => (factor - 1) * 100);
  const activeMonthlyReturns = [...activeMonths].map((month) => ((monthFactors.get(month) ?? 1) - 1) * 100);
  const startMs = Date.parse(startAt);
  const endMs = Date.parse(endAt);
  const observedDays = Math.max(1, (endMs - startMs) / 86_400_000);
  const bootstrapConfig = protocol.trainingEligibility.bootstrap;
  const bootstrap = returnsR.length === 0 ? null : movingBlockBootstrap(returnsR, {
    iterations: bootstrapConfig.iterations,
    blockLength: bootstrapConfig.blockLengthTrades,
    confidenceLevel: bootstrapConfig.confidenceLevel,
    seed: bootstrapConfig.seed,
  });
  return {
    replayStartAt: new Date(startMs).toISOString(),
    replayEndAt: new Date(endMs).toISOString(),
    tradeCount: selected.length,
    wins,
    winRatePct: selected.length > 0 ? round((wins / selected.length) * 100) : 0,
    netReturnPct: round((equity - 1) * 100),
    compoundDailyReturnPct: equity > 0 ? round(((equity ** (1 / observedDays)) - 1) * 100) : -100,
    meanNetR: returnsR.length > 0 ? round(average(returnsR)) : 0,
    medianNetR: returnsR.length > 0 ? round(median(returnsR)) : 0,
    profitFactor: grossLossR > 0 ? round(grossProfitR / grossLossR) : grossProfitR > 0 ? 999 : 0,
    maxDrawdownPct: round(maxDrawdownPct),
    activeDayCoveragePct: round((activeDays.size / observedDays) * 100),
    positiveMonthRatio: monthlyReturns.length > 0 ? round(monthlyReturns.filter((value) => value > 0).length / monthlyReturns.length) : 0,
    activeMonthPositiveRatio: activeMonthlyReturns.length > 0
      ? round(activeMonthlyReturns.filter((value) => value > 0).length / activeMonthlyReturns.length)
      : 0,
    activeMonthCoverage: monthlyReturns.length > 0 ? round(activeMonthlyReturns.length / monthlyReturns.length) : 0,
    medianMonthlyReturnPct: monthlyReturns.length > 0 ? round(median(monthlyReturns)) : 0,
    liquidationCount,
    maximumWinnerProfitConcentration: grossProfitR > 0
      ? round(Math.max(0, ...returnsR) / grossProfitR)
      : 0,
    bootstrap: bootstrap == null ? null : roundObject(bootstrap),
  };
}

function filterTrades(trades, startAt, endAt) {
  const startMs = Date.parse(startAt);
  const endMs = Date.parse(endAt);
  return trades.filter((trade) => trade.openedAtMs >= startMs && trade.closedAtMs < endMs);
}

function emptyMetrics(startAt, endAt) {
  return {
    replayStartAt: new Date(startAt).toISOString(),
    replayEndAt: new Date(endAt).toISOString(),
    tradeCount: 0,
    wins: 0,
    winRatePct: 0,
    netReturnPct: 0,
    compoundDailyReturnPct: 0,
    meanNetR: 0,
    medianNetR: 0,
    profitFactor: 0,
    maxDrawdownPct: 0,
    activeDayCoveragePct: 0,
    positiveMonthRatio: 0,
    activeMonthPositiveRatio: 0,
    activeMonthCoverage: 0,
    medianMonthlyReturnPct: 0,
    liquidationCount: 0,
    maximumWinnerProfitConcentration: 0,
    bootstrap: null,
  };
}

function monthKeys(startAt, endAt) {
  const cursor = new Date(Date.parse(startAt));
  cursor.setUTCDate(1);
  cursor.setUTCHours(0, 0, 0, 0);
  const endMs = Date.parse(endAt);
  const months = [];
  while (cursor.getTime() < endMs) {
    months.push(cursor.toISOString().slice(0, 7));
    cursor.setUTCMonth(cursor.getUTCMonth() + 1);
  }
  return months;
}

function average(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function round(value, digits = 8) {
  if (!Number.isFinite(value)) return value;
  const scale = 10 ** digits;
  return Math.round(value * scale) / scale;
}

function roundObject(value) {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, typeof item === "number" ? round(item) : item]));
}
