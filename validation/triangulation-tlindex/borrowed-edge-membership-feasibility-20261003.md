# T058 post-angle hotspot and borrowed-edge predicate feasibility — 2026-10-03

Decision: proceed to an owned complete-method prototype for5302/5303 only.
Production T057 remains unchanged. No Editor was launched. Predicate feasibility
is established, but exact full-method errors/lease/admission and host benefit
remain unproven. The coordinate and index predicates must remain distinct.

## Actual command-window hotspots

Frozen T057 JFR mapped to explicit three command windows has790 candidate Java
samples containing triangulation frames and16 native samples. The largest Java
leaf is settle:228samples,189atBCI163 and39atBCI92. Existing mandatory physical
absence proofs remain intact; T054 array-copy experiment remains rejected.

The TriPoint/TriangleList/k h.a overload has160samples atBCI544. The exact native
method's instruction542 invokes TriangleList.b(l): this is native removal context,
not proof of a new removable numerical loop. JIT inlining can attribute expensive
callees to this caller. The other h.a(TriangleList,k) overload has58samples atBCI256
and22atloopBCIs360/366. Instruction256 is k.a(j,false), with the argument borrowed
from the same k's current ArrayList iteration. These are samples, not calls or
CPU duration; no speedup percentage is inferred from their share.

## Proof boundary and native controls

In the53method, the initial full negative scan calls j.a(query,false), comparing
endpoint coordinates. The later redundant k.a(borrowed,false) compares endpoint
indices. The borrowed object's own entry necessarily matches its indices. Pure
reviewed intersection/endpoint checks between iteration and the repeated lookup
must not mutate that list. A production replacement needs a shared exact complete
definition lease, original null-lease path, exact ArrayList shape and full method
control; concurrent/unreviewed mutation cannot be assumed away.

Actual three-profile native k.a controls pass16747assertions in total. They cover
empty/member/reversed/duplicate indices, NaN/infinite coordinates, post-insertion
coordinate mutation and coordinate-vs-index disagreement. In5302/5303 the owned
coordinate stencil is compared directly to actual j.a.5203has no corresponding
j.a or private host method and is excluded from this proposed optimization;
its native index-membership checks are supporting predicate evidence only.

An owned malformed list with an early null makes native k.a throw, even when the
query is physically present. The preceding full negative coordinate scan also
throws first. A substituted ArrayList subclass can complete the first/second
iterations and throw the exact sentinel Throwable on the repeated native lookup.
Such a subclass must decline the shortcut and preserve native behavior. A bare
physical-member shortcut without these conditions is not a correct optimization.

## Narrow timing and compilation

Seven alternating measurements follow five warmup pairs for8/32/128/512members.
Recorded5303 native medians are28.52/43.13/135.05/479.71ns per borrowed lookup.
The owned constant-result/class guard is trivial and can collapse under JIT;
its sub-nanosecond timings are not a realistic woven-method cost or host gain.
The experiment excludes initial full scan, intersection work, lease, full-method
control flow and Editor. All raw timings are archived in three adjacent txt files.

Final compilation uses Java17 -Xlint:all, and runtime -Xverify:all. Official host
and Kotlin hashes are checked against existing authorities. One unresolved enum
h.AUTO warning originates in the reviewed legacy binary annotations; final runner
accepts exactly that warning and rejects others. No Werror pass is claimed. Failed
compiler attempts remain preserved. Adjacent JSON pins source/classes/logs,
commands, actual jars, command-window analyzer/JFR inputs and bytecode listings.

Next verification must execute complete native h.a in pristine/null-lease/owned
53fixtures, preserve result order/point identities/errors, exercise malformed
and subclass fallback, assertion modes and dependency/refusal controls. Only then
consider LaneC integration and a separately frozen FIFO A/B. No production,
removal semantics, modules, gate thresholds, merge, push or release changes here.
