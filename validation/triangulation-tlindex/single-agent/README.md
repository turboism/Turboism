# Single-Agent premain validation

This validation companion runs the real `TurboismAgent` premain with one
`-javaagent` and the owned instrumentation gateway. It appends the pinned
TLPROD capture sidecar to the system loader, where its official-typed helpers
can link. The production premain and all base Agent class entries remain
unchanged; the builder adds a validation contributor through the hook resource.
This companion is absent from release packaging.

Build a new directory with:

```sh
python3 validation/triangulation-tlindex/single-agent/build.py \
  --base-agent <reviewed-production-agent.jar> --base-sha256 <sha256> \
  --probe <tri-weave-agent.jar> --probe-sha256 <sha256> \
  --out <new-candidate-directory>
```

`candidate.json` records the base/companion/sidecar identities, source hashes,
changed resource and added entries. The fixed opt-in token is
`T050_SINGLE_AGENT_TLPROD_V1`; the verified mode is `capture-only` with
`tl-dump-only` / `tl-official`. Exact case JVM arguments, isolated configurations
and logs are saved with each evidence set. Official JARs must precede sibling
JARs in the classpath; a wildcard can select the unreviewed ANGLE variant.

The 2026-10-02 hash-composition evidence is under
`build/t050-lazy-edge-bytecode/hash-composition/`. Six cases cover three reviewed
versions with the existing hash optimization on/off. Three controls reject a
wrong sidecar SHA, attach-enabled startup and an unreviewed class origin.
All nine execute the real premain and metadata-only official definitions.
They do not initialize official classes, invoke geometry, start the full
Cubism runtime, execute Editor operations or measure performance.

The initial 5302 rejection is preserved under
`build/t050-lazy-edge-bytecode/single-agent-premain/`: its existing hash patch
changed the whole `l` runtime fingerprint. The lazy dependency admission now
accepts exactly pristine `l` or the reviewed existing hash-patched definition;
additional getter/hash/field changes still refuse admission. All remaining
dependency, origin, ownership and lease requirements remain enforced.

The optional `scene` builder/hook path is preparation only and has no host
PASS. In particular, 5303's T039 callback removes its transformer, which the
owned gateway correctly refuses. The current shared Runner also requires a
plugin or auxiliary Agent, so home-file-only single-Agent host integration is
not yet supported. Continue through the shared FIFO and official BAT once
a supported composition is ready. These metadata checks grant no production
acceptance; final native/repeated output and three-version performance,
resources, UI and independent startup-setting linkage remain open.
