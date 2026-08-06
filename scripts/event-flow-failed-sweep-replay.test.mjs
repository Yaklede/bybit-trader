import assert from "node:assert/strict";
import test from "node:test";

import { parseArgs } from "./event-flow-failed-sweep-replay.mjs";

test("failed-sweep replay accepts no candidate overrides", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-event-flow-failed-sweep-reversal-v1.json",
    "--output=build/research/result.json",
  ]);
  assert.match(parsed.protocol, /bybit-event-flow-failed-sweep-reversal-v1\.json$/);
  assert.match(parsed.output, /build\/research\/result\.json$/);
  assert.throws(() => parseArgs(["--threshold=0"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["loose-argument"]), /Invalid argument/);
});
