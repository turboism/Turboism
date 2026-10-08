# T077 defer settlement for native-only membership

Offline checks pass; no host performance result yet. The candidate is T057 plus
one membership change. T075 QuerySnapshot source/tests are restored to their pinned
T057 baseline after failed exact-production gates. Rejected T075 artifacts, original
instrumented passes and production failure evidence remain unchanged.

Unknown identities and unresolved removal arguments cannot receive the index's
positive membership shortcut. They now call native contains without first settling
pending removals. The next eligible indexed answer still proves actual identity
absence, detects equal-but-different native victims and rebuilds on drift. Native
exceptions propagate once. No equality assumption, victim guessing or removal-proof
shortcut is introduced; pending references retain their existing bound of eight.

The inspected official5303 h replacement path removes two triangles, constructs two
new triangles, then tests their membership before adding them. The previous eager
settlement traversed survivors at that native-only query. This supplies a concrete
workload path; static inspection alone does not prove frequency or CPU benefit.

-35 actual index JUnit tests pass, including unknown/equal/absent/pending membership,
 exact native equality counts, zero early survivor visits, one later shared scan,
 native exception propagation, mutable equality/tree victims, drift and release.
-7 exact official bytecode tests pass across the reviewed profiles.
-`devCheck` passes.
-Frozen candidate sole-premain controls pass all39 cases across5203/5302/5303:
 admission, dependency refusal/revocation and six paired geometry groups with
 assertions enabled/disabled. Paired outputs are identical; these are owned fixtures,
 not39 Editor scenarios or all-version native performance acceptance.

Frozen candidate SHA256:
`79e92acc450e92ee411bf8048b3ef58ef7b4df67bf262458494e1a562bc56675`.
Six index-family classes overlay T057;5298 unrelated ZIP entries are byte-identical.
No entries added/removed and no QuerySnapshot class. Source outside contains matches
the pinned T057 source after whitespace normalization. Adjacent JSON pins artifacts,
sources, real test XML, guard results and premain evidence.

First expanded test command accidentally filtered only its last Gradle task, starting
the unfiltered runtime suite. It was explicitly cancelled; its stop record remains.
First ZIP audit reused mutable ZipInfo objects and failed read-back verification.
Its partial artifact remains rejected; the isolated corrected r2 audit passes.
Neither failure was a native measurement or favorable host retry.

Next freeze a separate exact-production B1,C1,C2,B2 comparison using the unchanged
observer-free driver and original direct CPU, wall, output, lifecycle and RSS gates.
No JFR/producers/additional premain during those measurements. T057 remains delivery;
all-version/long-run/Lane C review and the broader performance goal remain open.
