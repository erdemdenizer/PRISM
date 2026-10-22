# 0007. Per-user folders and the log file

- Status: Accepted
- Date: 2026-10-05

## Context

LOG-1 asks for logs "in a per-user application folder, in size-bounded rolling files".
The architecture already says who decides where that folder is: `platform` resolves it
and it is "handed to Logback before the first logger is used, so there is one source of
truth for its location". What was open was the folder on each system, how it reaches
Logback when `app` may never call Logback, and what "size-bounded" means in numbers.

Two facts shaped the answer:

- The folder is needed before anything can be logged, so resolving it must not fail in a
  way that needs a log line, and must not use a logger. Creating OSHI's `SystemInfo`
  loads classes with static loggers; the folder therefore cannot come from the
  `HostPlatform` instance, which creates OSHI.
- `javafx:run` starts `prism.srm.app.App` directly and the packaged application starts
  `Launcher`. Whatever sets the folder must run in both.

## Decision

1. **`UserFolders` in `platform`**, one implementation per system, selected by the
   static `HostPlatform.userFolders()`. An implementation only works out paths from the
   environment and the user's home: it creates nothing, reads no file, loads no native
   library and uses no logger. `ArchitectureTest` forbids `org.slf4j` in these classes
   and in `HostPlatform`. Only the log folder exists so far; the settings file and the
   suspension journal get their methods when their milestones need them.

2. **The folders.**

   | System | Log folder | Source |
   |---|---|---|
   | Windows | `%LOCALAPPDATA%\Prism\SystemResourceManager\logs`; without a usable variable, `<home>\AppData\Local\...` | `FOLDERID_LocalAppData`: "Default Path: %LOCALAPPDATA% (%USERPROFILE%\AppData\Local)" ([KNOWNFOLDERID](https://learn.microsoft.com/en-us/windows/win32/shell/knownfolderid)) |
   | Linux | `$XDG_STATE_HOME/prism-srm/logs`; without a usable variable, `~/.local/state/prism-srm/logs` | XDG Base Directory Specification 0.8: state data is "actions history (logs, history, recently used files, ...)"; "If $XDG_STATE_HOME is either not set or empty, a default equal to $HOME/.local/state should be used"; a relative value is "invalid" and ignored ([specification](https://specifications.freedesktop.org/basedir/latest/)) |
   | macOS | `~/Library/Logs/PrismSystemResourceManager` | Library `Logs`: "Contains log files for the console and specific system services. Users can also view these logs using the Console app." ([File System Programming Guide](https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/FileSystemProgrammingGuide/MacOSXDirectories/MacOSXDirectories.html)) |
   | Any other | `~/.prism-srm/logs` | No convention is claimed |

   The local, not the roaming, application data folder: a log describes one machine.

3. **The environment variable, not the shell API, on Windows.** `SHGetKnownFolderPath`
   is the API for the same folder and `jna-platform` maps it. It is not used here
   because this code runs before logging exists: a native library that fails to load
   could not be reported. The variable is the documented default path of the folder.

4. **The folder reaches Logback as the system property `srm.log.dir`**, set in
   `App.init()` before the first logger is created. `init()` runs in both launch paths
   and off the JavaFX Application Thread. One method, `LoggingSetup.start`, sets the
   property and then creates the logger, so the order cannot be changed by moving a
   line; `ArchitectureTest` allows no other class of `app` to create a logger and
   requires that `App` does nothing before `init()`. A test ties the property name to
   `logback.xml`. The mistake this guards against is silent: a logger created too early
   writes to a folder named `srm.log.dir_IS_UNDEFINED` below the working directory.

5. **The file.** A `RollingFileAppender` with `SizeAndTimeBasedRollingPolicy`:
   `prism-<date>.<index>.log`, with `maxFileSize` 2 MB, `maxHistory` 30,
   `totalSizeCap` 20 MB and `cleanHistoryOnStart`. There is no fixed active file name,
   so a rollover never renames a file. The Logback manual calls
   `FixedWindowRollingPolicy`, which renames, "a deprecated policy" that it does "not
   recommend". What these settings bound, and what they do not, is under Consequences.

6. **Tests never write there.** `src/test/resources/logback-test.xml` logs to the
   console only, and Logback prefers it to `logback.xml`.

## Alternatives considered

- **Resolve the folder in `app` from system properties.** One class less, but `app`
  would then branch on the operating system, which only `platform` may do.
- **A Logback property definer class** that computes the folder inside Logback's own
  start-up. It would make Logback a compile-time dependency of production code and move
  the decision out of `platform`.
- **`%APPDATA%` (roaming).** Logs would follow the user to other machines.
- **`$XDG_DATA_HOME` or `$XDG_CACHE_HOME` on Linux.** Data is for what the user would
  back up; a cache may be deleted at any time. The specification names logs under state.
- **One fixed file with `FixedWindowRollingPolicy`.** A stable name is convenient to
  open, but renaming fails on Windows while another program has the file open.

## Consequences

- Verified on Windows only: the file is created under `%LOCALAPPDATA%`, written from the
  first line, and complete after the window is closed. The Linux and macOS rules are
  unit-tested with an injected environment and have never run on those systems.
- The Apple guide lists `Logs` among the subdirectories of a Library directory without
  saying in which domains it exists. That the user's Library has one is the convention
  applications follow, not a quotation.
- The variable is inherited text, not a query. A redirected local application data
  folder is honored only as far as Windows reflects it in the variable, which was not
  confirmed. A process started by another program with that program's environment gets
  that program's folder. Only a missing or unusable variable falls back to the default
  below the home folder.
- **The log is bounded less strictly than LOG-1 reads.** From Logback's source at
  version 1.5.38 (`TimeBasedArchiveRemover`), not from a run:
  - 2 MB is a trigger: a file ends at 2 MB plus the line that crossed it. The count is
    kept per process.
  - `maxHistory` 30 deletes files that are 31 to 62 days old, at start and at each
    rollover. `totalSizeCap` sums the files of the last 30 days only.
  - So a file that is never 31 to 62 days old on a day Prism starts or rolls over is
    never deleted. Used in autumn and not again before spring, Prism leaves the autumn
    files, up to about 20 MB of them, for good.
  A hard bound needs a sweep of Prism's own at start-up, which deletes its files older
  than a set age. That is a decision that has not been made.
- No run has produced 2 MB of log yet, so no rollover and no deletion was observed.
- If the folder cannot be created or written, Logback reports it on the console and the
  application runs with console logging only; a packaged application has no console,
  and the Overview would still name the folder. This path was not exercised.
- A folder path that contains `%` or `${` is misread by Logback, which parses the file
  name pattern after inserting the folder. Windows allows `%` in a folder name. Not
  handled.
- Logback's shutdown hook stops logging when the JVM exits. Shutdown hooks run in no
  set order, so what a later hook of Prism logs (the suspension release of milestone 9)
  may be lost unless that milestone orders the two. The hook also waits for Logback's
  clean-up job, so the exit can take longer than the 5 seconds the controlled shutdown
  allows itself.
- On Linux the specification asks that a missing directory be created "with permission
  0700". Prism creates nothing itself; Logback creates the folder with the default mode
  of the process, so other local users may be able to read the log where the home
  folder lets them in. To be settled when the code first runs on Linux.
- On Linux the default is built on `$HOME`, as the specification words it, and on
  Java's `user.home` only when the variable holds no absolute path.
- A home folder the file system cannot express as a path, or an empty one, gives a log
  folder below the working directory instead of an exception before logging exists.
- Two instances of Prism write to the same file, and nothing coordinates them. What
  that does to the file was not tested, and Logback's `prudent` mode, which exists for
  this case, was not evaluated. A single-instance rule, if wanted, is a product decision
  that has not been made.
- UI-7 (Settings) shows "the location of logs": it takes the path from `UserFolders`,
  the same source.
