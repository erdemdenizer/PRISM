# Milestone 13: technical debt

Debt that milestone 13 records and does not repay. An entry here is not a pass and does not relax any check.

## TD-1: the hosted `native-validation` job stays red

**Status: open.** Registered on 2026-11-07. The product owner decided that setting up a self-hosted runner is outside milestone 13
and that the existing policy stays. This is the same condition as exception 3 of the
[milestone 12 decision](m12-acceptance-decision.md), recorded here so that it has an owner beyond that milestone.

### What happens

The `native-validation` job of [ci.yml](../../.github/workflows/ci.yml) fails on every hosted run, so the workflow concludes `failure`.
The latest example at the time of writing is run 42374199438 on `main` at `9f4ec7b`:

| Suite | Tests | Skipped | Failures | Errors | Result of the check |
|---|---|---|---|---|---|
| `WindowsJobIntegrationTest` | 6 | 5 | 0 | 0 | Not validated |
| `NativeLimitRecoveryIntegrationTest` | 4 | 4 | 0 | 0 | Not validated |
| `ParentExitsChildSurvivesTest` | 1 | 1 | 0 | 0 | Not validated |
| `JnaWin32Test` | 9 | 0 | 0 | 0 | Validated |

### Why

- The hosted Windows runner runs the job inside a Job Object. The log of `verify (windows-latest)` says
  `native-validation: selfInJob=Optional[true] childInJob=Optional[true]`.
- A child of the test JVM inherits that job, and Prism refuses a process in a job it does not hold. There the tests cannot validate
  anything, so the test helper `NativeValidation` skips them, or fails them under `-Dprism.native.required=true`.
- [native-validation.mjs](../../.github/scripts/native-validation.mjs) counts a skip as "not validated", on purpose.

The check is working. Red means that **the native limit suites could not run on this runner**. It does not report a product fault, and
it does not say that the product was validated.

### What it costs

- The conclusion of the workflow carries no information. A new failure can hide behind the known one, so a hosted run has to be read
  job by job.
- The status of `main` is red.
- Branch protection was unreadable and reported as off on the private repository (`protected=false`). A red result stops no merge, and the
  discipline is manual.
- Each commit that needs this evidence needs a local run on a suitable Windows machine.

### What stands for it

Path C of [TESTING.md](../TESTING.md), run locally on Windows outside any job:

```
./mvnw -q test -Dprism.native.required=true -Dtest=WindowsJobIntegrationTest,NativeLimitRecoveryIntegrationTest,ParentExitsChildSurvivesTest,JnaWin32Test
node .github/scripts/native-validation.mjs target/surefire-reports
```

Evidence of this kind holds only for the commit it names. The latest result, 20 of 20 with none skipped, belongs to `c8c44cd`, the
product code of the functional completion, run outside any job with no VM running ([m13-functional-completion.md](m13-functional-completion.md)).
Milestone 13 has to run it again on its final commit.

### Options

| Option | Assessment |
|---|---|
| Self-hosted Windows runner labelled `windows-native`. [native-validation.yml](../../.github/workflows/native-validation.yml) exists and no runner is registered | Real validation, no hosted minutes. Needs a machine, security review and upkeep. Outside milestone 13 by decision of the owner |
| Find out whether the hosted job permits breakaway | Unknown, and depends on the runner. No run is started for this alone |
| Path C on each accepted commit | The policy in force |

### Not acceptable

These weaken the check, and none of them is done: `continue-on-error`, counting a skip as a pass, removing a suite from the list, removing
the job or hiding it behind a condition, reporting it as passed.
