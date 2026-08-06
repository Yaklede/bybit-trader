import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { loadSubminuteSequenceProtocol } from "./subminute-sequence-protocol.mjs";
import {
  parseArgs,
  validateSelectionReceipt,
} from "./subminute-sequence-replay.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const protocolPath = path.join(repositoryRoot, "config/bybit-subminute-sequence-development-v1.json");
const receiptPath = path.join(
  repositoryRoot,
  "config/bybit-subminute-sequence-selection-acquisition-receipt-v1.json",
);

test("selection replay exposes no data, candidate, or execution overrides", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-subminute-sequence-development-v1.json",
    "--receipt=config/bybit-subminute-sequence-selection-acquisition-receipt-v1.json",
    "--output=build/research/result.json",
  ]);
  assert.match(parsed.protocol, /bybit-subminute-sequence-development-v1\.json$/);
  assert.match(parsed.receipt, /bybit-subminute-sequence-selection-acquisition-receipt-v1\.json$/);
  assert.match(parsed.output, /build\/research\/result\.json$/);
  assert.throws(() => parseArgs(["--snapshot=other.sqlite"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--output=a", "--output=b"]), /Duplicate argument/);
  assert.throws(() => parseArgs(["loose-argument"]), /Invalid argument/);
});

test("selection replay binds the committed receipt and keeps all later evidence locked", async () => {
  const loaded = await loadSubminuteSequenceProtocol(protocolPath);
  const bytes = await readFile(receiptPath);
  const receipt = JSON.parse(bytes);
  const hash = createHash("sha256").update(bytes).digest("hex");
  assert.equal(validateSelectionReceipt(loaded.protocol, loaded.sha256, receipt, hash), receipt);

  assert.throws(() => validateSelectionReceipt(
    loaded.protocol,
    loaded.sha256,
    { ...receipt, lockedEvidence: { ...receipt.lockedEvidence, internalValidation2024Read: true } },
    hash,
  ), /cannot use internal/);
  assert.throws(() => validateSelectionReceipt(
    loaded.protocol,
    loaded.sha256,
    { ...receipt, stageSnapshot: "build/research/alternate.sqlite" },
    hash,
  ), /coverage or snapshot identity/);
  assert.throws(() => validateSelectionReceipt(loaded.protocol, loaded.sha256, receipt, "0".repeat(64)), /committed 2023/);
});
