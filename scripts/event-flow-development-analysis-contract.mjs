import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const EXPECTED_STATUS = "PREDECLARED_ANALYSIS_BEFORE_FEATURE_INSPECTION";
const EXPECTED_ACQUISITION_PROTOCOL_HASH = "0568fe88bacc55d6ab83f79e642d14a832716b06bc6b036116b298ef481e8a2d";

export async function loadAnalysisContract(path) {
  const bytes = await fs.readFile(path);
  return {
    contract: validateAnalysisContract(JSON.parse(bytes)),
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

export function validateAnalysisContract(contract) {
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
