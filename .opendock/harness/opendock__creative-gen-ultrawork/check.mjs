#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const title = "Creative Generation Ultrawork";
const runRoot = ".opendock/runs/creative-gen";
const maxTextFileBytes = 1024 * 1024;
const maxWalkEntries = 20000;
const maxWalkDepth = 32;
const traversalFailures = [];

const ignoredSegments = new Set([
  ".git",
  "node_modules",
  ".next",
  ".turbo",
  "dist",
  "build",
  "coverage"
]);

const activeStatuses = new Set(["active", "review", "ready", "ready-for-review", "handoff"]);
const inactiveStatuses = new Set(["draft", "none", "paused", "backlog"]);
const supportedModes = new Set(["image", "vector", "logo", "favicon", "video", "audio", "asset-analysis"]);

const sizeLimits = {
  image: 10 * 1024 * 1024,
  vector: 5 * 1024 * 1024,
  logo: 5 * 1024 * 1024,
  favicon: 2 * 1024 * 1024,
  video: 250 * 1024 * 1024,
  audio: 80 * 1024 * 1024
};

const modeSpecs = {
  image: {
    dirs: ["assets/generated/images"],
    exts: [".png", ".jpg", ".jpeg", ".webp", ".avif"]
  },
  vector: {
    dirs: ["assets/generated/vectors"],
    exts: [".svg"]
  },
  logo: {
    dirs: ["assets/generated/logos"],
    exts: [".svg", ".png"]
  },
  favicon: {
    dirs: ["assets/generated/favicons", "public"],
    exts: [".ico", ".png", ".svg", ".webmanifest", ".json"]
  },
  video: {
    dirs: ["assets/generated/videos"],
    exts: [".mp4", ".mov", ".webm"]
  },
  audio: {
    dirs: ["assets/generated/audio"],
    exts: [".mp3", ".wav", ".m4a", ".ogg", ".flac"]
  }
};

const requiredManagedDocs = [
  "HARNESS.md",
  ".opendock/templates/creative-gen/GENERATION_BRIEF.md",
  ".opendock/templates/creative-gen/OUTPUT_MANIFEST.md"
];

const manifestSections = [
  "Generated Outputs",
  "Prompt",
  "Prompt Draft",
  "Prompt Review",
  "Final Prompt",
  "Tool",
  "Model",
  "Date",
  "Rights",
  "Review",
  "Revision History"
];

function resolve(rel) {
  return path.join(root, rel);
}

function exists(rel) {
  return fs.existsSync(resolve(rel));
}

function readText(rel) {
  try {
    const full = resolve(rel);
    const stats = fs.statSync(full);
    if (stats.size > maxTextFileBytes) {
      throw new Error(`OpenDock harness refused to read ${rel}: file exceeds ${maxTextFileBytes} bytes.`);
    }
    return fs.readFileSync(full, "utf8");
  } catch (error) {
    if (error instanceof Error && error.message.startsWith("OpenDock harness refused")) throw error;
    return "";
  }
}

function normalize(file) {
  return path.relative(root, file).split(path.sep).join("/");
}

function recordTraversalFailure(rule, file, detail) {
  if (traversalFailures.some((failure) => failure.rule === rule && failure.file === file)) return;
  traversalFailures.push({ rule, file, detail });
}

function walk(dir, depth = 0, state = { entries: 0, stopped: false }) {
  const out = [];
  if (state.stopped || !fs.existsSync(dir)) return out;
  if (depth > maxWalkDepth) {
    recordTraversalFailure("walk-depth-budget", normalize(dir), `Directory traversal exceeded ${maxWalkDepth} levels.`);
    state.stopped = true;
    return out;
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ignoredSegments.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    state.entries += 1;
    if (state.entries > maxWalkEntries) {
      recordTraversalFailure("walk-entry-budget", normalize(full), `Directory traversal exceeded ${maxWalkEntries} entries.`);
      state.stopped = true;
      return out;
    }
    if (entry.isDirectory()) out.push(...walk(full, depth + 1, state));
    else if (entry.isFile()) out.push(full);
    if (state.stopped) break;
  }
  return out;
}

function listFiles(dirs, exts) {
  const files = [];
  for (const dir of dirs) {
    const full = resolve(dir);
    for (const file of walk(full)) {
      const ext = path.extname(file).toLowerCase();
      if (exts.includes(ext)) files.push({ full: file, rel: normalize(file), ext });
    }
  }
  return files;
}

function parseField(text, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(`^${escaped}\\s*:\\s*(.+)$`, "im");
  const match = text.match(re);
  return match ? match[1].trim() : "";
}

function parseModes(briefText, outputFiles) {
  const modeLine = parseField(briefText, "Mode").toLowerCase();
  const explicit = new Set();
  for (const mode of supportedModes) {
    const escaped = mode.replace("-", "[- ]");
    if (new RegExp(`\\b${escaped}\\b`, "i").test(modeLine)) explicit.add(mode);
  }

  for (const file of outputFiles) {
    if (file.rel.includes("/images/")) explicit.add("image");
    if (file.rel.includes("/vectors/")) explicit.add("vector");
    if (file.rel.includes("/logos/")) explicit.add("logo");
    if (file.rel.includes("/favicons/") || file.rel.endsWith("favicon.ico") || file.rel.endsWith("manifest.webmanifest")) explicit.add("favicon");
    if (file.rel.includes("/videos/")) explicit.add("video");
    if (file.rel.includes("/audio/")) explicit.add("audio");
  }

  if (exists("ASSET_INVENTORY.md") || exists("ASSET_REPORT.md")) {
    explicit.add("asset-analysis");
  }

  return [...explicit].filter((mode) => supportedModes.has(mode));
}

function hasHeading(text, heading) {
  const escaped = heading.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^#{1,6}\\s+${escaped}\\s*$`, "im").test(text);
}

function hasSafeName(rel) {
  return rel.split("/").every((segment) => /^[a-z0-9._@+-]+$/.test(segment));
}

function hasTemporaryName(rel) {
  return /(^|\/)(\.ds_store|thumbs\.db)$/i.test(rel) || /\.(tmp|temp|bak|backup|part|download)$/i.test(rel);
}

function fileSize(file) {
  try {
    return fs.statSync(file.full).size;
  } catch {
    return 0;
  }
}

function fileMtime(rel) {
  try {
    return fs.statSync(resolve(rel)).mtimeMs;
  } catch {
    return 0;
  }
}

function includesAny(text, words) {
  const lower = text.toLowerCase();
  return words.some((word) => lower.includes(word.toLowerCase()));
}

function extractManifestPaths(text) {
  const paths = new Set();
  const add = (candidate) => {
    const value = String(candidate).trim().replace(/^["'`]+|["'`.,)]+$/g, "");
    if (!value || value.includes("://") || path.isAbsolute(value)) return;
    const normalized = path.normalize(value).split(path.sep).join("/");
    if (normalized.startsWith("../") || normalized === "..") return;
    paths.add(normalized);
  };

  for (const match of text.matchAll(/`([^`]+)`/g)) add(match[1]);
  for (const line of text.split(/\r?\n/)) {
    if (!/^\s*[-*]\s+/.test(line) && !/\b(path|output|file|asset)s?\b/i.test(line)) continue;
    for (const match of line.matchAll(/[A-Za-z0-9._@+/-]+\.(?:png|jpe?g|webp|avif|svg|ico|webmanifest|json|mp4|mov|webm|vtt|srt|mp3|wav|m4a|ogg|flac|md)\b/gi)) {
      add(match[0]);
    }
  }
  return paths;
}

function escapeTerminal(value) {
  return String(value).replace(/[\x00-\x1f\x7f-\x9f]/g, (char) => {
    const code = char.charCodeAt(0).toString(16).padStart(2, "0");
    return `\\x${code}`;
  });
}

function validateSvg(file, failures) {
  if (file.ext !== ".svg") return;
  if (fileSize(file) > maxTextFileBytes) {
    failures.push({ rule: "file-too-large", file: file.rel, detail: `SVG exceeds ${maxTextFileBytes} bytes and was not scanned.` });
    return;
  }
  const text = fs.readFileSync(file.full, "utf8");
  if (!/<svg\b/i.test(text)) failures.push({ rule: "invalid-svg", file: file.rel, detail: "SVG output must contain an <svg> root." });
  if (!/\bviewBox=/i.test(text)) failures.push({ rule: "missing-viewbox", file: file.rel, detail: "SVG output needs a viewBox." });

  const unsafePatterns = [
    { pattern: /<\s*(script|foreignObject|iframe|object|embed|canvas)\b/i, detail: "SVG must not contain executable or embeddable active-content elements." },
    { pattern: /\bon[a-z][\w:-]*\s*=/i, detail: "SVG must not contain event-handler attributes." },
    { pattern: /\b(?:href|xlink:href)\s*=\s*["']?\s*(?:javascript:|data:text\/html|data:image\/svg\+xml)/i, detail: "SVG must not contain executable href or xlink:href values." },
    { pattern: /\bstyle\s*=\s*["'][^"']*(?:expression\s*\(|url\s*\(\s*['"]?\s*javascript:)/i, detail: "SVG inline styles must not contain executable CSS." },
    { pattern: /<\s*style\b[\s\S]*?(?:expression\s*\(|url\s*\(\s*['"]?\s*javascript:)[\s\S]*?<\s*\/\s*style\s*>/i, detail: "SVG style blocks must not contain executable CSS." }
  ];

  for (const { pattern, detail } of unsafePatterns) {
    if (pattern.test(text)) failures.push({ rule: "unsafe-svg", file: file.rel, detail });
  }
}

function countMatches(text, pattern) {
  return [...text.matchAll(pattern)].length;
}

function svgColors(text) {
  const colors = new Set();
  for (const match of text.matchAll(/#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b/g)) {
    colors.add(match[0].toLowerCase());
  }
  for (const match of text.matchAll(/\b(?:rgb|rgba|hsl|hsla)\([^)]+\)/gi)) {
    colors.add(match[0].toLowerCase().replace(/\s+/g, ""));
  }
  return colors;
}

function validateVectorSvg(file, manifestText, failures) {
  if (file.ext !== ".svg") return;
  if (fileSize(file) > maxTextFileBytes) return;

  const text = fs.readFileSync(file.full, "utf8");
  const lowerManifest = manifestText.toLowerCase();
  const hasExplicitVectorRequest =
    /(?:vector|svg|source vector)\s+requested\s*:\s*(?:yes|true|explicit|requested|예|네|명시)/i.test(manifestText) ||
    includesAny(lowerManifest, ["user explicitly requested svg", "user explicitly requested vector", "사용자가 svg", "사용자가 벡터", "명시적으로 요청"]);
  if (!hasExplicitVectorRequest) {
    failures.push({
      rule: "missing-vector-request",
      file: file.rel,
      detail: "Vector mode must document that SVG/source vector output was explicitly requested."
    });
  }

  if (!/<title\b[^>]*>[\s\S]*?<\/title>/i.test(text) && !/\baria-label\s*=\s*["'][^"']+["']/i.test(text)) {
    failures.push({
      rule: "svg-accessibility",
      file: file.rel,
      detail: "Vector SVG needs a <title> or aria-label for accessibility."
    });
  }

  for (const match of text.matchAll(/\b(?:href|xlink:href)\s*=\s*["']([^"']+)["']/gi)) {
    if (!match[1].startsWith("#")) {
      failures.push({
        rule: "external-svg-reference",
        file: file.rel,
        detail: "Vector SVG must not reference external, data, file, or remote href values."
      });
    }
  }

  const vectorOnlyPatterns = [
    { rule: "svg-raster-embed", pattern: /<\s*image\b/i, detail: "Vector SVG must not embed raster image payloads." },
    { rule: "svg-doctype", pattern: /<!doctype|<!entity/i, detail: "Vector SVG must not contain doctype or entity declarations." },
    { rule: "embedded-svg-data", pattern: /data:[^"')\s]+;base64/i, detail: "Vector SVG must not hide raster/base64 payloads inside the file." }
  ];
  for (const { rule, pattern, detail } of vectorOnlyPatterns) {
    if (pattern.test(text)) failures.push({ rule, file: file.rel, detail });
  }

  if (/\p{Extended_Pictographic}/u.test(text)) {
    failures.push({
      rule: "svg-emoji",
      file: file.rel,
      detail: "Do not use emoji as SVG artwork; build explicit vector paths or request raster generation."
    });
  }

  if (/#(?:000|000000)\b/i.test(text)) {
    failures.push({
      rule: "svg-pure-black",
      file: file.rel,
      detail: "Avoid pure black in generated SVG artwork; use an intentional near-black token instead."
    });
  }

  const primitiveCount = countMatches(text, /<(?:rect|circle|ellipse|polygon|polyline|line)\b/gi);
  const pathCount = countMatches(text, /<path\b/gi);
  const groupCount = countMatches(text, /<g\b/gi);
  const structureCount = countMatches(text, /<(?:defs|linearGradient|radialGradient|clipPath|mask|symbol)\b/gi);
  const textCount = countMatches(text, /<text\b/gi);
  const minimalIntent = includesAny(lowerManifest, ["minimal", "simple icon", "simple mark", "icon", "symbol", "의도적으로 단순", "미니멀"]);

  if (!minimalIntent && primitiveCount > 0 && primitiveCount <= 4 && pathCount === 0 && groupCount < 2 && structureCount === 0 && textCount === 0) {
    failures.push({
      rule: "svg-placeholder",
      file: file.rel,
      detail: "Vector SVG looks like a basic geometric placeholder. Use richer source artwork or document intentional minimalism."
    });
  }

  if (primitiveCount > 80 && pathCount < 2 && groupCount < 3) {
    failures.push({
      rule: "svg-shape-plaster",
      file: file.rel,
      detail: "Vector SVG contains many unstructured primitive shapes. Group and simplify the artwork instead of shape-plastering."
    });
  }

  const colors = svgColors(text);
  if (colors.size > 8) {
    failures.push({
      rule: "svg-color-sprawl",
      file: file.rel,
      detail: "Vector SVG uses too many direct colors. Keep one accent and a controlled palette."
    });
  }
}

function validateManifestJson(failures, outputPaths) {
  const candidates = ["public/manifest.webmanifest", "assets/generated/favicons/manifest.webmanifest"];
  const found = candidates.find((candidate) => outputPaths.has(candidate) && exists(candidate));
  if (!found) return false;
  try {
    const parsed = JSON.parse(readText(found));
    if (!Array.isArray(parsed.icons) || parsed.icons.length === 0) {
      failures.push({ rule: "manifest-icons", file: found, detail: "Web manifest needs a non-empty icons array." });
    }
  } catch {
    failures.push({ rule: "manifest-json", file: found, detail: "Web manifest must be valid JSON." });
  }
  return true;
}

function failIfMissingOutput(mode, files, failures, manifestRel) {
  if (files.length === 0) {
    failures.push({ rule: "missing-output", file: manifestRel, detail: `${mode} mode needs at least one generated output file listed in the active run manifest.` });
  }
}

function outputPathsMatching(outputPaths, pattern) {
  return [...outputPaths].filter((value) => pattern.test(value));
}

function findRunDocuments() {
  const docs = [];

  if (exists("GENERATION_BRIEF.md") && exists("OUTPUT_MANIFEST.md")) {
    docs.push({
      label: "root legacy run",
      briefRel: "GENERATION_BRIEF.md",
      manifestRel: "OUTPUT_MANIFEST.md",
      briefText: readText("GENERATION_BRIEF.md"),
      manifestText: readText("OUTPUT_MANIFEST.md"),
      mtime: Math.max(fileMtime("GENERATION_BRIEF.md"), fileMtime("OUTPUT_MANIFEST.md"))
    });
  }

  const fullRunRoot = resolve(runRoot);
  if (!fs.existsSync(fullRunRoot)) return docs;

  for (const entry of fs.readdirSync(fullRunRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const runRel = `${runRoot}/${entry.name}`;
    const briefRel = `${runRel}/brief.md`;
    const manifestRel = `${runRel}/manifest.md`;
    if (!exists(briefRel) || !exists(manifestRel)) continue;
    docs.push({
      label: runRel,
      briefRel,
      manifestRel,
      briefText: readText(briefRel),
      manifestText: readText(manifestRel),
      mtime: Math.max(fileMtime(briefRel), fileMtime(manifestRel))
    });
  }

  return docs.sort((a, b) => b.mtime - a.mtime || b.label.localeCompare(a.label));
}

function chooseActiveRun(runs, hasGeneratedOutput) {
  const active = runs.filter((run) => activeStatuses.has(parseField(run.briefText, "Status").toLowerCase()));
  if (active.length > 0) return active[0];

  if (hasGeneratedOutput) {
    const reviewable = runs.filter((run) => !inactiveStatuses.has(parseField(run.briefText, "Status").toLowerCase()));
    if (reviewable.length > 0) return reviewable[0];
  }

  return runs[0];
}

function validateMode(mode, manifestText, outputPaths, failures, run) {
  if (mode === "asset-analysis") {
    if (!outputPaths.has("ASSET_INVENTORY.md") || !exists("ASSET_INVENTORY.md")) failures.push({ rule: "missing-asset-inventory", file: "ASSET_INVENTORY.md", detail: "Asset analysis needs an inventory file listed in the active run manifest." });
    if (!outputPaths.has("ASSET_REPORT.md") || !exists("ASSET_REPORT.md")) failures.push({ rule: "missing-asset-report", file: "ASSET_REPORT.md", detail: "Asset analysis needs a report file listed in the active run manifest." });
    const inventory = readText("ASSET_INVENTORY.md");
    for (const word of ["license", "owner", "usage", "risk"]) {
      if (!includesAny(inventory, [word])) failures.push({ rule: "asset-inventory-field", file: "ASSET_INVENTORY.md", detail: `Inventory should include ${word}.` });
    }
    return [];
  }

  const spec = modeSpecs[mode];
  const files = listFiles(spec.dirs, spec.exts);
  const currentFiles = files.filter((file) => outputPaths.has(file.rel));
  failIfMissingOutput(mode, currentFiles, failures, run.manifestRel);

  for (const file of currentFiles) {
    const bytes = fileSize(file);
    if (!hasSafeName(file.rel)) failures.push({ rule: "unsafe-file-name", file: file.rel, detail: "Use lowercase safe file names with no spaces." });
    if (hasTemporaryName(file.rel)) failures.push({ rule: "temporary-file", file: file.rel, detail: "Temporary files must not be in generated output." });
    if (bytes > sizeLimits[mode]) failures.push({ rule: "file-size", file: file.rel, detail: `${mode} output exceeds the size budget.` });
    validateSvg(file, failures);
    if (mode === "vector") validateVectorSvg(file, manifestText, failures);
  }

  if (mode === "image") {
    const svgImageOutputs = outputPathsMatching(outputPaths, /^assets\/generated\/images\/.+\.svg$/i);
    for (const rel of svgImageOutputs) {
      failures.push({
        rule: "image-svg-placeholder",
        file: rel,
        detail: "Image mode requires raster generation output. Use Mode: vector and assets/generated/vectors/ only when the user explicitly requested SVG/source vector artwork.",
      });
    }
    if (!exists("ALT_TEXT.md") && !includesAny(manifestText, ["alt text", "alternative text"])) {
      failures.push({ rule: "missing-alt-text", file: run.manifestRel, detail: "Image output needs alt text in ALT_TEXT.md or the active run manifest." });
    }
    if (!includesAny(manifestText, ["dimension", "aspect ratio", "width", "height"])) {
      failures.push({ rule: "missing-image-spec", file: run.manifestRel, detail: "Image output needs dimensions or aspect ratio." });
    }
  }

  if (mode === "vector") {
    if (!includesAny(manifestText, ["palette", "one accent", "controlled palette", "accessibility", "title", "aria-label", "구조", "접근성", "팔레트"])) {
      failures.push({
        rule: "missing-vector-notes",
        file: run.manifestRel,
        detail: "Vector output needs palette, structure, and accessibility notes in the active run manifest."
      });
    }
  }

  if (mode === "logo") {
    if (!includesAny(manifestText, ["clearspace", "clear space", "minimum size", "usage"])) {
      failures.push({ rule: "missing-logo-usage", file: run.manifestRel, detail: "Logo output needs usage, clearspace, or minimum size notes." });
    }
  }

  if (mode === "favicon") {
    const hasFavicon = (outputPaths.has("public/favicon.ico") && exists("public/favicon.ico")) || (outputPaths.has("assets/generated/favicons/favicon.ico") && exists("assets/generated/favicons/favicon.ico"));
    if (!hasFavicon) failures.push({ rule: "missing-favicon", file: "public/favicon.ico", detail: "Favicon mode needs favicon.ico." });
    const hasManifest = validateManifestJson(failures, outputPaths);
    const hasAppIcons = currentFiles.some((file) => /(^|\/)(icon-192|icon-512|apple-touch-icon)\.png$/i.test(file.rel));
    if (!hasManifest && !hasAppIcons) {
      failures.push({ rule: "missing-install-icons", file: "assets/generated/favicons", detail: "Favicon mode needs app icon PNGs or a valid web manifest." });
    }
  }

  if (mode === "video") {
    if (!exists("VIDEO_SCRIPT.md") && !exists("STORYBOARD.md") && !includesAny(manifestText, ["script", "storyboard"])) {
      failures.push({ rule: "missing-video-script", file: "VIDEO_SCRIPT.md", detail: "Video output needs a script or storyboard." });
    }
    const hasCaptionFile = currentFiles.some((file) => [".vtt", ".srt"].includes(file.ext));
    if (!hasCaptionFile && !includesAny(manifestText, ["caption", "subtitle", "captions not required"])) {
      failures.push({ rule: "missing-captions", file: run.manifestRel, detail: "Video output needs captions or a documented exception." });
    }
  }

  if (mode === "audio") {
    if (!exists("TRANSCRIPT.md") && !includesAny(manifestText, ["transcript"])) {
      failures.push({ rule: "missing-transcript", file: "TRANSCRIPT.md", detail: "Audio output needs a transcript." });
    }
    if (!includesAny(manifestText, ["voice", "source", "consent", "license"])) {
      failures.push({ rule: "missing-audio-rights", file: run.manifestRel, detail: "Audio output needs voice/source rights notes." });
    }
  }

  return files;
}

function printFailures(failures, modes, outputCount, activeRunLabel) {
  console.error(`OpenDock harness: ${title}`);
  console.error(`Active run: ${activeRunLabel}`);
  console.error(`Modes: ${modes.length ? modes.join(", ") : "none"}`);
  console.error(`Generated files scanned: ${outputCount}`);
  console.error(`Failures: ${failures.length}`);
  for (const failure of failures.slice(0, 120)) {
    console.error(`- [${escapeTerminal(failure.rule)}] ${escapeTerminal(failure.file)}: ${escapeTerminal(failure.detail)}`);
  }
  if (failures.length > 120) console.error(`... ${failures.length - 120} more failures omitted`);
}

function run() {
  const failures = [];
  for (const doc of requiredManagedDocs) {
    if (!exists(doc)) failures.push({ rule: "missing-managed-doc", file: doc, detail: `${doc} is required.` });
  }

  const allOutputFiles = [
    ...listFiles(modeSpecs.image.dirs, modeSpecs.image.exts),
    ...listFiles(modeSpecs.vector.dirs, modeSpecs.vector.exts),
    ...listFiles(modeSpecs.logo.dirs, modeSpecs.logo.exts),
    ...listFiles(modeSpecs.favicon.dirs, modeSpecs.favicon.exts),
    ...listFiles(modeSpecs.video.dirs, [...modeSpecs.video.exts, ".vtt", ".srt"]),
    ...listFiles(modeSpecs.audio.dirs, modeSpecs.audio.exts)
  ];
  const runDocs = findRunDocuments();
  failures.push(...traversalFailures);

  if (runDocs.length === 0) {
    if (failures.length > 0) {
      printFailures(failures, [], allOutputFiles.length, "none");
      process.exit(1);
    }

    console.log(`OpenDock harness: ${title}`);
    console.log("Status: ready");
    console.log("Run docs: none yet");
    console.log("No active generation output detected.");
    console.log("Ultrawork passed.");
    return;
  }

  const allManifestText = runDocs.map((run) => run.manifestText).join("\n");
  const documentedOutputFiles = allOutputFiles.filter((file) => allManifestText.includes(file.rel));
  const hasGeneratedOutput = documentedOutputFiles.length > 0 || allManifestText.includes("ASSET_INVENTORY.md") || allManifestText.includes("ASSET_REPORT.md");
  const activeRun = chooseActiveRun(runDocs, hasGeneratedOutput);
  const status = parseField(activeRun.briefText, "Status").toLowerCase();
  const outputPaths = extractManifestPaths(activeRun.manifestText);
  const activeOutputFiles = allOutputFiles.filter((file) => outputPaths.has(file.rel));
  const activeHasGeneratedOutput =
    activeOutputFiles.length > 0 || outputPaths.has("ASSET_INVENTORY.md") || outputPaths.has("ASSET_REPORT.md");
  const isActive = activeStatuses.has(status) || (!inactiveStatuses.has(status) && activeHasGeneratedOutput);
  const modes = parseModes(activeRun.briefText, activeOutputFiles);

  for (const section of ["Purpose", "Audience", "Output Requirements", "Constraints", "Review Criteria"]) {
    if (!hasHeading(activeRun.briefText, section)) failures.push({ rule: "brief-section", file: activeRun.briefRel, detail: `Missing brief section: ${section}` });
  }

  if (!hasHeading(activeRun.briefText, "Prompt Plan")) {
    failures.push({ rule: "brief-section", file: activeRun.briefRel, detail: "Missing brief section: Prompt Plan" });
  }

  for (const section of manifestSections) {
    if (!hasHeading(activeRun.manifestText, section)) failures.push({ rule: "manifest-section", file: activeRun.manifestRel, detail: `Missing manifest section: ${section}` });
  }

  if (!isActive && !activeHasGeneratedOutput) {
    if (failures.length === 0) {
      console.log(`OpenDock harness: ${title}`);
      console.log(`Active run: ${activeRun.label}`);
      console.log("Status: draft");
      console.log("No active generation output detected.");
      console.log("Ultrawork passed.");
      return;
    }
  }

  if (isActive && modes.length === 0) {
    failures.push({ rule: "missing-mode", file: activeRun.briefRel, detail: "Active generation work must set Mode to a supported value." });
  }

  if (isActive || activeHasGeneratedOutput) {
    for (const field of ["prompt", "prompt draft", "prompt review", "final prompt", "tool", "model", "date", "rights", "review"]) {
      if (!includesAny(activeRun.manifestText, [field])) failures.push({ rule: "manifest-field", file: activeRun.manifestRel, detail: `Manifest must mention ${field}.` });
    }
    for (const mode of modes) validateMode(mode, activeRun.manifestText, outputPaths, failures, activeRun);
  }

  if (failures.length > 0) {
    printFailures(failures, modes, activeOutputFiles.length, activeRun.label);
    process.exit(1);
  }

  console.log(`OpenDock harness: ${title}`);
  console.log(`Active run: ${activeRun.label}`);
  console.log(`Modes: ${modes.length ? modes.join(", ") : "none"}`);
  console.log(`Generated files scanned: ${activeOutputFiles.length}`);
  console.log("Ultrawork passed.");
}

run();
