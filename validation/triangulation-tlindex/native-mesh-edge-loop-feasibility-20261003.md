# T079: bounded native mesh edge lookup research

Decision: investigate an index local to the final triangle-to-edge loop in
`GEditableMesh2.autoConnect`. Static direct-loop checks pass for all three
supported versions. Production remains T057; this is neither an integrated
optimization nor evidence of a speedup. No new Editor or queue job was launched.

T078 identified `Objects.checkIndex → ArrayList.get → CArrayList.get →
GEditableMesh2.chechExistingEdge_exe` in 142, 114 and 145 EDT execution samples
across three commands. Sample counts identify an investigation target; they
are not CPU durations or predicted savings.

| Version | cached_indices read | Triangle edge insertion sites |
| --- | ---: | --- |
| 5203 | 202 | 257, 283, 307 |
| 5302 | 206 | 261, 287, 311 |
| 5303 | 206 | 261, 287, 311 |

Offsets refer to original method bytecode. The proposed lifetime starts after
the last progress callback and native generation, and ends at the final return
or any thrown exception. The suffix has three NORMAL edge additions per
triangle, each with crossing disabled and default mask 8. Its other direct
calls are Kotlin null checking and progression-bound computation. The tool
rejects additional direct calls, unexpected edge arguments, crossing branches,
exception handlers and unsupported control flow. This does not prove transitive
call behavior or safety of an inserted lease.

## Native semantics that must remain intact

- `addEdge` updates the edge version before checking an existing nondegenerate
  edge. Returning early on an indexed duplicate would change behavior.
- Search returns the first matching list slot. Existing duplicate slots must
  retain that ordering. Stored endpoints are matched literally; malformed
  reversed endpoints must not be silently normalized during index construction.
- Typed search may replace that first slot with a new edge of higher enum byte
  rank. It preserves position and does not change list size. The original typed
  search body should retain responsibility for promotion.
- `chechExistingEdge_exe` with its boolean flag enabled excludes AUTO_TRIANGULATION
  and USER_TRIANGULATION. A helper limited to the false-flag append path must
  fall back for the filtered path.
- Equal input endpoints log and return -1 before version increment. Null type,
  assertion failures and crossing/similarity refusals also retain their native
  order and effects.
- `clearAutoTriangulation` can change the list without updating its edge version.
  The list is exposed by accessors. A broad cache keyed only by edit version is
  insufficient.

## Proposed owned prototype

Initialize a bounded first-slot map from the actual list immediately before the
final append loop, after generation and callbacks. Preserve original `addEdge`
and typed-promotion instructions; substitute only the unfiltered linear lookup
under an exact owned lease. Incrementally register successful native appends,
without replacing an existing first slot. Use full integer endpoint pairs,
explicit capacity limits and native fallback on unsupported identity or budget.
No cache survives the loop, reaches another mesh or remains after an exception.

Before production integration, prove exact archive/runtime-definition admission,
loader/helper/list identities, complete mutation closure and concurrency policy.
Do not assume the absence of direct callbacks proves absence of transitive
callbacks or concurrent list mutation. Unknown cases must execute native code.

The next check is an owned transformed-native differential fixture using pinned
official methods. It must cover first duplicates, rank promotion, malformed
stored orientation, filtered lookup, version increments, degenerate edges,
refusals, preexisting lists, rebuild modes, exceptions and budget fallback.
It must compare return values, ordered edges, types, versions and exception
behavior. An exact scoped artifact and frozen host protocol follow only if
those checks pass.

## Evidence and limits

`diagnostic/inspect-native-mesh-edge-loop.py` reads class bytes without executing
host classes. Two test methods pass, including ten rejected structural mutations.
All three actual archive selections pass the direct suffix checks. The adjacent
JSON records selected class/method hashes and raw inspection paths under
`build/t079-native-mesh-edge-search-r1/`. Full archive hashes were not revalidated
in this step; class hashes do not replace production admission.

No production helper, bytecode transformation, native differential result,
retention result or performance acceptance is claimed. T077's failed fixed gates
and withdrawal remain unchanged; it must not be retried or promoted on aggregates.
