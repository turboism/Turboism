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

The `scene` builder/hook path uses the supported plugin route for 5203/5302
TLPROD capture legs. Pass `--scene` and `--scene-sha256` to the builder, then
use the wrapper's `--tri-single-agent-startup-probe` with an explicit leg-matching
UI-saved configuration. The settings plugin verifies the real menu, SDK active
model, completed native frames, startup preference and unchanged config bytes
without opening settings, saving or exiting. The T040 driver owns Editor
operations and normal exit. Capture and driver sidecars are managed home files;
only Turboism supplies premain. The shared Runner, official BAT, exact identity
and cleanup gates remain in use.

5302 job 2395 passed the real host lifecycle. Independent checks bind its prepared
inventory, actual artifacts/config, startup report, lazy patch/admission, terminal
payload and cleanup. Its first four complete ordered endpoint-index digests match
explicitly bound off baseline job 2365. See [dated evidence](../host-evidence-20261002.md).
This covers those four normal returns, not all mesh returns or repeated cycles;
job 2395 has no resource measurement and establishes no performance gain.

Companion SHA `dd4b21a5d0851c43854a2e773ec19ad07cf8607c8216ef1354b984df81e87072`
uses production base `b47f6f47928f46d7fc2acd94223d66e89c80c903a4bd8d2878d5f6cc92e425cb`.
All base entries are unchanged except `META-INF/turboism/hooks`, plus added
validation Hook classes. The companion is absent from release packaging.

5303 T039 callback self-unregistration is correctly refused by the owned gateway;
that composition remains unadmitted. Final native/repeated outputs and three-version
stable performance, resources, UI and independent startup-setting linkage remain
open. Production acceptance stays on hold.
