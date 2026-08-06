import assert from "node:assert/strict";
import test from "node:test";

import { parseArgs } from "./event-flow-fixed-extension-backfill.mjs";

test("fixed extension backfill accepts only a frozen protocol and optional report path", () => {
  const defaults = parseArgs([]);
  assert.match(defaults.protocol, /config\/bybit-event-flow-fixed-extension-v1\.json$/);
  const explicit = parseArgs(["--protocol=config/extension.json", "--report=build/extension.json"]);
  assert.match(explicit.protocol, /config\/extension\.json$/);
  assert.match(explicit.report, /build\/extension\.json$/);
  assert.throws(() => parseArgs(["--stage=validation"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["config/extension.json"]), /Use --name=value/);
});
