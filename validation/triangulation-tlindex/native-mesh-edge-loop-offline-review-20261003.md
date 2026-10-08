# T079: owned native append-loop differential result

Decision: keep the candidate outside production. The bounded append-only prototype
matches tested native suffix behavior, but exposed-list mutation safety remains
open. A concrete same-size replacement counterexample prevents treating list
identity, size and mesh version as sufficient production admission.

The exact official 5203, 5302 and 5303 archive SHA-256 pins were revalidated.
`NativeMeshEdgeLoopPrototype` copies the native `autoConnect` suffix beginning at
the actual mesh load before `cached_indices`, and retains all three actual edge
addition sites. It adds an owned `ownedAppend()` fixture entry. Candidate mode
replaces only the typed-search call to the unfiltered linear lookup, and registers
successful native appends after their original list-add instruction. Native
normalization, version increment, degenerate handling and rank-promotion bodies
remain responsible for their effects. The complete native `autoConnect` method
is unchanged and was not invoked; this is a suffix experiment.

| Control | Result |
| --- | --- |
| 3 versions × assertions off/on × 96 fixtures | 576 groups PASS |
| Native suffix versus transformed null lease | Equal in every group |
| Native suffix versus indexed append-only lease | Equal in every group |
| Final checks | 5,870 PASS |
| Indexed lookups / native fallbacks / append registrations | 19,728 / 276 / 12,366 |
| Nested lease, other mesh, filtered lookup, external size change | PASS |
| Untargeted method/class metadata after frame/debug normalization | Equal |
| Double transformation and unknown native definition | Refused |
| Same-size external list replacement | Counterexample confirmed in all six version/assertion groups |

Results compare ordered endpoint/type rows, original seed-edge identity versus
replacement, native edge version, return/error signature and filtered/unfiltered
lookup results. Fixtures cover existing duplicates and priorities, malformed stored
orientation, null edges, empty/null/truncated index arrays, repeated/degenerate
endpoints, negative endpoints, immutable-list failures, capacity fallback and
seeded varied triangle streams. The loop itself always passes crossing=false;
this does not establish crossing/similarity refusal equivalence outside the suffix.
Both normal and exceptional suffix exits leave no current lease. Throwable
signature comparison across loaders is not Throwable object identity proof.

## Exposed-list counterexample

The official `getEdges()` and synthetic accessor return the mutable underlying
list. Under an owned lease initialized with edge (0,1), replace that slot with
(2,3) through native `CArrayList.set`. List identity, size and mesh edit version
remain unchanged. Prototype lookup of (0,1) returns slot 0; original native search
returns null. This was deliberately exercised and confirmed, not counted as a
passing equality case. Production integration must establish exclusive mutation
ownership or complete reliable invalidation before any such cache can answer.
Do not assume EDT execution or a lack of direct suffix callbacks supplies this
proof. The prototype does not support arbitrary concurrent/external mutations.

## Evidence and next action

The final guarded run is
`build/t079-native-mesh-edge-search-r1/native-suffix-r3/review.json`.
Its source/archive/tool/compiled-class/log hashes are copied into the adjacent
review JSON. Earlier r1 and r2 successful runs are preserved; r2 adds definition
and lease controls, r3 adds the explicit exposed-list counterexample. These are
functional controls, not repeated performance measurements. The CLI monitored
authoritative queued/running prepared arguments, stopping only its own process
group if performance work appeared. No Editor, javaagent or host job was started.

Next audit the actual mesh ownership/publication path and mutation closure before
attempting production integration. The boxed reflective map is an owned semantic
prototype; neither its timing nor sample counts establish a production gain.
Full caller, runtime-loader/dependency admission, complete-operation equivalence,
memory retention and fixed host performance gates remain open. T057 delivery,
T077 failed gates and withdrawal are unchanged. All-version host acceptance,
long-run validation and Lane C remain open; no merge, push or release.
