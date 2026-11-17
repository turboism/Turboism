# testing/ — JUnit Tests and Host-Validation Agents

This directory holds **automatically executable** test code. The split against
`validation/` (manual and scenario probe evidence) follows below.

## Layout

- `integration-tests/` — Gradle module `:testing:integration-tests`, all JUnit test sources.
  - `src/test/java/dev/turboism/tests/plugin/` — `*Probe` probes and their paired `*Test` verdict tests. Convention: every probe has at least one test that covers the core verdict decision path; `Assumptions` gates environment dependencies.
  - `src/test/java/dev/turboism/tests/validation/` — `WorkspaceValidationAgent` / `WorkspaceValidationUi`: host probe agents that ship inside the workspace-validation bundle and run inside a real Cubism Editor. Historical exception: the name says validation, but the code physically belongs to this module.
- `test-support/` — Gradle module `:testing:test-support`, shared test infrastructure.
- `host-validation/` — host-validation agent sources (the 11 `Native*HostAgent`/`Observation`/`Audit` classes under `image-archive/src/...`) and one `config.json` per scenario (`float-array`, `image-archive`, `mesa-gl-thread`, `mesa-gl-thread-off`, `texture-upload`). `scripts/preview/host_validation.py` drives them, and they run only on Linux + Proton + a licensed Cubism Editor.

## Where New Code Goes

- New automated tests or probe verdict tests → the matching package under `integration-tests/src/test/java/dev/turboism/tests/`.
- New real host probe agents → `host-validation/<scenario>/` (with a `config.json`) or `validation/<probe>/` (see the scenario probes in `validation/README.md`).
- Shell- or Python-driven evidence collection scenarios → `validation/<probe>/`.

## Known Exceptions (Recorded, Not Relocated)

- `tests/validation/` is the `dev.turboism.tests.validation` package of `integration-tests`. The name sits on the testing side, but the code is really a host agent.
- `testing/host-validation/` holds host probe agents instead of JUnit tests, because they need a real editor environment.
- `WindowsHistoryFloatProbe` (in `integration-tests`) is named history, but it is a manual panel-floating validation probe.
