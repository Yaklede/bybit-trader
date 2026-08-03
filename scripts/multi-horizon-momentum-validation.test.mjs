import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";

const protocolPath = new URL("../config/multi-horizon-momentum-validation-windows-v1.json", import.meta.url);

test("sealed momentum validation windows are chronological and outside development data", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const windows = protocol.windows;

  assert.equal(protocol.status, "SEALED_BEFORE_REPLAY");
  assert.equal(windows.length, 10);
  assert.equal(protocol.promotionAllowed, false);
  assert.ok(windows.every((window) => window.purpose === "sealed-validation"));
  assert.ok(windows.every((window) => window.replayStartAt >= protocol.source.developmentDataEndAt));
  assert.ok(windows.every((window) => window.replayEndAt <= protocol.source.latestAvailableAt));

  for (let index = 1; index < windows.length; index += 1) {
    assert.equal(windows[index - 1].replayEndAt, windows[index].replayStartAt);
  }
});

test("validation replays one frozen candidate under declared cost stress", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));

  assert.equal(protocol.candidate, "multi_momentum_scale0.75_votes3_stop8_trail16_long_only");
  assert.deepEqual(protocol.costMultipliers, [1.0, 1.5, 2.0]);
});
