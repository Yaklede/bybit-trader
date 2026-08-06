import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import {
  loadFundingRatesBySymbol,
  loadPortfolioFrames,
  parseArgs,
  rankCandidateEvaluations,
  validateAcquisitionReceipt,
  validateReplayFreeze,
} from "./multi-asset-delta-neutral-funding-carry-development-replay.mjs";
import {
  ensureMultiAssetDeltaNeutralFundingCarrySchema,
} from "./multi-asset-delta-neutral-funding-carry-acquire.mjs";

test("replay arguments cannot change stage, protocol, or evidence range", () => {
  const parsed = parseArgs(["--freeze=config/freeze.json", "--report=build/report.json"]);
  assert.match(parsed.freeze, /config\/freeze\.json$/);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--protocol=config/other.json"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2024-01-01"]), /Unsupported argument/);
});

test("freeze requires unchanged code, evidence, and unread outcomes", () => {
  const hash = "a".repeat(64);
  const manifest = {
    freezeId: "bybit-multi-asset-delta-neutral-funding-carry-development-replay-freeze-v1",
    status: "FROZEN_BEFORE_MULTI_ASSET_DEVELOPMENT_OUTCOME_REPLAY",
    protocol: { sha256: hash },
    acquisitionReceipt: { sha256: hash },
    implementation: { simulatorSha256: hash, replaySha256: hash },
    outcomeBoundary: {
      developmentCandidateMetricsReadBeforeFreeze: false,
      internalValidation2024Read: false,
      external2025Read: false,
      sealed2026Read: false,
    },
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  const actual = {
    protocolSha256: hash,
    acquisitionReceiptSha256: hash,
    simulatorSha256: hash,
    replaySha256: hash,
  };
  assert.doesNotThrow(() => validateReplayFreeze(manifest, actual));
  assert.throws(() => validateReplayFreeze({
    ...manifest,
    outcomeBoundary: { ...manifest.outcomeBoundary, developmentCandidateMetricsReadBeforeFreeze: true },
  }, actual), /boundary changed/);
});

test("receipt validation requires all symbols and keeps later evidence locked", () => {
  const protocolSha256 = "a".repeat(64);
  const receipt = {
    status: "COMPLETE_MULTI_ASSET_DEVELOPMENT_EVIDENCE_SEALED",
    stage: "development",
    protocolSha256,
    stageSnapshotSha256: "b".repeat(64),
    normalizedEvidenceSha256: "c".repeat(64),
    coverage: {
      symbolCount: 3,
      seriesPerSymbol: 4,
      expectedM5RowsPerSeries: 105120,
      matchingM5RowsPerSymbol: 105120,
      totalMatchingM5Rows: 315360,
      fundingRowsPerSymbol: 1095,
      totalFundingRows: 3285,
      missingDecisionInputCount: 0,
    },
    integrity: { complete: true },
    lockedEvidence: { internal: false, external: false, sealed: false, forward: false },
    developmentEvaluationAllowed: true,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  assert.doesNotThrow(() => validateAcquisitionReceipt(receipt, protocolSha256));
  assert.throws(() => validateAcquisitionReceipt({
    ...receipt,
    coverage: { ...receipt.coverage, symbolCount: 2 },
  }, protocolSha256), /not eligible/);
});

test("portfolio loader keeps symbol timelines and funding separate", () => {
  const db = new DatabaseSync(":memory:");
  ensureMultiAssetDeltaNeutralFundingCarrySchema(db);
  const symbols = ["BTCUSDT", "ETHUSDT", "SOLUSDT"];
  const timestamps = [
    Date.parse("2023-01-01T00:00:00Z"),
    Date.parse("2023-01-01T00:05:00Z"),
  ];
  const insertBar = db.prepare("INSERT INTO marketBars VALUES (?,?,?,?,?,?,?,NULL,NULL)");
  const insertFunding = db.prepare("INSERT INTO fundingRates VALUES (?,?,?)");
  for (const [symbolIndex, symbol] of symbols.entries()) {
    for (const series of ["SPOT_LAST", "PERPETUAL_LAST", "PERPETUAL_MARK", "PERPETUAL_INDEX"]) {
      for (const timestamp of timestamps) {
        const price = String(100 + symbolIndex);
        insertBar.run(symbol, series, instant(timestamp), price, price, price, price);
      }
    }
    insertFunding.run(symbol, instant(timestamps[0]), String(0.0001 + symbolIndex * 0.0001));
  }
  const protocol = {
    sourceData: {
      symbols,
      developmentStart: instant(timestamps[0]),
      developmentEndExclusive: instant(timestamps[1] + 5 * 60 * 1_000),
    },
  };
  const frames = loadPortfolioFrames(db, protocol);
  const funding = loadFundingRatesBySymbol(db, protocol);
  assert.deepEqual(Object.keys(frames), symbols);
  assert.equal(frames.ETHUSDT.length, 2);
  assert.equal(frames.SOLUSDT[0].spot.close, 102);
  assert.equal(funding.BTCUSDT[0].rate, 0.0001);
  assert.equal(Math.abs(funding.SOLUSDT[0].rate - 0.0003) < 1e-15, true);
  db.close();
});

test("ranking is deterministic and never selects a failed candidate over a pass", () => {
  const rows = [
    evaluation("b", false, ["x"], -0.1, 2, 1),
    evaluation("c", true, [], 0.01, 0.2, 0.01),
    evaluation("a", true, [], 0.02, 0.1, 0.01),
  ];
  assert.deepEqual(rankCandidateEvaluations(rows).map((row) => row.candidate.id), ["a", "c", "b"]);
});

function evaluation(id, passed, failedChecks, margin, costStress, bootstrap) {
  return {
    candidate: { id },
    gate: { passed, failedChecks, minimumGateMargin: margin },
    metrics: { costStressNetReturnPct: costStress, bootstrapLowerMeanDailyReturnPct: bootstrap },
  };
}

function instant(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}
