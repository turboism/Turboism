# Exact-host validation scheduling

Turboism exact-host validation supports multiple Cubism windows when every run opens a distinct project copy. Scheduling therefore protects real shared resources instead of serializing every run behind one global lock.

The executable task catalogue is [`scripts/preview/host-validation-tasks.json`](scripts/preview/host-validation-tasks.json). The dispatcher is [`scripts/preview/host_validation.py`](scripts/preview/host_validation.py). Existing feature wrappers and `run-cubism-host-validation.sh` remain the authority for building, launching Cubism, polling results, collecting evidence, and cleaning up task-owned processes.

## Safety invariants

Every scheduled or directly invoked exact-host run must satisfy these rules:

1. Launch only the reviewed official `CubismEditor5.bat` for the exact host version.
2. Treat the golden Proton prefix and source project as immutable shared inputs.
3. Create a task-scoped CoW prefix and Turboism home.
4. Copy the selected project into the task directory and prefix its filename with the generated validation run ID.
5. Identify processes and windows by the task-owned project path/name and recorded process identity—not by the newest directory, the current window, or an arbitrary Cubism PID.
6. Stop only processes owned and revalidated by the current task.
7. Keep terminal result, identity, source/copy hashes, exit, and cleanup evidence together under the task ID.
8. Never convert fake, static, screenshot-only, or incomplete evidence into a real-host readiness claim.

Different exact Cubism versions may run concurrently because each task owns its prefix, home, and project copy. A task may still require an exclusive resource described below.

## Resources

| Resource | Capacity | Meaning |
| --- | ---: | --- |
| `host-slot` | 4 | One isolated Cubism window/session. The capacity is a conservative host-load bound, not a Cubism limitation. |
| `display-input` | 1 | Exclusive X11 keyboard, mouse, window activation, or resize automation on display `:0`. |
| `performance-host` | 1 | Exclusive performance-sensitive host capacity for FPS, JFR, CPU/GPU, or latency evidence. |
| `interactive-desktop` | 1 | Exclusive human-operated desktop session. |

A task can reserve more than one unit. FPS and selection-lag tasks reserve every `host-slot`, so their measurements are not contaminated by other Cubism sessions. An interactive task also reserves `display-input`, preventing automation from acting on the operator's window.

Resource leases live on the validation host under:

```text
/home/local-user/TurboismValidation/.scheduler/leases/<resource>/slot-<n>/
```

Each lease records an owner, task spec, start time, and heartbeat. Acquisition uses atomic remote directory creation. A dispatcher releases only leases whose owner token matches its own. Stale lease removal is never automatic; it requires an explicit age threshold and `--force`.

## Task specifications

A task is selected as:

```text
<task>:<version>[@<variant>]
```

Examples:

```text
workspace:5302
parameter:5203@binding-matrix
host-locale:5302@ja
psd-clip-mask:5203@read
```

When a task has variants and the suffix is omitted, the manifest's `defaultVariant` is used. The manifest deliberately marks wrappers that require request-specific arguments as non-runnable rather than guessing those arguments.

## Dispatcher usage

List tasks and resource declarations:

```bash
python3 scripts/preview/host_validation.py list
```

Preview deterministic resource-compatible waves without SSH or Cubism launch:

```bash
python3 scripts/preview/host_validation.py plan \
  workspace:5302 recent-preview:5203 fps:5302
```

Run compatible tasks concurrently and incompatible tasks in later waves:

```bash
python3 scripts/preview/host_validation.py run \
  workspace:5302 recent-preview:5203 status-bar:5302
```

Inspect active remote leases:

```bash
python3 scripts/preview/host_validation.py status
```

Release leases only after verifying that their owning dispatcher and Cubism task are no longer active:

```bash
python3 scripts/preview/host_validation.py release-stale \
  --older-than 3600 --force
```

Common placement options are available on `plan`, `run`, `status`, and `release-stale`:

```text
--ssh-host
--ssh-key
--scheduler-root
```

`run` also supports:

```text
--run-label
--wait-seconds
--poll-seconds
--keep-going
```

## Planning behavior

The dispatcher preserves the request order and applies first-fit wave planning:

- tasks are added to the earliest wave whose declared capacities remain valid;
- all tasks in one wave launch concurrently;
- the next wave starts only after the current wave finishes;
- remote leases still arbitrate with other dispatcher processes and other worktrees;
- a failed wave stops later waves unless `--keep-going` is supplied.

`plan` is advisory and requires no host access. `run` is authoritative because it acquires remote leases immediately before each wrapper starts.

## Direct wrapper invocation

Feature wrappers remain valid one-off entry points. Direct invocation does not acquire scheduler leases, so the operator is responsible for checking `status` and respecting the same resource declarations. Automated, parallel, or unattended exact-host work should use the dispatcher.

The dispatcher intentionally does not duplicate:

- bundle construction;
- fixture identities or hashes;
- Cubism installation paths;
- JVM options and validation markers;
- result polling;
- process cleanup;
- evidence collection.

Those contracts continue to live in the feature wrapper and the generic runner.

## Adding or changing a task

1. Keep the feature wrapper thin and delegate host lifecycle to `run-cubism-host-validation.sh`.
2. Add or update one manifest entry with exact versions, argument templates, variants, and required resources.
3. Use only `{version}` and `{runLabel}` in scheduler argument templates.
4. Mark the entry `runnable: false` with `blockedReason` if safe invocation requires additional request-specific input.
5. Update the scheduler contract test and run:

```bash
bash scripts/test/check_host_validation_scheduler.sh
```

6. For any task using global desktop input or performance evidence, declare the corresponding exclusive resource instead of adding a new global host-validation lock.
