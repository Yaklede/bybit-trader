import assert from "node:assert/strict";
import test from "node:test";

import { parseArgs } from "./subminute-sequence-normalized-replay.mjs";

test("normalized replay exposes no source, threshold, candidate, or execution overrides", () => {
  const parsed = parseArgs([
    "--protocol=config/bybit-subminute-sequence-normalized-v2.json",
    "--output=build/research/normalized-result.json",
  ]);
  assert.match(parsed.protocol, /bybit-subminute-sequence-normalized-v2\.json$/);
  assert.match(parsed.output, /build\/research\/normalized-result\.json$/);
  assert.throws(() => parseArgs(["--snapshot=other.sqlite"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--microprice=0.1"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--target-r=5"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--output=a", "--output=b"]), /Duplicate argument/);
  assert.throws(() => parseArgs(["loose-argument"]), /Invalid argument/);
});
