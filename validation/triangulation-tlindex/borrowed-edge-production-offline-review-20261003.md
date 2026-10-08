# T060 borrowed-edge production integration: offline evidence

The private 5302/5303 `h.a(TriangleList,k)` weave removes one repeated index-membership lookup only while the actual shared definition lease is held and the borrowed list has exactly `ArrayList.class`. It retains the initial coordinate comparison, native operand load order, geometry, removal order and fallback. 5203 retains its existing native route.

## Verification

- 59 focused JUnit cases, 5 bootstrap contributor cases and final `devCheck` passed. The bootstrap invocation also named a nonexistent installer selector; no execution is attributed to it. Actual runtime installer verification contributed 6 of the 59 cases.
- Compiled production patcher with controlled eligibility: 50,450 assertions passed against complete native methods, including null lease, subclass iterator exceptions, special floats and assertion modes.
- Frozen scoped production sole-premain: 42 cases passed. Live operation/list/endpoint/index mutation rejects the shared gate, unsupported startup rejects, revocation disables both h and builder gates. No manufactured lifecycle owner, Editor or frame.
- Actual sole-premain composed geometry: 128 fixtures for each of 5302/5303 with assertions on/off; input/return triangle physical order and identities, endpoint raw bits/indices, and failures match T057 exactly across separate JVMs.
- Exact diagnostic return-capture companion passes actual sole-premain for all three versions. Production capture admission remains unchanged.

The first premain attempt incorrectly targeted a coordinate method absent from 5203; it is retained as failed diagnostic evidence. The final runner excludes that inapplicable control. Production code did not change to accommodate it.

## Frozen inputs

| Artifact | SHA-256 |
| --- | --- |
| T057 scoped production baseline | `17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295` |
| T060 scoped production candidate | `e91db380b40db8dbc3608c6cc387c1669955aad382563e688dc643e8ce55dc7b` |
| T057 diagnostic baseline | `82c0ecbaacf4c10c5fec8214a0fcee6fb1d680be970e1ad7bc189e9c5585d869` |
| T060 diagnostic candidate | `b10238ae4ae524603ca255550b2053c1128eaa2569dd7887999fb5cadf941b93` |

Runtime target method fingerprint: `ba0701d8f1dce4e49d822877126c53b0827c74eb42e4641d4c1c0a44afb872a3`. Expected composed 53 h output: `50580126c0b988113a75d6415248cae2060c33390307c161f055c0422256fec8`.

The scoped artifact overlays only Preparation and BorrowedEdgePatcher families on frozen T057, preserving unrelated framework/config/index bytecode. `build/t060-borrowed-integration-r1/artifact-review.json` records changed/added entries. Source, classes, commands and evidence pins accompany this report.

Native follow-up failed the CPU gate (wall +6.06%, Java CPU +7.91%); production integration and its exact-host test were withdrawn. The earlier T057 implementation remains the production baseline. Historical scoped JARs, compiled classes and source snapshots remain in the owned build evidence for diagnosis. FIFO 5303 comparison against frozen T057 uses the same 711-source × 3-cycle diagnostic protocol, probes and original CPU/RSS caps. No prior cap exception applies. Lane C human review remains pending; this is no main merge or release readiness claim.
