# Combined host acceptance client

`run.py` is a client of the existing `host_validation.py` prepare/submit/wait
commands. It does not implement a scheduler, launch Editor, start a worker, alter
queue state directly, or override any admission lock. Normal performance task
resources are retained unchanged. The two task-only functional entries reuse the
shared Runner and exact-version host slots. UI lifecycle tasks also request
`performance-host` to keep their bounded shutdown deadlines uncontended; that
exclusive resource selects the existing desktop and makes no timing claim.
Other functional tasks receive a private Xvfb.

Build the combined product and probes, complete offline gates, verify the host
inputs and containment, and start or use an independently managed queue worker
as documented in `scripts/preview/README-host-validation-scheduling.md`.
Then select explicit task specifications, for example:

```sh
python3 validation/issues54-55-host-acceptance/run.py \
  --evidence-root /tmp/host-acceptance \
  --label combined-r1 \
  product-ui-lifecycle:5304 incremental-undo:5304 \
  model-update-skip:5304@off-assisted-gl \
  model-update-skip:5304@probe-assisted-gl \
  model-update-skip:5304@on-assisted-gl \
  incremental-update:5304@off-assisted-gl \
  incremental-update:5304@probe-assisted-gl \
  incremental-update:5304@on-assisted-gl
```

Machine inputs come from the existing private `TURBOISM_ENV_FILE` and normal
Runner environment overrides. Native startup assistance is explicitly FREE-only.
The generated manifest copies the authoritative reviewed version catalogue; it
adds no compatibility claims. Task specifications and labels are explicit so a
retry requires a new snapshot and request key. The client preserves failed jobs,
continues independent safely cleaned-up tasks, and stops on unproven cleanup.
It never changes a failure into a PASS or automatically retries it.

Each task records its preparedId, jobId, runId and terminal state in `matrix.jsonl`.
Job evidence, prepared descriptors, assertions, state screenshots and logs are
copied outside the workspace before proceeding. Repeated collection accepts only
identical existing files. Raw output contains machine paths and must not be
committed; preserve source/product/probe hashes alongside it. A source checkout
is still needed for the client, so archive the final product/probes and relevant
tracked tool source separately before removing a workspace.

The product lifecycle tool's injected callback/cleanup retention is distinct
from its actual blocked-EDT scenario. The independent incremental Undo agent
compares OFF/ON geometry and canvas output and verifies reuse recovery. Structural
JAR tests, representative GUI smoke, full-version feature execution and timing
claims must always be reported separately.
