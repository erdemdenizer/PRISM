import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { check, counts, problem } from "./native-validation.mjs";

const report = (tests, skipped, failures, errors) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite xmlns:xsi="x" name="S" time="1" tests="${tests}" errors="${errors}" skipped="${skipped}" failures="${failures}">\n<testcase name="a"/></testsuite>`;

test("reads the counts of the testsuite element", () => {
  assert.deepEqual(counts(report(6, 0, 1, 2)), { tests: 6, skipped: 0, failures: 1, errors: 2 });
  assert.equal(counts("<nothing/>"), null);
});

test("a suite that ran every test and passed validates", () => {
  assert.equal(problem("S", report(6, 0, 0, 0)), null);
});

test("a skip, a failure, an error, no test or no report never validates", () => {
  assert.match(problem("S", report(6, 6, 0, 0)), /skipped=6/);
  assert.match(problem("S", report(6, 1, 0, 0)), /skipped=1/);
  assert.match(problem("S", report(6, 0, 1, 0)), /failures=1/);
  assert.match(problem("S", report(6, 0, 0, 1)), /errors=1/);
  assert.match(problem("S", report(0, 0, 0, 0)), /no test ran/);
  assert.match(problem("S", null), /did not run/);
  assert.match(problem("S", "<testsuite tests=\"x\">"), /cannot be read/);
});

test("the verdict over a folder needs every suite", () => {
  const folder = mkdtempSync(join(tmpdir(), "native-validation-"));
  try {
    writeFileSync(join(folder, "TEST-a.A.xml"), report(3, 0, 0, 0));
    assert.equal(check(folder, ["a.A"]).ok, true);
    assert.equal(check(folder, ["a.A", "b.B"]).ok, false);
    writeFileSync(join(folder, "TEST-b.B.xml"), report(3, 3, 0, 0));
    const verdict = check(folder, ["a.A", "b.B"]);
    assert.equal(verdict.ok, false);
    assert.match(verdict.lines[0], /did NOT pass/);
    assert.ok(verdict.lines.some((line) => line.startsWith("not validated: b.B")));
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});

test("the command exits 1 on a skip and 0 when every suite validated", async () => {
  const { spawnSync } = await import("node:child_process");
  const { fileURLToPath } = await import("node:url");
  const script = fileURLToPath(new URL("./native-validation.mjs", import.meta.url));
  const { SUITES } = await import("./native-validation.mjs");
  const folder = mkdtempSync(join(tmpdir(), "native-validation-cli-"));
  try {
    for (const suite of SUITES) {
      writeFileSync(join(folder, `TEST-${suite}.xml`), report(2, 0, 0, 0));
    }
    const env = { ...process.env, GITHUB_STEP_SUMMARY: "" };
    assert.equal(spawnSync(process.execPath, [script, folder], { env }).status, 0);
    writeFileSync(join(folder, `TEST-${SUITES[1]}.xml`), report(2, 2, 0, 0));
    const failed = spawnSync(process.execPath, [script, folder], { env, encoding: "utf8" });
    assert.equal(failed.status, 1);
    assert.match(failed.stdout, /did NOT pass/);
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});
