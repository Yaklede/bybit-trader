#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, mkdir, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { DatabaseSync } from "node:sqlite";
import {
  auditBinanceTrendEvidence,
  enumerateUtcMonths,
} from "./lib/binance-um-monthly-evidence.mjs";
import {
  canonicalInstantString,
  evaluateTrendExternal,
  normalizeH4Evidence,
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
    db: resolve(values.get("db") ?? "build/research/binance-volume-confirmed-trend-external-v1.sqlite"),
    out: resolve(values.get("out") ?? "build/research/volume-confirmed-trend-ensemble-v1-external-result.json"),
  };
}

export async function runExternalBacktest(options) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateTrendProtocol(JSON.parse(protocolBytes));
  const protocolSha256 = createHash("sha256").update(protocolBytes).digest("hex");
  const databaseBytes = await readFile(options.db);
  const databaseSha256 = createHash("sha256").update(databaseBytes).digest("hex");
  const db = new DatabaseSync(options.db, { readOnly: true });
  try {
    const expectedArchiveCount = enumerateUtcMonths(
      protocol.externalEvidence.startInclusive,
      protocol.externalEvidence.endExclusive,
    ).length * 2;
    const acquisitionAudit = auditBinanceTrendEvidence(db, protocol, expectedArchiveCount, protocolSha256);
    const startInclusive = canonicalInstantString(protocol.externalEvidence.startInclusive);
    const endExclusive = canonicalInstantString(protocol.externalEvidence.endExclusive);
    const bars = normalizeH4Evidence(db.prepare(`
      SELECT opened_at,open,high,low,close,volume
      FROM marketCandles
      WHERE symbol=? AND timeframe='H4' AND opened_at>=? AND opened_at<?
      ORDER BY opened_at
    `).all(
      protocol.market.symbol,
      startInclusive,
      endExclusive,
    ));
    const fundingRates = db.prepare(`
      SELECT timestamp,funding_rate AS rate
      FROM fundingRates
      WHERE symbol=? AND timestamp>=? AND timestamp<?
      ORDER BY timestamp
    `).all(
      protocol.market.symbol,
      startInclusive,
      endExclusive,
    );
    const evaluation = evaluateTrendExternal(protocol, bars, fundingRates);
    const evaluationSha256 = createHash("sha256").update(JSON.stringify(evaluation)).digest("hex");
    return {
      schemaVersion: 1,
      resultId: `${protocol.protocolId}-external-result`,
      protocolId: protocol.protocolId,
      candidateId: protocol.candidateId,
      generatedAt: new Date().toISOString(),
      protocolSha256,
      evaluationSha256,
      databasePath: options.db,
      databaseSha256,
      externalVenue: protocol.externalEvidence.venue,
      parametersChangedAfterExternalRead: false,
      acquisitionAudit,
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
  const result = await runExternalBacktest(options);
  const payload = `${JSON.stringify(result, null, 2)}\n`;
  await writeAtomically(options.out, payload);
  console.log(payload);
}
