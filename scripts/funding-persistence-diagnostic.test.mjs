import assert from "node:assert/strict";
import test from "node:test";

import {
  buildWindowObservations,
  evaluateFundingPersistenceGate,
  parseArgs,
  pearsonCorrelation,
  quantile,
} from "./funding-persistence-diagnostic.mjs";

test("diagnostic arguments cannot change windows, cost, or evidence", () => {
  const parsed = parseArgs(["--report=build/result.json"]);
  assert.match(parsed.protocol, /bybit-funding-persistence-diagnostic-v1\.json$/);
  assert.throws(() => parseArgs(["--cost=0"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2026-07-01"]), /Unsupported argument/);
});

test("window observations use only past rates for the feature and later rates for the outcome", () => {
  const rates = Array.from({ length: 8 }, (_, index) => ({ timestamp: index, rate: index + 1 }));
  const observations = buildWindowObservations(rates, 3, 2);
  assert.deepEqual(observations, [
    { timestamp: 2, trailingSum: 6, forwardSum: 9 },
    { timestamp: 3, trailingSum: 9, forwardSum: 11 },
    { timestamp: 4, trailingSum: 12, forwardSum: 13 },
    { timestamp: 5, trailingSum: 15, forwardSum: 15 },
  ]);
});

test("correlation and interpolated quantile are deterministic", () => {
  assert.equal(pearsonCorrelation([1, 2, 3], [2, 4, 6]), 1);
  assert.equal(pearsonCorrelation([1, 1, 1], [2, 3, 4]), 0);
  assert.equal(quantile([1, 2, 3, 4, 5], 0.8), 4.2);
});

test("viability requires at least two independently eligible symbols", () => {
  const eligible = {
    combinations: [{ trailingSettlements: 90, forwardSettlements: 90, anchoredPearsonCorrelation: 0.2 }],
    primary: {
      trailingSettlements: 90,
      forwardSettlements: 90,
      developmentHighPersistence: { observationCount: 7, costRecoveryRate: 0.7 },
      diagnosticHighPersistence: { observationCount: 2, costRecoveryRate: 0.5 },
    },
  };
  const gate = {
    minimumEligibleSymbolCount: 2,
    minimumAnchoredPearsonCorrelation: 0.15,
    minimumDevelopmentHighPersistenceObservationCount: 6,
    minimumDevelopmentCostRecoveryRate: 0.6,
    minimumDiagnosticHighPersistenceObservationCount: 2,
    minimumDiagnosticCostRecoveryRate: 0.5,
  };
  assert.equal(evaluateFundingPersistenceGate({ BTCUSDT: eligible, ETHUSDT: eligible }, gate).passed, true);
  assert.equal(evaluateFundingPersistenceGate({ BTCUSDT: eligible }, gate).passed, false);
});
