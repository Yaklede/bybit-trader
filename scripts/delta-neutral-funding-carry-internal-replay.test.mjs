import assert from "node:assert/strict";
import test from "node:test";

import {
  parseArgs,
  validateInternalAcquisitionReceipt,
} from "./delta-neutral-funding-carry-internal-replay.mjs";

test("internal replay cannot choose a candidate or open external evidence", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-delta-neutral-funding-carry-internal-v1.json",
    "--receipt=config/internal-receipt.json",
    "--report=build/internal-result.json",
  ]);
  assert.match(parsed.protocol, /bybit-delta-neutral-funding-carry-internal-v1\.json$/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--stage=external"]), /Unsupported argument/);
});

test("internal receipt must bind the exact candidate, simulator, and locked evidence", () => {
  const loaded = {
    sha256: "a".repeat(64),
    parentResultSha256: "b".repeat(64),
    researchLibrarySha256: "c".repeat(64),
    protocol: { selectedCandidateSha256: "d".repeat(64) },
  };
  const receipt = {
    status: "COMPLETE_DELTA_NEUTRAL_INTERNAL_EVIDENCE_SEALED",
    stage: "internal",
    protocolSha256: loaded.sha256,
    parentDevelopmentResultSha256: loaded.parentResultSha256,
    frozenCandidateSha256: loaded.protocol.selectedCandidateSha256,
    researchLibrarySha256: loaded.researchLibrarySha256,
    stageSnapshotSha256: "e".repeat(64),
    normalizedEvidenceSha256: "f".repeat(64),
    coverage: { matchingM5Rows: 105408, fundingRows: 1098, missingDecisionInputCount: 0 },
    integrity: { timeline: true, raw: true },
    lockedEvidence: { external: false, sealed: false },
    internalEvaluationAllowed: true,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  assert.equal(validateInternalAcquisitionReceipt(receipt, loaded), receipt);
  assert.throws(() => validateInternalAcquisitionReceipt({
    ...receipt,
    frozenCandidateSha256: "0".repeat(64),
  }, loaded), /not eligible/);
  assert.throws(() => validateInternalAcquisitionReceipt({
    ...receipt,
    lockedEvidence: { external: true },
  }, loaded), /not eligible/);
});
