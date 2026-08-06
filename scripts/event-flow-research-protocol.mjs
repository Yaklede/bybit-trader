import fs from "node:fs/promises";

const REQUIRED_STAGE_STATUS = {
  development: "OPEN_FOR_ACQUISITION",
  validation: "LOCKED_UNTIL_DEVELOPMENT_CANDIDATE_FROZEN",
  external: "LOCKED_UNTIL_VALIDATION_PASSED_AND_CANDIDATE_REFROZEN",
};

export async function loadEventFlowProtocol(path) {
  return validateEventFlowProtocol(JSON.parse(await fs.readFile(path, "utf8")));
}

export function validateEventFlowProtocol(protocol) {
  if (protocol?.status !== "PREDECLARED_DATA_ACQUISITION_AND_DEVELOPMENT") {
    throw new Error("Event-flow protocol must be predeclared before acquisition.");
  }
  if (protocol.trials?.stageCandidateCount !== 32 || protocol.trials?.cumulativeCount !== 92) {
    throw new Error("Event-flow protocol trial accounting must remain frozen at 32 stage and 92 cumulative candidates.");
  }
  if (expandEventFlowCandidates(protocol).length !== protocol.trials.stageCandidateCount) {
    throw new Error("Expanded event-flow candidate count does not match the trial ledger.");
  }
  if (protocol.sourceData?.retainedOrderBookDepth !== 50) throw new Error("Event-flow source must retain top-50 order book depth.");
  if (protocol.contaminationDisclosure?.reservedFreshSealedWindowMayBeReadDuringDevelopment !== false) {
    throw new Error("Development cannot read the reserved fresh sealed window.");
  }
  for (const [stageName, expectedStatus] of Object.entries(REQUIRED_STAGE_STATUS)) {
    const stage = protocol.stages?.[stageName];
    if (stage?.status !== expectedStatus) throw new Error(`${stageName} stage status is not frozen.`);
    validateBlocks(stageName, [...stage.primaryBlocks, ...stage.reserveBlocks], protocol);
  }
  if (protocol.stages.freshSealed?.eventDataMayBeAcquiredBeforeCandidateFingerprint !== false) {
    throw new Error("Fresh sealed event data cannot be acquired before a candidate fingerprint exists.");
  }
  if (protocol.stages.development.primaryBlocks.length !== 12 ||
      protocol.stages.validation.primaryBlocks.length !== 8 ||
      protocol.stages.external.primaryBlocks.length !== 8) {
    throw new Error("Primary block counts must remain 12 development, 8 validation, and 8 external.");
  }
  return protocol;
}

export function expandEventFlowCandidates(protocol) {
  const candidates = [];
  for (const hypothesis of protocol.hypotheses ?? []) {
    const entries = Object.entries(hypothesis.grid ?? {});
    const products = cartesian(entries.map(([, values]) => values));
    for (const values of products) {
      const parameters = Object.fromEntries(entries.map(([key], index) => [key, values[index]]));
      candidates.push({ family: hypothesis.family, ...hypothesis.fixed, ...parameters });
    }
    if (products.length !== hypothesis.candidateCount) throw new Error(`Candidate count mismatch for ${hypothesis.family}.`);
  }
  return candidates;
}

export function acquisitionBlocks(protocol, stageName) {
  validateEventFlowProtocol(protocol);
  if (stageName !== "development") {
    throw new Error(`${stageName} acquisition is locked until its predecessor evidence is committed.`);
  }
  return protocol.stages.development.primaryBlocks;
}

function validateBlocks(stageName, blocks, protocol) {
  const ids = new Set();
  for (const block of blocks) {
    if (ids.has(block.id)) throw new Error(`Duplicate ${stageName} block id: ${block.id}`);
    ids.add(block.id);
    const sourceStart = Date.parse(`${block.sourceStartDate}T00:00:00Z`);
    const sourceEndExclusive = Date.parse(addDays(block.sourceEndDate, 1));
    if (sourceEndExclusive - sourceStart !== protocol.blockContract.sourceDaysPerBlock * 86_400_000) {
      throw new Error(`${block.id} must contain exactly three source days.`);
    }
    if (Date.parse(block.replayStartAt) !== sourceStart + protocol.blockContract.warmupDaysPerBlock * 86_400_000 ||
        Date.parse(block.replayEndAt) !== sourceEndExclusive) {
      throw new Error(`${block.id} replay bounds do not preserve the frozen warmup contract.`);
    }
    for (const excluded of [
      protocol.contaminationDisclosure.oneDayImporterProofDate,
      protocol.contaminationDisclosure.knownOfficialArchiveGapDate,
    ]) {
      if (excluded >= block.sourceStartDate && excluded <= block.sourceEndDate) {
        throw new Error(`${block.id} overlaps excluded evidence date ${excluded}.`);
      }
    }
  }
  const sorted = [...blocks].sort((left, right) => left.sourceStartDate.localeCompare(right.sourceStartDate));
  for (let index = 1; index < sorted.length; index += 1) {
    if (sorted[index].sourceStartDate <= sorted[index - 1].sourceEndDate) {
      throw new Error(`${stageName} source blocks overlap.`);
    }
  }
}

function cartesian(arrays) {
  return arrays.reduce((rows, values) => rows.flatMap((row) => values.map((value) => [...row, value])), [[]]);
}

function addDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString();
}
