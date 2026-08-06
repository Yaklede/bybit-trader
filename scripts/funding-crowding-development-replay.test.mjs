import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

import { parseArgs, validateReceipt } from "./funding-crowding-development-replay.mjs";

const repositoryRoot = resolve(new URL("..", import.meta.url).pathname);
const receiptBytes = await readFile(resolve(
  repositoryRoot,
  "config/bybit-funding-crowding-development-acquisition-receipt-v1.json",
));
const receipt = JSON.parse(receiptBytes);
const receiptSha256 = createHash("sha256").update(receiptBytes).digest("hex");

test("development replay arguments cannot open later evidence stages", () => {
  const options = parseArgs([]);
  assert.match(options.protocol, /bybit-funding-crowding-development-v1\.json$/);
  assert.match(options.receipt, /bybit-funding-crowding-development-acquisition-receipt-v1\.json$/);
  assert.match(options.output, /bybit-funding-crowding-development-v1-result\.json$/);
  assert.throws(() => parseArgs(["--stage=internal"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2023-01-01"]), /Unsupported argument/);
});

test("development replay accepts only the exact committed locked receipt", () => {
  assert.doesNotThrow(() => validateReceipt(receipt, receiptSha256, receipt.protocolSha256));
  assert.throws(() => validateReceipt(
    { ...receipt, liveExecutionAllowed: true },
    receiptSha256,
    receipt.protocolSha256,
  ), /committed sealed acquisition receipt/);
  assert.throws(() => validateReceipt(receipt, "0".repeat(64), receipt.protocolSha256), /committed sealed/);
});
