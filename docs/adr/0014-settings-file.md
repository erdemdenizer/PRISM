# 0014. The settings file

- Status: Accepted with milestone 11 (2026-10-13; see docs/evidence/m11-acceptance-decision.md)
- Date: 2026-10-12

## Context

SET-1 asks that the global settings (the OOM Guard thresholds, the automatic intervention
switches, the appearance) persist between runs in a per-user settings file, and that a
missing or unreadable file means defaults and a log entry, never a failed start. SET-2 asks
that persisted settings never enable automatic termination, by default or by migration.
[ADR-0007](0007-per-user-folders-and-log-files.md) defines the folders for logs and state
only and leaves the settings file to the milestone that needs it. The technology baseline
([ADR-0001](0001-technology-baseline.md)) has no JSON or properties library, and none is to
be added. The suspension journal ([ADR-0011](0011-suspension-journal-and-recovery.md))
already has a durable write path (`JournalFiles`: write to a temporary file, force it, move
it atomically, read it back) and a text format with a checksum that is tested on every
system.

## Decision

1. **Location.** `UserFolders.settings()`, worked out like the other folders and creating
   nothing:
   - Windows: `%LOCALAPPDATA%\Prism\SystemResourceManager\settings` (the same root as the logs
     and the state, `FOLDERID_LocalAppData`; settings belong to this machine like the rest).
   - Linux: `$XDG_CONFIG_HOME/prism-srm`, or `$HOME/.config/prism-srm` when the variable is
     not set, empty or relative (XDG Base Directory 0.8: "$XDG_CONFIG_HOME defines the base
     directory relative to which user-specific configuration files should be stored").
     Configuration and state are separate concepts there, so the settings do not go into the
     state folder.
   - macOS: `~/Library/Application Support/PrismSystemResourceManager`, beside the state
     (Apple's File System Programming Guide places files that support the app there).
   - Another system: `~/.prism-srm/settings`.
2. **Format.** One file, `prism.settings`, UTF-8 text: the header `prism-settings 1`, one
   `key=value` line per setting with URL-encoded values, and a last line `end <n> <crc32>`
   that counts the lines and checksums them, as the journal does. Numbers and keys are
   written and read with `Locale.ROOT`. The keys of version 1 are `oom.monitoring`,
   `oom.warning.percent`, `oom.critical.percent`, `oom.emergency.percent`,
   `oom.emergency.floor.bytes`, `oom.auto.priority`, `oom.auto.suspend`,
   `oom.auto.terminate` and `appearance`.
3. **Reading** (`SettingsStore.load`, in `App.init()`, after logging and the instance lock,
   off the JavaFX thread):
   - no file: the defaults and one INFO line;
   - a file that does not decode (header, count, checksum, a malformed line): the defaults,
     a WARNING, and the file moved aside as `prism.settings.corrupt-<time>`, once;
   - a newer version header: the defaults, a WARNING, and saving is turned off for the
     session so that an older Prism never overwrites a newer file;
   - an unknown key is ignored and logged; a missing key takes its default;
   - three thresholds that are not a valid triple (1 ≤ emergency < critical < warning ≤ 90,
     floor ≥ 0) fall back to the defaults together, with a WARNING; the switches are kept;
   - `oom.auto.terminate` is true only for the literal `true`; anything else, missing
     included, is false (SET-2). No migration ever derives it.
   Nothing on any of these paths prevents start-up.
4. **Writing** (`SettingsStore.save`): the whole file on every change, through
   `JournalFiles` (temporary file, force, atomic move with retries, read back), on the
   `srm-journal` executor, never on the JavaFX thread. A refused or failed save is a WARNING;
   the setting in memory stays in force and the next change tries again.
5. **Appearance.** The `appearance` key is read in milestone 11 and applied to the theme
   controller; the Settings page of milestone 13 (UI-7) changes it, with no new format version.
   That page also says what went wrong with the file at start-up and with the last save.
6. Per-process OOM settings are not in the file: the managed list is per session (MGD-2).

## Alternatives considered

- **The state folder.** One folder fewer, but on Linux configuration and state are
  different XDG concepts, and UI-7 shows "the location of logs and settings" as two things.
- **`java.util.Properties`.** Its escaping and its reliance on the default encoding of older
  APIs are easy to get wrong, and it has no checksum, so a truncated file would read as a
  valid file with missing keys.
- **A JSON library.** A new dependency for nine keys; the baseline is frozen.
- **Tunable dwell, margin, settle and gap in the file** (plan O13 (a)). Tested code
  constants are the smaller surface; the file can gain keys later without a new version.

## Consequences

- A corrupt or newer file never stops Prism and never turns termination on.
- The settings file adds one durable write per user change, on the journal's thread.
- The Linux and macOS folder rules are worked out from the platform conventions, as for the rest
  of ADR-0007. Their unit tests (with a given environment) and the settings tests pass in CI on
  Linux and macOS (2026-10-13). The application itself was not run on a Linux or macOS desktop.
- Verified by `SettingsCodecTest` (round trip under `tr-TR`, each corrupt form),
  `SettingsStoreTest` (missing, corrupt, newer, invalid thresholds, failed and refused
  saves, the literal-`true` rule) and the `UserFolders` tests of each system.
