# Turboism Multi-probe Session Host Validation

This bundle runs one exact Live2D Cubism Editor launch through several
read-only group-S validation probes in sequence, instead of one launch per
probe. A task-local coordinator plugin (`dev.turboism.validation.session`)
reads the session plan staged at `state/session/plan.json`, then for each
step: writes the step's trigger file, polls the step's result file up to its
per-step timeout, archives the result under `state/session/steps/`, runs a
health check (visible modal dialogs, active document still present) and
continues. A step timeout or missing verdict becomes a synthetic `FAIL` and
the session continues; a failed health check terminates the session and the
remaining steps are recorded `NOT_RUN`. The aggregate verdict lands in
`state/session/session-result.properties` (`status=PASS` only when every
step is `PASS`), after which the coordinator exits the host itself — the
same bounded exit each standalone probe already performs.

## Bundle contents

- `turboism-agent.jar` — preview agent (from `./gradlew previewBundle`).
- `plugins/session-coordinator.jar` — the task-local coordinator described
  above (from `bash validation/session-coordinator/build.sh`).
- `plugins/texture-relations-host-probe.jar` — texture relations read-only
  smoke probe.
- `plugins/psd-export-observation-session-probe.jar` — PSD native export
  observation in its session variant identity
  (`dev.turboism.validation.textures.exportobs`); the shared
  `dev.turboism.validation.textures` plugin id would deduplicate both jars
  out of a combined bundle.
- `plugins/status-bar-host-validation-exerciser.jar` — status-bar read
  matrix (5.2.03 / 5.3.02 only).
- `plugins/clipmask-viewer.jar` — the production clipmask-viewer plugin the
  exerciser drives (from `./gradlew :plugins:clipmask-viewer:jar`).
- `plugins/clipmask-viewer-validation-exerciser.jar` — clipmask read matrix.
- `plugins/separate-save-path-host-validation-probe.jar` — separate save
  path toggle observation.
- `plugins/mcp-host-validation-probe.jar` — owned-MOC probe, which must be
  built with `TURBOISM_VALIDATION_MOC_FIXTURE` pointing at the pinned
  `Haru.moc3` (the fixture travels inside the jar).
- `README.md`, `SHA256SUMS.txt`.

## Session step gating

All probes run with `-Dturboism.validation.session=true`. Auto-run probes
(texture-relations, psd-export-observation, status-bar,
separate-save-path, core-owned-moc) wait for their own
`state/session/go/<plugin-id>.flag` before doing any work; the flag file
may carry `turboism.validation.*` key=value lines applied as system
properties when the step starts (the export-observation step uses this for
`exportObservation=true`). The flag-gated clipmask exerciser keeps waiting
for its usual `exerciser.flag` — without its standalone deadline — until
either the flag or `state/session/finished.flag` appears. Step ends that
would exit, halt or close the host (`Runtime.exit`, `Runtime.halt`,
`closeIfEligible`) instead write a step-done marker under
`state/session/steps/` and return, so one launch serves every step.
Standalone behavior is unchanged when the session property is absent.

The session plan is generated per job by
`scripts/preview/run-cubism-host-validation-session.sh` — the per-version
step set, trigger paths, result paths and timeouts live there — and staged
into the task-scoped home through the runner's `--home-file` channel. The
aggregate `state/session/session-result.properties` is the job's terminal
result file; per-step evidence maps back to `task:version@variant` rows
through `scripts/preview/host_validation_triage.py` and
`scripts/preview/sync_campaign_ledger.py`.

保存路径步骤采用只读契约。Session Runner 在启动前预置
`config/turboism.core/save-directory-history.properties`，数据来自探针的固定双槽文件。
Windows 验证包携带同一份 `fixtures/save-directory-history.properties`，并将其计入 SHA256SUMS。
手工准备任务 home 时，必须通过 Runner 的 `--home-file` 将此文件放到上述配置路径。
此步骤验证读取、持久化一致性和写入拒绝，不再写入 shell 历史。结果采用 schemaVersion=2。
