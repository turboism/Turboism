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
The only admitted custom hook inventory is the FPS resize driver. The history
baseline wrapper's `collect-history-validation-evidence.sh` cleanup hook, FX hooks,
MCP clients and generated plugin-chooser hooks remain blocked at snapshot admission
until their complete dependencies are explicitly reviewed; do not bypass the queue.
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
**not** automatically installed by a build or CLI command. Point it at a stable,
reviewed checkout and provide the login session's display/auth environment.
Do not upgrade its tool files or switch its checkout while a job is running.

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
