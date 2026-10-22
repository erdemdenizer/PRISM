# System Resource Manager

A JavaFX desktop system resource manager for Windows, Linux and macOS, built by team
Prism for an Operating Systems course.

The repository currently contains the engineering foundation only: build, quality
gates, architecture skeleton and documentation. Product features follow the
specification in [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md).

## Requirements

- A JDK 25 (Eclipse Temurin 25 LTS) with `JAVA_HOME` pointing at it. The build refuses
  any other Java version.
- Nothing else: the Maven Wrapper downloads Maven 3.10.0 on first use.

## Build and run

Use `./mvnw` on macOS/Linux/Git Bash and `.\mvnw.cmd` in PowerShell.

```
./mvnw verify          # full quality gate
./mvnw javafx:run      # start the application
./mvnw -q compile      # fast compile check
```

See [docs/TESTING.md](docs/TESTING.md) for the fast and full checks and the UI snapshot
harness.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): layers, dependency direction, threading
- [docs/UI_GUIDE.md](docs/UI_GUIDE.md): design language
- [docs/TESTING.md](docs/TESTING.md): verification
- [docs/adr/](docs/adr/README.md): architecture decision records
- [CLAUDE.md](CLAUDE.md): rules for AI-assisted development in this repository

## Working with Claude Code

Project configuration is in `.claude/` (permission rules, hooks, skills, review agents).
The hooks are Node scripts, so Node.js must be on `PATH` for them to run.

Claude Code sessions need `JAVA_HOME` too. Put it in `.claude/settings.local.json`,
which is ignored by git because the path is machine-specific:

```json
{
  "env": {
    "JAVA_HOME": "/path/to/jdk-25"
  }
}
```
