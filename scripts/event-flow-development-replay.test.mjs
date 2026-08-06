import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import { parseArgs, validateAcquisitionReport } from "./event-flow-development-replay.mjs";

const repositoryRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocolBytes = await fs.readFile(path.join(repositoryRoot, "config/bybit-event-flow-development-v1.json"));
const protocol = JSON.parse(protocolBytes);
const protocolSha256 = "0568fe88bacc55d6ab83f79e642d14a832716b06bc6b036116b298ef481e8a2d";

test("development replay paths default to generated research evidence", () => {
  const options = parseArgs([]);
  assert.match(options.acquisitionReport, /build\/research\/bybit-event-flow-development-v1-acquisition\.json$/);
  assert.match(options.output, /build\/research\/bybit-event-flow-development-v1-result\.json$/);
  assert.throws(() => parseArgs(["--stage=validation"]), /Unsupported argument/);
});

test("replay rejects an incomplete or reordered acquisition receipt", () => {
  const complete = {
    status: "COMPLETE",
    stage: "development",
    protocolId: protocol.protocolId,
    protocolSha256,
    targetDatabaseSha256: "a".repeat(64),
    developmentSourceFingerprintSha256: "b".repeat(64),
    completedBlocks: protocol.stages.development.primaryBlocks.map((block) => ({ id: block.id })),
  };
  assert.equal(validateAcquisitionReport(complete, protocol, protocolSha256), complete);
  assert.throws(() => validateAcquisitionReport({ ...complete, status: "IN_PROGRESS" }, protocol, protocolSha256), /complete development/);
  assert.throws(
    () => validateAcquisitionReport({ ...complete, completedBlocks: [...complete.completedBlocks].reverse() }, protocol, protocolSha256),
    /every primary development block in order/,
  );
});
