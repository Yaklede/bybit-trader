#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  evaluateResearchEvidence,
  sealExperiment,
  verifyManifestInputs,
} from "./lib/research-evidence.mjs";

export function parseArgs(argv) {
  const [command, ...items] = argv;
  if (!["seal", "evaluate"].includes(command)) throw new Error("Use research-evidence.mjs seal|evaluate with --name=value arguments.");
  const values = new Map();
  for (const item of items) {
    if (!item.startsWith("--") || !item.includes("=")) throw new Error(`Invalid argument: ${item}`);
    const [name, ...rest] = item.slice(2).split("=");
    values.set(name, rest.join("="));
  }
  const required = command === "seal" ? ["definition", "out"] : ["manifest", "run", "out"];
  for (const name of required) {
    if (!values.has(name)) throw new Error(`Missing --${name}=... argument.`);
  }
  return {
    command,
    definition: values.get("definition"),
    manifest: values.get("manifest"),
    run: values.get("run"),
    policy: values.get("policy") ?? "config/research-approval-policy-v1.json",
    registry: values.get("registry") ?? "config/research-sealed-registry-v1.json",
    out: values.get("out"),
  };
}

export async function runCli(options, repoRoot = process.cwd()) {
  const root = path.resolve(repoRoot);
  const policyPath = path.resolve(root, options.policy);
  const registryPath = path.resolve(root, options.registry);
  const policy = await readJson(policyPath);
  const registry = await readJson(registryPath);
  let result;
  if (options.command === "seal") {
    const definitionPath = path.resolve(root, options.definition);
    result = await sealExperiment({
      definition: await readJson(definitionPath),
      definitionPath,
      policy,
      policyPath,
      registry,
      registryPath,
      repoRoot: root,
    });
  } else {
    const manifest = await readJson(path.resolve(root, options.manifest));
    result = evaluateResearchEvidence({
      manifest,
      run: await readJson(path.resolve(root, options.run)),
      policy,
      registry,
      inputVerification: await verifyManifestInputs(manifest, root),
    });
  }
  const outPath = path.resolve(root, options.out);
  await fs.mkdir(path.dirname(outPath), { recursive: true });
  await fs.writeFile(outPath, `${JSON.stringify(result, null, 2)}\n`);
  return result;
}

async function readJson(filePath) {
  return JSON.parse(await fs.readFile(filePath, "utf8"));
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] != null && path.resolve(process.argv[1]) === scriptPath) {
  const options = parseArgs(process.argv.slice(2));
  runCli(options)
    .then((result) => {
      console.log(JSON.stringify(result, null, 2));
      if (["INVALID_EVIDENCE", "REJECTED"].includes(result.status)) process.exitCode = 2;
    })
    .catch((error) => {
      console.error(error.stack || error.message);
      process.exitCode = 1;
    });
}
