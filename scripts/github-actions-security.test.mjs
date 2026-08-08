import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

const workflowDirectory = path.resolve(".github/workflows");
const workflowFiles = fs
  .readdirSync(workflowDirectory)
  .filter((file) => file.endsWith(".yml") || file.endsWith(".yaml"))
  .sort();

test("every workflow uses read-only repository permissions", () => {
  for (const file of workflowFiles) {
    const workflow = fs.readFileSync(path.join(workflowDirectory, file), "utf8");
    const workflowHeader = workflow.split(/^jobs:/m, 1)[0];

    assert.match(
      workflowHeader,
      /^permissions:\n  contents: read$/m,
      `${file} must declare top-level read-only repository permissions`,
    );
  }
});

test("every third-party action is pinned to an immutable commit", () => {
  for (const file of workflowFiles) {
    const workflow = fs.readFileSync(path.join(workflowDirectory, file), "utf8");
    const actionReferences = [...workflow.matchAll(/^\s*uses:\s*([^\s#]+)(?:\s+#.*)?$/gm)].map(
      (match) => match[1],
    );

    for (const reference of actionReferences) {
      assert.match(
        reference,
        /^[^@\s]+@[0-9a-f]{40}$/,
        `${file} contains a mutable action reference: ${reference}`,
      );
    }
  }
});

test("checkout does not persist the Actions token", () => {
  for (const file of workflowFiles) {
    const workflow = fs.readFileSync(path.join(workflowDirectory, file), "utf8");
    if (!workflow.includes("uses: actions/checkout@")) continue;

    assert.match(
      workflow,
      /uses: actions\/checkout@[0-9a-f]{40}[^\n]*\n\s+with:\n\s+persist-credentials: false/,
      `${file} must not persist the checkout credential`,
    );
  }
});

test("Dependabot tracks pinned GitHub Actions", () => {
  const dependabot = fs.readFileSync(path.resolve(".github/dependabot.yml"), "utf8");

  assert.match(dependabot, /package-ecosystem: github-actions/);
  assert.match(dependabot, /interval: weekly/);
});
