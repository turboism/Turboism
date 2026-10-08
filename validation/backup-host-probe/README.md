# backup-host-probe

Task-local exerciser for the auto-backup manager takeover and the WebDAV backup
sync path on an exact Cubism host. Two queue tasks share this probe:

- `backup` (`scripts/preview/run-backup-host-validation.sh`): settings
  write-readback, `backupNow` artifact production, fixture-hash preservation,
  and a WebDAV upload through a probe-registered sync target compiled from the
  production `plugins/webdav-backup` sources, against an in-JVM recording mock.
- `backup-webdav` (`scripts/preview/run-backup-webdav-host-validation.sh`):
  additionally deploys the production `dev.turboism.plugin.webdav` plugin jar
  with a seeded `config/dev.turboism.plugin.webdav/backup/webdav.cfg`
  (rendered by `render-webdav-config.py`) that points it at the same mock on a
  fixed 127.0.0.1 port. The probe then drives the semantic
  `EditorCommand.SAVE` so the plugin's save-triggered
  `backupAfterSave` → streaming `PUT` → temp-artifact `discard()` chain runs
  end to end with the plugin's real manifest (no `turboism.file.*`
  permissions).

The mock listens on 127.0.0.1 only and records every request (method, path,
Content-Length, received byte count, body SHA-256) into
`state/dev.turboism.validation.backup/webdav-requests.jsonl`, which the runner
archives under `evidence/state`. It injects one 500 on the first PUT of each
collection so the plugin retry/stream-reopen path is exercised per uploader.

Extra exerciser gates (enabled by the `backup-webdav` wrapper through JVM
properties):

- `turboism.validation.webdav.port` — fixed mock port (default 0 = ephemeral,
  legacy `backup` task behavior).
- `turboism.validation.webdav.expect-plugin=1` — run the production-plugin
  phases: `WEBDAV_TARGET_READY` gating, save-triggered PUT byte/hash/length
  assertions, `turboism-backup-*` temp-dir cleanup check, host backup-dir
  artifact preservation, and the plugin permission/failure log audit.
- `turboism.validation.webdav.plugin-path` — the plugin's remote collection
  (default `/turboism-backup`).
- `turboism.validation.heap-bytes` — when > 0, upload a generated artifact of
  this size while sampling Editor JVM heap; fails if the peak heap delta
  reaches the artifact size (a buffered, non-streaming upload signature).

Build and run (through the local queue, never direct):

```bash
./gradlew :sdk:jar :plugins:webdav-backup:jar previewBundle
bash validation/backup-host-probe/build.sh
python3 scripts/preview/host_validation.py run backup-webdav:5303
```
