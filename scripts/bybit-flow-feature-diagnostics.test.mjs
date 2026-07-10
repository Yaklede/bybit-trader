import assert from "node:assert/strict";
import test from "node:test";
import {
  accountCrowdingBand,
  buildDiagnosticReport,
  fundingBand,
  imbalanceBand,
  oiChangeBand,
  parseArgs,
  relativeVolumeBand,
  summarizeReturns,
} from "./bybit-flow-feature-diagnostics.mjs";

test("parseArgs validates research-only flow diagnostics options", () => {
  const options = parseArgs([
    "--db=/tmp/flow.sqlite",
    "--start=2024-01-01T00:00:00Z",
    "--end=2024-02-01T00:00:00Z",
    "--horizon-m5=3",
  ]);
  assert.equal(options.symbol, "BTCUSDT");
  assert.equal(options.horizonM5, 3);
  assert.equal(
    parseArgs([
      "--db=/tmp/flow.sqlite",
      "--start=2024-01-01T00:00:00Z",
      "--end=2024-02-01T00:00:00Z",
      "--horizon-m5=288",
    ]).horizonM5,
    288,
  );
  assert.throws(() => parseArgs(["--start=2024-02-01T00:00:00Z", "--end=2024-01-01T00:00:00Z"]));
  assert.throws(() => parseArgs(["--start=2024-01-01T00:00:00Z", "--end=2024-02-01T00:00:00Z", "--horizon-m5=4"]));
});

test("bands preserve threshold boundaries", () => {
  assert.equal(imbalanceBand(0.24), "LOW");
  assert.equal(imbalanceBand(-0.5), "HIGH");
  assert.equal(relativeVolumeBand(3), "SURGE");
  assert.equal(oiChangeBand(-0.25), "CONTRACTING");
  assert.equal(oiChangeBand(null), "UNAVAILABLE");
  assert.equal(accountCrowdingBand(0.45), "SHORT_HEAVY");
  assert.equal(accountCrowdingBand(0.55), "LONG_HEAVY");
  assert.equal(accountCrowdingBand(0.5), "BALANCED");
  assert.equal(fundingBand(-0.0001), "NEGATIVE");
  assert.equal(fundingBand(0.0001), "POSITIVE");
  assert.equal(fundingBand(0), "NEUTRAL");
});

test("summarizeReturns applies round-trip cost before ranking outcomes", () => {
  assert.deepEqual(summarizeReturns([0.2, -0.1], 0.1), {
    samples: 2,
    meanNetReturnPct: -0.05,
    medianNetReturnPct: -0.05,
    winRatePct: 50,
    worstNetReturnPct: -0.2,
    bestNetReturnPct: 0.1,
  });
});

test("diagnostic report excludes incomplete flow and ranks both directions", () => {
  const options = {
    symbol: "BTCUSDT",
    start: "2024-01-01T00:00:00Z",
    end: "2024-01-02T00:00:00Z",
    horizonM5: 3,
    roundTripCostBps: 10,
    minSamples: 1,
  };
  const report = buildDiagnosticReport(options, [
    {
      completeFlow: true,
      candleSide: "BUY",
      imbalance: 0.8,
      relativeVolume: 3.2,
      openInterestChangePct: 0.5,
      accountBuyRatio: 0.6,
      fundingRate: 0.0002,
      futureReturnPct: 0.3,
    },
    {
      completeFlow: false,
      candleSide: "SELL",
      imbalance: -0.8,
      relativeVolume: 3.2,
      openInterestChangePct: -0.5,
      accountBuyRatio: 0.4,
      fundingRate: -0.0002,
      futureReturnPct: 0.3,
    },
  ]);

  assert.equal(report.coverage.eligibleM5Candles, 1);
  assert.equal(report.coverage.skippedMissingFlow, 1);
  assert.equal(report.flowOnly[0].continuation.meanNetReturnPct, 0.2);
  assert.equal(report.flowOnly[0].reversal.meanNetReturnPct, -0.4);
  assert.equal(report.flowAndOpenInterest[0].group.endsWith("oi=EXPANDING"), true);
  assert.equal(
    report.flowAndOpenInterestAndAccountRatio[0].group.endsWith("crowding=LONG_HEAVY"),
    true,
  );
  assert.equal(
    report.flowAndOpenInterestAndAccountRatioAndFunding[0].group.endsWith("funding=POSITIVE"),
    true,
  );
});
