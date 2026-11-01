# Local recovery acceptance helpers

These Java files are **manual lab fixtures**, excluded from production source sets. They do not
establish release admission, genuine CI provenance or native button interaction. Do not use them on
a user's installation. They generate no production credential; the README-only component helper
generates an ephemeral Ed25519 key in its own JVM and authenticates its fixture manifest there, and
the fixture manifest counter is not an official CI build number. It neither writes CI properties nor
installs a trusted-key resource into Cubism's agent.

## What changed from the original `D:/turboism-test` binding

The original fixtures hard-coded `D:/turboism-test/issue50-recovery-f173f64`. They are now bound to
a dedicated, marked lab home under **this repository's own** `build/native/issue50-host/`:

- The allowed base is **never caller-supplied**. `LabHomeGuard` derives it from the repo root
  (`settings.gradle.kts` + `build.gradle.kts` + `.git`), resolved from the current working directory
  or the `-Dturboism.lab.repo.root=<path>` system property. Run the fixtures from the repo root.
- A lab home must be **strictly beneath** `build/native/issue50-host/` (never equal to it).
- The home must carry the exact marker `turboism-issue50-lab-marker.json`
  (`{"schemaVersion":1,"purpose":"turboism-issue50-recovery-lab","isolated":true}`).
- `init` only marks a **freshly created, empty** home. It refuses to retroactively mark a home that
  already contains content, so a user directory can never be silently "isolated".
- Every path (home, stage, output) is checked for reparse points (symlinks and junctions, via
  `BasicFileAttributes.isOther()` under `NOFOLLOW_LINKS` — JDK 17 has no `DosFileAttributes.isReparsePoint()`).
  A path that does not exist yet is treated as not-a-link and re-verified after creation; real access
  errors still fail closed.
- `seed`/`apply` refuse while a live JVM references the lab's `turboism-agent.jar` (Windows CIM
  query, same approach as the production helper).
- `--plugins` accepts **bare file names only** (no `/`, `\`, `..`, drive letters or absolute forms)
  and only names present under `home/plugins/`.

## Compile (JDK 17; no Gradle)

Use the already-built baseline agent JAR plus Jackson 2.18.9 from the Gradle cache. From the repo root:

```powershell
$JDK = "$env:JAVA_HOME"                      # JDK 17
$AGENT = "build\native\issue50-host\probe-tools\turboism-agent.jar"
$JK = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.fasterxml.jackson.core"
$JDB = "$JK\jackson-databind\2.18.9\93f3c1564ae82c1f5c034883f9ce45782054e04\jackson-databind-2.18.9.jar"
$JC  = "$JK\jackson-core\2.18.9\ca645ad40f7be06d1a0bbdf9f363d3838b41f40\jackson-core-2.18.9.jar"
$JA  = "$JK\jackson-annotations\2.18.9\c19aab861fcdf6e2f185ad84fb23f0a8383097f8\jackson-annotations-2.18.9.jar"
$OUT = "build\native\issue50-validation\fixture-classes"
New-Item -ItemType Directory -Force $OUT | Out-Null
& "$JDK\bin\javac.exe" -encoding UTF-8 -cp "$AGENT;$JDB;$JC;$JA" -d $OUT `
    validation\issue-50-updater\fixtures\LabHomeGuard.java `
    validation\issue-50-updater\fixtures\HostRecoveryFixture.java `
    validation\issue-50-updater\fixtures\HostRollbackFixture.java
```

Run-time classpath is the same (`$AGENT;$OUT;$JDB;$JC;$JA`). All sources compile cleanly with
`-Xlint:all` on JDK 17 against the current baseline agent.

## HostRecoveryFixture (plugin bad-pending -> rollback -> suspended -> cancel -> retry)

```text
HostRecoveryFixture <command> --home <labHome> [--plugins a,b] [--out <file>]
commands: init | baseline | seed | restage | apply | status | blocked | cancel | chain
```

| command | effect |
|---------|--------|
| `init`  | Creates the marker + empty `plugins/`, `state/…`, `README.txt`, `config.json`. Only on an empty/new home. |
| `baseline` | Writes a reviewable baseline (`config.json`, `README.txt`, `installed-build.json`, every plugin JAR hash, journal presence) to `--out` (default `state/runtime/issue50-lab-baseline.txt`). Refuses to overwrite. |
| `seed`  | Stages every (or `--plugins`-filtered) plugin JAR, then flips the last staged byte → the bad-pending state. |
| `restage` | Same staging without corruption (the "retry" after cancel). |
| `apply`  | Runs `RuntimePluginManagementService.applyPending(home)`; prints `APPLY_STATUS`/`APPLY_CODE`. |
| `status` | Prints the framework-aware `pendingChanges()` review code and changes. |
| `blocked` | Asserts `PLUGIN_FRAMEWORK_RECOVERY_PENDING` blocks cancellation (run after `HostRollbackFixture`). |
| `cancel` | Discards the reviewed intent; prints resulting journal presence. |
| `chain`  | `baseline` → `seed` → `apply`(ROLLED_BACK) → verify all plugin hashes restored → `status`(PLUGIN_PENDING_FAILED) → `apply`(DEFERRED) → `cancel` → `restage`. |

The full verified sequence, including the framework component step, is:

1. `init`, then copy the staged `turboism-agent.jar`, `installed-build.json`, `config.json` and the
   real plugin JARs into the home; `baseline`.
2. `seed` → `apply` (expect `APPLY_STATUS=ROLLED_BACK`) → `status` (expect `REVIEW_CODE=PLUGIN_PENDING_FAILED`).
3. `HostRollbackFixture <home> <fresh-stage-dir> <result-json>` → `COMPONENT_AWAITING_HEALTH`; this
   leaves a framework journal so `blocked` now reports `PLUGIN_FRAMEWORK_RECOVERY_PENDING` and refuses
   `cancel`.
4. A controlled managed launch recovers the README component and clears the framework journal (launcher
   lane, not this fixture).
5. `apply` again → `APPLY_STATUS=DEFERRED` while the suspended intent remains; `cancel` → `CANCELLED`;
   `restage` → a fresh `pending.json`.
6. Re-run `baseline` to a new file and diff it against step 1 to prove installed plugin bytes/config
   were never mutated.

`chain` automates steps 2 and 5 in-process; step 3/4 are driven by the launcher lane.

## HostRollbackFixture (README-only component transaction)

```text
HostRollbackFixture <root> <fresh-stage-dir> <result-json>
```

- `root` must pass `LabHomeGuard.validate` (marker, under base, no links, not a Cubism root).
- `stage` and `result-json` must be fresh paths strictly under the lab base.
- It signs a README-only manifest with an ephemeral Ed25519 key, applies it through
  `FrameworkUpdateApplier.applyPendingHealth`, and asserts `AWAITING_HEALTH` with the agent JAR
  unchanged. It records the README/agent/config hashes and every plugin JAR hash into the result JSON.
- This is **not** a full signed framework or bytecode upgrade; it does not fake CI or install trust keys.

## Native UI verification

The 2026-11-01 local Cubism 5.3.02 run now covers the real review/default-keep/explicit-cancel controls;
see [the current Windows acceptance record](../windows-launch-lease-host-validation.md). It used PID/owner-bound
Windows keyboard events with screenshot verification, not the service-level fixture's `cancel` command.
The following manual procedure remains applicable to another host and does not imply full signed-update acceptance.

There is **no** AWT auto-click driver: a standalone `main` cannot enumerate AWT windows across
processes, and auto-clicking an unidentified `JOptionPane` risks hitting the official license/trial
dialog. The review/cancel buttons are therefore verified manually, with explicit stop rules:

1. Launch the lab agent against a suspended-intent home so the real plugin-management dialog opens.
   Confirm the failed batch is the only intent (run `status`, expect `PLUGIN_PENDING_FAILED`).
2. Click **“待办变更…”**. The confirmation dialog must be owned by the plugin-management dialog, carry
   the product's `plugins.pending.review` title, and show exactly two options — the localized
   “discard” and “keep” labels. If any other, extra, or unrecognized popup appears, **stop and do not
   click it** (never consent to a license/trial).
3. **Default retain**: choose “keep” (or close the dialog). Verify
   `state/runtime/plugin-management/failed-pending.json` still exists and `status` still reports
   `PLUGIN_PENDING_FAILED`; the pre-recorded plugin/config/README hashes are unchanged.
4. **Explicit cancel**: click “待办变更…” again, choose “discard”. Verify `failed-pending.json` is
   gone and `status` reports `PLUGIN_PENDING_EMPTY`; installed JARs and user config are byte-identical
   to the baseline.

On another host, do not claim this step as accepted until it is performed and the exact dialog identity is
confirmed. `HostRecoveryFixture` service-level `cancel`/`blocked` are NOT a substitute for native control interaction.

Do not run these fixtures on a user's installation. Do not re-seed a lab that already holds a retained
failed transaction. Never disable production eligibility checks or rewrite artifact provenance.
