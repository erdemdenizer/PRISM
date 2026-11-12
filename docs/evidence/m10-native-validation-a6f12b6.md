# Milestone 10 native validation, plan §11.6 path C: commit a6f12b6

This is external evidence for the `native-validation` requirement of milestone 10, for the code of
commit `a6f12b6`. It is **not a green CI result**. The GitHub-hosted `native-validation` check
stays red, for the reason given in "Why path C".

**Acceptance (2026-10-12).** An independent reviewer accepted this evidence as the alternative
native verification of plan revision 4.1 §11.6 (path C), for `a6f12b6` and its documentation-only
successor `53c545c`, merged into `main` by PR #2. It is not a green hosted check.

**Why a new record.** The accepted record,
[m10-native-validation-fdd08e3.md](m10-native-validation-fdd08e3.md), holds only for the code of
`fdd08e3` and for later commits that change nothing outside `docs/`. Two later commits changed
code, so that record does not cover them:

- `0a9792a`, a fix of the milestone 9 emulated CPU limiter: `LimitRun` and its tests.
- `a6f12b6`, a test change in `CpuLimitIntegrationTest`.

Both come from the Windows CI investigation of 2026-10-11. The native suites were therefore run
again on the new code.

## Source identity

| Fact | Value |
|---|---|
| Tested commit | `a6f12b62c6f6b65f61f2b06a31712b11addee99d` (`test: measure the emulated CPU limit only while other processes leave room for it`) |
| Branch | `feat/m10-windows-native-limits`; the commit is its local head at the time of the run |
| Checkout | a new detached `git worktree` at that SHA, outside the developer's checkout (`git -c core.longpaths=true`, a per-command setting, because the evidence paths of the earlier record exceed 260 characters there). `git status --porcelain=v1 --ignored` printed nothing before the run, and no `target/` existed. |
| After the run | `git status --porcelain` printed nothing |

**What changed since `fdd08e3`.** `git diff --name-status fdd08e3 a6f12b6` lists, besides
`docs/`:

- `src/main/java/prism/srm/application/LimitRun.java`
- `src/test/java/prism/srm/application/LimitRunTest.java`
- `src/test/java/prism/srm/platform/CpuLimitIntegrationTest.java`

The trees of `src/main/java/prism/srm/platform` (every native implementation, the Job Object
limiter among them), `pom.xml`, `.github`, `.mvn`, `config` and the wrappers are identical in
both commits.

This record holds only for the code of `a6f12b6`. A later commit that changes anything outside
`docs/` makes it void. To check a later commit `<C>`, run
`git diff --name-only a6f12b62c6f6b65f61f2b06a31712b11addee99d <C>`: it must list only files under `docs/`.

## Environment

| Fact | Value |
|---|---|
| Windows | Windows 11 Pro 25H2, version 10.0.26200, build 26200.9457, 64-bit (`os.arch` amd64) |
| Processor | AMD Ryzen 7 5800H, 16 logical processors |
| Java | Eclipse Temurin 25.0.4.1+1-LTS, OpenJDK 64-Bit Server VM |
| Maven | 3.10.0 (c43a36b8d67be7e0805a411bc0898af1a51f5472), through the wrapper |
| Locale | tr_TR, platform encoding UTF-8, native encoding Cp1254, time zone Europe/Istanbul |
| Test JVM in a Job Object | no: `selfInJob=Optional[false]` on all 10 `native-validation:` lines |
| Test children in a Job Object | no: `childInJob=Optional[false]` for every child (10 children) |
| Native capability probe | CPU `NATIVE_HARD` (`JOB_OBJECT`), memory `NATIVE_HARD` (`JOB_OBJECT`). The CPU rate-control probe was accepted (error 50, Dynamic Fair Share Scheduling, not seen). |
| Run time (UTC) | 2026-10-10T22:30:14Z to 2026-10-10T22:32:40Z |

## Command

Run in the clean worktree:

```
./mvnw -o -B test -Dtest=WindowsJobIntegrationTest,NativeLimitRecoveryIntegrationTest,ParentExitsChildSurvivesTest,JnaWin32Test -Dprism.native.required=true
```

This exited with code 0, `BUILD SUCCESS`. Then, on the reports of that run:

```
node .github/scripts/native-validation.mjs target/surefire-reports
```

Exit code 0:

```
native validation passed
validated: prism.srm.platform.windows.WindowsJobIntegrationTest: tests=6
validated: prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest: tests=4
validated: prism.srm.platform.windows.ParentExitsChildSurvivesTest: tests=1
validated: prism.srm.platform.windows.JnaWin32Test: tests=9
```

The script also passes on the sanitized copies in this folder.

## Results

| Suite | Tests | Skipped | Failures | Errors | Time |
|---|---|---|---|---|---|
| `WindowsJobIntegrationTest` | 6 | 0 | 0 | 0 | 32.0 s |
| `NativeLimitRecoveryIntegrationTest` | 4 | 0 | 0 | 0 | 64.0 s |
| `ParentExitsChildSurvivesTest` | 1 | 0 | 0 | 0 | 20.3 s |
| `JnaWin32Test` | 9 | 0 | 0 | 0 | 1.9 s |
| **Total** | **20** | **0** | **0** | **0** | |

This matches the expected 20.

**What the tests established.** The tests and what each establishes are those of the earlier
record (its table "What each real Windows test established"); the native code is unchanged.
Measured values in this run (whole-machine shares, 10 s windows, disposable demo processes only):

- **20 % cap:** baseline 58.4, capped 20.9, cleared 59.4.
- **256 MiB commit limit:** a 512 MiB allocation failed after 80 MiB.
- **Hard kill:** limited 22.0, after the kill 20.5, the job name gone (error 2), the next start
  `UNREACHABLE`, after recovery 20.3. After the target exited the verdict was
  `GONE_CHILDREN_UNKNOWN`.
- **Live session:** `HELD_ELSEWHERE`, use 21.3.
- **Child of an exited target:** use 21.0, `GONE_CHILDREN_UNKNOWN`.
- **Crash during a change from 20 % to 30 %:** after the kill 30.4, `UNREACHABLE`.
- **Target exits, child survives:** limited 20.5, `RELEASED` with `survivors=2`, then free 58.3.

## Observed during the run

These are the same as in the earlier record:

- **The clearing warning:** `WindowsJobLimiter` logged "Clearing a job's limits: CPU 87, memory
  0" in the `@AfterEach` clean-up after the commit-limit test. It came from a memory-only job that
  was already cleared, and the read-back decides the outcome.
- **The two expected recovery warnings:** a live session's ledger is left alone, and a process
  may still be limited by an earlier session.

## Not run, or not supported

- 32-bit targets, and systems with Dynamic Fair Share Scheduling enabled.
- Recovery across users or across elevation.
- Linux and macOS: no native limiter there; their CI runs are recorded on PR #2.
- `-Pui-snapshots` and manual checks, which are not part of this suite.
- Only one machine and one run.

## Why path C

On the GitHub-hosted `windows-latest` runner the test JVM and every child run inside a Job
Object (`selfInJob=Optional[true] childInJob=Optional[true]`; for example run 38069100036).

- **What follows:** Prism refuses such processes by design (plan D1), so the native suites are
  skipped there. The separate `native-validation` check then fails, as it must.
- **What was not done:** no threshold, skip rule or workflow was changed.
- **What this record is:** an external-evidence exception for independent review, not a
  substitute for a green check.

## Artifacts

The folder `m10-native-validation-a6f12b6/` holds sanitized copies of the original files. The
sanitizing is that of the earlier record, with the correction made there after its acceptance:

- In each XML, the `<properties>` block keeps only these properties:
  - `java.version`, `java.vendor`, `java.vm.name`, `java.vm.version`, `java.runtime.version`
  - `os.name`, `os.version`, `os.arch`, `sun.arch.data.model`
  - `file.encoding`, `native.encoding`, `user.language`, `user.country`
  - `prism.native.required`
- The user's home path became `%USERPROFILE%`, also where Maven doubles the backslashes (JaCoCo's
  `argLine`).
- The path of the temporary worktree became `<worktree>`.
- The host name became `<host>`.
- `native-suite.log` is stored as `native-suite-output.txt`, because `*.log` is ignored.
- A `.gitattributes` turns off Git's whitespace checks in the folder, because the trailing spaces
  of Maven's output are part of the record.

Test elements, counts, times, warnings and exit codes are unchanged. The originals are kept outside the repository by the
developer, with these hashes:

| File | SHA-256 of the original | SHA-256 of the committed copy |
|---|---|---|
| `TEST-prism.srm.platform.windows.WindowsJobIntegrationTest.xml` | `0338e300bf926393230cebcb475cb373fb279048154dbf7b0af0a940820deb14` | `d347f873173b8901c150f48f0b6ff41f54617e2e6be53f427221528302e52b6c` |
| `prism.srm.platform.windows.WindowsJobIntegrationTest.txt` | `a9851038993bfebe631e90fbcdd9c5d13771fa1c904b4ebbacb25db47fed3761` | `b58d9340a0b8df7e8f819966f1f24b2c0f113af5860b76cd9f616e2113825ad2` |
| `TEST-prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest.xml` | `acbae0a252d43e41a111a2d687cba9a50c425e782bc656165e6fd00010380987` | `474c205f50404bd9650af0ce3bef687be0d80455e3c63c812cbb5310808b5923` |
| `prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest.txt` | `f11dd953a1c533f0c795ee9589b1fc64f055c8b8fe842e87ec59cc29ec8bda4a` | `50e7365ca35ed30dd93ca6ebfd7ea682f381911e4b5be3b186773d23bad4fcfc` |
| `TEST-prism.srm.platform.windows.ParentExitsChildSurvivesTest.xml` | `3bb49595fd4fa5db6eb1f3fa008ada7eba664c7610f53847c03cd5b90fac9059` | `098647d17396c2ca93f181286f8f2e214f9b6f0d8d91e9009b5d98b8a012c8b0` |
| `prism.srm.platform.windows.ParentExitsChildSurvivesTest.txt` | `adb3e697124b92c44d2499d08de8f8b3f2a43877b7da71e66a9d38271c7cbaaf` | `2a896f4801b8a296273c9dc239588daa46da54a1668a9a5fb4dc1660cb4cef87` |
| `TEST-prism.srm.platform.windows.JnaWin32Test.xml` | `c95f8a19d22f6eeaa135a7b3b627fd10b09a3fbfa1e59c7e2cd25b4bf27e2a45` | `e032e2079e391623ac29bf48917e8ec46b3c41d1e071651e6302cb308ab9900a` |
| `prism.srm.platform.windows.JnaWin32Test.txt` | `df05d0c9abd7890d3360df18050b20e962d077d78594bee28f0fdc6527795735` | `4849aedc0e282933eb45731a323b48bbddb34e4965292cef93c41e3ac9461abe` |
| `native-suite.log` → `native-suite-output.txt` | `7adf578dcedbbb006f64aa5d459cfa871829a64982f57db4b3b3ad4f260b8fb3` | `4b9e00a67779cc48617a4b2ee431310024dd3c00566077b49a43abd7649435e1` |
| `native-suite.exit` | `19eaf43821a7660ec323a87c8457bf74823beb296c39f5e01aa8a683aa50f061` | same |
| `validation.out` | `b023d46b0faf09e92a4994a8a964910c01eeac96ed8e8c114b4373fefea2eda6` | same |
| `validation.exit` | `19eaf43821a7660ec323a87c8457bf74823beb296c39f5e01aa8a683aa50f061` | same |
| `maven-version.txt` | `76e9ed8054bcc07bde355c73e41f9ce6bea6ff2088ab78a72e6913249e17fd9a` | `08316f687d088bfbc7a1069f0c48b441998ab4b528646890dfa6de50a8fd453f` |
| `start.txt` | `59d852c2073fd979d436b22b026b64886a0c1474e1d84c1118a35d78bff4c821` | same |
| `end.txt` | `5de13ee468c929b3e76d7a9b80ba916fd9d5a65dcf22d6869b6f1fa530eb28ac` | same |

**Freshness of the reports.** The worktree had no `target/` before the run. The XML file times
fall within the run:

- 01:30:43 for `JnaWin32Test`
- 01:31:47 for `NativeLimitRecoveryIntegrationTest`
- 01:32:08 for `ParentExitsChildSurvivesTest`
- 01:32:40 for `WindowsJobIntegrationTest`

These are local times (UTC+3); the run went from 22:30:14Z to 22:32:40Z.
