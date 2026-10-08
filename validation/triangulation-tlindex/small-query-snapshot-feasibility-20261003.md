# T074 small detached query snapshot feasibility

Bounded direct-consumer audit and standalone prototype PASS. **No production integration or performance acceptance. T057 remains production.**

| Official profile | Primary classes scanned | Direct target call sites |
|---|---:|---|
| 5203 | 16540 | h:4 |
| 5302 | 16947 | h:6; TriangleList:1 |
| 5303 | 16992 | h:6; TriangleList:1 |

All primary-JAR class constant pools were scanned for exact `TriangleList.a(j):List` method references, then referring classes disassembled and18invocations inspected. Exact jar hashes, disassembly hashes and call-site excerpts are recorded in JSON. This scope excludes external plugins/reflection and is not a complete dynamic escape proof.

Reviewed consumers use size/get/iteration and `h.a(List):boolean`, which reads isEmpty,size,get. No direct ArrayList cast of a query result was observed in these consumers. The ArrayList casts elsewhere in h are not proof of a query cast. Crucially,5.3 `h.a(TriangleList,k)` traverses query results while removing triangles from the original TriangleList; results must remain detached. Mutable List behavior is preserved irrespective of these primarily read consumers.

Standalone `SmallQuerySnapshotSelfCheck.java` prototypes an AbstractList/RandomAccess snapshot with inline0–2entries, no retained source-list reference and no backing list on read. First mutation creates its private ArrayList before publishing it and clearing inline references. Native ordering/element identity/null values are retained. Production policy would keep the existing ArrayList snapshot for cardinality>2; no shared live bucket or cached mutable result.

Java17 `-Xlint:all -Werror`, `-Xverify:all` and1,709,997checks PASS:20,000seeded differential trials versusArrayList with12operations each, ordered equality/hash, object/typed arrays, streams, invalid index refusal without materialization, add/addAll/set/remove/removeIf/replaceAll/clear, iterator modification, sublist deletion, nested views, fail-fast structural change, null/identity preservation and released migrated inline slots. Final transcript/classes/source pins are in JSON and raw artifacts under `build/t074-query-snapshot-consumer-audit-r1/`.

No exact allocation-byte, CPU, RSS, retained-heap, native geometry, premain or lifecycle claim. Changing the concrete returned class still needs actual complete-index/native integration verification. Next integrate a private version for0–2hits, preserve>2/fallback/weakregistry/removalproof, run actual index behavior and three-profile sole-premain/native fixtures, then freeze a new balanced directCPU/RSS/output/lifecycle protocol before any host comparison. Historical rejected candidates remain withdrawn; no T072 retry, merge/push/release. Lane C human review and broader goal remain open.
