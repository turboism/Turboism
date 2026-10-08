# T080: native mesh caller ownership and logging boundary

Decision: the main measured command does not establish private mesh ownership.
Continue with exact callback/mutation closure and complete-operation controls;
do not promote the T079 cache using mesh version, list size or assumed privacy.
Production remains T057 and no new host job was submitted.

The guarded static archive scan finds the same three direct caller methods in all
three versions. This inventories normal bytecode invocation sites; it does not
cover reflection, method handles or external plugins.

| Direct caller | Site | Receiver evidence |
| --- | ---: | --- |
| GEditableMesh2.autoConnect$default | 18 | Explicit argument forwarded to native autoConnect |
| GEditableMeshHandler.a(GVector2) | 194 | Local 4, constructed at 21–32 then populated through ICopyable |
| CModelingEditMode_MeshEditor.commandAutoConnect | 208 | Existing editDataList element's b() result |

In 5303 the editor element's final `b()` getter is only aload_0/getfield b/areturn.
The command passes this existing mesh to Undo setup at146 and again to autoConnect
at200/208, then selection/version updates at213–230. There is no private mesh
construction at that command's invocation site. The handler's fresh local receiver
is a different path, and its copy/publication closure remains unproven. It cannot
justify private ownership for the primary command.

## Guard the native logger path

`addEdge` calls the native logger when its endpoint arguments are equal. A loop
without direct progress callbacks is therefore not automatically free of
transitive user callbacks. The owned helper now declines null/truncated index
arrays and any triangle containing equal endpoints before acquiring an index.
Native behavior, including original errors and logging, continues through fallback.
This is an eligibility guard, not proof of the remaining complete call closure.

The updated guarded native suffix experiment revalidates all three official
archive SHA pins and passes 576 fixture groups across both assertion modes:
5,942 checks, 21,438 indexed queries, 432 native fallbacks and 13,992 append
registrations. New explicit null/truncated/three degenerate-pair controls all
decline. Varied fixtures now include distinct endpoint triples to ensure the
guarded fast path is actually exercised. The same-size external-set
counterexample still reproduces in all six version/assertion groups. It remains
unsupported and prevents blanket production admission.

Additional 5303 static dependency evidence shows CArrayList.get/getSize forward
to ArrayList.get/size, size delegates to getSize, and exact add/set check an
immutable flag then call ArrayList.add/set. These paths do not call element
equals/hashCode. addEdgeIfNotExists and its default wrapper forward the reviewed
false duplicate/crossing arguments. This is selected-class evidence; it does not
prove all runtime dependencies or all routes through those methods.

## Evidence and next action

Raw direct inventories and selected definitions are under
`build/t080-native-mesh-ownership-r1/`. Updated guarded run:
`native-suffix-r4/review.json`, own session46847 terminal0.
The adjacent JSON pins these records and the current tested sources. T079 r3
results remain unchanged; replaying that earlier source requires commit75a7f65a1
or its recorded compiled classes, because this step adds an eligibility guard.

Next build a complete-autoConnect owned fixture and prove the remaining native
call/mutation closure under exact runtime admission. In particular, distinguish
native single-thread collection behavior from any proposed concurrent writer
guarantee; the original exposed ArrayList is not synchronized. Do not infer
exclusive ownership from EDT execution alone. Unsupported callbacks/mutations
must reach native behavior. A copied-suffix pass does not verify the complete
operation or production composition.

No JVM/host performance benefit is claimed. Full-operation equivalence, runtime
admission, retention, fixed CPU/wall/RSS gates, all-version host acceptance,
long-run validation and Lane C remain open. Failed T077 and T057 delivery remain
unchanged; no merge, push or release.
