# Auto Mesh Framework Host Validation

Proves the Auto Mesh framework end to end on a real Cubism Editor: the algorithm registry, the
transformed native funnel guard, custom dispatch with one Undo entry, the multi-keyform refusal and
the selector row. The probe is a test-only plugin and is never shipped.

## What the probe proves

The probe runs inside the Editor with the production runtime and the `turbo-mesh` plugin loaded. It
records every check as a `PASS`, `FAIL`, `BLOCKED` or `INFO` assertion in one result file.

| Step | Assertion prefix | Contract |
| --- | --- | --- |
| Registry | `registry.*` | `turbo-mesh.region` and `turbo-mesh.adaptive` are listed, the native generator is first, and the default selection is native. |
| Guard | `guard.*` | The two funnel callbacks and the container attach callback are published, and the host version is an exact reviewed release. |
| Native path | `native.*` | The native funnel produces one identical geometry digest across two runs, so a later difference cannot be host nondeterminism. |
| Custom path | `custom.<algorithm>.*` | One real funnel run per algorithm: geometry changed, no degenerate triangles, no unreferenced vertices, exactly one Undo entry labelled `Auto Mesh`, Alpha IoU above the floor, Undo restores the source digest and Redo re-applies the custom digest. |
| Multi-keyform | `multiKeyform.*` | A multi-keyform target keeps its exact geometry, adds no Undo entry and never falls back to the native generator. |
| Selector row | `selector.*` | The native Auto Mesh popup builds, its centre carries children, and the widget is painted to a PNG. |

`INFO` assertions are evidence only and never decide the verdict. Any `FAIL` fails the run, any
`BLOCKED` blocks it, and any `SKIPPED` skips it. The runner reads `assertions.verdict` before
`assertions`, so an evidence-only run cannot pass.

## Prerequisites

- An exact reviewed Cubism installation for the target version, reachable through the same `.env`
  variables the other tasks use (`TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX`,
  `TURBOISM_HOST_VALIDATION_PROTON_RUNNER`, and so on). See
  [host validation scheduling](README-host-validation-scheduling.md).
- A fixture registered as `TURBOISM_HOST_VALIDATION_FIXTURE_<version>` with its
  `_SHA256` companion. The private test model is never committed; only its digest appears in
  `.env`.
- The fixture must contain the probed ArtMeshes. The default ids are `ArtMesh342,hfa10`; override
  them with `TURBOISM_AUTOMESH_MESH_IDS` when the fixture differs. An absent id is reported as
  `INFO`, so a fixture without it cannot fail the run on a missing mesh.
- `TURBOISM_AUTOMESH_MULTI_KEYFORM_IDS` names the meshes that carry more than one keyform. Without
  it the multi-keyform step records evidence only.
- `TURBOISM_AUTOMESH_SETTLE_SECONDS` bounds the wait for one custom run to settle (default 30). The
  executor stops waiting after ten seconds, so the default covers a compute, a commit and a refusal.

## Package the bundle

```bash
bash scripts/preview/package-automesh-framework-validation.sh
```

The bundle holds the preview agent, `turbo-mesh.jar` and
`automesh-framework-validation-probe.jar`, plus `SHA256SUMS.txt`. Rebuild it after any change to the
probe, the plugin or the runtime.

## Submit the job

```bash
python3 scripts/preview/host_validation.py plan automesh:5203 automesh:5302 automesh:5303
python3 scripts/preview/host_validation.py prepare automesh:5203 --run-label automesh-r1
python3 scripts/preview/host_validation.py submit --prepared <id> --request-id automesh-r1-5203
python3 scripts/preview/host_validation.py wait <job-id>
```

The direct wrapper accepts the same versions:

```bash
bash scripts/preview/run-automesh-framework-host-validation.sh 5302 automesh-r1
```

`plan` and the wrapper's `--dry-run` inspect routing without launching a host.

## Read the result

The result file is
`state/dev.turboism.validation.automesh-framework/automesh-framework-validation.properties` inside
the task home. It is copied to the collected evidence directory with the rest of the run evidence.

Key records:

- `status` — the terminal verdict, one of `PASS`, `FAIL`, `BLOCKED`, `SKIPPED`.
- `assertions.verdict` — the number of assertions that decided the verdict.
- `assertion.<name>.expected`, `assertion.<name>.actual`, `assertion.<name>.status` — one group per
  check.
- `native.digest` — the deterministic native geometry digest.
- `custom.<algorithm>.digest`, `.vertices`, `.triangles`, `.alphaIou`, `.alphaRecall` — the custom
  result of each algorithm.
- `funnel.invocation` — how the native funnel answered. `inspector` means the real modeling funnel
  ran; any other value names the reason it did not.
- `selector.screenshot` — the painted popup PNG, or the reason no image exists.

## Render the dump offline

The probe writes one directory per probed mesh under the task home's `automesh-dump`, and the
`collect-automesh-dump.sh` pre-cleanup hook copies it into the evidence directory. Each variant
directory holds `meta.properties`, `positions.txt`, `uvs.txt`, `indices.txt` and `texture.bin` in the
same layout the earlier Turbo Mesh probe used.

```bash
python3 scripts/preview/render_automesh_dump.py \
  build/host-validation/automesh/5302/<task-id>/automesh-dump
```

The renderer writes one PNG per mesh under `<dump>/rendered`: the source texture with the mesh
overlay, then one panel per variant with its vertex and triangle counts in the title. Native panels
are outlined in amber and custom panels in blue. The renderer needs Pillow, which the repository
already uses for its artwork scripts.

## Limits

- The probe reads the guard's published callback properties, not the transformed bytecode. A
  property is present only while the installer holds it, so its absence is a real failure, but the
  check cannot tell which of two funnels a callback serves beyond its own key.
- The selector row check builds the native popup and paints it. It does not drive the row's combo
  boxes, because the SDK exposes no preset-selection call; the recorded preset names prove the
  declaration reached the SDK.
- A custom run uses the algorithm's declared default parameter values. Preset values are recorded
  but not applied by the probe.
