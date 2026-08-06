import assert from "node:assert/strict";
import test from "node:test";

import { parseArgs } from "./event-flow-accepted-continuation-replay.mjs";

test("accepted continuation replay accepts only frozen protocol and output paths", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-event-flow-accepted-continuation-v1.json",
    "--output=build/research/result.json",
  ]);
  assert.match(parsed.protocol, /bybit-event-flow-accepted-continuation-v1\.json$/);
  assert.match(parsed.output, /build\/research\/result\.json$/);
  assert.throws(() => parseArgs(["--candidate=adc_custom"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["loose-argument"]), /Invalid argument/);
});
