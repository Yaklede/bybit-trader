import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import { execFileSync } from "node:child_process";

const EULER_MASCHERONI = 0.5772156649015329;
const REQUIRED_EXPERIMENT_STATUSES = new Set(["PREDECLARED_DEVELOPMENT", "PREDECLARED_VALIDATION"]);
const PARTITIONS = new Set(["DEVELOPMENT", "EXTERNAL", "SEALED"]);

export function canonicalJson(value) {
  return JSON.stringify(canonicalize(value));
}

export function sha256(value) {
  return createHash("sha256").update(typeof value === "string" ? value : canonicalJson(value)).digest("hex");
}

export async function sha256File(filePath) {
  const hash = createHash("sha256");
  await new Promise((resolve, reject) => {
    const input = createReadStream(filePath);
    input.on("data", (chunk) => hash.update(chunk));
    input.on("end", resolve);
    input.on("error", reject);
  });
  return hash.digest("hex");
}

export function validateExperimentDefinition(definition) {
  if (definition?.schemaVersion !== 1) throw new Error("Experiment definition must use schemaVersion=1.");
  if (!isIdentifier(definition.experimentId)) throw new Error("Experiment definition requires a stable experimentId.");
  if (!REQUIRED_EXPERIMENT_STATUSES.has(definition.status)) {
    throw new Error("Experiment status must be PREDECLARED_DEVELOPMENT or PREDECLARED_VALIDATION.");
  }
  if (!isNonEmptyString(definition.hypothesis?.family) || !isNonEmptyString(definition.hypothesis?.statement)) {
    throw new Error("Experiment hypothesis requires family and statement.");
  }
  if (!Array.isArray(definition.hypothesis?.falsificationCriteria) || definition.hypothesis.falsificationCriteria.length === 0) {
    throw new Error("Experiment hypothesis requires at least one falsification criterion.");
  }
  if (
    !Number.isInteger(definition.trials?.cumulativeCount) ||
    definition.trials.cumulativeCount < 1 ||
    !Number.isInteger(definition.trials?.stageCandidateCount) ||
    definition.trials.stageCandidateCount < 1 ||
    definition.trials.stageCandidateCount > definition.trials.cumulativeCount
  ) {
    throw new Error("Experiment trials must declare positive cumulativeCount and stageCandidateCount values.");
  }
  if (!isIdentifier(definition.candidate?.id) || !isSha256(definition.candidate?.fingerprint)) {
    throw new Error("Experiment candidate requires an id and SHA-256 fingerprint.");
  }
  if (definition.selection?.candidateFrozenBeforeExternalReplay !== true) {
    throw new Error("Candidate must be frozen before external replay.");
  }
  if (definition.selection?.candidateFrozenBeforeSealedReplay !== true) {
    throw new Error("Candidate must be frozen before sealed replay.");
  }
  if (definition.selection?.automaticPromotionAllowed !== false) {
    throw new Error("Experiment definitions cannot allow automatic promotion.");
  }
  const inputs = definition.inputs;
  if (!Array.isArray(inputs) || inputs.length === 0) throw new Error("Experiment definition requires frozen inputs.");
  const inputIds = new Set();
  const inputPaths = new Set();
  for (const input of inputs) {
    if (!isIdentifier(input?.id) || inputIds.has(input.id)) throw new Error("Experiment inputs require unique ids.");
    if (!isSafeRelativePath(input?.path) || inputPaths.has(input.path)) {
      throw new Error("Experiment inputs require unique repository-relative paths.");
    }
    if (!isNonEmptyString(input.role)) throw new Error(`Experiment input ${input.id} requires a role.`);
    inputIds.add(input.id);
    inputPaths.add(input.path);
  }
  const protocols = definition.protocols;
  if (!Array.isArray(protocols) || protocols.length === 0) throw new Error("Experiment definition requires protocols.");
  const protocolIds = new Set();
  for (const protocol of protocols) {
    if (!isIdentifier(protocol?.protocolId) || protocolIds.has(protocol.protocolId)) {
      throw new Error("Experiment protocols require unique protocolId values.");
    }
    if (!PARTITIONS.has(protocol.partition)) throw new Error(`Invalid protocol partition: ${protocol.partition}.`);
    if (!isSafeRelativePath(protocol.path)) throw new Error(`Protocol ${protocol.protocolId} requires a safe path.`);
    protocolIds.add(protocol.protocolId);
  }
  return definition;
}

export function validateApprovalPolicy(policy) {
  if (policy?.schemaVersion !== 1 || policy?.status !== "ACTIVE" || !isIdentifier(policy.policyId)) {
    throw new Error("Approval policy must be an ACTIVE schemaVersion=1 policy.");
  }
  const selection = policy.selectionPolicy;
  if (
    selection?.dailyCompoundReturnIsSearchObjective !== false ||
    selection?.candidateMustBeFrozenBeforeExternalReplay !== true ||
    selection?.candidateMustBeFrozenBeforeSealedReplay !== true ||
    selection?.automaticLivePromotionAllowed !== false ||
    !Number.isInteger(selection?.maximumCumulativeTrialCount) ||
    selection.maximumCumulativeTrialCount < 1
  ) {
    throw new Error("Approval selection policy is incomplete or unsafe.");
  }
  const requirements = policy.evidenceRequirements;
  if (
    !Array.isArray(requirements?.requiredInputRoles) ||
    requirements.requiredInputRoles.length === 0 ||
    requirements.requiredInputRoles.some((role) => !isNonEmptyString(role)) ||
    !Array.isArray(requirements?.requiredPartitions) ||
    requirements.requiredPartitions.some((partition) => !PARTITIONS.has(partition)) ||
    !Array.isArray(requirements?.requiredCostMultipliers) ||
    requirements.requiredCostMultipliers.some((value) => !Number.isFinite(value) || value < 1) ||
    !positive(requirements?.baseRiskFraction) ||
    !Array.isArray(requirements?.requiredRiskFractions) ||
    requirements.requiredRiskFractions.some((value) => !positive(value)) ||
    !requirements.requiredRiskFractions.includes(requirements.baseRiskFraction) ||
    !Number.isInteger(requirements?.minimumExternalFoldCount) ||
    requirements.minimumExternalFoldCount < 1 ||
    !Number.isInteger(requirements?.minimumSealedFoldCount) ||
    requirements.minimumSealedFoldCount < 1 ||
    !between(requirements?.minimumExternalPositiveFoldRatio, 0, 1) ||
    !Number.isInteger(requirements?.minimumTotalClosedTrades) ||
    requirements.minimumTotalClosedTrades < 1 ||
    !positive(requirements?.maximumBaseDrawdownPct) ||
    !positive(requirements?.maximumStressDrawdownPct) ||
    requirements.maximumStressDrawdownPct < requirements.maximumBaseDrawdownPct ||
    !Number.isInteger(requirements?.maximumLiquidationCount) ||
    requirements.maximumLiquidationCount < 0 ||
    !between(requirements?.maximumSingleTradeProfitContributionPct, Number.EPSILON, 100) ||
    !isNonEmptyString(requirements?.requiredParityStatus)
  ) {
    throw new Error("Approval evidence requirements are incomplete.");
  }
  const statistics = policy.statistics;
  if (
    !Number.isInteger(statistics?.bootstrap?.iterations) ||
    statistics.bootstrap.iterations < 100 ||
    !Number.isInteger(statistics.bootstrap.blockLengthTrades) ||
    statistics.bootstrap.blockLengthTrades < 1 ||
    !between(statistics.bootstrap.confidenceLevel, 0.5, 0.9999) ||
    !Number.isFinite(statistics.bootstrap.minimumExpectancyLowerBoundR) ||
    !Number.isInteger(statistics.bootstrap.seed) ||
    !Number.isInteger(statistics?.deflatedSharpe?.minimumObservationCount) ||
    statistics.deflatedSharpe.minimumObservationCount < 2 ||
    !between(statistics.deflatedSharpe.minimumProbability, 0, 1) ||
    !Number.isInteger(statistics?.probabilityOfBacktestOverfitting?.minimumCandidateCount) ||
    statistics.probabilityOfBacktestOverfitting.minimumCandidateCount < 2 ||
    !Number.isInteger(statistics.probabilityOfBacktestOverfitting.minimumObservationCount) ||
    statistics.probabilityOfBacktestOverfitting.minimumObservationCount < 2 ||
    !between(statistics.probabilityOfBacktestOverfitting.maximumProbability, 0, 1) ||
    !Number.isInteger(statistics.probabilityOfBacktestOverfitting.maximumCombinationCount) ||
    statistics.probabilityOfBacktestOverfitting.maximumCombinationCount < 1
  ) {
    throw new Error("Approval statistical policy is incomplete.");
  }
  const forward = policy.forwardValidation;
  if (
    forward?.requiredForLiveApproval !== true ||
    !Array.isArray(forward.allowedModes) ||
    forward.allowedModes.length === 0 ||
    forward.allowedModes.some((mode) => !new Set(["SHADOW", "PAPER"]).has(mode)) ||
    !Number.isInteger(forward.minimumCalendarDays) ||
    forward.minimumCalendarDays < 1 ||
    !Number.isInteger(forward.minimumClosedTrades) ||
    forward.minimumClosedTrades < 1 ||
    !between(forward.minimumNoDriftPValue, 0, 1) ||
    !Number.isInteger(forward.maximumUnprotectedPositionCount) ||
    forward.maximumUnprotectedPositionCount < 0 ||
    !Number.isInteger(forward.maximumLedgerMismatchCount) ||
    forward.maximumLedgerMismatchCount < 0
  ) {
    throw new Error("Approval forward-validation policy is incomplete.");
  }
  return policy;
}

export function validateSealedRegistry(registry) {
  if (registry?.schemaVersion !== 1 || !isIdentifier(registry.registryId) || !Array.isArray(registry.protocols)) {
    throw new Error("Sealed registry must be a schemaVersion=1 registry.");
  }
  const ids = new Set();
  for (const protocol of registry.protocols) {
    if (!isIdentifier(protocol?.protocolId) || ids.has(protocol.protocolId)) {
      throw new Error("Sealed registry protocol ids must be unique.");
    }
    if (!isSafeRelativePath(protocol.path)) throw new Error(`Registry protocol ${protocol.protocolId} has an invalid path.`);
    if (!new Set(["AVAILABLE", "CONSUMED_REJECTED", "CONSUMED_APPROVED"]).has(protocol.status)) {
      throw new Error(`Registry protocol ${protocol.protocolId} has an invalid status.`);
    }
    if (protocol.status !== "AVAILABLE" && (!isIdentifier(protocol.consumedByExperimentId) || !isNonEmptyString(protocol.consumedAt))) {
      throw new Error(`Consumed protocol ${protocol.protocolId} requires an experiment and date.`);
    }
    ids.add(protocol.protocolId);
  }
  return registry;
}

export async function sealExperiment({ definition, definitionPath, policy, policyPath, registry, registryPath, repoRoot }) {
  validateExperimentDefinition(definition);
  validateApprovalPolicy(policy);
  validateSealedRegistry(registry);
  const declaredRoles = new Set(definition.inputs.map((input) => input.role));
  const missingRoles = policy.evidenceRequirements.requiredInputRoles.filter((role) => !declaredRoles.has(role));
  if (missingRoles.length > 0) throw new Error(`Experiment inputs are missing required roles: ${missingRoles.join(", ")}.`);
  const root = path.resolve(repoRoot);
  const sealedProtocolReservations = definition.protocols
    .filter((protocol) => protocol.partition === "SEALED")
    .map((protocol) => {
      const registered = registry.protocols.find((item) => item.protocolId === protocol.protocolId);
      if (registered == null) throw new Error(`Sealed protocol is not registered: ${protocol.protocolId}.`);
      if (registered.path !== protocol.path) throw new Error(`Sealed protocol path does not match registry: ${protocol.protocolId}.`);
      if (registered.status !== "AVAILABLE") throw new Error(`Sealed protocol is not available: ${protocol.protocolId}.`);
      return { ...registered };
    });
  const declared = [
    { id: "experiment-definition", role: "EXPERIMENT_DEFINITION", path: relativeToRoot(root, definitionPath) },
    { id: "approval-policy", role: "APPROVAL_POLICY", path: relativeToRoot(root, policyPath) },
    { id: "sealed-registry", role: "SEALED_REGISTRY", path: relativeToRoot(root, registryPath) },
    ...definition.inputs,
    ...definition.protocols.map((protocol) => ({
      id: `protocol-${protocol.protocolId}`,
      role: `${protocol.partition}_PROTOCOL`,
      path: protocol.path,
    })),
  ];
  const uniquePaths = [...new Map(declared.map((input) => [input.path, input])).values()];
  const resolvedInputs = [];
  for (const input of uniquePaths) {
    const absolutePath = resolveRepositoryPath(root, input.path);
    const stat = await fs.stat(absolutePath);
    if (!stat.isFile()) throw new Error(`Experiment input is not a file: ${input.path}`);
    resolvedInputs.push({
      ...input,
      bytes: stat.size,
      sha256: await sha256File(absolutePath),
    });
  }

  const relevantPaths = new Set(uniquePaths.map((input) => input.path));
  const dirtyPaths = gitDirtyPaths(root);
  const relevantDirtyPaths = dirtyPaths.filter((dirtyPath) => relevantPaths.has(dirtyPath));
  const definitionFingerprint = sha256(definition);
  const policyFingerprint = sha256(policy);
  const registryFingerprint = sha256(registry);
  const manifestCore = {
    schemaVersion: 1,
    experimentId: definition.experimentId,
    status: "SEALED",
    gitSha: git(root, ["rev-parse", "HEAD"]).trim(),
    definitionFingerprint,
    policyId: policy.policyId,
    policyFingerprint,
    registryId: registry.registryId,
    registryFingerprint,
    candidate: definition.candidate,
    trials: definition.trials,
    hypothesis: definition.hypothesis,
    selection: definition.selection,
    protocols: definition.protocols,
    sealedProtocolReservations,
    inputs: resolvedInputs,
    relevantDirtyPaths,
    reproducible: relevantDirtyPaths.length === 0,
  };
  return {
    ...manifestCore,
    manifestFingerprint: sha256(manifestCore),
  };
}

export async function verifyManifestInputs(manifest, repoRoot) {
  if (manifest?.schemaVersion !== 1 || manifest?.status !== "SEALED" || !Array.isArray(manifest?.inputs)) {
    throw new Error("A sealed manifest with input fingerprints is required.");
  }
  const root = path.resolve(repoRoot);
  const dirtyPaths = new Set(gitDirtyPaths(root));
  const checks = [];
  for (const input of manifest.inputs) {
    if (!isSafeRelativePath(input?.path) || !isSha256(input?.sha256) || !Number.isInteger(input?.bytes)) {
      checks.push({ id: input?.id ?? null, path: input?.path ?? null, status: "INVALID_DECLARATION" });
      continue;
    }
    if (input.role === "SEALED_REGISTRY") {
      checks.push({ id: input.id, path: input.path, role: input.role, status: "REGISTRY_TRANSITION_ALLOWED" });
      continue;
    }
    try {
      const absolutePath = resolveRepositoryPath(root, input.path);
      const stat = await fs.stat(absolutePath);
      const currentSha256 = stat.isFile() ? await sha256File(absolutePath) : null;
      const dirty = dirtyPaths.has(input.path);
      const matches = stat.isFile() && stat.size === input.bytes && currentSha256 === input.sha256 && !dirty;
      checks.push({
        id: input.id,
        path: input.path,
        role: input.role,
        status: matches ? "MATCH" : "MISMATCH",
        expectedBytes: input.bytes,
        actualBytes: stat.size,
        expectedSha256: input.sha256,
        actualSha256: currentSha256,
        dirty,
      });
    } catch (error) {
      checks.push({
        id: input.id,
        path: input.path,
        role: input.role,
        status: "UNAVAILABLE",
        error: error.code ?? error.name ?? "ERROR",
      });
    }
  }
  const verificationCore = {
    schemaVersion: 1,
    manifestFingerprint: manifest.manifestFingerprint,
    status: checks.every((check) => ["MATCH", "REGISTRY_TRANSITION_ALLOWED"].includes(check.status)) ? "PASS" : "FAIL",
    checks,
  };
  return {
    ...verificationCore,
    verificationFingerprint: sha256(verificationCore),
  };
}

export function movingBlockBootstrap(values, options) {
  if (!Array.isArray(values) || values.length === 0 || values.some((value) => !Number.isFinite(value))) {
    throw new Error("Bootstrap values must be a non-empty finite array.");
  }
  const iterations = options?.iterations;
  const blockLength = options?.blockLength;
  const confidenceLevel = options?.confidenceLevel;
  const seed = options?.seed;
  if (!Number.isInteger(iterations) || iterations < 100) throw new Error("Bootstrap iterations must be at least 100.");
  if (!Number.isInteger(blockLength) || blockLength < 1) throw new Error("Bootstrap blockLength must be positive.");
  if (!between(confidenceLevel, 0.5, 0.9999)) throw new Error("Bootstrap confidenceLevel is invalid.");
  if (!Number.isInteger(seed)) throw new Error("Bootstrap seed must be an integer.");

  const random = mulberry32(seed);
  const sampleMeans = new Array(iterations);
  const maximumInformativeBlockLength = Math.max(1, Math.floor(values.length / 2));
  const effectiveBlockLength = Math.min(blockLength, maximumInformativeBlockLength);
  for (let iteration = 0; iteration < iterations; iteration += 1) {
    let sum = 0;
    let count = 0;
    while (count < values.length) {
      const start = Math.floor(random() * values.length);
      for (let offset = 0; offset < effectiveBlockLength && count < values.length; offset += 1) {
        sum += values[(start + offset) % values.length];
        count += 1;
      }
    }
    sampleMeans[iteration] = sum / values.length;
  }
  sampleMeans.sort((left, right) => left - right);
  const alpha = 1 - confidenceLevel;
  return {
    method: "CIRCULAR_MOVING_BLOCK_BOOTSTRAP",
    observationCount: values.length,
    iterations,
    blockLength: effectiveBlockLength,
    confidenceLevel,
    seed,
    sampleMean: average(values),
    lowerBound: quantile(sampleMeans, alpha),
    upperBound: quantile(sampleMeans, 1 - alpha),
  };
}

export function deflatedSharpeRatio(selectedReturns, trialReturns) {
  validateReturnSeries(selectedReturns, "selectedReturns");
  if (!Array.isArray(trialReturns) || trialReturns.length < 2) {
    throw new Error("Deflated Sharpe requires at least two trial return series.");
  }
  trialReturns.forEach((values, index) => validateReturnSeries(values, `trialReturns[${index}]`));
  if (trialReturns.some((values) => values.length !== selectedReturns.length)) {
    throw new Error("Deflated Sharpe trial returns must share the selected observation count.");
  }
  const trialSharpes = trialReturns.map(sharpeRatio);
  if (trialSharpes.some((value) => !Number.isFinite(value))) {
    throw new Error("Deflated Sharpe trial series must have finite Sharpe ratios.");
  }
  const selectedSharpe = sharpeRatio(selectedReturns);
  const trialSharpeVariance = sampleVariance(trialSharpes);
  const trialCount = trialSharpes.length;
  const expectedMaximumSharpe = Math.sqrt(Math.max(0, trialSharpeVariance)) * (
    (1 - EULER_MASCHERONI) * inverseNormalCdf(1 - 1 / trialCount) +
    EULER_MASCHERONI * inverseNormalCdf(1 - 1 / (trialCount * Math.E))
  );
  const skewness = sampleSkewness(selectedReturns);
  const kurtosis = sampleKurtosis(selectedReturns);
  const denominatorSquared =
    1 - skewness * selectedSharpe + ((kurtosis - 1) / 4) * selectedSharpe * selectedSharpe;
  const zScore = denominatorSquared > 0
    ? ((selectedSharpe - expectedMaximumSharpe) * Math.sqrt(selectedReturns.length - 1)) / Math.sqrt(denominatorSquared)
    : Number.NEGATIVE_INFINITY;
  return {
    method: "BAILEY_LOPEZ_DE_PRADO_DSR",
    observationCount: selectedReturns.length,
    trialCount,
    selectedSharpe,
    trialSharpeVariance,
    expectedMaximumSharpe,
    skewness,
    kurtosis,
    zScore,
    probability: normalCdf(zScore),
  };
}

export function probabilityBacktestOverfitting(returnMatrix, options = {}) {
  if (!Array.isArray(returnMatrix) || returnMatrix.length < 2 || returnMatrix.some((row) => !Array.isArray(row))) {
    throw new Error("PBO requires a rectangular return matrix.");
  }
  const candidateCount = returnMatrix[0].length;
  if (candidateCount < 2 || returnMatrix.some((row) => row.length !== candidateCount || row.some((value) => !Number.isFinite(value)))) {
    throw new Error("PBO return matrix must be finite and rectangular with at least two candidates.");
  }
  const requestedSlices = options.slices ?? Math.min(8, returnMatrix.length - (returnMatrix.length % 2));
  if (!Number.isInteger(requestedSlices) || requestedSlices < 2 || requestedSlices % 2 !== 0 || requestedSlices > returnMatrix.length) {
    throw new Error("PBO slices must be an even integer within the observation count.");
  }
  const blocks = contiguousBlocks(returnMatrix.length, requestedSlices);
  const combinations = chooseIndices(requestedSlices, requestedSlices / 2);
  const maxCombinationCount = options.maxCombinationCount ?? Number.POSITIVE_INFINITY;
  if (combinations.length > maxCombinationCount) {
    throw new Error(`PBO combination count ${combinations.length} exceeds limit ${maxCombinationCount}.`);
  }
  let overfitCount = 0;
  const logits = [];
  for (const inSampleBlocks of combinations) {
    const inSampleSet = new Set(inSampleBlocks);
    const inSampleRows = blocks.flatMap((block, index) => inSampleSet.has(index) ? block : []);
    const outOfSampleRows = blocks.flatMap((block, index) => inSampleSet.has(index) ? [] : block);
    const inSampleScores = candidateScores(returnMatrix, inSampleRows);
    const outOfSampleScores = candidateScores(returnMatrix, outOfSampleRows);
    const selectedIndex = indexOfMaximum(inSampleScores);
    const rank = averageRank(outOfSampleScores, selectedIndex);
    const omega = rank / (candidateCount + 1);
    const logit = Math.log(omega / (1 - omega));
    logits.push(logit);
    if (logit <= 0) overfitCount += 1;
  }
  return {
    method: "CSCV_PBO",
    observationCount: returnMatrix.length,
    candidateCount,
    slices: requestedSlices,
    combinationCount: combinations.length,
    overfitCount,
    probability: overfitCount / combinations.length,
    medianLogit: median(logits),
  };
}

export function evaluateResearchEvidence({ manifest, run, policy, registry, inputVerification }) {
  validateApprovalPolicy(policy);
  validateSealedRegistry(registry);
  const integrityFailures = [];
  const gateFailures = [];
  const incompleteReasons = [];

  if (manifest?.schemaVersion !== 1 || manifest?.status !== "SEALED" || !isSha256(manifest?.manifestFingerprint)) {
    integrityFailures.push("MANIFEST_INVALID");
  } else if (sha256(withoutKey(manifest, "manifestFingerprint")) !== manifest.manifestFingerprint) {
    integrityFailures.push("MANIFEST_FINGERPRINT_MISMATCH");
  }
  if (manifest?.reproducible !== true || (manifest?.relevantDirtyPaths?.length ?? 0) > 0) {
    integrityFailures.push("RELEVANT_INPUTS_DIRTY_AT_SEAL");
  }
  if (run?.schemaVersion !== 1 || run?.experimentId !== manifest?.experimentId) integrityFailures.push("RUN_EXPERIMENT_MISMATCH");
  if (run?.manifestFingerprint !== manifest?.manifestFingerprint) integrityFailures.push("RUN_MANIFEST_MISMATCH");
  if (run?.candidateFingerprint !== manifest?.candidate?.fingerprint) integrityFailures.push("RUN_CANDIDATE_MISMATCH");
  if (manifest?.policyFingerprint !== sha256(policy)) integrityFailures.push("POLICY_FINGERPRINT_MISMATCH");
  if (
    inputVerification?.status !== "PASS" ||
    inputVerification?.manifestFingerprint !== manifest?.manifestFingerprint ||
    !isSha256(inputVerification?.verificationFingerprint) ||
    sha256(withoutKey(inputVerification, "verificationFingerprint")) !== inputVerification.verificationFingerprint
  ) {
    integrityFailures.push("CURRENT_INPUT_VERIFICATION_FAILED");
  }
  if (manifest?.trials?.cumulativeCount > policy.selectionPolicy.maximumCumulativeTrialCount) {
    gateFailures.push("TRIAL_BUDGET_EXCEEDED");
  }
  if (run?.parity?.status !== policy.evidenceRequirements.requiredParityStatus) gateFailures.push("EXECUTION_PARITY_FAILED");

  const folds = Array.isArray(run?.folds) ? run.folds : [];
  validateFoldEvidence(folds, integrityFailures);
  validateFoldRanges(folds, integrityFailures);
  for (const partition of policy.evidenceRequirements.requiredPartitions) {
    if (!folds.some((fold) => fold.partition === partition)) incompleteReasons.push(`PARTITION_${partition}_MISSING`);
  }
  for (const multiplier of policy.evidenceRequirements.requiredCostMultipliers) {
    if (!folds.some((fold) => fold.costMultiplier === multiplier && fold.riskFraction === policy.evidenceRequirements.baseRiskFraction)) {
      incompleteReasons.push(`COST_STRESS_${multiplier}_MISSING`);
    }
  }
  for (const riskFraction of policy.evidenceRequirements.requiredRiskFractions) {
    if (!folds.some((fold) => fold.costMultiplier === 1 && fold.riskFraction === riskFraction)) {
      incompleteReasons.push(`RISK_STRESS_${riskFraction}_MISSING`);
    }
  }

  const externalBase = folds.filter((fold) =>
    fold.partition === "EXTERNAL" && fold.costMultiplier === 1 && fold.riskFraction === policy.evidenceRequirements.baseRiskFraction
  );
  const sealedBase = folds.filter((fold) =>
    fold.partition === "SEALED" && fold.costMultiplier === 1 && fold.riskFraction === policy.evidenceRequirements.baseRiskFraction
  );
  if (externalBase.length < policy.evidenceRequirements.minimumExternalFoldCount) incompleteReasons.push("EXTERNAL_FOLD_COUNT_INSUFFICIENT");
  if (sealedBase.length < policy.evidenceRequirements.minimumSealedFoldCount) incompleteReasons.push("SEALED_FOLD_COUNT_INSUFFICIENT");
  const externalPositiveFoldRatio = externalBase.length === 0
    ? 0
    : externalBase.filter((fold) => fold.netReturnPct > 0).length / externalBase.length;
  if (externalBase.length > 0 && externalPositiveFoldRatio < policy.evidenceRequirements.minimumExternalPositiveFoldRatio) {
    gateFailures.push("EXTERNAL_POSITIVE_FOLD_RATIO_BELOW_MINIMUM");
  }

  const allTrades = folds
    .filter((fold) =>
      fold.costMultiplier === 1 &&
      fold.riskFraction === policy.evidenceRequirements.baseRiskFraction &&
      ["EXTERNAL", "SEALED"].includes(fold.partition)
    )
    .flatMap((fold) => fold.trades ?? []);
  if (allTrades.length < policy.evidenceRequirements.minimumTotalClosedTrades) incompleteReasons.push("CLOSED_TRADE_COUNT_INSUFFICIENT");
  const liquidationCount = folds.reduce((sum, fold) => sum + (fold.liquidationCount ?? 0), 0);
  if (liquidationCount > policy.evidenceRequirements.maximumLiquidationCount) gateFailures.push("LIQUIDATION_LIMIT_EXCEEDED");
  const baseDrawdownPct = maxOrZero(
    folds
      .filter((fold) => fold.costMultiplier === 1 && fold.riskFraction === policy.evidenceRequirements.baseRiskFraction)
      .map((fold) => fold.maxDrawdownPct),
  );
  const stressDrawdownPct = maxOrZero(
    folds
      .filter((fold) => fold.costMultiplier > 1 || fold.riskFraction !== policy.evidenceRequirements.baseRiskFraction)
      .map((fold) => fold.maxDrawdownPct),
  );
  if (baseDrawdownPct > policy.evidenceRequirements.maximumBaseDrawdownPct) gateFailures.push("BASE_DRAWDOWN_LIMIT_EXCEEDED");
  if (stressDrawdownPct > policy.evidenceRequirements.maximumStressDrawdownPct) gateFailures.push("STRESS_DRAWDOWN_LIMIT_EXCEEDED");

  const positivePnl = allTrades.filter((trade) => trade.pnl > 0).map((trade) => trade.pnl);
  const grossProfit = positivePnl.reduce((sum, value) => sum + value, 0);
  const singleTradeProfitContributionPct = grossProfit > 0 ? (Math.max(...positivePnl) / grossProfit) * 100 : 100;
  if (singleTradeProfitContributionPct > policy.evidenceRequirements.maximumSingleTradeProfitContributionPct) {
    gateFailures.push("SINGLE_TRADE_PROFIT_CONCENTRATION_EXCEEDED");
  }

  let bootstrap = null;
  const rMultiples = allTrades.map((trade) => trade.rMultipleNet).filter(Number.isFinite);
  if (rMultiples.length === allTrades.length && rMultiples.length > 0) {
    bootstrap = movingBlockBootstrap(rMultiples, {
      iterations: policy.statistics.bootstrap.iterations,
      blockLength: policy.statistics.bootstrap.blockLengthTrades,
      confidenceLevel: policy.statistics.bootstrap.confidenceLevel,
      seed: policy.statistics.bootstrap.seed,
    });
    if (bootstrap.lowerBound <= policy.statistics.bootstrap.minimumExpectancyLowerBoundR) {
      gateFailures.push("BOOTSTRAP_EXPECTANCY_LOWER_BOUND_NOT_POSITIVE");
    }
  } else {
    incompleteReasons.push("BOOTSTRAP_TRADE_RETURNS_MISSING");
  }

  let deflatedSharpe = null;
  const selectedReturns = run?.statistics?.selectedReturns;
  const trialReturns = run?.statistics?.trialReturns;
  const selectedTrialIndex = run?.statistics?.selectedTrialIndex;
  if (Array.isArray(trialReturns) && trialReturns.length !== manifest?.trials?.cumulativeCount) {
    incompleteReasons.push("TRIAL_LEDGER_RETURNS_MISMATCH");
  }
  if (
    Array.isArray(selectedReturns) &&
    selectedReturns.length >= policy.statistics.deflatedSharpe.minimumObservationCount &&
    Array.isArray(trialReturns) &&
    Number.isInteger(selectedTrialIndex) &&
    selectedTrialIndex >= 0 &&
    selectedTrialIndex < trialReturns.length &&
    canonicalJson(selectedReturns) === canonicalJson(trialReturns[selectedTrialIndex])
  ) {
    try {
      deflatedSharpe = deflatedSharpeRatio(selectedReturns, trialReturns);
      if (deflatedSharpe.probability < policy.statistics.deflatedSharpe.minimumProbability) {
        gateFailures.push("DEFLATED_SHARPE_BELOW_MINIMUM");
      }
    } catch {
      integrityFailures.push("DEFLATED_SHARPE_CALCULATION_FAILED");
    }
  } else {
    incompleteReasons.push("DEFLATED_SHARPE_INPUT_INSUFFICIENT");
  }

  let pbo = null;
  const returnMatrix = run?.statistics?.candidateReturnMatrix;
  if (
    Array.isArray(returnMatrix?.[0]) &&
    returnMatrix[0].length !== manifest?.trials?.stageCandidateCount
  ) {
    incompleteReasons.push("PBO_STAGE_CANDIDATE_COUNT_MISMATCH");
  }
  if (
    Array.isArray(returnMatrix) &&
    returnMatrix.length >= policy.statistics.probabilityOfBacktestOverfitting.minimumObservationCount &&
    Array.isArray(returnMatrix[0]) &&
    returnMatrix[0].length >= policy.statistics.probabilityOfBacktestOverfitting.minimumCandidateCount
  ) {
    try {
      pbo = probabilityBacktestOverfitting(returnMatrix, {
        maxCombinationCount: policy.statistics.probabilityOfBacktestOverfitting.maximumCombinationCount,
      });
      if (pbo.probability > policy.statistics.probabilityOfBacktestOverfitting.maximumProbability) {
        gateFailures.push("PBO_ABOVE_MAXIMUM");
      }
    } catch {
      integrityFailures.push("PBO_CALCULATION_FAILED");
    }
  } else {
    incompleteReasons.push("PBO_INPUT_INSUFFICIENT");
  }

  const sealedProtocolChecks = [];
  for (const protocol of manifest?.protocols?.filter((item) => item.partition === "SEALED") ?? []) {
    const registered = registry.protocols.find((item) => item.protocolId === protocol.protocolId);
    const reservation = manifest?.sealedProtocolReservations?.find((item) => item.protocolId === protocol.protocolId);
    const protocolInput = manifest?.inputs?.find((input) => input.id === `protocol-${protocol.protocolId}`);
    const receipt = run?.protocolReceipts?.find((item) => item.protocolId === protocol.protocolId);
    const reservedAvailable = reservation?.status === "AVAILABLE" && reservation?.path === protocol.path;
    const registryAccepts =
      registered?.status === "AVAILABLE" ||
      (registered?.status?.startsWith("CONSUMED_") && registered?.consumedByExperimentId === manifest?.experimentId);
    const receiptMatches =
      receipt?.protocolSha256 === protocolInput?.sha256 &&
      receipt?.candidateFingerprint === manifest?.candidate?.fingerprint &&
      isIsoTimestamp(receipt?.replayedAt);
    sealedProtocolChecks.push({
      protocolId: protocol.protocolId,
      registryStatus: registered?.status ?? "MISSING",
      reservedAvailable,
      registryAccepts,
      receiptMatches,
    });
    if (!reservedAvailable) integrityFailures.push(`SEALED_PROTOCOL_${protocol.protocolId}_NOT_RESERVED`);
    if (!registryAccepts) integrityFailures.push(`SEALED_PROTOCOL_${protocol.protocolId}_NOT_AVAILABLE`);
    if (!receiptMatches) integrityFailures.push(`SEALED_PROTOCOL_${protocol.protocolId}_RECEIPT_MISMATCH`);
  }

  const forward = evaluateForwardEvidence(run?.forwardEvidence, policy.forwardValidation);
  const uniqueIntegrityFailures = [...new Set(integrityFailures)];
  const uniqueGateFailures = [...new Set(gateFailures)];
  const uniqueIncompleteReasons = [...new Set(incompleteReasons)];
  let status;
  if (uniqueIntegrityFailures.length > 0) status = "INVALID_EVIDENCE";
  else if (uniqueGateFailures.length > 0) status = "REJECTED";
  else if (uniqueIncompleteReasons.length > 0) status = "INCOMPLETE";
  else if (!forward.passed) status = "FORWARD_VALIDATION_REQUIRED";
  else status = "VERIFIED";

  const reportCore = {
    schemaVersion: 1,
    experimentId: manifest?.experimentId ?? null,
    manifestFingerprint: manifest?.manifestFingerprint ?? null,
    candidateFingerprint: run?.candidateFingerprint ?? null,
    status,
    automaticExecutionAllowed: false,
    integrityFailures: uniqueIntegrityFailures,
    gateFailures: uniqueGateFailures,
    incompleteReasons: uniqueIncompleteReasons,
    metrics: {
      externalFoldCount: externalBase.length,
      sealedFoldCount: sealedBase.length,
      externalPositiveFoldRatio,
      totalClosedTrades: allTrades.length,
      liquidationCount,
      baseDrawdownPct,
      stressDrawdownPct,
      singleTradeProfitContributionPct,
      bootstrap,
      deflatedSharpe,
      probabilityOfBacktestOverfitting: pbo,
    },
    sealedProtocolChecks,
    forwardValidation: forward,
  };
  return {
    ...reportCore,
    reportFingerprint: sha256(reportCore),
  };
}

function evaluateForwardEvidence(evidence, policy) {
  if (policy?.requiredForLiveApproval !== true) return { required: false, passed: true, reasons: [] };
  const reasons = [];
  if (!policy.allowedModes.includes(evidence?.mode)) reasons.push("FORWARD_MODE_INVALID");
  if (!Number.isFinite(evidence?.calendarDays) || evidence.calendarDays < policy.minimumCalendarDays) {
    reasons.push("FORWARD_CALENDAR_DAYS_INSUFFICIENT");
  }
  if (!Number.isInteger(evidence?.closedTrades) || evidence.closedTrades < policy.minimumClosedTrades) {
    reasons.push("FORWARD_CLOSED_TRADES_INSUFFICIENT");
  }
  if (!Number.isFinite(evidence?.modelDriftPValue) || evidence.modelDriftPValue <= policy.minimumNoDriftPValue) {
    reasons.push("FORWARD_MODEL_DRIFT_DETECTED_OR_UNKNOWN");
  }
  if (evidence?.unprotectedPositionCount !== policy.maximumUnprotectedPositionCount) {
    reasons.push("FORWARD_UNPROTECTED_POSITION_DETECTED");
  }
  if (evidence?.ledgerMismatchCount !== policy.maximumLedgerMismatchCount) reasons.push("FORWARD_LEDGER_MISMATCH_DETECTED");
  return { required: true, passed: reasons.length === 0, reasons };
}

function validateFoldEvidence(folds, failures) {
  const keys = new Set();
  for (const fold of folds) {
    const key = `${fold?.partition}|${fold?.id}|${fold?.costMultiplier}|${fold?.riskFraction}`;
    if (
      !isIdentifier(fold?.id) ||
      !PARTITIONS.has(fold?.partition) ||
      !Number.isFinite(fold?.costMultiplier) ||
      fold.costMultiplier < 1 ||
      !positive(fold?.riskFraction) ||
      !Number.isFinite(fold?.netReturnPct) ||
      !isIsoTimestamp(fold?.replayStartAt) ||
      !isIsoTimestamp(fold?.replayEndAt) ||
      Date.parse(fold.replayEndAt) <= Date.parse(fold.replayStartAt) ||
      !Number.isFinite(fold?.maxDrawdownPct) ||
      fold.maxDrawdownPct < 0 ||
      !Number.isInteger(fold?.liquidationCount) ||
      fold.liquidationCount < 0 ||
      !Array.isArray(fold?.trades)
    ) {
      failures.push("FOLD_EVIDENCE_INVALID");
      continue;
    }
    if (keys.has(key)) failures.push("FOLD_EVIDENCE_DUPLICATED");
    keys.add(key);
    for (const trade of fold.trades) {
      if (!Number.isFinite(trade?.pnl) || !Number.isFinite(trade?.rMultipleNet)) failures.push("TRADE_EVIDENCE_INVALID");
    }
  }
}

function validateFoldRanges(folds, failures) {
  const groups = new Map();
  for (const fold of folds) {
    if (!isIsoTimestamp(fold?.replayStartAt) || !isIsoTimestamp(fold?.replayEndAt)) continue;
    const key = `${fold.partition}|${fold.costMultiplier}|${fold.riskFraction}`;
    const ranges = groups.get(key) ?? [];
    ranges.push({ start: Date.parse(fold.replayStartAt), end: Date.parse(fold.replayEndAt) });
    groups.set(key, ranges);
  }
  for (const ranges of groups.values()) {
    ranges.sort((left, right) => left.start - right.start);
    for (let index = 1; index < ranges.length; index += 1) {
      if (ranges[index].start < ranges[index - 1].end) failures.push("FOLD_EVIDENCE_OVERLAPS_WITHIN_PARTITION");
    }
  }
}

function candidateScores(matrix, rowIndices) {
  const columns = matrix[0].length;
  const scores = new Array(columns).fill(0);
  for (let column = 0; column < columns; column += 1) {
    const values = rowIndices.map((row) => matrix[row][column]);
    const deviation = standardDeviation(values);
    scores[column] = deviation > 0 ? average(values) / deviation : average(values);
  }
  return scores;
}

function averageRank(values, selectedIndex) {
  const selected = values[selectedIndex];
  let below = 0;
  let equal = 0;
  for (const value of values) {
    if (value < selected) below += 1;
    else if (value === selected) equal += 1;
  }
  return below + (equal + 1) / 2;
}

function contiguousBlocks(length, count) {
  const blocks = [];
  for (let block = 0; block < count; block += 1) {
    const start = Math.floor((block * length) / count);
    const end = Math.floor(((block + 1) * length) / count);
    blocks.push(Array.from({ length: end - start }, (_, offset) => start + offset));
  }
  return blocks;
}

function chooseIndices(total, selected) {
  const combinations = [];
  const current = [];
  function visit(start) {
    if (current.length === selected) {
      combinations.push([...current]);
      return;
    }
    for (let index = start; index <= total - (selected - current.length); index += 1) {
      current.push(index);
      visit(index + 1);
      current.pop();
    }
  }
  visit(0);
  return combinations;
}

function normalCdf(value) {
  if (value === Number.POSITIVE_INFINITY) return 1;
  if (value === Number.NEGATIVE_INFINITY) return 0;
  const sign = value < 0 ? -1 : 1;
  const x = Math.abs(value) / Math.sqrt(2);
  const t = 1 / (1 + 0.3275911 * x);
  const coefficients = [0.254829592, -0.284496736, 1.421413741, -1.453152027, 1.061405429];
  const polynomial = coefficients.reduceRight((accumulator, coefficient) => (accumulator * t) + coefficient, 0);
  const erf = sign * (1 - polynomial * t * Math.exp(-x * x));
  return 0.5 * (1 + erf);
}

function inverseNormalCdf(probability) {
  if (!(probability > 0 && probability < 1)) throw new Error("Normal quantile probability must be between zero and one.");
  const a = [-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239];
  const b = [-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572];
  const c = [-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783];
  const d = [0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416];
  const low = 0.02425;
  const high = 1 - low;
  if (probability < low) {
    const q = Math.sqrt(-2 * Math.log(probability));
    return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
      ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  if (probability > high) {
    const q = Math.sqrt(-2 * Math.log(1 - probability));
    return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
      ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
  }
  const q = probability - 0.5;
  const r = q * q;
  return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
}

function sharpeRatio(values) {
  const deviation = standardDeviation(values);
  return deviation > 0 ? average(values) / deviation : average(values) > 0 ? Number.POSITIVE_INFINITY : 0;
}

function sampleSkewness(values) {
  const mean = average(values);
  const deviation = standardDeviation(values);
  if (deviation === 0) return 0;
  return values.reduce((sum, value) => sum + ((value - mean) / deviation) ** 3, 0) / values.length;
}

function sampleKurtosis(values) {
  const mean = average(values);
  const deviation = standardDeviation(values);
  if (deviation === 0) return 3;
  return values.reduce((sum, value) => sum + ((value - mean) / deviation) ** 4, 0) / values.length;
}

function sampleVariance(values) {
  if (values.length < 2) return 0;
  const mean = average(values);
  return values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / (values.length - 1);
}

function standardDeviation(values) {
  return Math.sqrt(sampleVariance(values));
}

function validateReturnSeries(values, name) {
  if (!Array.isArray(values) || values.length < 2 || values.some((value) => !Number.isFinite(value))) {
    throw new Error(`${name} must contain at least two finite returns.`);
  }
}

function average(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function quantile(sortedValues, probability) {
  const index = Math.min(sortedValues.length - 1, Math.max(0, Math.floor(probability * sortedValues.length)));
  return sortedValues[index];
}

function indexOfMaximum(values) {
  let index = 0;
  for (let current = 1; current < values.length; current += 1) {
    if (values[current] > values[index]) index = current;
  }
  return index;
}

function maxOrZero(values) {
  return values.length === 0 ? 0 : Math.max(...values);
}

function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value != null && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
  }
  return value;
}

function git(root, args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8" });
}

function gitDirtyPaths(root) {
  const output = git(root, ["status", "--porcelain=v1", "--untracked-files=all"]);
  return output
    .split("\n")
    .filter(Boolean)
    .map((line) => line.slice(3))
    .map((value) => value.includes(" -> ") ? value.split(" -> ").at(-1) : value);
}

function resolveRepositoryPath(root, relativePath) {
  if (!isSafeRelativePath(relativePath)) throw new Error(`Unsafe repository path: ${relativePath}`);
  const resolved = path.resolve(root, relativePath);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) throw new Error(`Path escapes repository: ${relativePath}`);
  return resolved;
}

function relativeToRoot(root, filePath) {
  const relative = path.relative(root, path.resolve(filePath));
  if (!isSafeRelativePath(relative)) throw new Error(`File is outside repository: ${filePath}`);
  return relative;
}

function withoutKey(value, key) {
  const copy = { ...value };
  delete copy[key];
  return copy;
}

function isSafeRelativePath(value) {
  return isNonEmptyString(value) && !path.isAbsolute(value) && !value.split(/[\\/]/).includes("..");
}

function isIdentifier(value) {
  return typeof value === "string" && /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(value);
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

function isNonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function isIsoTimestamp(value) {
  return isNonEmptyString(value) && Number.isFinite(Date.parse(value));
}

function between(value, minimum, maximum) {
  return Number.isFinite(value) && value >= minimum && value <= maximum;
}

function positive(value) {
  return Number.isFinite(value) && value > 0;
}
