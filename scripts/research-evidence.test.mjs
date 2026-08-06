import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  canonicalJson,
  deflatedSharpeRatio,
  evaluateResearchEvidence,
  movingBlockBootstrap,
  probabilityBacktestOverfitting,
  sealExperiment,
  sha256,
  validateApprovalPolicy,
  validateExperimentDefinition,
  validateSealedRegistry,
  verifyManifestInputs,
} from "./lib/research-evidence.mjs";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const policyPath = path.join(repoRoot, "config/research-approval-policy-v1.json");
const registryPath = path.join(repoRoot, "config/research-sealed-registry-v1.json");

test("canonical fingerprints ignore object key insertion order", () => {
  assert.equal(canonicalJson({ b: 2, a: { d: 4, c: 3 } }), '{"a":{"c":3,"d":4},"b":2}');
  assert.equal(sha256({ b: 2, a: 1 }), sha256({ a: 1, b: 2 }));
});

test("approval policy and consumed sealed registry are fail-closed", async () => {
  const policy = JSON.parse(await fs.readFile(policyPath, "utf8"));
  const registry = JSON.parse(await fs.readFile(registryPath, "utf8"));
  assert.equal(validateApprovalPolicy(policy), policy);
  assert.equal(validateSealedRegistry(registry), registry);
  assert.equal(policy.selectionPolicy.dailyCompoundReturnIsSearchObjective, false);
  assert.equal(policy.selectionPolicy.automaticLivePromotionAllowed, false);
  assert.ok(registry.protocols.every((protocol) => protocol.status === "CONSUMED_REJECTED"));
});

test("experiment definitions require a frozen candidate and bounded trial ledger", () => {
  const definition = makeDefinition();
  assert.equal(validateExperimentDefinition(definition), definition);
  assert.throws(
    () => validateExperimentDefinition({ ...definition, selection: { ...definition.selection, candidateFrozenBeforeSealedReplay: false } }),
    /frozen before sealed replay/,
  );
  assert.throws(
    () => validateExperimentDefinition({ ...definition, trials: { cumulativeCount: 0, stageCandidateCount: 0 } }),
    /positive cumulativeCount/,
  );
});

test("moving block bootstrap is deterministic and separates positive from negative expectancy", () => {
  const options = { iterations: 2000, blockLength: 3, confidenceLevel: 0.95, seed: 42 };
  const positive = movingBlockBootstrap(Array.from({ length: 100 }, (_, index) => 0.2 + (index % 5) * 0.01), options);
  const repeated = movingBlockBootstrap(Array.from({ length: 100 }, (_, index) => 0.2 + (index % 5) * 0.01), options);
  const negative = movingBlockBootstrap(Array.from({ length: 100 }, (_, index) => -0.2 - (index % 5) * 0.01), options);
  assert.deepEqual(positive, repeated);
  assert.ok(positive.lowerBound > 0);
  assert.ok(negative.upperBound < 0);
});

test("CSCV PBO detects a selection process whose in-sample winner reverses out of sample", () => {
  const stable = Array.from({ length: 16 }, (_, row) => [0.03 + row * 0.0001, 0.02, 0.01, -0.01]);
  const stableResult = probabilityBacktestOverfitting(stable, { slices: 8 });
  assert.equal(stableResult.probability, 0);

  const reversing = Array.from({ length: 16 }, (_, row) => {
    const phase = Math.floor(row / 2);
    return [0, 1, 2, 3].map((candidate) => ((phase + candidate) % 4 < 2 ? 0.05 : -0.05));
  });
  const reversingResult = probabilityBacktestOverfitting(reversing, { slices: 8 });
  assert.ok(reversingResult.probability >= 0.5);
});

test("deflated Sharpe probability falls as the trial search expands", () => {
  const selected = Array.from({ length: 120 }, (_, index) => 0.0005 + ((index % 7) - 3) * 0.0015);
  const fewTrials = [
    Array.from({ length: 120 }, (_, index) => 0.0001 + ((index % 5) - 2) * 0.002),
    Array.from({ length: 120 }, (_, index) => 0.0002 + ((index % 9) - 4) * 0.0015),
  ];
  const manyTrials = Array.from({ length: 50 }, (_, trial) =>
    Array.from({ length: 120 }, (_, index) => ((trial % 10) - 4.5) * 0.0004 + ((index % 11) - 5) * 0.001),
  );
  const few = deflatedSharpeRatio(selected, fewTrials);
  const many = deflatedSharpeRatio(selected, manyTrials);
  assert.ok(few.probability > many.probability);
  assert.ok(few.probability >= 0 && few.probability <= 1);
});

test("evidence evaluation rejects reused sealed protocols and never enables execution", async () => {
  const policy = JSON.parse(await fs.readFile(policyPath, "utf8"));
  const registry = JSON.parse(await fs.readFile(registryPath, "utf8"));
  const manifestCore = {
    schemaVersion: 1,
    experimentId: "test-experiment-v1",
    status: "SEALED",
    policyFingerprint: sha256(policy),
    registryFingerprint: sha256(registry),
    candidate: { id: "candidate-a", fingerprint: sha256("candidate-a") },
    trials: { cumulativeCount: 4, stageCandidateCount: 4 },
    protocols: [{
      protocolId: "multi-horizon-momentum-validation-v1",
      partition: "SEALED",
      path: "config/multi-horizon-momentum-validation-windows-v1.json",
    }],
    sealedProtocolReservations: [{
      protocolId: "multi-horizon-momentum-validation-v1",
      path: "config/multi-horizon-momentum-validation-windows-v1.json",
      status: "AVAILABLE",
    }],
    inputs: [{ id: "protocol-multi-horizon-momentum-validation-v1", sha256: sha256("protocol") }],
    relevantDirtyPaths: [],
    reproducible: true,
  };
  const manifest = { ...manifestCore, manifestFingerprint: sha256(manifestCore) };
  const run = makePassingRun(manifest, policy);
  const result = evaluateResearchEvidence({
    manifest,
    run,
    policy,
    registry,
    inputVerification: passingInputVerification(manifest),
  });
  assert.equal(result.status, "INVALID_EVIDENCE");
  assert.ok(result.integrityFailures.includes("SEALED_PROTOCOL_multi-horizon-momentum-validation-v1_NOT_AVAILABLE"));
  assert.equal(result.automaticExecutionAllowed, false);
});

test("complete research evidence still requires independent forward validation", async () => {
  const policy = JSON.parse(await fs.readFile(policyPath, "utf8"));
  const availableRegistry = {
    schemaVersion: 1,
    registryId: "test-registry-v1",
    protocols: [{ protocolId: "fresh-sealed-v1", path: "config/fresh.json", status: "AVAILABLE" }],
  };
  const manifestCore = {
    schemaVersion: 1,
    experimentId: "test-experiment-v1",
    status: "SEALED",
    policyFingerprint: sha256(policy),
    registryFingerprint: sha256(availableRegistry),
    candidate: { id: "candidate-a", fingerprint: sha256("candidate-a") },
    trials: { cumulativeCount: 4, stageCandidateCount: 4 },
    protocols: [{ protocolId: "fresh-sealed-v1", partition: "SEALED", path: "config/fresh.json" }],
    sealedProtocolReservations: [{ protocolId: "fresh-sealed-v1", path: "config/fresh.json", status: "AVAILABLE" }],
    inputs: [{ id: "protocol-fresh-sealed-v1", sha256: sha256("fresh-protocol") }],
    relevantDirtyPaths: [],
    reproducible: true,
  };
  const manifest = { ...manifestCore, manifestFingerprint: sha256(manifestCore) };
  const run = makePassingRun(manifest, policy);
  const result = evaluateResearchEvidence({
    manifest,
    run,
    policy,
    registry: availableRegistry,
    inputVerification: passingInputVerification(manifest),
  });
  assert.equal(result.status, "FORWARD_VALIDATION_REQUIRED", JSON.stringify(result, null, 2));
  assert.equal(result.gateFailures.length, 0);
  assert.equal(result.incompleteReasons.length, 0);
  assert.equal(result.automaticExecutionAllowed, false);

  const consumedBySameExperiment = {
    ...availableRegistry,
    protocols: [{
      protocolId: "fresh-sealed-v1",
      path: "config/fresh.json",
      status: "CONSUMED_APPROVED",
      consumedByExperimentId: manifest.experimentId,
      consumedAt: "2026-08-06",
    }],
  };
  const replayedResult = evaluateResearchEvidence({
    manifest,
    run,
    policy,
    registry: consumedBySameExperiment,
    inputVerification: passingInputVerification(manifest),
  });
  assert.equal(replayedResult.status, "FORWARD_VALIDATION_REQUIRED");
});

test("sealing fingerprints every declared input and ignores unrelated dirty files", async () => {
  const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), "research-evidence-"));
  await fs.mkdir(path.join(temporaryRoot, "config"));
  await fs.mkdir(path.join(temporaryRoot, "src"));
  for (const role of ["strategy", "simulator", "execution", "feature", "fee", "risk", "data"]) {
    await fs.writeFile(path.join(temporaryRoot, `src/${role}.txt`), `${role}-v1\n`);
  }
  await fs.writeFile(path.join(temporaryRoot, "config/windows.json"), "{}\n");
  const definition = makeDefinition();
  const policy = JSON.parse(await fs.readFile(policyPath, "utf8"));
  const registry = {
    schemaVersion: 1,
    registryId: "test-registry-v1",
    protocols: [{ protocolId: "fresh-sealed-v1", path: "config/windows.json", status: "AVAILABLE" }],
  };
  const definitionPath = path.join(temporaryRoot, "config/definition.json");
  const localPolicyPath = path.join(temporaryRoot, "config/policy.json");
  const localRegistryPath = path.join(temporaryRoot, "config/registry.json");
  await fs.writeFile(definitionPath, JSON.stringify(definition));
  await fs.writeFile(localPolicyPath, JSON.stringify(policy));
  await fs.writeFile(localRegistryPath, JSON.stringify(registry));
  await runGit(temporaryRoot, ["init"]);
  await runGit(temporaryRoot, ["config", "user.email", "test@example.com"]);
  await runGit(temporaryRoot, ["config", "user.name", "Test"]);
  await runGit(temporaryRoot, ["add", "."]);
  await runGit(temporaryRoot, ["commit", "-m", "fixture"]);
  await fs.writeFile(path.join(temporaryRoot, "unrelated.txt"), "dirty\n");

  const manifest = await sealExperiment({
    definition,
    definitionPath,
    policy,
    policyPath: localPolicyPath,
    registry,
    registryPath: localRegistryPath,
    repoRoot: temporaryRoot,
  });
  assert.equal(manifest.reproducible, true);
  assert.equal(manifest.relevantDirtyPaths.length, 0);
  assert.equal(manifest.inputs.length, 11);
  assert.ok(manifest.inputs.every((input) => /^[a-f0-9]{64}$/.test(input.sha256)));
  const verification = await verifyManifestInputs(manifest, temporaryRoot);
  assert.equal(verification.status, "PASS");

  await fs.writeFile(path.join(temporaryRoot, "src/strategy.txt"), "strategy-v2\n");
  const changedVerification = await verifyManifestInputs(manifest, temporaryRoot);
  assert.equal(changedVerification.status, "FAIL");
  assert.equal(changedVerification.checks.find((check) => check.id === "strategy-source").status, "MISMATCH");
});

function makeDefinition() {
  return {
    schemaVersion: 1,
    experimentId: "test-experiment-v1",
    status: "PREDECLARED_VALIDATION",
    hypothesis: {
      family: "TEST_FAMILY",
      statement: "A deterministic test hypothesis.",
      falsificationCriteria: ["External net expectancy is not positive."],
    },
    trials: { cumulativeCount: 4, stageCandidateCount: 4 },
    candidate: { id: "candidate-a", fingerprint: sha256("candidate-a") },
    selection: {
      candidateFrozenBeforeExternalReplay: true,
      candidateFrozenBeforeSealedReplay: true,
      automaticPromotionAllowed: false,
    },
    inputs: [
      { id: "strategy-source", role: "STRATEGY_SOURCE", path: "src/strategy.txt" },
      { id: "simulator-source", role: "SIMULATOR_SOURCE", path: "src/simulator.txt" },
      { id: "execution-contract", role: "EXECUTION_CONTRACT", path: "src/execution.txt" },
      { id: "feature-schema", role: "FEATURE_SCHEMA", path: "src/feature.txt" },
      { id: "fee-model", role: "FEE_MODEL", path: "src/fee.txt" },
      { id: "risk-policy", role: "RISK_POLICY", path: "src/risk.txt" },
      { id: "data-snapshot", role: "DATA_SNAPSHOT", path: "src/data.txt" },
    ],
    protocols: [{ protocolId: "fresh-sealed-v1", partition: "SEALED", path: "config/windows.json" }],
  };
}

function makePassingRun(manifest, policy) {
  const folds = [];
  const baseRiskFraction = policy.evidenceRequirements.baseRiskFraction;
  for (const multiplier of policy.evidenceRequirements.requiredCostMultipliers) {
    for (let index = 0; index < 8; index += 1) {
      folds.push(makeFold(`E${index + 1}`, "EXTERNAL", multiplier, baseRiskFraction, index));
    }
    folds.push(makeFold("S1", "SEALED", multiplier, baseRiskFraction, 0));
  }
  for (const riskFraction of policy.evidenceRequirements.requiredRiskFractions) {
    if (riskFraction === baseRiskFraction) continue;
    for (let index = 0; index < 8; index += 1) {
      folds.push(makeFold(`E${index + 1}`, "EXTERNAL", 1, riskFraction, index));
    }
    folds.push(makeFold("S1", "SEALED", 1, riskFraction, 0));
  }
  const selectedReturns = Array.from({ length: 120 }, (_, index) => 0.01 + ((index % 5) - 2) * 0.001);
  const trialReturns = Array.from({ length: 4 }, (_, trial) =>
    Array.from({ length: 120 }, (_, index) => 0.001 * trial + ((index % 7) - 3) * 0.002),
  );
  const candidateReturnMatrix = Array.from({ length: 16 }, (_, row) => [
    0.04 + row * 0.0001,
    0.02,
    0.01,
    -0.01,
  ]);
  return {
    schemaVersion: 1,
    experimentId: manifest.experimentId,
    manifestFingerprint: manifest.manifestFingerprint,
    candidateFingerprint: manifest.candidate.fingerprint,
    parity: { status: "PASS" },
    folds,
    statistics: {
      selectedReturns,
      trialReturns: [selectedReturns, ...trialReturns.slice(1)],
      selectedTrialIndex: 0,
      candidateReturnMatrix,
    },
    protocolReceipts: (manifest.protocols ?? [])
      .filter((protocol) => protocol.partition === "SEALED")
      .map((protocol) => ({
        protocolId: protocol.protocolId,
        protocolSha256: manifest.inputs.find((input) => input.id === `protocol-${protocol.protocolId}`)?.sha256,
        candidateFingerprint: manifest.candidate.fingerprint,
        replayedAt: "2026-08-06T00:00:00Z",
      })),
    forwardEvidence: null,
  };
}

function makeFold(id, partition, costMultiplier, riskFraction, index) {
  const trades = Array.from({ length: partition === "EXTERNAL" ? 25 : 10 }, (_, trade) => ({
    pnl: 10 + ((trade + index) % 3),
    rMultipleNet: 0.2 + ((trade + index) % 5) * 0.01,
  }));
  return {
    id,
    partition,
    costMultiplier,
    riskFraction,
    replayStartAt: new Date(Date.UTC(2020 + index, partition === "SEALED" ? 1 : 0, 1)).toISOString(),
    replayEndAt: new Date(Date.UTC(2020 + index, partition === "SEALED" ? 2 : 1, 1)).toISOString(),
    netReturnPct: Math.max(0.1, 4 - (costMultiplier - 1) * 2),
    maxDrawdownPct: 5 + costMultiplier,
    liquidationCount: 0,
    trades,
  };
}

function passingInputVerification(manifest) {
  const core = {
    schemaVersion: 1,
    manifestFingerprint: manifest.manifestFingerprint,
    status: "PASS",
    checks: [],
  };
  return { ...core, verificationFingerprint: sha256(core) };
}

async function runGit(directory, args) {
  const { execFile } = await import("node:child_process");
  await new Promise((resolve, reject) => {
    execFile("git", args, { cwd: directory }, (error) => error ? reject(error) : resolve());
  });
}
