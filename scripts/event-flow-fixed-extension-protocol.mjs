import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const EXPECTED_STATUS = "PREDECLARED_FIXED_CANDIDATE_SAMPLE_EXTENSION_BEFORE_ACQUISITION";
const EXPECTED_PARENT_RESULT_HASH = "f6fdbe2a4586325827679ed97a9bb989195540c81f6af9d7fec41c47f5a4be0b";
const EXPECTED_CANDIDATE_HASH = "4e7f9a1f11207ae26fcf7017cf9b4fc8b3a6aba3e841d8ebcb98010196b2f81e";
const EXPECTED_BLOCKS_HASH = "7dc3a78513566d0ff4b44a264007e25b2de03c546e8869eecd8d1265e92fc948";
const DAY_MS = 86_400_000;

export async function loadFixedExtensionProtocol(path, parentProtocolPath) {
  const [protocolBytes, parentBytes] = await Promise.all([
    fs.readFile(path),
    fs.readFile(parentProtocolPath),
  ]);
  const parentProtocol = JSON.parse(parentBytes);
  return {
    protocol: validateFixedExtensionProtocol(JSON.parse(protocolBytes), parentProtocol),
    sha256: sha256(protocolBytes),
    parentProtocolSha256: sha256(parentBytes),
  };
}

export function validateFixedExtensionProtocol(protocol, parentProtocol) {
  if (protocol?.protocolId !== "bybit-event-flow-fixed-extension-v1" || protocol.status !== EXPECTED_STATUS) {
    throw new Error("Fixed extension protocol must be frozen before event-data acquisition.");
  }
  if (parentProtocol?.protocolId !== "bybit-event-flow-development-v1") {
    throw new Error("Fixed extension protocol requires the frozen parent acquisition protocol.");
  }
  if (protocol.parentResult?.resultReceiptSha256 !== EXPECTED_PARENT_RESULT_HASH ||
      protocol.parentResult?.requiredStatus !== "REJECTED_INSUFFICIENT_SELECTION_SAMPLE") {
    throw new Error("Fixed extension protocol must bind the rejected v3 result receipt.");
  }
  const trials = protocol.trialAccounting;
  if (trials?.priorEvidenceContractCandidates !== 140 || trials.stageCandidates !== 1 ||
      trials.cumulativeCandidates !== 141 || trials.maximumCumulativeCandidates !== 192) {
    throw new Error("Fixed extension trial accounting must remain frozen at 140 + 1 = 141 of 192.");
  }
  if (protocol.purpose?.kind !== "NON_PROMOTING_FIXED_CANDIDATE_SAMPLE_EXTENSION" ||
      protocol.purpose?.candidateSelectionWasInformedByPriorOutcomes !== true ||
      protocol.purpose?.extensionEventDataWasNotReadBeforeThisProtocol !== true ||
      protocol.purpose?.candidateMayChangeAfterExtensionOutcome !== false) {
    throw new Error("Fixed extension must disclose prior selection and forbid candidate changes.");
  }
  if (sha256(JSON.stringify(protocol.fixedCandidate)) !== EXPECTED_CANDIDATE_HASH ||
      protocol.fixedCandidateSha256 !== EXPECTED_CANDIDATE_HASH) {
    throw new Error("Fixed extension candidate fingerprint changed.");
  }
  validateSource(protocol, parentProtocol);
  validateBlocks(protocol, parentProtocol);
  if (canonicalJson(protocol.executionContract) !== canonicalJson(parentProtocol.executionContract) ||
      canonicalJson(protocol.statistics) !== canonicalJson(parentProtocol.statistics)) {
    throw new Error("Fixed extension must inherit the development execution and statistics contracts unchanged.");
  }
  const gate = protocol.extensionGate;
  if (gate?.minimumTrades !== 30 || gate.minimumLongTrades !== 5 || gate.minimumShortTrades !== 5 ||
      gate.minimumPositiveQuarters !== 6 || gate.totalQuarters !== 8 || gate.minimumPositiveYears !== 2 ||
      gate.totalYears !== 2 || gate.minimumProfitFactor !== 1.2 || gate.minimumMeanNetR !== 0 ||
      gate.minimumBootstrapLowerMeanNetR !== 0 || gate.maximumDrawdownPct !== 20 ||
      gate.maximumLiquidationCount !== 0 || gate.maximumWinnerProfitConcentration !== 0.25) {
    throw new Error("Fixed extension statistical gate changed after declaration.");
  }
  const outcome = protocol.outcomePolicy;
  if (outcome?.validationDataMayBeAcquiredDirectly !== false ||
      outcome.externalDataMayBeAcquiredDirectly !== false ||
      outcome.freshSealedDataMayBeAcquiredDirectly !== false ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Fixed extension cannot directly unlock later evidence or execution.");
  }
  return protocol;
}

function validateSource(protocol, parentProtocol) {
  const source = protocol.sourceData;
  const parent = parentProtocol.sourceData;
  if (source?.symbol !== parent.symbol ||
      source.canonicalCandleDatabase !== parent.canonicalCandleDatabase ||
      source.canonicalCandleDatabaseSha256 !== parent.canonicalCandleDatabaseSha256 ||
      source.timeframes?.join(",") !== parent.timeframes.join(",") ||
      source.orderBookProvider !== parent.orderBookProvider || source.tradeProvider !== parent.tradeProvider ||
      source.orderBookImporterVersion !== parent.orderBookImporterVersion ||
      source.tradeImporterVersion !== parent.tradeImporterVersion ||
      source.retainedOrderBookDepth !== 50 ||
      source.researchDatabase !== "build/research/bybit-event-flow-fixed-extension-v1.sqlite") {
    throw new Error("Fixed extension source contract changed.");
  }
}

function validateBlocks(protocol, parentProtocol) {
  const contract = protocol.blockContract;
  if (contract?.sourceDaysPerBlock !== 7 || contract.warmupDaysPerBlock !== 1 ||
      contract.evaluationDaysPerBlock !== 6 || contract.blockCount !== 16 ||
      contract.totalSourceDays !== 112 || contract.totalEvaluationDays !== 96 ||
      protocol.blocks?.length !== 16) {
    throw new Error("Fixed extension block contract must remain 16 seven-day blocks with 96 evaluation days.");
  }
  const blockHash = sha256(protocol.blocks.map((block) => [
    block.id,
    block.era,
    block.sourceStartDate,
    block.sourceEndDate,
    block.replayStartAt,
    block.replayEndAt,
  ].join("|")).join("\n"));
  if (blockHash !== EXPECTED_BLOCKS_HASH || protocol.dateSelection?.blocksSha256 !== EXPECTED_BLOCKS_HASH) {
    throw new Error("Fixed extension date blocks changed after declaration.");
  }
  if (protocol.dateSelection?.method !== "SHA256_SEEDED_STRATIFIED_TWO_NONOVERLAPPING_SEVEN_DAY_BLOCKS_PER_QUARTER" ||
      protocol.dateSelection?.seed !== "bybit-event-flow-fixed-extension-v1|20260806" ||
      protocol.dateSelection?.selectionUsedPriceOrEventOutcomes !== false ||
      protocol.dateSelection?.officialArchiveAvailabilityMetadataCheckedBeforeFreeze !== true ||
      protocol.dateSelection?.availableOrderBookDays !== 112 || protocol.dateSelection?.availableTradeDays !== 112) {
    throw new Error("Fixed extension date-selection provenance changed.");
  }
  const blockedParentDates = new Set([
    ...parentProtocol.stages.development.primaryBlocks,
    ...parentProtocol.stages.development.reserveBlocks,
  ].flatMap((block) => datesBetween(block.sourceStartDate, block.sourceEndDate)));
  const eras = new Map();
  let previousEnd = null;
  for (const [index, block] of protocol.blocks.entries()) {
    if (block.id !== `X${String(index + 1).padStart(2, "0")}`) throw new Error("Fixed extension block IDs must remain ordered.");
    const start = Date.parse(`${block.sourceStartDate}T00:00:00Z`);
    const endExclusive = Date.parse(`${addDays(block.sourceEndDate, 1)}T00:00:00Z`);
    if (endExclusive - start !== 7 * DAY_MS || Date.parse(block.replayStartAt) !== start + DAY_MS ||
        Date.parse(block.replayEndAt) !== endExclusive || block.sourceStartDate >= "2025-01-01") {
      throw new Error(`Fixed extension block ${block.id} violates its causal date bounds.`);
    }
    if (previousEnd != null && block.sourceStartDate <= previousEnd) throw new Error("Fixed extension blocks overlap or are not chronological.");
    previousEnd = block.sourceEndDate;
    if (datesBetween(block.sourceStartDate, block.sourceEndDate).some((date) => blockedParentDates.has(date))) {
      throw new Error(`Fixed extension block ${block.id} overlaps parent development evidence.`);
    }
    eras.set(block.era, (eras.get(block.era) ?? 0) + 1);
  }
  if (eras.size !== 8 || [...eras.values()].some((count) => count !== 2)) {
    throw new Error("Fixed extension must retain two blocks in each of eight quarters.");
  }
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value != null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function datesBetween(start, end) {
  const dates = [];
  for (let date = start; date <= end; date = addDays(date, 1)) dates.push(date);
  return dates;
}

function addDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
