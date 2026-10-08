# T076 command-return pairing feasibility: source written, not executed

The final view dispatcher metadata corrects one interpretation in the preceding
review. CEViewContext.onInputEvent_exe has access flags 17 (public final).
Although its call uses invokevirtual, this method cannot be overridden by a
subclass. Calls inside the method can still reach runtime actions and handlers.
Reviewing every possible subclass override of this method is unnecessary.

The repaint event factory builds an event with ui/event/f.a when its widget binding
exists, and otherwise returns null. The final dispatcher has event-specific
branches. Its whole member list does not establish which calls a repaint event
actually reaches; neither can it establish that every listed mouse/key handler
runs during repaint. Raw bounded class-byte inspection and hashes are in
`build/t076-production-acceptance-preflight-r1/`. Complete official archive
revalidation remains deferred while another performance leg runs.

The next executable approach is a separate dual-boundary feasibility diagnostic.
It retains the existing producer recorder solely to prove the command-return
observer's output. Both observations occur during one driver EDT task; the driver
must separately bind the same task document, edit mode, complete source order and
pre/post mesh identities. This is not an observer-free performance comparison.
No frozen T075 source, driver, script or protocol input is changed.

NativeCommandReturnSnapshot.java and its own-fixture SelfCheck source are written
but intentionally uncommitted, uncompiled and unexecuted. The helper requires a
complete normal producer observation, exact cycle/thread/ordered source/invocation
coverage, stable native fields/array identities, valid geometry shape and exact
point/position/index lengths and hash equality with the corresponding producer
record. It returns detached scalar/hash evidence and clears its temporary native
reference map. Producer-edge, command-edge and GL cache versions are recorded
separately without a fabricated monotonicity or cache-freshness rule. Legacy
MeshResultSnapshot.capture and its CacheNotReady refusal remain unchanged.

The SelfCheck source covers detached results, old stale-cache refusal, missing or
failed/incomplete producer evidence, source/order/invocation/cycle/thread mismatch,
stale positions, mutation during getters, invalid triangle indices, released scope
and off-EDT use. These are planned executable checks, not PASS evidence.

Next compile these actual sources and the existing diagnostic dependencies with
Java 17 lint/Werror, run the SelfCheck, then implement a separate opt-in diagnostic
driver with document/mode/mesh binding. Only a complete exact-host dual-boundary
probe may establish this new observation boundary. Passing fixture checks alone
does not grant host or observer-free collector admission. Full source coverage,
all-version checks, complete repository gate, production comparison and Lane C
human review remain pending; the broader optimization goal stays active.
