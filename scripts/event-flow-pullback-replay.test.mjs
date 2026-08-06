import assert from "node:assert/strict";
import test from "node:test";

import { parseArgs } from "./event-flow-pullback-replay.mjs";

test("pullback replay accepts no candidate or exit overrides", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-event-flow-pullback-reacceleration-v1.json",
    "--output=build/research/result.json",
  ]);
  assert.match(parsed.protocol, /bybit-event-flow-pullback-reacceleration-v1\.json$/);
  assert.match(parsed.output, /build\/research\/result\.json$/);
  assert.throws(() => parseArgs(["--target-r=9"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["loose-argument"]), /Invalid argument/);
});
