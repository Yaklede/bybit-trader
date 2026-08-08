import assert from "node:assert/strict";
import test from "node:test";
import { compareVolumeConfirmedTrendParity } from "./volume-confirmed-trend-parity.mjs";

test("trend parity comparison accepts bounded numeric drift and reordered object keys", () => {
  const expected = { policy: { daily: 0.03, losses: 3 }, values: [100, 90] };
  const actual = { values: [100 + 1e-9, 90], policy: { losses: 3, daily: 0.03 } };

  assert.deepEqual(compareVolumeConfirmedTrendParity(expected, actual), []);
});

test("trend parity comparison reports nested risk replay mismatches", () => {
  const mismatches = compareVolumeConfirmedTrendParity(
    { riskPolicyReplay: { blockedEntryCount: 162 } },
    { riskPolicyReplay: { blockedEntryCount: 161 } },
  );

  assert.equal(mismatches.length, 1);
  assert.match(mismatches[0], /riskPolicyReplay.blockedEntryCount/);
});
