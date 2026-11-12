// The native-validation check of milestone 10 (docs/TESTING.md, plan §11.6, D24): reads the
// surefire reports of a Windows run and passes only if every native-limit suite ran at least one
// test and none of its tests was skipped, failed or ended in error. A suite that was skipped as a
// whole (for example because the runner's processes are in a Job Object) is "not validated", never
// "passed". The main gate is a separate check and is not changed by this one.
//
// Usage: node .github/scripts/native-validation.mjs <surefire-reports folder>

import { existsSync, readFileSync, appendFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

export const SUITES = [
  "prism.srm.platform.windows.WindowsJobIntegrationTest",
  "prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest",
  "prism.srm.platform.windows.ParentExitsChildSurvivesTest",
  "prism.srm.platform.windows.JnaWin32Test",
];

/** The counts of the first testsuite element of a surefire report, or null if it has none. */
export function counts(xml) {
  const suite = xml.match(/<testsuite\b[^>]*>/);
  if (!suite) {
    return null;
  }
  const read = (name) => {
    const found = suite[0].match(new RegExp(`\\b${name}="(\\d+)"`));
    return found ? Number(found[1]) : NaN;
  };
  return { tests: read("tests"), skipped: read("skipped"), failures: read("failures"), errors: read("errors") };
}

/** Why the suite does not validate, or null when it does. */
export function problem(suite, report) {
  if (report === null) {
    return `${suite}: no report (the suite did not run)`;
  }
  const c = counts(report);
  if (c === null || Object.values(c).some(Number.isNaN)) {
    return `${suite}: the report cannot be read`;
  }
  if (c.tests === 0) {
    return `${suite}: no test ran`;
  }
  if (c.skipped > 0 || c.failures > 0 || c.errors > 0) {
    return `${suite}: tests=${c.tests} skipped=${c.skipped} failures=${c.failures} errors=${c.errors}`;
  }
  return null;
}

/** The verdict over a reports folder: { ok, lines }. */
export function check(folder, suites = SUITES) {
  const lines = [];
  let ok = true;
  for (const suite of suites) {
    const path = join(folder, `TEST-${suite}.xml`);
    const report = existsSync(path) ? readFileSync(path, "utf8") : null;
    const why = problem(suite, report);
    if (why) {
      ok = false;
      lines.push(`not validated: ${why}`);
    } else {
      lines.push(`validated: ${suite}: tests=${counts(report).tests}`);
    }
  }
  lines.unshift(ok ? "native validation passed" : "native validation did NOT pass (a skip is not a pass)");
  return { ok, lines };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const folder = process.argv[2] ?? "target/surefire-reports";
  const { ok, lines } = check(folder);
  for (const line of lines) {
    console.log(line);
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `### native-validation\n\n${lines.map((l) => `- ${l}`).join("\n")}\n`);
  }
  process.exit(ok ? 0 : 1);
}
