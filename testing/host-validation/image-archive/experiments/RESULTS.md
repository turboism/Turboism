# Five loading-hotspot experiments — native Windows screening

> Historical snapshot: results and product status below describe the frozen
> pre-promotion experiment builds. The large-image range has since been integrated
> into production source, but the merged product still needs real-host acceptance.
> See [current integration status](../benchmark/PROMOTION.md). Historical metrics,
> attempts and hashes are unchanged.

## Decision

**Large-image coverage is the only candidate worth advancing from this batch.**
Two separate bracketed blocks showed approximately **4% shorter document-ready time**
and about **20% less observed cumulative allocation**. These are small, noisy
screening cohorts, not a statistically established speedup or a default-on recommendation.

PNG memory input improved its local operation but not overall loading. Archive
buffer replacement was marginal. Typed interpolation projection and short-float
special cases did not establish useful whole-load benefits. **No candidate from
this batch was promoted into the product, and production size bounds/defaults remain unchanged.**

## Results, using only each candidate's adjacent controls

Each number is a median of **two samples per arm** in that cohort. Different probe
builds and measurement blocks are kept separate, not pooled.

| Candidate / cohort | Control ready s | Candidate ready s | Observed allocation GB, control → candidate | Interpretation |
| --- | ---: | ---: | ---: | --- |
| Large images, screen | 40.774 | 39.005 (−4.3%) | 21.058 → 16.789 (−20.3%) | Promising; initial control drift present |
| Large images, confirmation | 42.877 | 41.264 (−3.8%) | 21.095 → 16.751 (−20.6%) | Direction repeated in new bracketed block |
| PNG memory input | 40.236 | 42.030 (+4.5%) | 21.052 → 21.145 | Local win, no overall win |
| Archive readAllBytes | 41.641 | 41.178 (−1.1%) | 21.048 → 20.909 | Small changes within noise |
| Typed read-only warp projection | 43.048 | 43.759 (+1.7%) | 21.046 → 20.848 | No useful overall gain |
| Short float literals | 42.378 | 41.380 (−2.4%) | 21.051 → 21.053 | Only 0.0935% of calls hit; timing difference not attributable to this shortcut |

### Large-image memory and CPU observations

| Median | Screen control → large | Confirmation control → large |
| --- | ---: | ---: |
| CPU to stable document, s | 32.508 → 31.977 | 35.594 → 34.336 |
| Loading peak working set, GiB | 7.025 → 4.973 | 6.408 → 6.201 |
| Loading peak private commit, GiB | 11.300 → 9.172 | 8.434 → 8.024 |
| Final 10 s idle working set, GiB | 7.393 → 5.884 | 5.672 → 4.485 |

Peak-working-set magnitude is **not stable** across blocks (−29% versus −3%).
CPU ranges overlap. Idle working-set medians were about 21% lower in both blocks,
but this remains one host/model, a 30-second no-input idle window, and very few samples.
Do not equate allocation reduction with retained RAM/VRAM, predict a fixed amount
of memory saved, or turn these point estimates into universal performance claims.

## Environment and isolation

Same native Windows 11 x64 / Ryzen 9 7940H / approximately 32 GB RAM / NVIDIA RTX 4060
Laptop OpenGL renderer / Cubism **5.3.04**, bundled Java **17.0.3.1+2-LTS** as the
[previous loading/JFR investigation](../benchmark/HOTSPOTS.md). Fixture:
763,166,356 bytes, 1,029 model images, 12 atlases; SHA-256
`83326ac519dd5e4cad9b39e4c7234410cc62bf0d1f5241364561d6906292b6da`.

A new task-owned output root on a drive with ample space was used for **all arms
of this batch**, including controls. Do not compare these timings directly with
the earlier C-drive performance table. Every case has a new JVM, model copy,
Turboism home, AppData seed copy and user home. It launches only a task-local copy
of the pristine official BAT, with `%~dp0` rebased; the installed BAT, original
model and real user Turboism configuration are never changed by the harness.

All runs use the existing Windows Job Object containment and process accounting.
No builds ran concurrently with measurements. There is an eight-second gap between
runs. Exact official host JAR and original target-class bytes are checked before
any experimental transform. The private probe is **not** a plugin or a product
module: do not install/copy it into a real home or launch it against unsaved work.
It is admitted only for the pinned 5.3.04 artifact in this experiment.

Every control carries the same observation/candidate-weave scaffolding as its arm:
image/stream timing, archive digesting, setup/projection counts. Mode `none` calls
the original operations. The float cohort uses its own four-target probe and its
own controls; the other cohorts use the earlier three-target probe. Both keep the
old float-array cache and warp projection feature disabled. No optimizations are
stacked across candidates; PNG/warp/float use the ordinary current converter, not
the large-image variant.

## What was changed and what was verified

### 1. Large-image preparation

Only the private agent's `CanonicalRgbaImage.class` differs from the control agent:
the area limit changes from 33,554,432 to **67,108,864 pixels**; dimension limit 8192,
channel table, source-read algorithm, ownership and native GL calls stay unchanged.
The product source still has the old limit. Target-image maximum is **256 MiB**,
not a claim of 256 MiB additional peak relative to the native conversion path.

`large-shadow` compared **11/11 preparations / 939,524,096 output bytes**, including
the two actual 8192² inputs, with zero JOGL byte/metadata/effective-row-stride
mismatches. Four non-shadow candidate loads were measured in two bracketed blocks.
The callback now performs more work (11 inputs rather than 9), so its cumulative
preparation time must not be compared as if the coverage were unchanged.

### 2. PNG per-read memory stream

At the pinned `UtImage.getBufferedImage(InputStream, int, ...)` ImageIO call sites,
only exact `ByteArrayInputStream` inputs with a PNG signature and at most 256 MiB
remaining encoded data use a per-read `MemoryCacheImageInputStream`. Other streams
and formats take the original path. **No global `ImageIO.setUseCache(false)` call.**
Final native image-type conversion remains untouched. The cache's close is
idempotent because JDK ImageIO can close a recognized ImageInputStream internally.

Shadow comparison decoded all **11/11 real images** through original and candidate
readers and checked dimensions, image type, premultiplication, color model and every
pixel row. Local ImageIO wall time fell **4.376 → 2.526 s (−42.3%)**, but overall
loading did not improve. This narrow observed workload does not establish the full
contract for arbitrary public stream callers; in particular, eligibility lookahead
uses mark/reset on the owned byte-array stream. A production design would need a
strict owned-byte-array call boundary and complete caller-state/reader/cleanup tests.

### 3. Archive buffer copies

Only the pinned model-XML closure's `ByteStreamsKt.readBytes` call is substituted.
Exact `ZipInputStream` or `ByteArrayInputStream` inputs can use JDK `readAllBytes`;
other stream classes retain Kotlin. No replay/fallback after consumption, no
validation removal, no modified compressed-data handling and no stream ownership change.

Offline tests cover 0/1/8191/8192/8193/1,048,577-byte payloads, raw and ZIP reads,
current-entry EOF (next ZIP entry remains available), and the same exception type
for truncated ZIP input. The **two actual archive reads / 124,757,880 output bytes**
have the same ordered length+byte SHA-256 in every accepted run. Local median read
time changed **0.595 → 0.551 s**, including identical digest overhead in all arms.
The difference is too small to establish an overall loading improvement.

### 4. Typed interpolation projection

This is **not a re-enable of the old method-handle projection experiment**. A private,
strongly typed helper removes the per-vector MethodHandle/global-callback route.
For exact `CWarpDeformerForm`, it constructs a fresh list of fresh native `GVector2`
values from the current positions array. Other form types fall back. Array/source
identity is rechecked, and no mutable form/point array is cached or retained.
Only the first read-only projection block is bypassed: the second point-reference
write-bearing loop, native interpolation math, dirty updates and ownership are intact.

Shadow checked **5,390 projections / 3,771,822 points** against original point-reference
`getPos`, comparing raw x/y float bits. A total 7,046 projection entries and 682 setup
entries were observed; **no setup pass was skipped**. The shortcut still covers only
a small subset of the whole interpolation hotspot. Its observed allocation decrease
was under 1%; working-set/commit medians were worse and variable. Do not promote it
or repeat the earlier failed broad claim that all point-wrapper weight can be removed.
Interactive editing, Undo/Redo and arbitrary concurrent form mutation were not tested.

### 5. No-cache short float parser

This new candidate recognizes only exact ASCII forms `d`, `-d`, `d.0`, `-d.0`
for digits 0–9, preserving negative zero. Everything else, including whitespace,
hex/scientific notation, non-finite values, invalid input and null, stays on the
original JDK parser. It retains **no token cache** and does not use approximate decimal math.
Native float-array construction and list validation remain native.

More than 50,000 offline cases compare raw float bits and exception classes. In the
actual fixture, **6,860,804** parse calls produced only **6,414** fast hits
(**0.0935%**). All 6,414 candidate hits were independently shadow-compared with JDK
raw bits; the other calls already execute the original parser. There was no meaningful
allocation decrease. The small ready-time difference cannot reasonably establish
benefit from this tiny hit population; do not broaden this special-case parser or
re-enable the older token cache on that basis.

## Correctness/exit evidence shared by every accepted run

All **26 accepted attempts** (including validation/preflight attempts, not 26 timing
samples) have normal launcher exit **0**, `cleanup=safe`, zero Job Object processes,
unchanged source/copy/artifact hashes, original texture-hook restoration, and
CP-independent instruction/branch/handler restoration of every private-probe target.

Every accepted case also matches these same post-measurement witnesses:

- Model thumbnail pixels SHA-256:
  `016c5878259d0b2a1402a1d62b80a718847736cb851e54449ccb3d74e7d9ec91`
- Ordered model-XML archive bytes SHA-256: recorded in
  [screening.json](observations/screening.json).

The thumbnail is generated on the EDT **after** the 30-second idle memory window.
It is a limited render witness, not framebuffer equivalence, every texture/source
pixel, all model parameters, interactive FPS, Undo or save/reopen certification.
PNG, large-image and warp shadow work is untimed correctness evidence, excluded
from all performance cohorts. The archive/float offline tests do not substitute
for broader malformed/sensitive-file validation in a production implementation.

## Retained failed preflights — never performance results

Three attempts failed safely and remain failures in [all-attempts.csv](observations/all-attempts.csv):

- `control-s1`: raw class-byte equality is unsuitable after JVM retransformation;
  constant-pool reconstitution can change bytes. The restoration proof was corrected
  to the product's tested all-method semantic instruction/branch/handler identity.
- `control-s2`: non-retransformable transformer output remained cached by the JVM
  after transformer removal. The probe now registers retransformable transforms;
  a separate real-JVM weave/remove/retransform regression passes without Cubism.
- `control-f1`: float serializer has an erased **Object** return descriptor, not
  `float[]`; the exact call-site count gate rejected the attempted weave. The exact
  descriptor was corrected and a complete installed-class offline weave/mutated-body
  rejection gate was added before retrying. The failed probe is not compared with
  the corrected four-target float probe.

These failed processes were forcibly ended in their own jobs, with zero remaining
kernel job processes, but their cleanup remains `unknown`/non-normal rather than
being relabeled PASS. No unrelated session was killed. Exact source/probe receipts
and failing logs remain local.

## Measurement limits and next step

- Two samples per arm per cohort, one model/version/GPU, no randomization within the
  bracketed blocks. Working-machine load, JIT, GC and thermal drift are visible;
  baseline times span roughly 39–44 seconds. No confidence/significance claim.
- Agent-entry → stable-document boundary includes startup and 500 ms polling.
  Hashing/copying precede launch: **not disk-cold I/O**. Neither local method time
  nor sampled allocation is a direct measure of user interaction latency.
- Windows working set and private commit are different metrics; no GPU timer,
  VRAM, FPS, heap-cap tuning, forced GC or user-input automation is used. Do not
  add the local PNG and large-image deltas to predict an untested combination.
- New large-image correctness coverage is real, but 256 MiB arrays and unchanged
  Java2D source-tracking risks still require broader-memory/host/input validation.

**Recommended next step:** advance only large-image coverage to a bounded opt-in
production design after wider memory/compatibility and interaction checks. Keep
PNG as a narrower research candidate; do not promote archive/warp/short-float
changes from this batch. No automatic default or production promotion was made.

## Reproduction and evidence

- `build_experiments.py`: builds private probes/large-image variant from explicit
  JDK, reviewed host, product and ASM paths; never rewrites a product/installed JAR.
  It runs byte/pixel/float tests, real-JVM restoration tests and exact-host class
  weave/count/mutated-input rejection tests. No licensed host classes are embedded.
- `run_cases.py`: serial isolated runs, original archive/thumbnail witnesses and
  positive-consumer/full-shadow assertions. Do not run it during a build or with
  an existing Cubism/Java session; keep the original model read-only to the harness.
- `summarize_experiments.py`: explicit adjacent cohorts; rejects mixed probe/runner
  hashes and shadow/failed samples. It does not reuse the old nine-run medians.
- [All 29 attempts CSV](observations/all-attempts.csv) includes failed attempts and
  marks preflight/shadow/performance roles. [Full cohort metrics/hash receipts](observations/screening.json)
  retain CPU, memory, allocation and local operation times for every accepted case.

Raw logs, load/result properties, process JSONL, model copies and isolated state
remain in the private task root; its local pointer is
`build/texture-experiments/run-root.private.txt`. The directory is ACL-restricted
for the current user/system/administrators; after hardening, child inheritance
was reset to that private root and all envelope/content hashes were reverified.
No original model permissions were changed.

A private local archive under **`build/texture-experiments/`** contains the selected
raw evidence, every probe build receipt/binary and byte-identical source snapshots
for both measured probe cohorts, including the measured runner newline encoding.
It **excludes copied user models, AppData/license state and proprietary javap output**.
Archive digest: [archive-receipt.json](observations/archive-receipt.json).
Public tracked records contain no machine-local paths or user model/license content.
