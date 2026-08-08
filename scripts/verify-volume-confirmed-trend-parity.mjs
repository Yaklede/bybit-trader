#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { compareVolumeConfirmedTrendParity } from "./lib/volume-confirmed-trend-parity.mjs";

const options = Object.fromEntries(process.argv.slice(2).map((argument) => {
  if (!argument.startsWith("--") || !argument.includes("=")) throw new Error(`Invalid argument: ${argument}`);
  const [key, ...value] = argument.slice(2).split("=");
  return [key, value.join("=")];
}));
const nodePath = resolve(options.node ?? "build/research/volume-confirmed-trend-node-parity.json");
const kotlinPath = resolve(options.kotlin ?? "build/research/volume-confirmed-trend-kotlin-parity.json");
const nodeResult = JSON.parse(await readFile(nodePath));
const kotlinResult = JSON.parse(await readFile(kotlinPath));
const mismatches = compareVolumeConfirmedTrendParity(nodeResult, kotlinResult);
if (mismatches.length > 0) {
  throw new Error(`Volume-confirmed trend parity failed:\n${mismatches.slice(0, 50).join("\n")}`);
}
console.log(JSON.stringify({
  status: "PARITY_PASS",
  commandCount: nodeResult.commands.length,
  runCount: nodeResult.runs.length,
  tradeCount: nodeResult.runs.reduce((total, run) => total + run.trades.length, 0),
  numericTolerance: 1e-8,
}, null, 2));
