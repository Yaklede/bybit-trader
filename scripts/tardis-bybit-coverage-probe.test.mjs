import assert from "node:assert/strict";
import test from "node:test";
import { buildCoverageReport, fetchCoverageMetadata, parseArgs } from "./tardis-bybit-coverage-probe.mjs";

const options = {
  symbol: "BTCUSDT",
  requiredStart: "2020-05-28T00:00:00.000Z",
  requiredEnd: "2026-07-02T00:00:00.000Z",
  metadataUrl: "https://api.tardis.dev/v1/exchanges/bybit",
  out: null,
};

test("probe requires an explicit UTC observation range", () => {
  assert.throws(() => parseArgs([]), /required-start\/end/);
  assert.throws(
    () => parseArgs(["--required-start=2020-05-28T00:00:00.000Z", "--required-end=2020-05-27T00:00:00.000Z"]),
    /required-start < required-end/,
  );
  assert.deepEqual(
    parseArgs([
      "--required-start=2020-05-28T00:00:00.000Z",
      "--required-end=2026-07-02T00:00:00.000Z",
    ]),
    options,
  );
});

test("probe reports metadata incidents without treating metadata as raw-data proof", () => {
  const report = buildCoverageReport(fixtureMetadata(), options);

  assert.equal(report.status, "RAW_DAY_AUDIT_REQUIRED");
  assert.equal(report.gates.advertisedRangeCoversRequest, true);
  assert.equal(report.gates.datasetRangeCoversRequest, true);
  assert.equal(report.gates.datasetSupportsOrderBookAndTrades, true);
  assert.equal(report.gates.requiredOrderBookAndTradeChannelsAdvertised, true);
  assert.equal(report.gates.metadataFreeOfIncidents, false);
  assert.equal(report.gates.rawDayAuditRequired, true);
  assert.deepEqual(report.incidents.map((incident) => incident.from), ["2021-03-05T14:14:00.000Z"]);
});

test("probe rejects a requested symbol or channel contract that metadata cannot support", () => {
  const missingSymbol = structuredClone(fixtureMetadata());
  missingSymbol.availableSymbols = [];
  assert.equal(buildCoverageReport(missingSymbol, options).status, "UNUSABLE");

  const missingLegacyTrade = structuredClone(fixtureMetadata());
  missingLegacyTrade.availableChannels = missingLegacyTrade.availableChannels.filter((channel) => channel !== "trade");
  assert.equal(buildCoverageReport(missingLegacyTrade, options).status, "UNUSABLE");

  const missingDatasetFlow = structuredClone(fixtureMetadata());
  missingDatasetFlow.datasets.symbols[0].dataTypes = ["trades"];
  assert.equal(buildCoverageReport(missingDatasetFlow, options).status, "UNUSABLE");
});

test("metadata fetch rejects failed responses and malformed payloads", async () => {
  await assert.rejects(
    () => fetchCoverageMetadata(options, async () => ({ ok: false, status: 403 })),
    /HTTP 403/,
  );
  await assert.rejects(
    () => fetchCoverageMetadata(options, async () => ({ ok: true, json: async () => ({ id: "bybit" }) })),
    /unexpected Bybit schema/,
  );
});

function fixtureMetadata() {
  return {
    id: "bybit",
    enabled: true,
    availableChannels: ["orderBook_200", "trade", "liquidation", "orderbook.50", "publicTrade", "allLiquidation"],
    availableSymbols: [
      { id: "BTCUSDT", type: "perpetual", availableSince: "2020-05-28T00:00:00.000Z" },
    ],
    datasets: {
      symbols: [
        {
          id: "BTCUSDT",
          availableSince: "2020-05-28T00:00:00.000Z",
          dataTypes: ["incremental_book_L2", "trades", "liquidations"],
        },
      ],
    },
    incidentReports: [
      {
        from: "2021-03-05T14:14:00.000Z",
        to: "2021-03-05T14:23:00.000Z",
        status: "resolved",
        details: "fixture incident",
      },
      {
        from: "2027-01-01T00:00:00.000Z",
        to: "2027-01-01T00:01:00.000Z",
        status: "resolved",
        details: "outside range",
      },
    ],
  };
}
