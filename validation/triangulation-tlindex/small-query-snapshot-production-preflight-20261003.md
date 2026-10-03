# T076 production acceptance preflight

T075 implementation/evidence commit `0674ae76a` passes both original instrumented
pairs. It does not close the separate production acceptance step. This preflight
is a read-only archive check and acceptance design, not a frozen host protocol.
No Editor was launched, prepared, or submitted for T076.

The exact frozen production baseline and candidate pass the executable
`diagnostic/audit-small-snapshot-production-inputs.py` check. The only addition is
`TriangulationEdgeIndex$QuerySnapshot`; the six original index family class entries
change and all 5298 unrelated entries remain byte-identical. There are no removed
entries, changed production hook registrations, or packaged producer/validation
observer classes. Actual producer, capture, and canonical Gradle jars are rejected
against the production pin. The companion JSON records hashes and all refusals.
This check covers these exact archives; it does not prove live transformation,
driver behavior, thread ownership, source coverage, or performance.

## Required next implementation and evidence

1. Keep both production jars exact: T057 `17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295`
   and T075 `aa960bc455ca78f3a8dfbab08a609f878f7d0773fdc1bc88136beed7e9d4b79a`.
   Do not insert validation premain hooks into either jar. Independently check
   fixture, official host identity, config, and driver composition before startup.
2. Create a separate command-boundary driver that uses the reviewed native
   selection/mesh-entry/auto-connect/cancel path and the same task binding.
   It must not register a class transformer, call MeshProducerRecorder, or add a
   callback inside triangulation. Keep every selected source and all native
   rebuild/preserve-border options. Do not relabel the existing T068 driver as
   production: it installs MeshProducerWeave at premain and collects per-source
   producer events inside the measured command.
3. First prove an output boundary usable by the new driver. The legacy delayed
   MeshResultSnapshot.capture checks GL index-cache freshness and failed because
   commandAutoConnect marks edges after autoConnect while GL cache-version
   publication happens through updateIndices. Its stale-cache refusal must stay
   unchanged. Reusing that driver or dropping its guard is not an accepted fix.
   Review the exact three-version producer and command-return publication chain,
   including possible early returns, native edge/selection updates, edit completion
   and repaint. The existing producer-return evidence cannot prove command-return
   raw-array freshness by itself. Do not infer generation from array identity:
   native code can overwrite an equal-length array in place.
4. If static and executable evidence support a separate command-return collector,
   capture all selected sources on the same EDT dispatch immediately after the
   native command. Record its own boundary schema and pre/post source, mesh,
   document, version and array checks; return detached scalar/hash evidence only.
   Hashing and evidence I/O must lie outside the measured native command interval,
   and filesystem work must run off EDT. Do not force getGlIndices/updateMesh,
   refresh caches, write versions, or retain native mesh/array references between
   commands. Refuse incomplete/ambiguous generation rather than call it output PASS.
   Prove this collector with stale/partial/duplicate/reordered/switched-context
   fixtures before composing it with a host driver. If this boundary is unsupported,
   preserve that finding and choose another independently reviewed observation
   boundary; do not substitute a command-success marker for full output evidence.
5. Preserve direct cumulative process-CPU readings and independent kernel brackets
   with PID/start/cgroup/HZ identity. Read immediately around native command execution;
   separate setup, post-command validation and I/O. Keep the distinction between
   cumulative-unit verification and timer/provider resolution. Use the same
   collector, CPU boundary and one-second resource observer in both arms.
6. Verify the actual new driver/package contains no validation transform or producer
   callback path. Prove exact production cold startup on all three reviewed host
   profiles and no driver dependency leak; startup checks alone are not geometry
   or performance acceptance. Keep command/result equivalence checks independent
   of the measurement implementation.
7. Only after the driver/output-boundary offline gates pass, freeze and commit a
   new production acceptance protocol, complete requests and all input hashes
   before any FIFO submission. Define balanced order, fixed sample count,
   command/CPU/memory/output/lifecycle gates and invalid-evidence handling upfront.
   Keep the existing T075 four-leg result immutable; no extra leg can amend its
   verdict. The new protocol measures a distinct observer-free native command path,
   not a favorable retry of any withdrawn optimization. Its sample count must
   match the scope of the claimed result; two pairs alone do not prove statistical
   benefit. Multi-version and long-run acceptance remain separate requirements.
8. Resume checkCompletedCommit only when other shared host performance legs are
   absent; save the exact gate outcome. Prior focused tests/devCheck are not that
   full gate. Do not build or run large allocation analyses while a live host
   performance leg is active. At this preflight, seq2576 has a live verified worker
   identity, so the full gate and driver builds remain deferred.
9. Obtain the exact Lane C human review after automated evidence is available.
   Delivery remains T057 until the applicable production gates/review pass.
   Do not merge, push, or release. The broader optimization goal stays active.

## Current result

Archive preflight PASS; three actual nonproduction input refusals PASS. Driver
implementation, command-return publication proof, offline driver checks, new
protocol freeze, host comparison, complete repository gate and human review are
pending. Raw archive evidence is
`build/t076-production-acceptance-preflight-r1/archive-review.json`.
