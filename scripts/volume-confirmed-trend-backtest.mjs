#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, mkdir, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { DatabaseSync } from "node:sqlite";
import {
  aggregateM15ToH4,
  evaluateTrendDevelopment,
  validateTrendProtocol,
} from "./lib/volume-confirmed-trend-research.mjs";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? "config/volume-confirmed-trend-ensemble-v1.json"),
    db: resolve(values.get("db") ?? "build/runtime-test/bybit-trader-full-history.sqlite"),
    out: resolve(values.get("out") ?? "build/research/volume-confirmed-trend-ensemble-v1-result.json"),
  };
}

export async function runTrendBacktest(options) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateTrendProtocol(JSON.parse(protocolBytes));
  const db = new DatabaseSync(options.db, { readOnly: true });
  try {
    const rows = db.prepare(`
      SELECT opened_at, open, high, low, close, volume
      FROM marketCandles
      WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<?
      ORDER BY opened_at
    `).all(
      protocol.market.symbol,
      protocol.developmentEvidence.startInclusive,
      protocol.developmentEvidence.endExclusive,
    );
    const bars = aggregateM15ToH4(rows, protocol.market.requiredSourceBarsPerDecisionBar);
    const fundingRates = db.prepare(`
      SELECT timestamp, funding_rate AS rate
      FROM fundingRates
      WHERE symbol=? AND timestamp>=? AND timestamp<?
      ORDER BY timestamp
    `).all(
      protocol.market.symbol,
      protocol.developmentEvidence.startInclusive,
      protocol.developmentEvidence.endExclusive,
    );
    const evaluation = evaluateTrendDevelopment(protocol, bars, fundingRates);
    return {
      schemaVersion: 1,
      resultId: `${protocol.protocolId}-development-result`,
      protocolId: protocol.protocolId,
      candidateId: protocol.candidateId,
      generatedAt: new Date().toISOString(),
      protocolSha256: createHash("sha256").update(protocolBytes).digest("hex"),
      databasePath: options.db,
      developmentDisposition: protocol.developmentEvidence.disposition,
      ...evaluation,
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

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const result = await runTrendBacktest(options);
  const payload = `${JSON.stringify(result, null, 2)}\n`;
  await writeAtomically(options.out, payload);
  console.log(payload);
}
