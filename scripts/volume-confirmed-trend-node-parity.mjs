#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, mkdir, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { DatabaseSync } from "node:sqlite";
import {
  buildTrendCommands,
  canonicalInstantString,
  normalizeH4Evidence,
  simulateTrendRun,
  validateTrendProtocol,
} from "./lib/volume-confirmed-trend-research.mjs";

function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) throw new Error(`Invalid argument: ${argument}`);
    const [name, ...rest] = argument.slice(2).split("=");
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? "config/volume-confirmed-trend-ensemble-v1.json"),
    db: resolve(values.get("db") ?? "build/research/binance-volume-confirmed-trend-external-v1.sqlite"),
    out: resolve(values.get("out") ?? "build/research/volume-confirmed-trend-node-parity.json"),
  };
}

export async function buildNodeParity(options) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateTrendProtocol(JSON.parse(protocolBytes));
  const start = canonicalInstantString(protocol.externalEvidence.startInclusive);
  const end = canonicalInstantString(protocol.externalEvidence.endExclusive);
  const db = new DatabaseSync(options.db, { readOnly: true });
  try {
    const bars = normalizeH4Evidence(db.prepare(`
      SELECT opened_at,open,high,low,close,volume FROM marketCandles
      WHERE symbol=? AND timeframe='H4' AND opened_at>=? AND opened_at<? ORDER BY opened_at
    `).all(protocol.market.symbol, start, end));
    const fundingRates = db.prepare(`
      SELECT timestamp,funding_rate AS rate FROM fundingRates
      WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
    `).all(protocol.market.symbol, start, end);
    const commands = buildTrendCommands(bars, protocol.strategy, protocol.market.warmupDecisionBars);
    return {
      schemaVersion: 1,
      protocolSha256: createHash("sha256").update(protocolBytes).digest("hex"),
      venue: protocol.externalEvidence.venue,
      h4BarCount: bars.length,
      fundingRateCount: fundingRates.length,
      commands: commands.flatMap((command) => command == null ? [] : [{
        side: command.side > 0 ? "BUY" : "SELL",
        decisionAt: new Date(command.decisionAt).toISOString(),
        executionAt: new Date(bars[command.executionIndex].openedAt).toISOString(),
        decisionIndex: command.decisionIndex,
        executionIndex: command.executionIndex,
        netVotes: command.votes,
        decisionVolume: round8(command.decisionVolume),
        priorVolumeMedian: round8(command.priorVolumeMedian),
      }]),
      runs: protocol.capital.startingEquitiesUsdt.flatMap((startingEquityUsdt) =>
        protocol.costs.stressMultipliers.map((costMultiplier) => {
          const run = simulateTrendRun({
            bars,
            fundingRates,
            commands,
            protocol,
            startingEquity: Number(startingEquityUsdt),
            costMultiplier: Number(costMultiplier),
          });
          return {
            startingEquityUsdt,
            costMultiplier,
            endingEquityUsdt: run.endingEquityUsdt,
            netReturnPct: run.netReturnPct,
            compoundDailyReturnPct: run.compoundDailyReturnPct,
            maximumConservativeIntrabarDrawdownPct: run.maximumConservativeIntrabarDrawdownPct,
            maximumEntryExposureFraction: run.maximumEntryExposureFraction,
            maximumAdverseExposureFraction: run.maximumAdverseExposureFraction,
            totalFeesUsdt: run.totalFeesUsdt,
            totalSlippageUsdt: run.totalSlippageUsdt,
            totalFundingPnlUsdt: run.totalFundingPnlUsdt,
            liquidationCount: run.liquidationCount,
            trades: run.trades.map((trade) => ({
              side: trade.side > 0 ? "BUY" : "SELL",
              quantity: trade.quantity,
              entryAt: trade.entryAt,
              exitAt: trade.exitAt,
              entryPrice: trade.entryPrice,
              exitPrice: trade.exitPrice,
              grossPnl: trade.grossPnl,
              fundingPnl: trade.fundingPnl,
              fees: trade.fees,
              netPnl: trade.netPnl,
              reason: trade.reason,
            })),
          };
        }),
      ),
    };
  } finally {
    db.close();
  }
}

async function writeAtomically(path, payload) {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp`;
  await writeFile(temporary, payload);
  await rename(temporary, path);
}

function round8(value) {
  return Math.round((value + Number.EPSILON) * 1e8) / 1e8;
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const result = await buildNodeParity(options);
  const payload = `${JSON.stringify(result, null, 2)}\n`;
  await writeAtomically(options.out, payload);
  console.log(payload);
}
