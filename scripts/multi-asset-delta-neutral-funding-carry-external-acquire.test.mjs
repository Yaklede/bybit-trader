import assert from "node:assert/strict";
import test from "node:test";

import {
  parseArgs,
  stageProtocolFromExternal,
} from "./multi-asset-delta-neutral-funding-carry-external-acquire.mjs";

test("external acquisition cannot override its frozen year, candidate, or gate", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-multi-asset-delta-neutral-funding-carry-external-v2.json",
    "--request-delay-ms=200",
  ]);
  assert.match(parsed.protocol, /multi-asset-delta-neutral-funding-carry-external-v2\.json$/);
  assert.equal(parsed.requestDelayMs, 200);
  assert.throws(() => parseArgs(["--start=2024-01-01"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--minimum-trades=1"]), /Unsupported argument/);
});

test("external stage maps only the predeclared 2025 range and quarters", () => {
  const protocol = {
    sourceData: {
      symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
      stageStart: "2025-01-01T00:00:00Z",
      stageEndExclusive: "2026-01-01T00:00:00Z",
    },
    externalValidationBlocks: [{ id: "E01" }],
  };
  const stage = stageProtocolFromExternal(protocol);
  assert.equal(stage.sourceData.developmentStart, protocol.sourceData.stageStart);
  assert.equal(stage.sourceData.developmentEndExclusive, protocol.sourceData.stageEndExclusive);
  assert.deepEqual(stage.evidenceSchedule.developmentBlocks, protocol.externalValidationBlocks);
});
