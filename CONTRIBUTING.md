# Contributing to Turboism

## Repository hygiene policy

Remote history must never contain:

- environment-variable values and environment files;
- local configuration/state;
- AI instructions, prompts, transcripts, or tool state;
- agent/task/research artifacts and editor swap files.

Allowed in committed code, and not flagged:

- ordinary program local-variable names (e.g. `prompt`, `agent`, `apiKey`);
- source references such as `System.getenv("APPDATA")`;
- CI secret-name references such as `${{ secrets.NAME }}`;
- policy/guard code (like this policy) that names the forbidden path classes
  without any real prompt/transcript/value.

## Before you push

Run the checker on your staged files and on the outgoing commit range:

```sh
python3 scripts/check_remote_hygiene.py --staged
python3 scripts/check_remote_hygiene.py --outgoing main..HEAD
```

Replace `main` with the intended remote base when pushing another branch lineage. The `--all` mode audits all reachable historical commits and is reserved for history-cleanup work; existing remote history is not a normal change gate.

Local `pre-commit` and `pre-push` hooks are installed (untracked, local-only)
with:

```sh
python3 scripts/check_remote_hygiene.py --install-hooks
```

The hooks fail closed: if the checker is missing or errors, the commit or
push is refused.

## Verification

### Gate cheat sheet

| Gate | What it checks | When to run it |
| --- | --- | --- |
| focused compile/test | the narrowest affected `:module:compileJava`, `:module:test --tests '<class>'` | during implementation |
| `devCheck` | every production `classes` task plus permanent structural boundaries: duplicate Java imports, package layout, module boundaries, code-quality ratchet (Javadoc/digests/naming/assets), repository hygiene, Editor-model alias admission, plugin metadata validation, fx-broker argument contracts | slices that touch structural boundaries, plugin metadata, code-quality ratchets, or repository hygiene |
| `checkIntegration` | `devCheck` plus packaged and cross-module behavior: async host-read foundation, Cubism Core API inventory/member/selector policies, plugin inspection runtime, first-party plugin metadata and READMEs, distribution protocol contract, preview bundle layout, packaged host-validation bundle checks, preview bootstrap bridge and plugin runtime, external plugin-template consumer check | when a change crosses module, packaging, or preview-agent seams |
| `checkCompletedCommit` | `checkIntegration` plus every subproject `test`, `:sdk:javadoc`, official-plugin i18n completeness and README checks, SDK API baseline tool and report, module-boundary/code-quality/hygiene selftests, plugin event reference determinism | once, when a coherent change is ready to land |
| `checkRelease` | `checkCompletedCommit` plus supply-chain and release-artifact checks: ASM admission and resolved bytecode dependency graph, release tooling selftests, SDK v2–v10 exact-API compatibility and linkage, market release metadata, Java/Windows installer and localization checks | release work only; requires `-PinstallerVersion=<release-version> -PturboismRelease=true` matching the framework version |
| host validation | exact-version Cubism runs through `validate*Host<version>` tasks (parameter, workspace, theme, clip-mask, PSD import, FPS, status bar, bounding-box overlay, separate save path) | explicitly selected per affected feature and Cubism version; needs a separately installed licensed Editor; never part of a default aggregate |

### Build wiring

`build.gradle.kts` applies seven scripts from `gradle/`:

- `common-java.gradle.kts` — JDK 17 toolchain, the single framework-version source, and stable/beta/nightly channel identity for all modules.
- `module-boundaries.gradle.kts` — `checkModuleBoundaries`: dependency direction and forbidden import/package/host-UI-traversal scanning.
- `asm-admission.gradle.kts` — bytecode-tool supply chain: only `:runtime` may resolve the pinned ASM artifact; other bytecode libraries fail closed.
- `runtime-verification.gradle.kts` — plugin metadata validation and static host-selector verification CLIs.
- `sdk-api.gradle.kts` — SDK public-API baseline tooling and exact-version compatibility checks.
- `distribution-preview.gradle.kts` — preview bundle assembly plus distribution protocol and bundle-layout contracts.
- `verification.gradle.kts` — the layered gates in the table above and the host-validation task registrations.

`scripts/preview/` holds the local host-validation machinery those `validate*Host` tasks invoke: the `host_validation.py` admission queue and containment, packaging/launch scripts, and per-feature probes. It is opt-in, uses one UID-scoped queue root and host-admission lock, and is never installed or started by Gradle; see `scripts/preview/README-host-validation-scheduling.md`.

### Running the gates

Verification is proportionate to the change. During implementation, run only the
narrowest affected compile or test task. Documentation, packaging metadata, and
pure configuration changes need no test run.

Examples:

```sh
./gradlew :sdk:test --tests '<affected test class>'
./gradlew :runtime:test --tests '<affected test class>'
./gradlew :plugins:<plugin>:test
```

`devCheck` is optional and reserved for slices that touch structural boundaries,
plugin metadata, code-quality ratchets, or repository hygiene:

```sh
./gradlew devCheck
```

The full automated repository gate is reserved for final acceptance of a
coherent change — before promoting to `main`, preparing a release, or when
explicitly requested. It is not a per-commit step:

```sh
./gradlew checkCompletedCommit
```

`checkRelease -PinstallerVersion=<release-version> -PturboismRelease=true` adds supply-chain, historical, Java-installer, and other release-artifact checks and is reserved for release-oriented work. The installer version must exactly match the framework version. Exact-host validation runs only when real-host evidence must be collected or at final feature acceptance; it requires a separately installed, licensed Live2D Cubism Editor and is never part of a default aggregate.

### Test suite expectations

Prefer behavioral regression tests over structural probes. Extend an existing
test class that already covers the contract instead of opening a new class for
every small change; delete or merge tests that only duplicate existing coverage.
Admission, profile, and hook-installer tests pin bytecode-injection contracts
per exact host artifact; treat them as behavioral safety tests and preserve
their asserted contracts when consolidating.

The public SDK has one tier. `@CubismEditor` and exact command catalogs describe Editor-version availability; permissions, session state, verified adapters, and capabilities remain separate runtime checks.

Do not commit generated runtime logs, prompts or transcripts, agent/tool output, local absolute paths, proprietary Cubism material, raw host traces, credentials, or verification claims without a reproducible tracked command or accepted evidence source.

Run repository hygiene checks before completing and pushing a change:

```sh
python3 scripts/test/test_check_remote_hygiene.py
python3 scripts/check_remote_hygiene.py --staged
python3 scripts/check_remote_hygiene.py --outgoing main..HEAD
```

Use `--all` only for an explicit audit of all reachable repository history, not as the normal completed-change or push gate.

## Build requirements

- **Java**: a JDK 17 toolchain is required (`java` and `javac` on `PATH`).

See the project documentation at https://docs.turboism.dev for the current
architecture and contributor guidance.