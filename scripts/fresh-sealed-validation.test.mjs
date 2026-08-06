import assert from "node:assert/strict";
import fs from "node:fs/promises";
import test from "node:test";
import { sha256 } from "./lib/research-evidence.mjs";

const protocolPath = new URL("../config/fresh-sealed-validation-2026-08-v1.json", import.meta.url);
const registryPath = new URL("../config/research-sealed-registry-v1.json", import.meta.url);

test("fresh captured candles are reserved before any candidate replay", async () => {
  const protocol = JSON.parse(await fs.readFile(protocolPath, "utf8"));
  const [window] = protocol.windows;

  assert.equal(protocol.status, "SEALED_BEFORE_REPLAY");
  assert.equal(protocol.generation.tuningAllowed, false);
  assert.equal(protocol.generation.candidateMayBeSelectedAfterWindowInspection, false);
  assert.equal(protocol.windows.length, 1);
  assert.equal(protocol.windowsSha256, sha256(protocol.windows));
  assert.equal(window.replayStartAt, protocol.sourceData.previousResearchClosedThroughExclusive);
  assert.equal(window.replayEndAt, protocol.sourceData.closedThroughExclusive);
  assert.ok(Date.parse(window.replayEndAt) > Date.parse(window.replayStartAt));
  assert.equal(Date.parse(window.replayStartAt) % (15 * 60_000), 0);
  assert.equal(Date.parse(window.replayEndAt) % (15 * 60_000), 0);
});

test("fresh protocol is available exactly once in the sealed registry", async () => {
  const registry = JSON.parse(await fs.readFile(registryPath, "utf8"));
  const matches = registry.protocols.filter((item) => item.protocolId === "fresh-sealed-validation-2026-08-v1");

  assert.equal(matches.length, 1);
  assert.equal(matches[0].path, "config/fresh-sealed-validation-2026-08-v1.json");
  assert.equal(matches[0].status, "AVAILABLE");
});
