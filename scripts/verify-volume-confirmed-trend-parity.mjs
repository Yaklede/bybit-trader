#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const options = Object.fromEntries(process.argv.slice(2).map((argument) => {
  if (!argument.startsWith("--") || !argument.includes("=")) throw new Error(`Invalid argument: ${argument}`);
  const [key, ...value] = argument.slice(2).split("=");
  return [key, value.join("=")];
}));
const nodePath = resolve(options.node ?? "build/research/volume-confirmed-trend-node-parity.json");
const kotlinPath = resolve(options.kotlin ?? "build/research/volume-confirmed-trend-kotlin-parity.json");
const nodeResult = JSON.parse(await readFile(nodePath));
const kotlinResult = JSON.parse(await readFile(kotlinPath));
const mismatches = [];
compare(nodeResult, kotlinResult, "$", mismatches);
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

function compare(expected, actual, path, mismatches) {
  if (typeof expected === "number" && typeof actual === "number") {
    const tolerance = Math.max(1e-8, Math.abs(expected) * 1e-10);
    if (!Number.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
      mismatches.push(`${path}: expected=${expected} actual=${actual} tolerance=${tolerance}`);
    }
    return;
  }
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual) || expected.length !== actual.length) {
      mismatches.push(`${path}: expected array length=${expected.length} actual=${actual?.length}`);
      return;
    }
    expected.forEach((value, index) => compare(value, actual[index], `${path}[${index}]`, mismatches));
    return;
  }
  if (expected != null && typeof expected === "object") {
    if (actual == null || typeof actual !== "object" || Array.isArray(actual)) {
      mismatches.push(`${path}: expected object`);
      return;
    }
    const expectedKeys = Object.keys(expected).sort();
    const actualKeys = Object.keys(actual).sort();
    if (JSON.stringify(expectedKeys) !== JSON.stringify(actualKeys)) {
      mismatches.push(`${path}: key mismatch expected=${expectedKeys} actual=${actualKeys}`);
      return;
    }
    expectedKeys.forEach((key) => compare(expected[key], actual[key], `${path}.${key}`, mismatches));
    return;
  }
  if (expected !== actual) mismatches.push(`${path}: expected=${expected} actual=${actual}`);
}
