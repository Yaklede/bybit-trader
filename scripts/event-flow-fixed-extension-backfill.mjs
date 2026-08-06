#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { acquireEventFlowBlocks } from "./event-flow-development-backfill.mjs";
import { validateFixedExtensionProtocol } from "./event-flow-fixed-extension-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-event-flow-fixed-extension-v1.json";
const PARENT_PROTOCOL = "config/bybit-event-flow-development-v1.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "report"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    report: values.has("report") ? resolve(values.get("report")) : null,
  };
}

export async function acquireFixedExtension(options, dependencies = {}) {
  const protocolBytes = await readFile(options.protocol);
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const parentProtocol = JSON.parse(await readFile(resolve(repositoryRoot, PARENT_PROTOCOL)));
  const protocol = validateFixedExtensionProtocol(JSON.parse(protocolBytes), parentProtocol);
  return acquireEventFlowBlocks({
    options,
    protocolBytes,
    protocol,
    blocks: protocol.blocks,
    stage: "fixed-candidate-extension",
    sourceFingerprintField: "extensionSourceFingerprintSha256",
    logLabel: "event-flow fixed extension",
  }, dependencies);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const report = await acquireFixedExtension(options);
  console.log(JSON.stringify({
    status: report.status,
    completedBlocks: report.completedBlocks.length,
    sourceFingerprintSha256: report.extensionSourceFingerprintSha256,
    targetDatabaseSha256: report.targetDatabaseSha256,
  }));
}
