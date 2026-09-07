# SDK v7 release review

Approved on 2026-09-06 for Turboism 0.43.9: publish the current API rather than restore the v6 surface. Plugins using affected APIs must adapt; this is not a claim of exact v6 compatibility.

## Immutable identity

- Source: `46ea5cb303a2a1a9191859885c56c059d1d538b6`.
- Reference reconstruction: Gradle 8.10.2, Temurin 17.0.20.1+1, `scripts/test/reconstruct_sdk_gradle_jar.py`, worktree ID `history-reference`.
- JAR: 841079 bytes; SHA-256 `8d889ebecf4a04b2032f91a9f8388d9636970486e44ac3d9564147e4398971e7`.
- Canonical dump (`dev.turboism.sdk`): 6400 lines including headers; SHA-256 `cbe6f0685da31b8122cdeff4553a47b3c74a331566f82db2e6fc6bca047c8277`.
- v2–v6 baseline files and commit anchors remain unchanged. v6 continues as a historical exact audit; v7 is the live exact release gate.

## Reviewed v6 → v7 difference

There are 7 removed/changed canonical records and 91 additions (net +84). A changed record appears on both sides; these counts are not counts of removed Java methods.

| Changed v6 record | Disposition |
| --- | --- |
| `Glue` class | Adds exact `@CubismEditor` version annotations. |
| `Glues` class | Adds exact `@CubismEditor` version annotations. |
| `CubismModel.glues()` | Adds the same version annotations; signature remains. |
| `ModelObjectOperationException.Code.FAILED` | Ordinal shifts after inserting `COMMITTED`; consumers must not persist enum ordinals. |
| Four-argument `HistoryEntry` constructor | Signature retained as an explicit delegating constructor with changed parameter metadata; new canonical constructor carries entry/transaction identity. Record equality/shape also changes. |
| Three-argument `McpHttpConnection` constructor | Removed; use the two-argument endpoint/protocol constructor. |
| `McpHttpConnection.authorization()` | Removed with bearer authentication; do not request or send a bearer token. |

Additions cover stable `HistoryEntryId` and history transaction identity, synchronous authoring transaction options/work/results/receipts/outcomes/service and facade access, Glue transaction helpers, committed-write readback information, and the credential-free MCP connection constructor.

## Enforcement

`checkSdkV7ExactApiCompatibility` verifies the current SDK against the pinned v7 reference and new baseline using **verify-exact**, including reference artifact and canonical bindings. It does not capture or overwrite a baseline during verification. `checkRelease` retains all historical audits and adds the new live gate. The unchanged verifier mutation selftests plus a negative comparison using the v6 API verify that drift still fails.

Reproduce the review by reconstructing both pinned v6/v7 JARs, dumping each with `sdk_api_baseline_cli.py dump --package-prefix dev.turboism.sdk`, and comparing the canonical lines. This review does not promote any Cubism host capability or claim new real-host evidence.
