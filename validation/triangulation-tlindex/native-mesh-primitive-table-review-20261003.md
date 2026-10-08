# T082: bounded primitive mesh edge storage and shared-admission constraint

Decision: actual runtime primitive storage passes unit and three-version native
controls. It is not wired into the production transformer. Frozen T057 delivery
SHA remains17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295.
No Editor or host job was launched; no production speedup is claimed.

`NativeMeshEdgeTable` stores literal signed integer endpoint pairs in long keys
and the lowest physical list slot in int values. Zero and reversed endpoint
pairs remain distinct; no normalization, host references or element equality
are used. Maximum16,384 entries, load at most one-half, fixed allocation/no
growth. Its process reservation uses an atomic ledger with a4MiB total limit.
Reservation accounts12*capacity primitive bytes plus1,024 bytes for object/array
headers and alignment. It is a declared buffer budget, not an RSS/heap cap.
Capacity or invalid-slot refusal permanently discards the partial table and
returns UNKNOWN thereafter. Close drops arrays/thread references and releases
the ledger once; cross-thread calls cannot answer or release another owner's
reservation. Unsupported/budget requests refuse before table allocation.

| Verification | Result |
| --- | --- |
| Runtime tests: literal pairs, first slot, dense/sparse oracle, capacity, budget, cross-thread and concurrent release | 6 PASS |
| Seeded first-slot reference oracle | 14,000 inserted pairs plus4,000 probes |
| Concurrent process reservation requests | 16 workers, bounded admission and full release PASS |
| devCheck and formatting | PASS |
| Actual primitive storage in full official autoConnect | 288 groups,9,729 core checks plus zero-reservation check PASS |
| Actual primitive storage in native suffix controls | 576 groups,5,942 core checks plus zero-reservation check PASS |
| Final primitive reservation count in both native JVMs | 0 |

Owned wrappers call the actual package-private runtime class compiled from the
same tested source. They replace the earlier boxed map only inside diagnostic
fixtures. Both assertion modes and all three full official archive pins are
checked. Full-operation query/fallback/append counts remain4614/5622/18; suffix
counts21438/432/13992. Pre-entry mutations, original callback Throwable identity,
native immutable failures and exceptional cleanup remain exercised. Unknown
edge initialization discards reserved storage; initialization errors dispose it
before propagating. The same-size external-edit counterexample remains confirmed,
so primitive storage does not solve mutation admission.

## Composition constraint found in authoritative lifecycle code

`TriangulationDefinitionLifecycle.capture` calls
`revokeAll("OWNED_CAPTURE_RETRANSFORM")` before retransformation. Successful
capture therefore permanently revokes previously held gates. The current
`LazyTriangulationEdgeBridge` ClassValue holder attempts capture once and keeps
that gate. Creating an independent mesh definition capture after the existing
h/TriangleList capture could disable later T057 lazy/builder/angle operations.

The integration must extend the existing immutable dependency plan and capture
all required final definitions once. Native mesh entry should resolve/acquire
that same h-owned gate. Include the woven mesh class and exact edge/type/list/
Kotlin dependencies and their links before the first capture, validate helper
identities, and preserve weak loader ownership. No second independent capture
may be justified merely because its own new gate is admitted. This constraint
is observed from code, not yet a verified new integrated implementation.
Select the extended plan only when the mesh weave is prepared and linked. If
that feature declines, retain the original T057 plan and reject mesh entry;
an unavailable mesh dependency must not disable unrelated existing acceleration.

## Evidence and next action

Raw directory `build/t082-mesh-primitive-table-r1/` contains final unit-guard-r2
logs/frozen6-test XML, native-complete-primitive-r1 and native-suffix-primitive-r1
reports. Own sessions29312/32750/24933 are terminal0. Earlier unit-guard-r1 PASS
used the smaller header allowance; r2 revalidates the final1,024-byte allowance.
Current source/tool pins match both native runs after formatting. The adjacent
JSON records those pins and unchanged delivery identity.

Next implement the shared final-definition plan, exact final-loop callback/
mutation admission and public native entry bridge, compose over frozen T057,
then verify sole-premain controls and geometry before freezing a host protocol.
Runtime storage exists, but no new transformer path, gate capture, candidate
Agent or performance measurement exists yet. Full T057 composition, actual
CPU/wall/RSS acceptance, all-version host validation, long-run and Lane C remain
open. T077 failed gates remain unchanged; no merge/push/release.
