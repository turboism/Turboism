# SDK v9 release review

Prepared on 2026-09-11 for Turboism 0.43.9: re-anchor the live exact release gate because the
observed-operation lifecycle gained a presentation label. This review is the anchor change; the
release owner confirms it at release time exactly as the v7 and v8 reviews were confirmed.

## Why a new anchor was due

Spec 012 HR-05 adjudicated that a native Cubism Editor edit observed through the undo listener
publishes a `before`/`on`/`after` lifecycle carrying the localizable native edit name. That name had
no transport: `CubismOperationEvent` carried only `sequence`, `operation`, `origin` and an optional
`subjectId`, and `subjectId` is documented and validated as a **Turboism-owned** project, document,
model, object or **command** identity. A native edit name is a localizable host label with no
Turboism identity, so putting it in `subjectId` would have broken the contract the MCP schema and the
history rows rely on.

Unlike v7, this anchor is not a re-anchor after a silent drift: the v8 gate was green
(`baseline=6631 current=6631 additions=0`) immediately before this change. The anchor moves because
the SDK surface deliberately grew by one presentation field.

## Immutable identity

- Source: `cfdfc0102755b45d858a271c981f6e83249a465c`.
- Reference reconstruction: Gradle 8.10.2, Temurin 17.0.20.1+1,
  `scripts/test/reconstruct_sdk_gradle_jar.py`, worktree ID `history-reference`.
- JAR: 884177 bytes; SHA-256 `15285c6e42e5ce57d311d8ebc3cbf173865508d3ba3da3453ddb87e5470dc610`.
- Canonical dump (`dev.turboism.sdk`): 6635 lines including headers; SHA-256
  `a1b475883beecac2e4ede7bae9683f9aa626ae688d9fb427012fde52d15233e9`.
- The live `:sdk:jar` built from the same SDK tree is byte-identical to this reference JAR
  (`checkSdkV9ExactApiCompatibility` reports `baseline=6633 current=6633 additions=0`).

## Reviewed v8 → v9 difference

Against the v8 canonical records: **2 added**, **1 changed**.

| Change | Record | Disposition |
| --- | --- | --- |
| added | `CubismOperationEvent.label()` (`()Ljava/util/Optional;`) | New accessor for the optional presentation label. |
| added | `CubismOperationEvent` record component `label` (`Ljava/util/Optional;`, index 4) | New record component, appended after `subjectId`. |
| changed | `CubismOperationEvent` canonical constructor | Descriptor grew from `(JLdev/turboism/sdk/cubism/event/CubismOperation;Ldev/turboism/sdk/cubism/event/CubismOperationOrigin;Ljava/util/Optional;)V` to include the second `Ljava/util/Optional;`. |

**Plugin API migration may be required:** `CubismOperationEvent` is a record, so a plugin that
constructs one directly must pass the new fifth component. Consumers that only read the event are
unaffected, and the new component is appended, so no existing record component's index moves and no
enum ordinal changes.

The `label` component is documented as presentation only. It is never an identity and never proof of
what an operation changed: the observer publishes the native name it read, while the operation itself
is still established only from the exact admitted native entry. An undo or a redo names no label of
its own and publishes none rather than deriving one from the entry it moved.

## Enforcement

`checkSdkV9ExactApiCompatibility` verifies the live SDK against the pinned v9 reference and this
baseline using **verify-exact**; it does not capture or overwrite a baseline during verification. v8
is demoted to a historical audit (`checkSdkV8ExactApiCompatibility` now audits the pinned v8 artifact
against its own binding, as v2–v7 already do), and `checkRelease` runs the historical audits followed
by the live v9 gate.

Reproduce by reconstructing the pinned commit with `./gradlew prepareSdkV9ExactReference` and
dumping it with `sdk_api_baseline_cli.py dump --package-prefix dev.turboism.sdk`.

## Anchor constraint

The anchor is pinned to the commit that added the label component. That commit must stay reachable
and must not be rebased away; if the release line re-creates the SDK change as a different commit,
re-pin `sdkV9ExactCommit`, rebuild the reference with `prepareSdkV9ExactReference` and capture the
baseline again with the same procedure. Because the binding is the SDK tree content, any later SDK
API change needs another anchor.

This review promotes no Cubism host capability and claims no new real-host evidence.
