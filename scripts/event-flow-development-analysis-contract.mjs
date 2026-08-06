import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const EXPECTED_STATUS = "PREDECLARED_ANALYSIS_BEFORE_FEATURE_INSPECTION";
const EXPECTED_V2_STATUS = "PREDECLARED_EXECUTION_REPAIR_BEFORE_V2_REPLAY";
const EXPECTED_V3_STATUS = "PREDECLARED_CONFIRMED_REVERSAL_BEFORE_V3_REPLAY";
const EXPECTED_ACQUISITION_PROTOCOL_HASH = "0568fe88bacc55d6ab83f79e642d14a832716b06bc6b036116b298ef481e8a2d";
const EXPECTED_V1_ANALYSIS_HASH = "10a69cbf42452346e26e983634c3377fede3c67076ba2e35c494301789f157b9";
const EXPECTED_V1_RESULT_RECEIPT_HASH = "12c80593ec2780b0a88f08f1e54f6b103cc106a89a977c3bbf8309389a4ecd32";
const EXPECTED_V2_RESULT_RECEIPT_HASH = "0c95001165aa2b2b3158fc066431a453fc0e33e55a8405b658465ba0a7f154e4";

export async function loadAnalysisContract(path) {
  const bytes = await fs.readFile(path);
  return {
    contract: validateAnalysisContract(JSON.parse(bytes)),
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

export function validateAnalysisContract(contract) {
  if (contract?.analysisId === "bybit-event-flow-development-analysis-v3") {
    return validateV3AnalysisContract(contract);
  }
  if (contract?.analysisId === "bybit-event-flow-development-analysis-v2") {
    return validateV2AnalysisContract(contract);
  }
  if (contract?.status !== EXPECTED_STATUS) throw new Error("Event-flow analysis must be predeclared before feature inspection.");
  if (contract.acquisitionProtocol?.protocolId !== "bybit-event-flow-development-v1" ||
      contract.acquisitionProtocol?.protocolSha256 !== EXPECTED_ACQUISITION_PROTOCOL_HASH) {
    throw new Error("Analysis contract must bind the frozen acquisition protocol hash.");
  }
  if (contract.acquisitionProtocol.validationExternalAndFreshDataAllowed !== false) {
    throw new Error("Development analysis cannot access validation, external, or fresh event data.");
  }
  if (contract.causalFeatureDefinitions?.decisionTime !== "END_OF_EACH_COMPLETE_M1_EVENT_BAR" ||
      contract.causalFeatureDefinitions?.minimumM15WarmupBars !== 50) {
    throw new Error("Causal feature decision and warmup clocks must remain frozen.");
  }
  if (contract.positionContract?.entry !== "NEXT_CONTIGUOUS_M1_OPEN_AFTER_SIGNAL_CLOSE_WITH_ADVERSE_ENTRY_SLIPPAGE") {
    throw new Error("Analysis entry must occur after the completed signal minute.");
  }
  if (contract.positionContract?.sameBarPriority?.join(",") !== [
    "GAP_LIQUIDATION",
    "GAP_STOP",
    "INTRABAR_STOP",
    "INTRABAR_LIQUIDATION",
    "INTRABAR_TARGET",
    "TIME_EXIT",
  ].join(",")) {
    throw new Error("Same-bar execution priority must remain adverse and deterministic.");
  }
  if (contract.outcomePolicy?.analysisContractMayChangeAfterOutcome !== false ||
      contract.outcomePolicy?.automaticExecutionAllowed !== false ||
      contract.outcomePolicy?.liveExecutionAllowed !== false) {
    throw new Error("Development analysis cannot mutate itself or authorize execution.");
  }
  return contract;
}

function validateV3AnalysisContract(contract) {
  if (contract.status !== EXPECTED_V3_STATUS) throw new Error("Event-flow v3 confirmation stage must be predeclared before replay.");
  if (contract.parentResult?.resultReceiptSha256 !== EXPECTED_V2_RESULT_RECEIPT_HASH ||
      contract.parentResult?.requiredStatus !== "REJECTED_NO_STATISTICALLY_POSITIVE_FAMILY") {
    throw new Error("Event-flow v3 must bind the rejected v2 result receipt.");
  }
  if (contract.acquisitionProtocol?.protocolSha256 !== EXPECTED_ACQUISITION_PROTOCOL_HASH ||
      contract.acquisitionProtocol?.validationExternalAndFreshDataAllowed !== false) {
    throw new Error("Event-flow v3 cannot change acquisition evidence or access locked data.");
  }
  const trials = contract.trialAccounting;
  if (trials?.priorEvidenceContractCandidates !== 124 || trials.stageCandidates !== 16 ||
      trials.cumulativeCandidates !== 140 || trials.maximumCumulativeCandidates !== 192) {
    throw new Error("Event-flow v3 trial accounting must remain frozen at 124 + 16 = 140 of 192.");
  }
  const hypothesis = contract.hypothesis;
  const candidateCount = Object.values(hypothesis?.grid ?? {}).reduce(
    (count, values) => count * (Array.isArray(values) ? values.length : 0),
    1,
  );
  if (hypothesis?.family !== "CONFIRMED_ABSORPTION_REVERSAL" || candidateCount !== 16 || hypothesis.candidateCount !== 16 ||
      hypothesis.setupFixed?.minimumEffectiveStopFloorPct !== 0.004 || hypothesis.setupFixed?.targetR !== 2.5) {
    throw new Error("Event-flow v3 must preserve its bounded 16-candidate confirmed-reversal family.");
  }
  if (contract.stateMachine?.confirmationMustOccurAfterSetupClose !== true ||
      contract.stateMachine?.preReplaySetupAllowed !== false ||
      contract.stateMachine?.confirmedToPending !== "NEXT_CONTIGUOUS_M1_OPEN_AFTER_CONFIRMATION_CLOSE") {
    throw new Error("Event-flow v3 must confirm causally after setup and enter on the next minute.");
  }
  const development = contract.chronologicalDevelopment;
  if (development?.selectionEras?.join(",") !== "2023H1,2023H2" ||
      development?.validationEras?.join(",") !== "2024H1,2024H2" ||
      development?.selectedCandidateMayChangeBetweenValidationEras !== false ||
      development?.trainingEligibility?.minimumTrades !== 15 ||
      development?.validationGate?.minimumPooledTrades !== 15 ||
      development?.validationGate?.minimumPositiveEraCount !== 2 ||
      development?.validationGate?.minimumBootstrapLowerMeanNetR !== 0) {
    throw new Error("Event-flow v3 chronological selection and validation gates must remain frozen.");
  }
  if (contract.outcomePolicy?.analysisContractMayChangeAfterOutcome !== false ||
      contract.outcomePolicy?.automaticExecutionAllowed !== false ||
      contract.outcomePolicy?.liveExecutionAllowed !== false) {
    throw new Error("Event-flow v3 cannot mutate after outcome or authorize execution.");
  }
  return contract;
}

function validateV2AnalysisContract(contract) {
  if (contract.status !== EXPECTED_V2_STATUS) throw new Error("Event-flow v2 execution repair must be predeclared before replay.");
  if (contract.parentResult?.resultReceiptSha256 !== EXPECTED_V1_RESULT_RECEIPT_HASH ||
      contract.parentResult?.requiredStatus !== "REJECTED_EXECUTION_INCOMPATIBLE_ZERO_TRADES") {
    throw new Error("Event-flow v2 must bind the rejected v1 result receipt.");
  }
  if (contract.inheritsAnalysisContract?.analysisContractSha256 !== EXPECTED_V1_ANALYSIS_HASH ||
      contract.inheritsAnalysisContract?.causalFeaturesUnchanged !== true ||
      contract.inheritsAnalysisContract?.signalDefinitionsUnchanged !== true ||
      contract.inheritsAnalysisContract?.nestedSelectionAndGateUnchanged !== true) {
    throw new Error("Event-flow v2 must preserve v1 features, signals, selection, and gates.");
  }
  if (contract.acquisitionProtocol?.protocolSha256 !== EXPECTED_ACQUISITION_PROTOCOL_HASH ||
      contract.acquisitionProtocol?.validationExternalAndFreshDataAllowed !== false) {
    throw new Error("Event-flow v2 cannot change acquisition evidence or access locked data.");
  }
  const trials = contract.trialAccounting;
  if (trials?.priorEvidenceContractCandidates !== 92 || trials.stageCandidates !== 32 ||
      trials.cumulativeCandidates !== 124 || trials.maximumCumulativeCandidates !== 192) {
    throw new Error("Event-flow v2 trial accounting must remain frozen at 92 + 32 = 124 of 192.");
  }
  if (contract.candidateSet?.signalParametersMayChange !== false || contract.candidateSet?.candidateIdPrefix !== "sf04_") {
    throw new Error("Event-flow v2 cannot change signal parameters or candidate identity prefix.");
  }
  const repair = contract.executionRepair;
  if (repair?.kind !== "COST_AWARE_MINIMUM_EFFECTIVE_STOP_FLOOR" || repair.minimumStopFloorPct !== 0.004 ||
      repair.belowFloorPolicy !== "WIDEN_STOP_TO_FLOOR_AND_RESIZE_QUANTITY" ||
      repair.aboveMaximumInitialRiskPolicy !== "NO_TRADE" ||
      repair.feeSlippageLeverageHoldingLimitDailyLimitAndCooldownUnchanged !== true ||
      repair.sameBarPriorityUnchanged !== true) {
    throw new Error("Event-flow v2 may only apply the frozen 0.4 percent effective stop floor.");
  }
  if (contract.costRationale?.riskFloorWasSelectedFromOutcome !== false ||
      contract.outcomePolicy?.analysisContractMayChangeAfterOutcome !== false ||
      contract.outcomePolicy?.automaticExecutionAllowed !== false ||
      contract.outcomePolicy?.liveExecutionAllowed !== false) {
    throw new Error("Event-flow v2 cannot optimize its floor from outcomes or authorize execution.");
  }
  return contract;
}
