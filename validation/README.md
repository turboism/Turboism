# validation/ — Scenario Probes and Evidence

This directory holds **scenario-level validation probes**. Every subdirectory is a self-contained scenario: `build.sh` compiles, `run.sh` drives, and evidence files record the result. Most need a real Cubism Editor host. See `testing/README.md` for the split against `testing/` (automated JUnit tests).

## Layout Conventions

- `<probe>/build.sh` compiles the probe tooling and the classes under test. It can run compile-time assertions without a host.
- `<probe>/run.sh` drives the scenario and produces the evidence and verdict log under `work/`.
- `<probe>/tools/`, `fixture/`, `selfcheck/` hold scenario-private source. **Share tooling only through `validation/shared/src/`** (below).
- Evidence files (dated `*.md`, `*.json`, and logs) are committed with the directory. `triangulation-tlindex/` documents its retention and archiving policy in `INDEX.md`.
- Delete a one-off experiment when it ends and create no archive directory. Keep only files that `build.sh`, `run.sh`, `scripts/`, `gradle/`, or documentation still reference. `REMOVED.md` lists deleted directories. Put every new probe in `validation/<probe>/`.

## shared/ — Single-Source Toolbox

`shared/src/dev/turboism/validation/shared/`:

- `tools/RelocateJar.java` relocates ASM package prefixes in a jar (`RelocateJar <in.jar> <out.jar> <from/prefix> <to/prefix>`). Each `build.sh` compiles this file directly with `javac` and calls `dev.turboism.validation.shared.tools.RelocateJar`.
- `fixture/FixtureLoader.java` is a child-first `URLClassLoader`, so a fixture class of the same name never falls back to the system classpath.
- `fixture/CodeSourceUrl.java` prints `Path.toUri().toURL().toExternalForm()` for a directory, so `run.sh` can derive the expected code source.
- `verdict/ProbeTerminal.java` holds the terminal state set (PASS/FAIL/BLOCKED/SKIPPED) and the decision rule: any FAIL yields FAIL, a present BLOCKED yields BLOCKED, zero assertions yield BLOCKED, a present SKIPPED yields SKIPPED, and all PASS yields PASS. `INFO` is evidence only and never decides.
- `verdict/ProbeAssertions.java` is the assertion recorder. It rejects an unknown state while recording, and emits the `assertions=` count line and the two result line layouts.

`verdict/` is the single decision source for the four terminal-state probes. Offline case: `bash validation/shared/check-probe-terminal.sh`.

History: `RelocateJar` once existed as four diverged copies (the triweave copy lost the module-info strip note) and `FixtureLoader`/`CodeSourceUrl` as two copies each; they converged to a single source in 2026-10. The probe terminal logic once existed as seven diverged copies and converged into the shared `verdict/` implementation in 2026-11 (spec 066). `checkValidationToolsSync` (`scripts/test/check_validation_tools_sync.py`, wired into `devCheck`) rejects a new same-name copy inside a directory, and rejects a fourth terminal probe that reimplements a local `computeTerminal` or omits the shared source from compilation. Deliberate exceptions (the meshhash byte-array loader) are named inside the script. SelfCheck and Probe implementations differ per directory by design and stay outside the convergence scope.

## Where a New Probe Goes

- A scenario probe that needs a real editor host goes to `validation/<probe>/` and follows the `triangulation-*` structure.
- Verdict logic that JUnit can cover goes to `testing/integration-tests/` as a `dev.turboism.tests.plugin.*Probe` with a paired `*Test`.
- An in-host agent that ships in a jar and runs inside the editor process goes to `testing/host-validation/`.

## Directories Without a README

The following directory is an older probe with no README of its own; read the header comment of its `build.sh` and `run.sh` for usage: `supply-chain/` (supply-chain and dependency audit scripts, not a probe). Every other probe directory now carries a minimal README. A new directory states its purpose, how to run it, and whether it needs a real host.
