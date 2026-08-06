import assert from "node:assert/strict";
import test from "node:test";

import {
  parseArgs,
  stageProtocolFromSealed,
} from "./multi-asset-cost-recovery-carry-sealed-acquire.mjs";

test("sealed acquisition accepts only operational output options", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-multi-asset-cost-recovery-carry-sealed-2026-h1-v3.json",
    "--request-delay-ms=250",
  ]);
  assert.match(parsed.protocol, /cost-recovery-carry-sealed-2026-h1-v3\.json$/);
  assert.equal(parsed.requestDelayMs, 250);
  assert.throws(() => parseArgs(["--start=2026-02-01"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--minimum-trades=1"]), /Unsupported argument/);
});

test("sealed acquisition maps exactly the frozen 2026 H1 range", () => {
  const protocol = {
    sourceData: {
      symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
      stageStart: "2026-01-01T00:00:00Z",
      stageEndExclusive: "2026-07-01T00:00:00Z",
    },
    sealedValidationBlocks: [{ id: "S01" }, { id: "S02" }],
  };
  const stage = stageProtocolFromSealed(protocol);
  assert.equal(stage.sourceData.developmentStart, protocol.sourceData.stageStart);
  assert.equal(stage.sourceData.developmentEndExclusive, protocol.sourceData.stageEndExclusive);
  assert.deepEqual(stage.evidenceSchedule.developmentBlocks, protocol.sealedValidationBlocks);
});

test("request delay must be a non-negative integer", () => {
  assert.throws(() => parseArgs(["--request-delay-ms=-1"]), /non-negative integer/);
  assert.throws(() => parseArgs(["--request-delay-ms=1.5"]), /non-negative integer/);
});
