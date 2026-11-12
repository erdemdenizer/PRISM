import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { LINUX_SUITES, MINIMUM_TESTS, checkLinux } from "./linux-native-validation.mjs";

const report = (tests, skipped) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="S" time="1" tests="${tests}" errors="0" skipped="${skipped}" failures="0"></testsuite>`;

const withReports = (integration, smoke, body) => {
  const folder = mkdtempSync(join(tmpdir(), "linux-native-validation-"));
  try {
    writeFileSync(join(folder, `TEST-${LINUX_SUITES[0]}.xml`), integration);
    writeFileSync(join(folder, `TEST-${LINUX_SUITES[1]}.xml`), smoke);
    body(folder);
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
};

test("the Linux check names the native integration suite and the read-only smoke test", () => {
  assert.deepEqual(LINUX_SUITES, [
    "prism.srm.platform.linux.LinuxNativeLimitIntegrationTest",
    "prism.srm.platform.linux.SystemdReadOnlySmokeTest",
  ]);
});

test("a Linux suite that skipped itself does not validate", () => {
  const n = MINIMUM_TESTS[LINUX_SUITES[0]];
  withReports(report(n, n), report(1, 0), (folder) => assert.equal(checkLinux(folder).ok, false));
  withReports(report(n, 0), report(1, 0), (folder) => assert.equal(checkLinux(folder).ok, true));
});

test("fewer tests than expected do not validate", () => {
  const n = MINIMUM_TESTS[LINUX_SUITES[0]];
  withReports(report(n - 1, 0), report(1, 0), (folder) => {
    const { ok, lines } = checkLinux(folder);
    assert.equal(ok, false);
    assert.match(lines.join("\n"), /expected at least/);
  });
});
