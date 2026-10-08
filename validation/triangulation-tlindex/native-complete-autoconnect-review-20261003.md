# T081: complete native autoConnect owned differential result

Decision: complete-operation functional evidence now passes, including progress
mutations and native failures. Keep the implementation outside production until
exact runtime admission and the remaining mutation/callback closure are proven.
This is not a performance acceptance result. T057 delivery remains unchanged.

The isolated candidate weaves the actual official `GEditableMesh2.autoConnect`
method. It enters the index immediately before the final cached_indices read,
after native generation and all progress callbacks. Earlier native branches,
including the original early return, are retained. One normal suffix return is
redirected to cleanup and a Throwable handler cleans exceptional suffix exits.
The lookup/append substitutions remain the reviewed native typed-search and
successful native-append sites. Baseline executes the original complete method;
candidate null-lease and indexed modes both execute the full operation.

| Control | Result |
| --- | --- |
| Three official versions × assertion modes × 48 fixtures | 288 groups PASS |
| Complete checks | 9,729 PASS |
| Actual indexed queries / native fallbacks / append registrations | 4,614 / 5,622 / 18 |
| Same-size list edits in progress callbacks before index entry | 24 exercised |
| Injected progress failures, with original Throwable identity | 24 exercised |
| Immutable native-list failures | 18 exercised |
| Protected suffix failures with lease cleanup | 18 exercised |
| Untargeted methods/metadata after debug/frame normalization | Equal |
| Complete double transformation | Refused |
| Existing suffix regression | 576 groups, 5,942 checks PASS |

Complete results compare ordered endpoint/type rows, point-coordinate raw bits,
all cached_* fields (array contents/raw float bits), edge version, progress call
sequence and error signature. Fixtures include empty/one/two-point inputs,
curved and jittered boundaries, collinear/near-collinear geometry, both rebuild
modes, valid preserved boundaries, null progress, callback mutation and failures.
Callbacks assert no active index lease. Callback failures and null progress
must happen before index entry. Post-generation same-size changes are observed
by the fresh index. The protected suffix actually throws on immutable-list
mutation and releases its lease; this is not only a normal-return cleanup test.

The low append count is descriptive, not a saving estimate: complete native
generation can already populate edges before the final loop, and typed lookup
can promote an existing edge without appending. Native generation remains
unaltered. Neither lookup counts nor fixture duration establish host CPU/RSS gain.

## Evidence and remaining scope

Full archive SHA pins were revalidated for5203/5302/5303. Each run uses javac
-Xlint:all/-Werror and java -Xverify:all in isolated loaders, no javaagent or Editor.
Authoritative queue/prepared arguments were monitored; only the owned process
group could be stopped if performance work appeared.

Final complete run:
`build/t081-native-complete-autoconnect-r1/native-complete-r2/review.json`,
own session37894 terminal0. Initial r1 success is preserved; r2 adds explicit
path exercise and untargeted-definition controls. Shared-tool regression:
`native-suffix-regression-r1/review.json`, session96619 terminal0. The adjacent
JSON pins tested source, archives, tools, compiled classes and raw reports.

The T079/T080 externally edited same-size list counterexample remains unchanged
and unsupported during an active index lease. Correctly handling callbacks
before index entry does not prove that every transitive callback or external
writer is excluded during the final loop. Runtime definition/dependency/loader
admission, complete mutation closure, production helper identity and T057
composition remain open. These fixtures exercise pristine official definitions,
not the composed production Agent. The current map is boxed and reflective,
with no production memory/performance claim.

Next establish exact final-loop callback/mutation admission, then implement and
verify a bounded production helper and compose only its required changes over
the frozen T057 artifact. Freeze a new protocol before any host comparison.
Previous failed T077 gates and withdrawal remain intact. All-version host
acceptance, long-run validation and Lane C remain open; no merge/push/release.
Historical T079/T080 source replay requires its corresponding committed source
or recorded classes; the current shared tool also supports complete weaving.
