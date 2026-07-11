#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_METADATA_URL = "https://api.tardis.dev/v1/exchanges/bybit";
const LEGACY_CUTOFF = "2023-04-05T00:00:00.000Z";
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["symbol", "required-start", "required-end", "metadata-url", "out"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const options = {
    symbol: (values.get("symbol") ?? "BTCUSDT").toUpperCase(),
    requiredStart: values.get("required-start") ?? null,
    requiredEnd: values.get("required-end") ?? null,
    metadataUrl: values.get("metadata-url") ?? DEFAULT_METADATA_URL,
    out: values.get("out") == null ? null : path.resolve(values.get("out")),
  };
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isIsoInstant(options.requiredStart) || !isIsoInstant(options.requiredEnd) || options.requiredStart >= options.requiredEnd) {
    throw new Error("required-start/end must be valid ISO-8601 UTC instants with required-start < required-end.");
  }
  if (!options.metadataUrl.startsWith("https://")) throw new Error("metadata-url must use HTTPS.");
  return options;
}

export async function fetchCoverageMetadata(options, fetchImpl = fetch) {
  const response = await fetchImpl(options.metadataUrl);
  if (!response.ok) throw new Error(`Tardis metadata request failed with HTTP ${response.status}.`);
  const body = await response.json();
  if (body?.id !== "bybit" || !Array.isArray(body.availableChannels) || !Array.isArray(body.availableSymbols)) {
    throw new Error("Tardis metadata has an unexpected Bybit schema.");
  }
  return body;
}

export function buildCoverageReport(metadata, options) {
  const instrument = metadata.availableSymbols.find((candidate) => candidate.id === options.symbol) ?? null;
  const channels = new Set(metadata.availableChannels);
  const legacyRequired = options.requiredStart < LEGACY_CUTOFF;
  const v5Required = options.requiredEnd > LEGACY_CUTOFF;
  const incidents = (metadata.incidentReports ?? [])
    .filter((incident) => overlaps(incident.from, incident.to, options.requiredStart, options.requiredEnd))
    .map((incident) => ({
      from: incident.from,
      to: incident.to,
      status: incident.status ?? "unknown",
      details: incident.details ?? null,
    }));
  const rangeAdvertised = instrument != null && instrument.availableSince <= options.requiredStart &&
    (instrument.availableTo == null || instrument.availableTo >= options.requiredEnd);
  const legacyChannelsAdvertised = !legacyRequired || (channels.has("orderBook_200") && channels.has("trade"));
  const v5ChannelsAdvertised = !v5Required || (channels.has("orderbook.50") && channels.has("publicTrade"));
  const coreMetadataPassed = metadata.enabled === true && rangeAdvertised && legacyChannelsAdvertised && v5ChannelsAdvertised;
  return {
    schemaVersion: 1,
    provider: "tardis",
    exchange: metadata.id,
    symbol: options.symbol,
    requiredRange: { start: options.requiredStart, end: options.requiredEnd },
    instrument: instrument == null ? null : {
      type: instrument.type ?? null,
      availableSince: instrument.availableSince,
      availableTo: instrument.availableTo ?? null,
    },
    channelContract: {
      legacyRequired,
      legacyOrderBookChannel: legacyRequired ? "orderBook_200" : null,
      legacyTradeChannel: legacyRequired ? "trade" : null,
      v5Required,
      v5OrderBookChannel: v5Required ? "orderbook.50" : null,
      v5TradeChannel: v5Required ? "publicTrade" : null,
      legacyLiquidationChannelAdvertised: channels.has("liquidation"),
      v5LiquidationChannelAdvertised: channels.has("allLiquidation"),
    },
    incidents,
    gates: {
      exchangeEnabled: metadata.enabled === true,
      instrumentPresent: instrument != null,
      advertisedRangeCoversRequest: rangeAdvertised,
      requiredOrderBookAndTradeChannelsAdvertised: legacyChannelsAdvertised && v5ChannelsAdvertised,
      metadataFreeOfIncidents: incidents.length === 0,
      rawDayAuditRequired: true,
      rawReplayCredentialRequired: true,
    },
    status: coreMetadataPassed ? "RAW_DAY_AUDIT_REQUIRED" : "UNUSABLE",
  };
}

function overlaps(from, to, start, end) {
  const fromMillis = Date.parse(from);
  const toMillis = Date.parse(to);
  const startMillis = Date.parse(start);
  const endMillis = Date.parse(end);
  return Number.isFinite(fromMillis) && Number.isFinite(toMillis) && fromMillis < endMillis && toMillis > startMillis;
}

function isIsoInstant(value) {
  return typeof value === "string" && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const report = buildCoverageReport(await fetchCoverageMetadata(options), options);
  const output = `${JSON.stringify(report, null, 2)}\n`;
  if (options.out != null) {
    await fs.mkdir(path.dirname(options.out), { recursive: true });
    await fs.writeFile(options.out, output);
  }
  console.log(output);
  if (report.status !== "RAW_DAY_AUDIT_REQUIRED") process.exitCode = 2;
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] != null && path.resolve(process.argv[1]) === scriptPath) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
