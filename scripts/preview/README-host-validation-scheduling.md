# Local exact-host validation queue

`host_validation.py` is the single scheduling entry point. It keeps the existing
feature catalogue and generic `run-cubism-host-validation.sh` lifecycle, but runs
on the development host itself. **No SSH server, key, SSH/SCP invocation, or
localhost-SSH fallback is required.**

## Safety and ownership

- One managed Cubism session at a time, across all worktrees and exact versions.
  Admission covers host setup, official BAT launch, verification, exit and cleanup.
- Continue building/preparing in separate worktrees; only prepared jobs enter the
  FIFO queue. Do not run builds during performance measurements requiring a quiet host.
- Only the reviewed official `CubismEditor5.bat` may launch Cubism. Preserve the
  exact identity checks, task-scoped CoW prefix, isolated home and fixture copy.
  Never modify official files, the golden prefix or original models.
- Never kill unrelated sessions. Unknown/external Cubism processes pause admission.
  An expired heartbeat or an available lock is **not** proof of cleanup.
- `quarantined` blocks the host until matching process/cleanup evidence is verified.
  A safely cleaned-up failed verification does not block unrelated queued jobs.
- Managed tools cannot prevent users/old checkouts from bypassing them. Before
  rollout, stop using old dispatchers and direct host launch scripts. Do not remove
  old leases blindly or interrupt running jobs. Other users and adversarial
  same-UID processes are outside the managed mutual-exclusion boundary.

## Configuration and preparation

Use the ignored `.env` described by `.env.example` for golden prefix, Proton and
fixture paths. The loader parses assignments as data, never executes shell code,
and exported values take precedence. Existing `--remote-root`/`--fixture-remote`
Runner names are compatibility aliases for **local host paths**; prefer
`--host-root`/`--fixture-host`. Explicit SSH and old wave/lease CLI options fail
with a migration message rather than accessing another machine.

Build the capability's bundle/probe first. The capability wrapper and Runner
`--help` remain the authority for version, identity, fixture and assertion options.
`plan` and `--dry-run` do not enqueue, clone a prefix, launch Cubism or execute hooks.

```bash
python3 scripts/preview/host_validation.py list
python3 scripts/preview/host_validation.py plan status-bar:5302 workspace:5302
python3 scripts/preview/host_validation.py prepare status-bar:5302 --run-label check-a
```

`prepare` returns a prepared ID. It fixes the actual Agent, plugins, configuration,
fixture, helpers and normalized Runner arguments into a private input snapshot.
Changing/removing the original worktree does not substitute another build.
The source revision/dirty fingerprint and every captured file digest remain
associated with the job. Golden/official installation and Proton are separately
revalidated host dependencies, not redistributed input bundles.

Unknown custom hook dependencies are rejected, not executed speculatively.
Admitted inventories are the FPS resize driver and the exact `native-resource:5302`
readonly memory observer closure documented in [native resource workload](README-native-resource-workload.md).
The latter requires three fixed helper destinations, a pinned Python interpreter and
managed background execution; it is not general hook admission. The history
baseline wrapper's `collect-history-validation-evidence.sh` cleanup hook, FX hooks,
generated plugin-chooser hooks remain blocked at snapshot admission until their complete
dependencies are explicitly reviewed; do not bypass the queue. The MCP task admits only
`scripts/preview/mcp-host-validation-client.py` with its fixed task filename, MCP task/version,
terminal result path, unchanged-fixture requirement and the preparing Python interpreter.
The client uses only Python's standard library; the interpreter hash is recorded and rechecked,
and Runner invokes it with `-I`. The client disables inherited HTTP proxies and redirects.
Its code and the Runner are captured in the immutable input snapshot. Missing/duplicate inputs,
substituted clients/interpreters, extra hooks and all other custom clients remain rejected.
`host-validation-transport.sh` retains local-only utility names for path/copy
compatibility; it contains no SSH/SCP execution and is a required snapshotted helper.
Interactive WebDAV backup is not supported unattended and is explicitly blocked
before configuration copying or any host side effect. `list` also identifies
other catalogue tasks requiring explicit unsupported inputs.

## Submit independently of the Agent

```bash
python3 scripts/preview/host_validation.py submit \
  --prepared ACTUAL_PREPARED_ID --request-id unique-request-key --json
python3 scripts/preview/host_validation.py status ACTUAL_JOB_ID --json
python3 scripts/preview/host_validation.py events --after 0 --follow
python3 scripts/preview/host_validation.py wait ACTUAL_JOB_ID
python3 scripts/preview/host_validation.py cancel ACTUAL_JOB_ID
```

Replace the example IDs with returned values. Reusing a request key with the same
inputs/timeout returns the same job; conflicting reuse is rejected. Submission
succeeds durably even while the worker is offline. A successful submit is **not**
a verification PASS. Interrupting a client/waiter does not cancel its job.

`run TASK...` is a convenience prepare+submit+wait client. Direct updated wrappers
use the same queue, not another lock. The worker consumes the prepared Runner
without recursively resubmitting it. A claimed attempt and inherited admission
FD must match a live recorded supervisor before the Runner may touch the host;
an environment variable alone cannot bypass admission.

## Run the independent worker

The worker requires a responsive systemd user manager and delegated cgroup v2
scopes with readable `cgroup.events` and writable `cgroup.kill`. There is no PID-scan
fallback for proving cleanup. Each attempt binds a random scope's actual kernel
identity before acknowledging launch; Runner, hooks and descendants stay inside
that scope. The supervisor remains outside and holds the admission lock until
bound-scope cleanup and final evidence are complete. Unsupported containment fails
closed. Task/golden storage must support the required CoW clone (ordinary tmpfs
does not).
After checking the host is free, start a separate long-lived terminal:

```bash
python3 scripts/preview/host_validation.py serve
```

State is stored at `.local/state/turboism/host-validation/` under the account home
resolved for the current UID. `HOME`, `XDG_STATE_HOME`, cwd and per-worktree settings
cannot create competing production lock domains. Do not relocate/copy the live
state directory or delete its lock files. Tests inject isolated temporary roots
and non-host backends through Python, never via a production fake-mode flag.

A second worker is rejected. Completion directly triggers the next dispatch;
clients do not need to wake up or poll for the queue to advance. The worker also
checks durable state periodically to recover missed wakeups. SIGTERM requests a
graceful drain: the running job finishes before the worker exits.

`turboism-host-validation.service.example` is an opt-in user-service example,
**not** automatically installed by a build or CLI command. Set
`TURBOISM_HOST_VALIDATION_CHECKOUT` to the absolute path of a stable, reviewed
checkout in your ignored `.env` (see `.env.example`). Replace the unit's
`EnvironmentFile=/path/to/turboism/.env` placeholder only in your private installed
copy. Keep machine-specific paths out of the tracked example. Use systemd-compatible
plain `KEY=value` entries without `export` or shell expansion; quote paths with spaces.
The unit runs `env --chdir=${TURBOISM_HOST_VALIDATION_CHECKOUT}` because systemd
`WorkingDirectory=` does not expand environment variables. This changes directory
without a shell; a missing/empty path fails rather than starting from another checkout.
Provide the login session's display/auth environment. Treat `.env` as private and
never commit it. Do not upgrade tool files or switch the checkout while a job is running.

With a running systemd user manager, the opt-in regression
`python3 scripts/test/test_host_validation_service_example.py` verifies the unit and
executes only temporary Python stubs: space/metacharacter paths work; empty, missing
or nonexistent checkout paths fail. It does not install the service or start the queue.

## Evidence, failures and recovery

Jobs and attempt evidence live under the shared state root, not a guessed newest
worktree build directory. Inspect `status` for the job, run ID, reason, timestamps
and outcome. `runner.log`, process identity, `outcome.json` and the Runner's
`evidence/lifecycle-result.json` are associated with that exact job/attempt/input.

`containment.json` records the bound scope and attempt identity. The Runner's
initial lifecycle is preliminary: it cannot declare cleanup safe. Only the outside
supervisor combines bound kernel cleanup proof with post-cleanup official-file,
fixture and staged-artifact hashes to write the authoritative lifecycle. Missing
or uncertain proof retains the prefix and prevents a success claim.

PASS requires structured terminal result plus exact host identity, fixture hashes,
normal exit and task-owned cleanup evidence. Exit code 0 or screenshots alone are
insufficient. Failed/unknown evidence is retained; a missing outcome after a crash
is never automatically rerun. Retry explicitly with a new job/run ID.

```bash
python3 scripts/preview/host_validation.py recover --inspect ACTUAL_JOB_ID
# Stop/drain serve before confirming recovery so it cannot race this operation.
python3 scripts/preview/host_validation.py recover --confirm ACTUAL_JOB_ID \
  --reason 'Reviewed matching process identity and cleanup evidence'
```

Confirmation still checks evidence and process state; the reason is an audit note,
not a bypass. Missing safe evidence remains blocked. Never manufacture evidence,
use `release-stale --force`, or terminate unrelated processes to unblock a queue.

Events are durable JSONL records with cursors. Consumers may reconnect and replay;
notification failure cannot block the next job. This is an event interface, not
an automatic Paseo callback or arbitrary webhook/shell executor.

## Agent rollout

After acceptance and merge, each active project Agent must update its validation
tooling at a safe worktree boundary, read this document and acknowledge adoption.
Use the shared queue for the next real-host run; do not ask for SSH credentials,
spin on old leases, run legacy direct performance launch scripts or bypass the
Runner. Finish already-running sessions normally before switching. Report any
unsupported capability rather than reverting to an uncoordinated launcher.

Do not equate successful notification with confirmed adoption; retain the agent
IDs, workspaces, adopted revision and unresolved migration blockers.

## Focused verification

```bash
bash scripts/test/check_host_validation_scheduler.sh
python3 scripts/test/test_host_validation_queue.py
```

Explicit real-kernel integration checks (only isolated harmless test processes;
no Cubism, Wine or Java launch):

```bash
python3 scripts/test/test_host_validation_containment.py
python3 scripts/test/test_host_validation_scoped_runner.py
```

These require the user-manager/cgroup capabilities above and use `cgroup.kill`
only on their own freshly bound test scopes.

These are isolated process/contract tests, not proof of real Cubism readiness.
Real-host acceptance additionally requires reviewed exact-host evidence.

## Artifact retention and collection

`host_validation.py gc` is the only collection entry point. It never launches or
signals Cubism. The fixed current-UID state root and existing admission lock are
unchanged. **Default operation is reporting, not deletion.** Existing jobs and
old directory layouts are protected until individually reviewed/adopted. Removing
a Git worktree is not part of this collector.

```bash
python3 -B scripts/preview/host_validation.py gc plan > /tmp/validation-gc-plan.json
python3 -B scripts/preview/host_validation.py gc inventory --legacy-root /absolute/legacy-validation-root
```

`plan` reads a stable temporary copy of the queue database/WAL; it does not
instantiate/migrate the live Store, create live SQLite sidecars, register jobs or
change retention metadata. A concurrent write/checkpoint may require retry.
Plans contain candidate paths, task identity, expiration, evidence/context and
inode manifests, plus protected items/reasons and `planDigest`. `apparentBytes`
is **not physical reclaimable space**: Btrfs reflinks/snapshots may share blocks.
Inventory reports legacy prefixes only; directory names/mtime never authorize
collection. Use a private location for reports: they contain local paths and
artifact inventories. Full manual plans are not suitable for periodic journal
output; scheduled reports omit the large manifests.

Retention policy defaults (relative to durable terminal time, not directory mtime):

| Artifact | Default |
| --- | --- |
| Successful prefix | Existing finalizer still removes it immediately unless kept |
| Successful temporary task payload | 3 days, only after evidence preservation |
| Safely failed/timed-out/cancelled environment | 14 days |
| Prepared input referenced by jobs | Latest referencing job's diagnostic expiry; every reference must be safe and managed |
| Never-submitted prepared input | 7 days since verified preparation |
| Registered abandoned staging | 24 hours and a proven-dead creator identity |
| Ordinary logs | 30 days, after the environment has been archived/removed |
| Core assertions, identity/hash/cleanup proofs, preserved models | Indefinite |
| Pinned, `--keep-prefix`, active, quarantined, unverified or legacy-unadopted | Protected |

Task payload archival separates raw runtime logs into `logs.tar.gz`; saved models,
assertions and non-log state go to `core.tar.gz` under the job's private
`retention-archive`. An unchanged canonical fixture copy is omitted only after
matching its final recorded hash; changed fixture copies and saved model outputs
are preserved. Temporary Agent/plugin deployment, launch files, home config and
private MCP connection records are not repacked as indefinite evidence. These
omissions are recorded in the archive manifest; prepared descriptors and hashes
remain traceable. This avoids converting shared CoW inputs into new permanent
compressed copies. Publication requires durable writes, archive readability and
hash verification. A manifest binds preserved files to the original task; new or
changed files after archival block later deletion. Existing mixed state/evidence
archives and files not positively classified as ordinary logs remain core
records, even when they contain some log text. Marker-only results retain runtime
logs as core proof; an explicit result file is core even if named `.log` or stored
under `logs/`. This preserves old proof formats.
Raw `runner.log` and the collector's pure `logs.tar.gz` expire at the log deadline;
core lifecycle records never expire. Missing/corrupt archives block collection.

Pins and explicit adoption are audited metadata changes, not deletion:

```bash
python3 scripts/preview/host_validation.py gc pin ACTUAL_JOB_ID --reason 'Acceptance baseline'
python3 scripts/preview/host_validation.py gc unpin ACTUAL_JOB_ID --reason 'Baseline superseded; release keep-prefix hold'
python3 scripts/preview/host_validation.py gc adopt ACTUAL_JOB_ID --reason 'Reviewed exact task ownership and final cleanup evidence'
```

Unpinning a historical hold does not adopt the job. `adopt` validates the existing
final verdict, binds the actual task directory and registers its verified shared
prepared input if needed; other unadopted/active references still protect that
input. It does not support arbitrary legacy paths or manufacture old cgroup proof.
Unknown legacy runs need separate operator investigation and a separately approved
cleanup list. `unpin` explicitly releases the `--keep-prefix` hold as well.

### Apply and recovery

Only after reviewing the exact plan and migrating all old queue writers:

```bash
python3 -B scripts/preview/host_validation.py gc apply \
  --plan /tmp/validation-gc-plan.json --approve ACTUAL_PLAN_DIGEST
```

The digest must be copied from the approved full plan. A modified/rehashed plan
cannot grant access to arbitrary paths: the collector independently reconstructs
managed candidates and compares their fingerprints. Policy, roots, identity,
contents, pins and references are rechecked. Changed/already-collected entries
are skipped, never silently expanded. The default batch is at most 10 artifacts.

Collection takes the storage lock exclusively and the existing admission lock,
checks queued-work priority, global activity/quarantine and external sessions, then rechecks inside a
queue transaction before deletion. Preparation holds a shared storage lock;
submission uses the same lock. The worker waits for a collector holding admission
instead of exiting. Busy collectors never stop the worker or any host process.
Files are removed relative to no-follow directory FDs; Wine links are unlinked
without traversing their target. Mount crossings (including same-filesystem bind
mounts), changed paths and special files are rejected. Same-UID malicious actors
and manual official-BAT launches remain outside managed mutual-exclusion claims.

Durable collection receipts are separate from original verification results.
An error stops the rest of the batch. If a process crashes mid-delete, preserve
receipts/archives, generate a fresh plan and approve the remaining files; do not
reuse an invalidated fingerprint or manually remove lock files. Prepared-input
retirement has an independent durable marker, so even a database rollback cannot
make a partially removed input available for new submission. Remaining input
files must be an unchanged subset of the approved inventory before retry.
Completed job IDs, request keys and final evidence remain queryable. Repeating an
old submit request still returns its original job; a new request using a retired
input is rejected. Re-prepare explicitly to recreate verified inputs. An
interrupted retirement must finish before the same partial input can be rebuilt.

### Opt-in schedule and rollout

`host-validation-retention-policy.example.json` documents all policy fields. Its
private deployed location is `retention-policy.json` directly under the fixed
state root. Unknown keys, invalid types, nonpositive/nonfinite limits or log
retention shorter than environment retention are rejected. The 20 GiB default
free-space reserve checks preparation and production launch storage; low space
pauses new work, never kills jobs or relaxes protected-artifact rules.

1. Review the branch and isolated regression results, then perform/review an
   authorized new exact-host lifecycle/collection acceptance. Isolated tests are
   not real-host readiness evidence.
2. Upgrade/retire every old prepare/submit/worker checkout at a safe boundary.
   Old tools do not implement storage locking or retired-input checks. Do not
   enable collection while an old writer can still use the queue.
3. Copy the policy example only after approval. Set `writersMigrated: true` only
   when that migration is actually complete. Manual `apply` requires this flag.
4. Review the opt-in `turboism-host-validation-gc.service.example` and `.timer.example`.
   Configure the same private EnvironmentFile/checkout pattern as the queue
   service. No build, CLI or test installs/enables them. `Persistent=true` catches
   missed daily runs; idle priority limits interference. `gc run` defaults to a
   compact report while `enabled` is false.
5. Observe reports and obtain explicit approval before setting `enabled: true`.
   Each scheduled run then plans and rechecks its own bounded batch. An active or
   quarantined host makes it skip. Stop/disable the timer or set `enabled: false`
   to prevent subsequent automatic collections; this cannot undo prior deletion.

Focused regressions: `python3 scripts/test/test_host_validation_retention.py`.
All its queues/artifacts are private temporary fixtures; it never launches Cubism.
