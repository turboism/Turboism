# Non-typed persistence audit: journals, installer state and signed receipts

Analysis and regression coverage for **#69 / #111**. This document is the companion to
`architecture/PERSISTENCE_FORMAT_INVENTORY.md` (#109): the inventory names *what was emitted*, this
audit names *how the current tree reads, writes, updates and recovers it*, and which production
paths are now pinned by tests.

It is **analysis plus focused hardening**, not a format or support-window change. No persisted
`schemaVersion` was bumped, no migration was added, no historical signature was reissued and no
fixture was relabelled as release-derived.

## 0. Scope and method

In scope:

- legacy `config.json` and its typed settings facades;
- plugin key-value `Properties`, confined `StorageRoot` state, install/enable/pending journals and
  `catalog-state.json`;
- launcher/bootstrap/install state (`cubism-installations.json`, `installed-build.json`);
- framework self-update transaction state (`journal.json`, `pending.json`, `lease/*`,
  `update-state.json`, `update-preferences.json`);
- signed installation receipts (`installed-receipt.json`, immutable `manifest.json` +
  `manifest.json.sig`) and the verification boundary.

Out of scope by design:

- the typed plugin config envelope, its migration chains and recovery points are owned by #84/#110
  and the #109 inventory; this audit only records the destructive-transition boundary;
- installer UI/rollback UX, catalog publication semantics and automatic rollback behaviour;
- provenance of real historical release signatures (see §7).

Method: read the current production reader/writer and its failure branches, then add regression
tests that drive those production classes only. No test reimplements a parser, a writer or a
migration. Fixtures are either generated through the production writer during the test or, for the
signed receipt, committed as a clearly marked `test-key-reproducible` document.

## 1. Production reader/writer map

Paths are relative to `<turboism-home>` unless stated otherwise.

| Family | Path(s) | Production writer | Production reader | Update / rollback path |
| --- | --- | --- | --- | --- |
| legacy global config | `config.json` | `RuntimeConfigRepository#writeLocked`; facades `RuntimeSettingsFileService`, `CubismJvmSettingsFileService`, `AtlasCacheReuseSettingsFileService`, `AtlasTileBboxSettingsFileService`, `MeshTriangulationSettingsFileService`, `TriangulationEdgeIndexSettingsFileService` | `RuntimeConfigRepository#readLocked` + `RuntimeConfigValidator#validateForRead` | every facade delegates to `RuntimeConfigRepository#update`; no read-time migration; missing file is seeded from `defaults()` |
| plugin key-value config | `<plugin data dir>/*.properties` | `RuntimePluginConfigRegistry#writeProperty` (`java.util.Properties.store`) | `RuntimePluginConfigRegistry#read` | temp file + atomic move; parse happens before mutation; last writer wins |
| plugin private state | `data/<pluginId>/`, `state/<pluginId>/`, `cache/<pluginId>/` | plugin code through `RuntimePluginStorage` / `ConfinedStorageBackend#writeBytesAtomicLocked` | plugin code | `StorageFiles#writeDurably` (`CREATE_NEW` + `force`) then `StorageAtomicMover#move`; no runtime format layer |
| plugin install/enable journal | `state/runtime/plugin-management/pending.json` | `PendingPluginOperations#writeOperations` | `PendingPluginOperations#readJournal` / `#operations` / `#apply` | journal is the recovery record; `failed-pending.json` is suspended intent, not a second format |
| plugin catalog state | `state/runtime/plugin-management/catalog-state.json` | `PluginCatalogStateStore#save` | `PluginCatalogStateStore#read` | v2 closed schema; v1 is refused rather than migrated; corrupt state reads as empty |
| framework update journal | `state/runtime/update/helper/journal.json` | `FrameworkUpdateApplier#writeJournal` / `#writeAtomic` | `FrameworkUpdateApplier#readJournal` / `#recover` / `#prepareLaunch` | state machine `PREPARED → APPLYING → AWAITING_HEALTH/COMMITTED`; backups under `helper/backup/` restore files, `installed-build.json` and the receipt index |
| framework pending descriptor | `state/runtime/update/helper/pending.json` | `FrameworkUpdatePendingTask` / `FrameworkUpdateTaskStore` | helper re-authenticates every byte before use | pending is untrusted routing state; not execution authority |
| framework launch lease | `state/runtime/update/helper/lease/*` | `FrameworkUpdateLaunchLease` | same | bounded lease entries; stale entries are treated conservatively |
| framework update state | `update-state.json` | `UpdateStateStore#save` | `UpdateStateStore#read` | invalid state reads as empty; next `save` replaces it |
| framework preferences | `update-preferences.json` | `UpdatePreferencesStore#save*` | `UpdatePreferencesStore#readAll` | invalid preference reads as default; next `save` replaces it |
| installed identity | `installed-build.json` | installer/ZIP assembly; `FrameworkUpdateApplier#writeAtomic` (runtime updates) | `InstalledBuild`, `FrameworkBuildInfo` | applier backs up and restores the previous bytes |
| signed receipt index | `state/runtime/update/installed-receipt.json` | `FrameworkUpdateReceipt#initialize` / `FrameworkUpdateApplier` | `FrameworkInstallationReceiptLoader#load` / `#loadForHelper` | applier backs up the index before replacement and restores it on rollback |
| signed receipt documents | `state/runtime/update/receipts/<sha256>/manifest.json` + `.sig` | `FrameworkUpdateReceipt.Prepared#publishDocuments` | `FrameworkInstallationReceiptLoader` + `UpdateManifestVerifier#authenticate` | append-only immutable documents; index is advanced only after authentication |
| installer-owned cubism state | `cubism-installations.json`; installer discovery snapshot | PowerShell `Write-CubismInstallationState`; installer scan | PowerShell `Read-CubismInstallationState`; `Read-CubismInstallerSnapshot` | exact `schemaVersion`; required-key check; temp + atomic replace with a transient `replacement-backup` |

### 1.1 Existing durability primitives

| Primitive | Used by | Notes |
| --- | --- | --- |
| JVM per-path lock (`ConcurrentHashMap<Path,Object>` + `synchronized`) | `RuntimeConfigRepository`, `PluginCatalogStateStore`, `UpdateFileSupport` stores | serializes writers inside one JVM; no cross-process lock |
| `synchronized` instance methods | `PendingPluginOperations` | serializes staging/apply inside one instance/JVM; `PluginUpdateGate` adds a root lease for coordinated flows |
| temp sibling + `force` + `ATOMIC_MOVE` | config, plugin journal, catalog state, update state/preferences, update journal, receipt index | `RuntimePluginConfigRegistry` writes a temp file and atomically moves it but does not `force` before publication |
| `CREATE_NEW` + `force` + atomic move | `ConfinedStorageBackend` | plugin private state; fails closed when the filesystem cannot move atomically |
| rollback journal + backups | `FrameworkUpdateApplier` | backs up every replaced payload, `installed-build.json` and the receipt index before mutation |
| symlink rejection | all runtime-owned paths above | `NOFOLLOW_LINKS` plus explicit ancestor checks; plugin storage is confined to three roots |

## 2. Named threat / failure matrix

Legend: **Yes** = explicitly enforced by production code; **Partial** = enforced in one layer or
only under some conditions; **No** = not enforced; **N/A** = not applicable to this family.

| Family | Atomic replace | Unknown-field policy | Concurrent-writer protection | Corrupt/tampered bytes | Destructive transition without a durable pre-image | Regression test |
| --- | --- | --- | --- | --- | --- | --- |
| `config.json` | Yes: temp + atomic move, symlink chain rejected, 64 KiB cap | **Closed**: read and write refuse unknown top-level/hook/launcher/textureAtlas fields; duplicate JSON keys and trailing tokens now refused | **Partial**: per-path JVM lock; no cross-process lock; last writer wins between processes | read/update throw without writing; tolerated `locale`/`launcher.memoryProfile` is the only read-mode exception | No read-time migration; installer normalization is a separate destructive path (§3.1) | `RuntimeConfigNonTypedCompatibilityTest` |
| plugin `Properties` | Partial: temp + atomic move, no `force`, non-atomic fallback if `ATOMIC_MOVE` unsupported | No schema: properties merges unknown keys silently | **Partial**: one bounded I/O lane + lifecycle lock per registry; no cross-process lock | malformed properties fail the read/write path; the file is not rewritten on a refused write | A failed write leaves the old file; no parser-level migration | covered by existing `RuntimePluginConfigRegistryTest`; no new fixture because `Properties.store` timestamps bytes |
| plugin private state | Yes: `CREATE_NEW` + `force` + atomic move | plugin-owned; runtime does not interpret bytes | plugin-owned | plugin-owned; runtime confinement rejects links and escapes | plugin-owned; runtime has no format layer | inventory §5.2 / existing plugin tests; behaviour tests, not golden bytes |
| `pending.json` / `failed-pending.json` | Yes: temp + `force` + atomic move; 64 KiB cap | **Closed since this PR**: unknown top-level or operation fields are rejected; duplicate keys and trailing tokens are rejected | **Partial**: instance `synchronized`; ABA `revision` detects a changed journal during apply; no OS file lock | invalid journal ⇒ `RECOVERY_REQUIRED`; `stage`/`apply` refuse; original bytes untouched | No conversion: the journal itself is the recovery record; failed intent is moved aside, not rewritten | `PluginPersistenceJournalCompatibilityTest` |
| `catalog-state.json` | Yes: temp + `CREATE_NEW` + atomic move; 16 MiB cap | **Closed since this PR**: unknown v2 top-level fields rejected; v1 refused; duplicate keys/trailing tokens already rejected | **Partial**: per-path JVM lock; no cross-process lock | corrupt state reads as `State.empty()` with `PLUGIN_CATALOG_STATE_CORRUPT`; the file is left in place by `read` | `read` → `save`/`recordStagedUpdate` can replace corrupt bytes with a fresh document without a backup (§3.3) | `PluginPersistenceJournalCompatibilityTest` |
| `journal.json` (framework update) | Yes: temp + `force` + atomic move | **Partial**: format/state/file inventory are validated; unknown root fields are currently ignored | **Yes**: cross-process `helper-transaction.lock` plus journal/lease coordination | wrong format/state/path inventory is rejected; malformed bytes stay in place | No: backups are taken before replacement and restored on failure/crash | `SignedReceiptAndUpdateJournalCompatibilityTest` |
| `pending.json` (framework) | Yes: task store atomic record | **Partial**: untrusted state, re-authenticated before use | **Yes**: helper transaction lock / launch lease | refused or ignored as untrusted input | No: no authority is taken from pending bytes | existing `FrameworkUpdate*` tests |
| `installed-build.json` | Yes: temp + atomic move in runtime; installer writer is outside the runtime | no schema layer in the applier; bytes are opaque | applier serializes with the update journal | rejected if not a plain regular file / oversize | applier backs up and restores the previous bytes | `FrameworkUpdateApplierTest` + new signed-transition test |
| `installed-receipt.json` | Yes: private temp + `force` + atomic move; private directory/file permissions checked | **Closed**: exactly five fields, exact format/layout, digest and root identity | **Yes**: transaction lock and journal `receiptTracked` | refused if any field, digest, root binding or document byte differs | applier backs up the index before replacement and restores it on rollback; documents are append-only | new signed-transition tests |
| `receipts/<sha256>/manifest.json` + `.sig` | Yes: append-only immutable publication; existing bytes must match exactly | **Closed**: manifest/envelope parsers reject unknown fields | **Yes**: transaction lock; documents are immutable | `UpdateManifestVerifier#authenticate` refuses tampered manifest/envelope, unknown key, revoked key, wrong algorithm/schema | No re-signing, no mutation; a crash may leave unreferenced immutable documents | new signed-receipt tests |
| `update-state.json` | Yes: temp + atomic move; 1 MiB cap | **Permissive**: known fields are validated, unknown root fields are ignored | **Partial**: per-path JVM lock; no cross-process lock | invalid/future state reads as empty with `UPDATE_STATE_CORRUPT`; file preserved by `read` | next `save` replaces an unreadable/future document without a backup (§3.4) | new signed test (`corruptUpdateState...`) |
| `update-preferences.json` | Yes: temp + atomic move; 1 MiB cap | **Permissive**: unknown root fields ignored | **Partial**: per-path JVM lock | invalid/future preference reads as default with `UPDATE_PREFERENCES_CORRUPT`; file preserved by `read` | next `save` replaces an unreadable/future document without a backup (§3.4) | new signed test (`...preferences...`) |
| `cubism-installations.json` | Yes: temp + atomic replace; a transient `replacement-backup` is deleted only after success | **Closed at read**: required keys + optional allow-list + exact `schemaVersion`; unknown keys are rejected | PowerShell/installer serialized; no runtime lock | refused by exact version/required-key checks | installer rewrite can drop data only if the PowerShell reader accepted the document; no runtime rollback image | PowerShell contract tests; byte-exact fixture remains a #110 gap |
| installer discovery snapshot | transient handoff; digest-bound | required fields + verifier SHA-256 | installer-only | refused when bytes or verifier digest differ | no user state | installer tests; exact bytes remain a #110 gap |

### 2.1 Read-mode exceptions that are deliberately not failures

- `config.json` tolerates one unsupported `locale` and one unsupported `launcher.memoryProfile`.
  The repository removes them from the returned document, reports `RUNTIME_CONFIG_BAD_LOCALE` /
  `RUNTIME_CONFIG_BAD_MEMORY_PROFILE`, and writes them back unchanged on an unrelated `update()`
  (#85). A change that replaces or removes the field remains strict.
- `catalog-state.json`, `update-state.json` and `update-preferences.json` convert a corrupt file to
  an empty/default in-memory state instead of failing the whole UI. The original file stays in place
  until the next successful save.
- A missing `config.json` is materialized from `defaults()` through the normal write path; there is
  no in-memory fallback that hides a creation failure.

## 3. Destructive transitions without a durable pre-image

Only transitions that actually replace or discard bytes are listed. Backup-on-every-read is
explicitly not proposed.

### 3.1 Installer `config.json` normalization (real, released)

`packaging/java-installer/listener-src/.../ConfigMerge#normalizeToCurrent` and the PowerShell
configurator rewrite `config.json` field by field for every document that fails the strict v1
contract and **drop unknown fields and invalid values**. The write is atomic and leaves the old
bytes alone if it fails, but there is no durable copy of the pre-normalization document. The
runtime, by contrast, refuses unknown fields and never normalizes. Three released installer
policies exist (`v0.42.0`–`v0.43.2` preserve unknown fields; `v0.43.9`–`v0.44.1` migrate v0→v1;
`v0.67.0`+ normalize and drop). This is the strongest candidate for a scoped remediation because a
user who runs an installer once loses data that the runtime would have refused to touch.

Scoped proposal (not implemented here): before the installer writes a normalized document, copy the
original bytes to a bounded sibling such as `config.json.pre-normalize-<sha256>.bak` (retain one or
two), and record the dropped field names in the install log. This belongs to the installer lane,
not to `RuntimeConfigRepository`, and needs a separate review because it changes installer output
and packaging tests.

### 3.2 Typed-config read-time migration (owned by #84/#110)

Every published stable release up to `v0.68.1-beta.11` replaces a migrated `<doc>.cfg` with no
pre-upgrade image. `TypedConfigDocumentStore#recordRecoveryPoint` exists only in
`v0.68.1-0.nightly.88` and later. This audit does not duplicate the typed-config work; it records
that the “no destructive migration without a recovery point” rule is **not yet satisfied by any
stable release**, and that #110's release-derived v3 fixture is the right place to prove it.

### 3.3 Catalog-state overwrite after a corrupt read (real, current tree)

`PluginCatalogStateStore#read` returns `State.empty()` on corruption and leaves the file alone.
Any later `save` / `recordStagedUpdate` (for example a successful catalog check or a staged update
marker) writes a fresh v2 document over the corrupt bytes. There is no backup and no diagnostic
that the previous bytes were discarded. This is defensible for a cache-like file, but it is a real
destructive transition and should be named as such.

Scoped proposal (not implemented here): when `read` observed corruption, expose a “degraded” flag
and have `save` first copy the unreadable file to `catalog-state.json.corrupt-<sha256>` (bounded to
one). Do not back up every normal save.

### 3.4 Update-state / preferences overwrite after a corrupt read (real, current tree)

`UpdateStateStore#read` and `UpdatePreferencesStore#readAll` return empty/default on corruption or
an unsupported `schemaVersion` and leave the file in place. The next `save` replaces it. For a
preference toggle this is a silent loss of the user's previous (perhaps newer-build) document.

Scoped proposal (not implemented here): on a save that follows a corrupt read, preserve one
`*.corrupt-<sha256>` sibling before the atomic replace. This is a small, local change; it should not
turn reads into backups.

### 3.5 Plugin journal and cross-process writers (control gap, not a format loss)

`PendingPluginOperations` is `synchronized` per instance and the journal write is atomic, but there
is no OS file lock. Two processes (for example a running app and a separate installer/helper) can
both stage. The `revision` UUID and the apply-time snapshot check catch a journal that changed
during `apply`, and a failed apply moves intent to `failed-pending.json`; they do not prevent two
independent stage operations from producing a last-writer-wins journal. `PluginUpdateGate` provides
an in-process root lease and blocks background work when framework pending/journal or
`failed-pending.json` exists, but it is not a cross-process lock.

Scoped proposal (not implemented here): add a bounded lock file under
`state/runtime/plugin-management/` with stale-owner detection, or route all cross-process staging
through the coordinated update service. This needs a separate concurrency design; a test-only lock
would not change production behaviour.

### 3.6 Framework update journal unknown fields (forward-compatibility gap)

`FrameworkUpdateApplier#readJournal` validates `format`, `state`, the file inventory and
`receiptTracked`, but ignores unknown root fields. Unlike the plugin journal (hardened here), a
future journal carrying an unknown semantic field would still be read by an older helper. The
state set and optional fields are broader (`agentSha256`, `agentBytes`, `identityExisted`,
`launcherPid`, `launcherStarted`), so a strict allow-list needs its own design and tests. This is
recorded as a next-PR boundary rather than changed in this audit.

## 4. Regression tests added by this PR

| Test class | Production path driven | Coverage |
| --- | --- | --- |
| `dev.turboism.config.RuntimeConfigNonTypedCompatibilityTest` | `RuntimeConfigRepository#read` / `#update` only | future `schemaVersion`; unknown top-level/launcher fields; malformed JSON; duplicate keys; trailing tokens; invalid field values; rejected write; #85 tolerated `locale` + `launcher.memoryProfile` preservation; POSIX write-failure byte preservation; concurrent updates; non-regular path |
| `dev.turboism.pluginmanagement.PluginPersistenceJournalCompatibilityTest` | `PendingPluginOperations#stage` / `#stageUninstall` / `#apply` / `#operations` / `#recoveryRequired`; `PluginUpdateGate#isAutoUpdateBlocked`; `PluginCatalogStateStore#read` / `#save` | corrupt/truncated journal; wrong `schemaVersion`; unknown top-level field; unknown operation field; duplicate keys; trailing tokens; suspended `failed-pending.json` blocks new staging; corrupt/v1/unknown-field catalog state preserved; valid state round-trips; optional historical `revision` absence |
| `dev.turboism.update.SignedReceiptAndUpdateJournalCompatibilityTest` | `UpdateManifestVerifier#authenticate`; `FrameworkInstallationReceiptLoader#loadForHelper`; `FrameworkUpdateReceipt#prepare` / `#publishDocuments`; `FrameworkUpdateApplier#applyAuthenticated` / `#recover`; `UpdateStateStore#read`; `UpdatePreferencesStore#readAll` | fixed `test-key-reproducible` receipt verifies; tampered manifest/signature/revoked/unknown key; unknown receipt-index field; injected failure after receipt-index replacement restores index, `installed-build.json`, payloads and old signed documents; simulated crash at `after-receipt-index` recovers the same bytes; corrupt/wrong-state update journal preserved; corrupt state/preferences read as defaults without rewriting |

### 4.1 Production hardening included in this PR

- `RuntimeConfigRepository` now parses with strict duplicate-key detection and
  `FAIL_ON_TRAILING_TOKENS`. This closes a tamper/ambiguity gap: previously duplicate keys were
  “last value wins” and bytes after the first JSON value were ignored, so a failed read could hide
  data that a later write would erase.
- `PendingPluginOperations` applies the same strict parse and rejects unknown top-level/operation
  fields. A transaction journal is execution authority; an older reader must not act on a field it
  does not understand.
- `PluginCatalogStateStore` rejects unknown top-level fields in its closed v2 schema. The v1 refusal
  and the corrupt→empty read behaviour are unchanged.

No `schemaVersion`, format id, field name or migration path changed. The hardening only narrows
inputs that were already invalid or forward-written.

### 4.2 Signed fixture provenance (explicitly not historical)

`runtime/src/test/resources/fixtures/update/test-key-reproducible/` contains:

- `signed-receipt-manifest.json` — canonical manifest bytes;
- `signed-receipt-manifest.json.sig` — detached `turboism-update-manifest-sig-v1` envelope;
- `README.md` — key id, test-only seed, public key and the non-historical statement.

The fixture uses `keyId=test-key-reproducible`, a deterministic Ed25519 seed committed **only** for
tests and a public key documented in the README. It is not a real release receipt, was not produced
by a protected signing job, and must never be described as release-derived provenance. Real
historical receipts remain gap G3 in the #109 inventory; verification is covered, issuance is not.

## 5. Findings that remain open

| ID | Finding | Why it is not fixed here | Next owner |
| --- | --- | --- | --- |
| N1 | Installer normalization drops unknown `config.json` fields without a pre-normalization copy | installer lane and packaging contract; needs product decision on preserve vs refuse | packaging/installer PR |
| N2 | Stable releases still migrate typed configs without recovery points | #84/#110 own the migration and release corpus | #110 |
| N3 | `catalog-state.json` / `update-state.json` / `update-preferences.json` can overwrite corrupt or future bytes on the next save | cache/preference semantics need an explicit decision; backup-on-every-read is rejected | follow-up persistence PR |
| N4 | Plugin journal staging has no cross-process lock | concurrency design and stale-lock handling are separate from format compatibility | follow-up concurrency PR |
| N5 | Framework update journal ignores unknown root fields | allowed-field set is state-dependent; strictness needs its own review | follow-up updater PR |
| N6 | `RuntimePluginConfigRegistry` does not `force` the temp properties file and falls back to a non-atomic move | properties bytes are timestamped and not golden-fixture material; durability review belongs with plugin config | follow-up plugin config PR |
| N7 | Real historical signed receipts and catalog signatures | requires the protected private key; re-signing is forbidden | #69 evidence gap G3 |
| N8 | `cubism-installations.json` and installer discovery snapshot byte-exact generation | PowerShell formatting and a Windows host; freeze or generate on Windows | #110 / installer evidence |

## 6. Supported-behaviour statement

For the families covered here the current contract is:

1. A document this build understands is read and, where applicable, migrated or normalized only by
   the production path named in §1.
2. A document this build does **not** understand (future schema, unknown field, malformed bytes,
   duplicate keys, trailing tokens) is refused **without writing**. The only deliberate exceptions
   are the read-mode tolerance for `config.json` choices and the cache-like empty/default reads for
   `catalog-state.json`, `update-state.json` and `update-preferences.json`.
3. A signed document is verified as exact bytes. It is never re-signed, rewritten or “refreshed” to
   satisfy a newer key.
4. A failed framework update restores the previous payload, `installed-build.json` and receipt index
   from the journal backup; a crash is recovered on the next start by the same journal.
5. No new format, schema version or automatic rollback UX is introduced by this PR.
