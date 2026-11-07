# Private native loading experiments

Read [RESULTS.md](RESULTS.md) for all five candidates, adjacent-control measurements,
correctness limits, failed preflights and artifact receipts. These are **test-only**
probes, not plugins, production feature switches or instructions for a real user home.

Build with explicit local paths (JDK 17, the pinned reviewed 5.3.04 installation,
frozen pre-promotion product agent and a local ASM JAR):

```sh
python testing/host-validation/image-archive/experiments/build_experiments.py \
  --host "$HOST" --java-home "$JDK17" --product "$PRODUCT_AGENT" \
  --asm "$ASM_JAR" --output build/texture-experiments/new-frozen-bundle
```

Since the production promotion, the current `CanonicalRgbaImage` source no longer
contains the historical `pixels > 33_554_432L` literal, so the experiment builder
can no longer derive the frozen pre-promotion converter source from today's tree.
To keep the historical large-image experiment reproducible, pass the frozen
PRE-promotion source explicitly:

```sh
python testing/host-validation/image-archive/experiments/build_experiments.py \
  ... \
  --converter-source /path/to/frozen/pre-promotion/CanonicalRgbaImage.java
```

`--converter-source` is optional. When given, it must point at the frozen
pre-promotion `CanonicalRgbaImage.java` (the one containing exactly one
`33_554_432L` area-limit literal); the builder compiles that frozen source into
the large-image variant instead of reading the current tree. The corresponding
`--product` agent ZIP must be the frozen matching pre-promotion control build,
not a current-product build. The receipt records both hashes; matching the supplied
source to that frozen control is currently the caller's responsibility, not a
verified equivalence check.

If the file is absent, or it contains zero or more than one historical-limit
literal (for example a current-source variant with `67_108_864L`, or a file with
both limits), the builder fails with an explicit message instead of silently
building a same-policy variant. The converter source SHA-256 is added to the
build receipt as `converterSourceSha256`.

Build runs byte/ZIP/PNG/float boundary tests, a real-JVM transformer-removal regression,
and an exact-installed-class call-site/mutated-input gate. It writes a private
experiment probe, a single-class-difference large-image variant and a hash receipt;
it never rewrites the product or installed files. Never rebuild a probe between
arms of one comparison. Probe ZIP timestamps can differ on rebuild: retain the exact
measured JAR, not just equivalent class entries.

`run_native.py --experiment-agent ... --experiment none|large|png|archive|warp|float`
owns isolation, the official BAT launch, process accounting and normal-exit checks.
`--experiment-shadow` selects untimed correctness work. For large-image shadow use
`--mode shadow` as well. The private worker checks archive/thumbnail witnesses and
restores its targets after idle observation, before the ordinary texture validator
closes the task frame. Use a fresh task-owned output path and a disposable model copy.
Do not run alongside other Cubism/Java sessions or during builds.

`run_cases.py` sequentially checks a batch against the accepted baseline witness;
its recorded recipe uses `control-s3` from this evidence set. Prepare an equivalent
fresh control for a new experiment rather than silently reusing an unrelated model.
`summarize_experiments.py` uses explicit cohort names for this recorded matrix.
Adjust and freeze a new cohort specification before new measurements, not afterward.

Model names/paths, copied models, license/AppData state, native bytecode inspection
and raw local logs are not tracked. Sanitized CSV/JSON in `observations/` contains
all attempts and the complete accepted-cohort metrics. Full private evidence is
stored outside the tracked source tree; see the archive receipt and RESULTS.md.
