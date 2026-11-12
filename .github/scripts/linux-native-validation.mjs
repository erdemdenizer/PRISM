// The linux-native-validation check of milestone 12 (plan Rev 2.2 §8.2, S6): reads the surefire reports of the
// Linux native job and passes only if every Linux native suite ran at least its expected number of tests and none of
// its tests was skipped, failed or ended in error. The suites skip themselves unless the job asks for them, and a skip
// is "not validated", never "passed". The same rules as the Windows check (native-validation.mjs), over the Linux
// suites, plus a minimum count, so that deleted tests do not validate either.
//
// Usage: node .github/scripts/linux-native-validation.mjs <surefire-reports folder>

import { appendFileSync, existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { check, counts } from "./native-validation.mjs";

export const LINUX_SUITES = [
  "prism.srm.platform.linux.LinuxNativeLimitIntegrationTest",
  "prism.srm.platform.linux.SystemdReadOnlySmokeTest",
];

/** The fewest tests each suite must run; raise it when tests are added. */
export const MINIMUM_TESTS = {
  "prism.srm.platform.linux.LinuxNativeLimitIntegrationTest": 11,
  "prism.srm.platform.linux.SystemdReadOnlySmokeTest": 1,
};

/** The verdict over a reports folder: { ok, lines }. */
export function checkLinux(folder) {
  const { ok, lines } = check(folder, LINUX_SUITES);
  let enough = true;
  for (const suite of LINUX_SUITES) {
    const path = join(folder, `TEST-${suite}.xml`);
    const c = existsSync(path) ? counts(readFileSync(path, "utf8")) : null;
    if (c !== null && c.tests < MINIMUM_TESTS[suite]) {
      enough = false;
      lines.push(`not validated: ${suite}: tests=${c.tests}, expected at least ${MINIMUM_TESTS[suite]}`);
    }
  }
  if (ok && !enough) {
    lines[0] = "native validation did NOT pass (fewer tests ran than expected)";
  }
  return { ok: ok && enough, lines };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const folder = process.argv[2] ?? "target/surefire-reports";
  const { ok, lines } = checkLinux(folder);
  for (const line of lines) {
    console.log(line);
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(
      process.env.GITHUB_STEP_SUMMARY,
      `### linux-native-validation\n\n${lines.map((l) => `- ${l}`).join("\n")}\n`,
    );
  }
  process.exit(ok ? 0 : 1);
}
