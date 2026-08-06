import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  evaluateFixedExtension,
  parseArgs,
  validateFixedExtensionAcquisition,
} from "./event-flow-fixed-extension-replay.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await fs.readFile(path.join(repositoryRoot, "config/bybit-event-flow-fixed-extension-v1.json"));
const protocol = JSON.parse(protocolBytes);
const receipt = JSON.parse(await fs.readFile(
  path.join(repositoryRoot, "config/bybit-event-flow-fixed-extension-acquisition-receipt-v1.json"),
  "utf8",
));
const protocolSha256 = "41f109407b8e9076331f6c097573f789957b04e4de5ba58c3add5b08746c9049";

test("fixed extension replay defaults to sealed evidence and rejects undeclared arguments", () => {
  const options = parseArgs([]);
  assert.match(options.receipt, /config\/bybit-event-flow-fixed-extension-acquisition-receipt-v1\.json$/);
  assert.match(options.acquisitionReport, /build\/research\/bybit-event-flow-fixed-extension-v1-acquisition\.json$/);
  assert.match(options.output, /build\/research\/bybit-event-flow-fixed-extension-v1-result\.json$/);
  assert.throws(() => parseArgs(["--candidate=changed"]), /Unsupported argument/);
});

test("fixed extension acquisition validator rejects reordered or changed block evidence", () => {
  const report = {
    status: "COMPLETE",
    stage: "fixed-candidate-extension",
    protocolId: protocol.protocolId,
    protocolSha256,
    completedBlocks: receipt.blockFingerprints.map((block) => ({
      id: block.id,
      sourceFingerprintSha256: block.sha256,
    })),
    extensionSourceFingerprintSha256: receipt.extensionSourceFingerprintSha256,
    targetDatabaseSha256: receipt.researchDatabaseSha256,
  };
  assert.equal(validateFixedExtensionAcquisition(
    report,
    receipt,
    protocol,
    protocolSha256,
    receipt.acquisitionReportSha256,
  ), report);
  assert.throws(() => validateFixedExtensionAcquisition(
    { ...report, completedBlocks: [...report.completedBlocks].reverse() },
    receipt,
    protocol,
    protocolSha256,
    receipt.acquisitionReportSha256,
  ), /every declared block/);
});

test("fixed extension passes only when the one frozen candidate clears every stability gate", () => {
  const trades = protocol.blocks.flatMap((block, blockIndex) => Array.from({ length: 3 }, (_, tradeIndex) => ({
    blockId: block.id,
    side: (blockIndex + tradeIndex) % 2 === 0 ? "BUY" : "SELL",
    closedAtMs: Date.parse(block.replayStartAt) + tradeIndex * 60_000,
    netR: 0.2,
    maeR: -0.1,
    exitReason: "TARGET",
  })));
  const evaluation = evaluateFixedExtension(trades, protocol);
  assert.equal(evaluation.status, "FIXED_EXTENSION_PASSED_REFREEZE_REQUIRED");
  assert.equal(evaluation.gate.passed, true);
  assert.equal(evaluation.positiveQuarters, 8);
  assert.equal(evaluation.positiveYears, 2);
  assert.equal(evaluation.validationDataAcquisitionAllowed, false);
  assert.equal(evaluation.liveExecutionAllowed, false);
});

test("fixed extension rejects an undersampled outcome and cannot authorize execution", () => {
  const evaluation = evaluateFixedExtension([], protocol);
  assert.equal(evaluation.status, "REJECTED_FIXED_EXTENSION");
  assert.equal(evaluation.gate.checks.minimumTrades, false);
  assert.equal(evaluation.candidateRefreezeRequired, false);
  assert.equal(evaluation.automaticExecutionAllowed, false);
  assert.equal(evaluation.liveExecutionAllowed, false);
});
