export function compareVolumeConfirmedTrendParity(expected, actual) {
  const mismatches = [];
  compare(expected, actual, "$", mismatches);
  return mismatches;
}

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
