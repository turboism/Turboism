# 0.70.0 Windows matrix runner audit

Status: read-only audit of runner support. The audit ran no Cubism session and no matrix stage.
It writes this document only. It claims no matrix PASS.

## Scope and boundaries

The audit examined the native Windows execution path for the three 0.70.0 matrix stages:

- `core-acquisition:<TASK_CODE>` (Stage 1).
- `core-owned-moc:<TASK_CODE>` (Stage 2).
- `status-bar:<TASK_CODE>` (Stage 3).

The audit read the canonical queue, the generic runner, the containment helper, the evidence
finalizer, the managed launcher and the representative matrix.
The audit started no Cubism, Wine, Proton, GUI or host probe process.
The audit performed no desktop input, install, elevation, service restart, push or publish.
The audit did not modify the official input roots, the private profiles or the fixtures.
The main agent owns every Cubism process and every desktop action.

CodeGraph was unavailable on this host. A bounded source inspection replaced it.
Raw command output is retained in the ignored directory `build/windows-matrix-audit/`.

This machine is native Windows x64 with Python 3.12.7.
The WSL Ubuntu inventory reports Java 21 and no verified Proton prefix.
That inventory does not establish Linux readiness. It is not host evidence.

## Inputs verified elsewhere

All eight matrix endpoints have verified official inputs.
The tracked manifest is [host-matrix-official-inputs.json](host-matrix-official-inputs.json).
The acquisition record is [host-matrix-official-acquisition.md](host-matrix-official-acquisition.md).

The private facts at `D:/TurboismValidation/070-worthy-emu/matrix-inputs/official-inputs-all-eight.json`
match the tracked manifest. The audit read that file read-only.
That read compared the installer hash, the Editor JAR hash, the Core JAR hash and the presence of
the official `CubismEditor5.bat`. All eight endpoints matched on every field.
Each record lists a publisher-verified installer, one extracted host root, the Editor hash and a
per-file trusted inventory of 149 to 155 runtime files.
The inventory includes `app/lib/Live2D_Cubism.jar`, `app/lib/Live2DCubismCore.jar`,
`app/dll64/Live2DCubismCoreJNI.dll` and the bundled JRE.
Each record reports `INPUTS_VERIFIED_NATIVE_ACCEPTANCE_PENDING`.
A native host run therefore needs no Proton prefix and no installer acquisition.

The [representative matrix](representative-host-matrix.md) still owns the stage assertions,
the pinned fixture and the acceptance rules.

## Verified native capabilities

Four capabilities already exist on native Windows. The native path should reuse them.

1. Kernel containment. `scripts/preview/host_validation_containment.py` selects a kill-on-close
   Job Object on native Windows (`_windows_containment_supported`, line 525).
   The payload joins the job before it can spawn (line 747).
   Cleanup proof comes from the kernel `ActiveProcesses` count after `TerminateJobObject`.
   The proof keeps the shared identity fields and adds `backend: "windows-job"`.
2. Process identity fence. `scripts/preview/hvq/common.py` reads the Windows creation time in
   FILETIME ticks and reports the fixed boot label `windows` (line 145 onwards).
   This fences PID reuse without `/proc`.
3. Locking and storage privacy. `hvq.common.FileLock` uses `msvcrt` byte-range locks when
   `fcntl` is absent. `private_directory` falls back to ACL-backed privacy.
4. Official launch seam. `packaging/windows-installer/launch-cubism-turboism.ps1` accepts
   `-HomePath` and `-CubismRoot` (lines 83 and 126).
   The launcher delegates to the selected root's official `CubismEditor5.bat` (line 165)
   through `Invoke-CubismOfficialBat` in `cubism-launch-common.ps1` (line 2713).
   An explicit `-CubismRoot` skips the mutable installation state file.
   The launcher requires `turboism-agent.jar` in the supplied home.

The audit did not run that launcher. The statements above come from source and from the
existing PowerShell tests under `packaging/windows-installer/`.

## Blocker map

Numbering is stable. Each blocker cites the exact module and line.

### B1. The canonical queue entry cannot import on Windows

`scripts/preview/host_validation_queue.py` performs `import fcntl` (line 12) and `import pwd`
(line 18) at module scope.
`scripts/preview/host_validation.py` imports that module (line 28).
`scripts/preview/host_validation_evidence.py` imports the same module.

Direct native evidence: all three imports fail with
`ModuleNotFoundError: No module named 'fcntl'`.
The guarded platform surface in `hvq/common.py` never gets a chance to apply.

Consequence: `list`, `plan`, `prepare`, `submit`, `status`, `wait`, `serve` and `recover` are all
unavailable. The private library-injection points are test seams, not production switches.

### B2. Prepared input binds a POSIX executor and a POSIX durability path

`hvq/snapshot.py` hardcodes the executor at line 802:

```python
return ["bash", str(root / "tool/scripts/preview/run-cubism-host-validation.sh"), ...]
```

`host_validation.py` `render_command` builds `["bash", <adapter>]` at line 288.
The frozen descriptor therefore records a POSIX shell, not a host-specific launcher.

The same module opens the prepared directory with `os.O_DIRECTORY` at line 757.
That attribute is absent on Windows. Direct native evidence: `hasattr(os, "O_DIRECTORY") == False`.
The final `chmod(0o500)` freeze at line 764 has no Windows mode equivalent.

### B3. The worker cannot start an attempt on Windows

`hvq/worker.py` line 214 requests `multiprocessing.get_context("fork")`.
Direct native evidence: `get_all_start_methods()` returns `['spawn']` only.

The admission descriptor is inherited across `fork` (`admission.fd`, line 215).
Windows rejects descriptor passing. Direct native evidence:
`subprocess.Popen(..., pass_fds=...)` raises
`AssertionError: pass_fds not supported on Windows.`
`host_validation_containment.py` confirms the same gap on its own Windows path: it records
`"admissionFdInherited": False` (line 738) and calls `Popen(..., close_fds=True)` without
`pass_fds` (line 756). The Linux branch passes the descriptor (line 933).

### B4. Display allocation blocks every claim on Windows

`hvq/store.py` `claim` allocates a private display for each parallel job (line 312).
`_allocate_display` defers the job when `Xvfb` is missing (lines 331 to 332) with reason
`display-pool-unavailable`.
Direct native evidence: `shutil.which("Xvfb") is None`.

Only the `interactive-desktop` and `performance-host` resources map to display `:0`
(lines 307 to 308). Windows has no X display and no queue arbiter for the interactive desktop.

This blocker hits `status-bar` too. That task declares only `host-slot`, but the claim path still
requests a display allocation.

### B5. The runner requires a Proton prefix by construction

`scripts/preview/run-cubism-host-validation.sh` fails without a golden Proton prefix (line 479)
and without a Proton runner (line 481).
It clones the prefix with a CoW reflink (line 2264) and requires `pfx/drive_c/windows` (line 2266).
It enters the guest through
`$prefix_dir/pfx/drive_c/windows/system32/cmd.exe` (line 2340).
It writes `DISPLAY` into the launch environment (line 2489) and asserts Wine-side ownership
through `WINEPREFIX` markers.

There is no native branch. A native launch cannot use this script unchanged.

### B6. The admission gate is cgroup-only

`hvq/runner.py` `validate_admission` walks the parent chain through `/proc/<pid>/stat`
(line 250).
`verify_containment_membership` builds `Path(metadata["cgroupPath"])` (line 184) and compares
`/proc/self/cgroup` (line 187) with the recorded cgroup device and inode.
The Windows job proof records `cgroupPath: None`.

Consequence: a job launched under the Windows Job Object cannot pass its own gate.

### B7. The durable outcome validator rejects a Windows job proof

`hvq/runner.py` requires `kernelProof.originalCgroupBound is True` (line 308) and matching
`bootId`, `cgroupPath`, `cgroupDevice` and `cgroupInode` keys.
The same rule repeats in the recovery path (line 353).

The Windows job proof reports `originalJobBound`, `jobObjectTerminated`,
`jobActiveProcesses` and `finalReading.kind == "same"`.
Consequence: a real, kernel-proven Windows cleanup would be recorded as incomplete.

### B8. The evidence layout assumes a Proton prefix

`host_validation_evidence.py` line 127 derives the golden and cloned hosts from
`pfx/drive_c/Program Files/<hostLabel>`.
The finalizer then re-hashes the golden BAT, the cloned BAT and both JARs.

That layout does not exist in a native staged host root.

### B9. The managed launcher offers no task option channel

`cubism-launch-common.ps1` `New-CubismManagedOptionsBat` emits a fixed option set
(lines 2628 to 2641): `-Duser.dir`, `-Dturboism.home`, the `-javaagent` token with
`home`, `timeoutSeconds` and `launchToken`, the Graal settings, and bounded JDK tokens from
the home configuration.

The three stages need more than that set:

- `core-acquisition` needs `turboism.coreAcquisition.profile`, both expected hashes,
  `exitOnComplete` and the auxiliary probe agent.
- `core-owned-moc` needs the plugin staging, the fixture path, the expected Core tuple and
  several `turboism.validation.*` options.
- `status-bar` needs the exerciser plugin and the mode option.

The launcher also performs bootstrap-receipt and startup-update preparation for any launch that
is not `-ProbeOnly` (lines 203 to 236). A frozen task home must not be mutated by that step
without an explicit decision.

### B10. Task contract mismatches in the catalogue

These are documentation or catalogue defects, not host limits. They will cause an
unreproducible or mislabelled run if left unchanged.

- The matrix says the queue selects the startup-assistance variant by default.
  `scripts/preview/host-validation-tasks.json` declares `"defaultVariant": "standard"` for
  `core-acquisition` (line 1055) and for `status-bar` (line 3185).
  Only the `assisted` variants set `turboism.coreAcquisition.startFree` or
  `turboism.validation.startupAssistance`.
- `run-core-owned-moc-host-validation.sh` passes `--linux-env 'mesa_glthread=false'`
  unconditionally (line 31). `--linux-env` is a Mesa and Proton knob.
  A native launch must reject or drop that option explicitly. Silent ignoring is not acceptable.
- `run-status-bar-host-validation.sh` comments that only 5.2.03 and 5.3.02 run the visible
  manager and compact matrix. The task catalogue covers all 20 reviewed versions.
- `status-bar` declares no `display-input` resource, yet the probe reports visible UI state.
  The native path needs an explicit desktop claim or an explicit operator-present rule.

## Blocker per stage

| Stage | Blocking items | First hard stop |
| --- | --- | --- |
| `core-acquisition` | B1, B2, B3, B4, B5, B6, B7, B8, B9, B10 | Queue import fails before preparation |
| `core-owned-moc` | B1, B2, B3, B4, B5, B6, B7, B8, B9, B10 | Same import failure, plus the Mesa option contract |
| `status-bar` | B1, B2, B3, B4, B5, B6, B7, B8, B9, B10 | Same import failure, plus the visible-desktop requirement |

No stage can reach Cubism on native Windows through the canonical path today.
The blocker is the toolchain and the launch adapter, not the official inputs.

## Direct native evidence versus queue acceptance

Three evidence classes exist. They are not interchangeable.

1. Direct native evidence. A real Windows session under the Job Object.
   The tracked record [plugin-lifecycle-native.md](plugin-lifecycle-native.md) shows such
   sessions with normal exit and zero active job processes.
   That record covers plugin lifecycle only. It does not cover the three matrix stages.
2. Canonical queue acceptance. A prepared digest, an admitted attempt, the runner lifecycle
   fence, a durable verdict and a terminal supervisor record.
   No such verdict exists for any endpoint at this revision.
3. Offline gates. `admissionPreflightTest`, `checkReviewedReleases` and the static selector
   checks run natively today, as the matrix Stage 0 notes.
   Exit 0 there is not host acceptance.

The audit produced no class 1 evidence and no class 2 evidence.
It ran bounded import and capability checks only.

## Smallest defensible path

Three shapes were considered.

| Option | Shape | Cost | Risk |
| --- | --- | --- | --- |
| A | Port the whole queue, including scheduling, to Windows | High: worker, admission, display and storage | Desktop arbitration must be designed inside the queue |
| B | A separate native harness with its own evidence format | Low code, new provenance format | Two evidence contracts diverge; review burden moves to the main agent |
| C | Keep one evidence contract; add a host-selected native backend | Medium: ten bounded edits and focused tests | Windows proof variants must stay as strict as the cgroup proof |

Option C is the recommended path. It keeps one prepared store, one finalizer, one durable
verdict schema and one review surface. It replaces only the host-specific pieces.

Option B is acceptable only if the harness emits the same `lifecycle-result.json` schema and
passes the same finalizer. A second schema would weaken review.

Option A is larger than the remaining 0.70.0 work.

### Invariants the native path must preserve

- Prepared binding. The frozen descriptor keeps the source HEAD, the dirty digest, the tool tree
  inventory, the source inputs and the artifact hashes.
  The descriptor must also record the executor and the launch backend.
- Artifact binding. Each attempt re-hashes the Editor JAR, the Core JAR and the official BAT
  before and after the run. It re-hashes the staged agent, probe and fixture.
- Fixture binding. Each endpoint uses its own fixture path and SHA-256.
  The 5.3.03 fallback is not acceptable for a 5.0, 5.1 or 5.2 endpoint.
- Oracle assertions. The stage assertions stay exactly as the matrix states them.
  No stage may borrow a verdict from another stage or another version.
- Lifecycle fence. Supervisor identity, attempt identity and prepared digest must match.
  The fence may change mechanism, never strength.
- Work budgets. Keep the ready, result and exit timeouts, and set an explicit queue job
  timeout. The default job budget is 1800 seconds.
  Native staging adds host-root copy time, so budgets need a measured margin.
- Normal exit. The probe must record a normal Editor exit, not a kill.
- Kernel cleanup proof. The proof must come from the kernel, not from a PID list.
  The Windows proof is `jobActiveProcesses == 0` plus a terminated job and a matching entry
  identity.

Two rules must hold for any implementation.

- Host selection, not a flag, picks the backend. A `--force`, an environment variable or a
  test-only root must never enable a Windows proof on a Linux host or the reverse.
- A proof that lacks its backend tag is invalid. Accepting either proof generically would
  weaken both.

### Recommended order

1. Repair the toolchain import and the prepare path. This enables `list`, `plan` and `prepare`
   on Windows without changing any scheduling rule.
2. Add the native admission and outcome contract. This enables one host-slot job at a time.
3. Add the native launch adapter and run one tracer-bullet job:
   `core-acquisition:5304`, because that endpoint already has a recorded native session and a
   verified input root.
4. Extend to the remaining endpoints, then to `core-owned-moc`, then to `status-bar`.
5. Keep `status-bar` last. It needs an explicit rule for the interactive desktop.

## Required tracked changes

These changes are required for the native path. The audit did not implement them.

### Modules and interfaces

| Change | Module | Interface |
| --- | --- | --- |
| C1 | `scripts/preview/host_validation_queue.py` | Remove the hard `fcntl` and `pwd` imports. Re-export the guarded `hvq.common` bindings so `__all__` and callers stay stable |
| C2 | `scripts/preview/hvq/snapshot.py` | Guard `os.O_DIRECTORY` and the POSIX mode freeze. Record `executor` and `launchBackend` in the descriptor. `PreparedStore.command` selects the recorded executor |
| C3 | `scripts/preview/hvq/worker.py` | Use the host start method. Replace the inherited admission descriptor with a durable admission record on Windows. Keep the existing handshake order |
| C4 | `scripts/preview/hvq/runner.py` | Split `validate_admission` into a POSIX gate and a `windows-job` gate. Dispatch `verify_containment_membership` on `metadata["backend"]`. Accept a `windows-job` kernel proof in `durable_outcome` and in `containment_failure_outcome`, and reject a mismatched backend |
| C5 | `scripts/preview/host_validation_containment.py` | Add the job-side admission record: job name, entry identity, creation ticks, active-process reading at bind time. Keep the existing proof fields |
| C6 | `scripts/preview/hvq/display.py`, `hvq/store.py` | Add a display-provider seam: `xvfb` on Linux, `interactive-desktop` on Windows. Record the provider in the job row and in the evidence |
| C7 | `scripts/preview/run-cubism-host-validation.sh` | Extract the host-dependent steps behind a backend seam, or add a native entry script. Required native steps: hash-verified staging of the official host root, task-owned home creation, official BAT launch, result polling, `lifecycle-result.json` |
| C8 | `packaging/windows-installer/launch-cubism-turboism.ps1`, `cubism-launch-common.ps1` | Add a bounded, hash-declared validation option channel, or move the task options into the staged home configuration. Add an explicit no-update contract for a frozen home |
| C9 | `scripts/preview/host_validation_evidence.py` | Resolve golden and cloned host roots from the backend-declared layout. Keep every hash re-check |
| C10 | `scripts/preview/host_validation_retention.py`, `host_validation_disposition.py` | Replace `os.getuid()` with the shared identity helper and skip POSIX mode checks on Windows |

### Focused tests

Minimal effective set. Append to existing suites where a suite already exists.

- `scripts/test/test_host_validation_containment_windows.py`: assert that
  `host_validation_queue` and `host_validation_evidence` import on Windows. Assert that a
  `windows-job` proof is accepted by `durable_outcome` and that a cgroup proof is rejected on
  that backend.
- `scripts/test/test_host_validation_queue.py`: prepare and load round trip with the recorded
  executor, plus a digest-stability check.
- `scripts/test/test_host_validation_evidence.py`: layout dispatch per backend, and a negative
  case for a mismatched proof backend.
- `scripts/test/test_host_validation_worker_native.py` (new, Windows-only): start method,
  admission record, and the absence of descriptor inheritance.
- `packaging/windows-installer/test-managed-launch.ps1`: the frozen home case and the validation
  option channel, if C8 is chosen.

### Remaining host evidence

Code changes alone do not satisfy the matrix. Each endpoint still needs:

1. Stage 0 offline gates for the candidate revision, run natively.
2. Stage 1 `core-acquisition` PASS with `editorHashMatch`, `coreHashMatch`,
   `sameDefiningClassLoader`, the endpoint `nativeCoreVersion`, `stableSnapshots=3`,
   a model instance count of at least 1, `identityVerified`, `fixtureUnchanged`, `normalExit`
   and a safe cleanup.
3. Stage 2 `core-owned-moc` PASS with the pinned fixture
   `aa59f7a3dc3b30b23b5f10afd9192ed755e4d01dc9a12442d99b972a418e2543`, independent model
   instances, immutable copies, explicit close order and use-after-close rejection.
   The 5.0 and 5.1 endpoints keep `allStructuralReadMethods=PARTIAL_EXPECTED` with 42 parameters
   and 19 parts. The 5.2 and 5.3 endpoints report 42 parameters, 19 parts, 84 drawables,
   97 deformers and 0 Glues. Assert the observed `latestMocVersion` per endpoint.
4. Stage 3 `status-bar` PASS for the visible manager and compact status, severity rendering,
   status removal, the canvas hint path, and removal on disable and shutdown.
5. One record per job that binds the prepared digest, the source revision, the agent and probe
   hashes, the fixture hash, the host JAR hashes and the terminal supervisor result.
6. An explicit rule for the interactive desktop. Either an operator is present and recorded,
   or the run proves it needs no input. The audit cannot prove unattended desktop behavior.

The two known withdrawals stay open. A 5.0 or 5.1 typed capability absence stays `BLOCKED`.
Seam-failure withdrawal for `status-bar` remains unproven.

## Non-claims

- No matrix stage passed.
- No endpoint passed.
- The eight verified inputs prove file identity only.
- The native 5.3.04 sessions prove plugin lifecycle behavior only.
- The WSL Ubuntu inventory is not Linux readiness evidence.
- The static selector gates are not host acceptance.

## Reproduction

The audit ran these bounded commands from the worktree root in Git Bash.
None of them starts a host process beyond the Python interpreter that runs the check.

```sh
python --version
python -c "import sys;sys.path.insert(0,'scripts/preview');import host_validation_queue"
python -c "import sys;sys.path.insert(0,'scripts/preview');import host_validation_evidence"
python -c "import sys;sys.path.insert(0,'scripts/preview');import host_validation"
python -c "import sys;sys.path.insert(0,'scripts/preview');import hvq.common as c,hvq.display as d;print(c.fcntl,c.pwd,bool(c.msvcrt),c.account_root(),d.external_sessions())"
python -c "import importlib.util;s=importlib.util.spec_from_file_location('hvc','scripts/preview/host_validation_containment.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);print(m._windows_containment_supported())"
python -c "import multiprocessing;print(multiprocessing.get_all_start_methods())"
python -c "import socket;print(socket.socketpair()[0].family)"
python -c "import os,socket;print(hasattr(os,'O_DIRECTORY'),hasattr(os,'getuid'),getattr(socket,'AF_UNIX',None))"
```

Source inspection used `grep -n` on these files:

```text
scripts/preview/host_validation_queue.py
scripts/preview/host_validation.py
scripts/preview/host_validation_evidence.py
scripts/preview/host_validation_containment.py
scripts/preview/host_validation_retention.py
scripts/preview/hvq/common.py
scripts/preview/hvq/store.py
scripts/preview/hvq/worker.py
scripts/preview/hvq/runner.py
scripts/preview/hvq/snapshot.py
scripts/preview/hvq/display.py
scripts/preview/run-cubism-host-validation.sh
scripts/preview/run-core-acquisition-host-validation.sh
scripts/preview/run-core-owned-moc-host-validation.sh
scripts/preview/run-status-bar-host-validation.sh
scripts/preview/host-validation-tasks.json
packaging/windows-installer/launch-cubism-turboism.ps1
packaging/windows-installer/cubism-launch-common.ps1
```

Retained raw output: `build/windows-matrix-audit/native-import-probe.txt`.

## Evidence references

- [representative-host-matrix.md](representative-host-matrix.md): stage assertions and rules.
- [host-matrix-official-acquisition.md](host-matrix-official-acquisition.md): input acquisition.
- [host-matrix-official-inputs.json](host-matrix-official-inputs.json): tracked input manifest.
- [plugin-lifecycle-native.md](plugin-lifecycle-native.md): direct native evidence.
- [README-host-validation-scheduling.md](../../scripts/preview/README-host-validation-scheduling.md): queue contract and the Linux-only statement.
- [host-validation-tasks.json](../../scripts/preview/host-validation-tasks.json): task catalogue, variants and resources.
- [CONTRIBUTING.md](../../CONTRIBUTING.md#cubism-version-adaptation): matrix rule.
