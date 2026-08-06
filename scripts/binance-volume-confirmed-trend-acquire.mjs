#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, mkdir, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { acquireBinanceTrendEvidence } from "./lib/binance-um-monthly-evidence.mjs";
import { validateTrendProtocol } from "./lib/volume-confirmed-trend-research.mjs";

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
    archives: resolve(values.get("archives") ?? "build/research/binance-volume-confirmed-trend-archives-v1"),
    out: resolve(values.get("out") ?? "build/research/binance-volume-confirmed-trend-acquisition-v1.json"),
  };
}

export async function runAcquisition(options, dependencies = {}) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateTrendProtocol(JSON.parse(protocolBytes));
  const protocolSha256 = createHash("sha256").update(protocolBytes).digest("hex");
  return acquireBinanceTrendEvidence({
    protocol,
    protocolSha256,
    databasePath: options.db,
    archiveDirectory: options.archives,
    fetchImpl: dependencies.fetchImpl,
    unzipBinary: dependencies.unzipBinary,
    now: dependencies.now,
    onProgress: dependencies.onProgress ?? ((event) => {
      console.error(`archive ${event.dataset} ${event.month} ${event.reused ? "reused" : "downloaded"}`);
    }),
  });
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
  const result = await runAcquisition(options);
  const payload = `${JSON.stringify(result, null, 2)}\n`;
  await writeAtomically(options.out, payload);
  console.log(payload);
}
