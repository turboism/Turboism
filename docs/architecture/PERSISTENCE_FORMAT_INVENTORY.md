# Persistence format inventory: released writers, readers and provenance

Analysis for **#69 / #109**. This document is the provenance inventory the golden-corpus work
(#110) and the non-typed-state audit (#111) depend on. It is **analysis only**: it changes no
runtime format, no migration, no support claim and no fixture.

Read it with the two existing contracts:

- `docs/architecture/ARCHITECTURE_GOVERNANCE.md` — golden snapshot contract and recovery point contract.
- `testing/fixtures/snapshots/manifest.json` + `scripts/check_golden_snapshots.py` — the immutable
  fixture inventory and its checker.

## 0. What this document owns, and what it does not

| Question | Authority |
| --- | --- |
| Which on-disk formats has a **published release** ever written? | this inventory |
| What bytes does a given release's writer emit, and what reads them back? | this inventory (paths + commands); the **code** is authoritative |
| Is a stored document accepted, migrated or refused? | `RuntimeConfigRepository`, `RuntimeTypedPluginConfigRegistry`, `TypedConfigDocumentStore`, `ConfigMerge` — never this document |
| Is a fixture authentic and unmodified? | `scripts/check_golden_snapshots.py` + `manifest.json` |
| Is a signed artifact authentic? | `UpdateManifestVerifier` / `PluginCatalogVerifier` against bundled public keyrings |
| Which old versions must keep working? | §12 (decided: every published release from `v0.42.0` on) |

Evidence status labels used below:

- **Confirmed emitted** — a released writer is identified by class/method and the format is
  reachable from a normal user action; reproduction command given.
- **Inferred-only** — the format is documented by code or by a tolerant reader, but no released
  writer can be shown to have produced it from this repository's history.
- **Evidence gap** — the artifact cannot be produced or verified offline; the gap is registered
  in §14 with what would close it.

## 1. Publication window

`v0.42.0` (2026-08-25) is the **first published release**; nothing older is tagged, so formats
emitted by earlier builds are not recoverable from this repository.

| Fact | Value |
| --- | --- |
| Git tags | 50 |
| GitHub releases | 40 (13 non-prerelease including one test-corpus release, 27 pre-release) |
| Published **product** stable releases | 12: `v0.42.0`, `v0.43.0`, `v0.43.1`, `v0.43.2`, `v0.43.9`, `v0.43.10`, `v0.43.11`, `v0.44.0`, `v0.67.0`, `v0.67.1`, `v0.67.2`, `v0.67.4` |
| Latest stable release | `v0.67.4` @ `d9d630c65` (2026-10-24) |
| Newest release of any channel | `v0.68.1-0.nightly.88` @ `e44df0290` (2026-11-04) |
| Tagged but **never published** | `v0.43.3`–`v0.43.8`, `v0.43.10-0.nightly.2`, and the three internal boundary tags `preserve-pre-boundary-v0.42.0-20260826`, `rewrite-v0.42.0-final`, `rewrite-v0.42.0-pre-cleanup` |

Consequence: a format change that only ever existed in `v0.43.3`–`v0.43.8` or in a nightly has
**no downloadable artifact**. It still matters for schema history (the commit is in the tree and a
nightly user may hold the bytes), but it cannot be a *release-derived* fixture. Nightlies and
betas are deduplicated into the epoch they belong to; the matrix never lists one row per nightly.

Reproduce the counts:

```bash
git tag -l | wc -l
gh release list --limit 100 --json tagName,isPrerelease \
  --jq '.[] | select(.isPrerelease==false) | .tagName'
comm -13 <(gh release list --limit 100 --json tagName --jq '.[].tagName' | sort) \
         <(git tag -l | sort)          # tagged, never published
```

## 2. Format epochs (deduplicated)

A *format epoch* is a maximal tag range in which the persisted bytes of one family are produced
by one schema/envelope. Byte-level additions of optional fields are recorded as **field epochs**
(§2.2, §4.3) rather than as new formats, because an older reader's behaviour is what a fixture
must pin.

### 2.1 Family epoch matrix

| Format ID | Family | First **published** writer | Last **published** writer | Envelope / schema | Migrations | Recovery point | Evidence status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `turboism.runtime.config` v1 (`config.json`) | 1 | `v0.42.0` @ `4f75113d7` | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON object, `format`+`schemaVersion:1` | none (closed schema, fail-closed) | none | Confirmed emitted |
| legacy `config.json` **v0** (no `schemaVersion`) | 1 | *pre-publication*; migrated by installers `v0.43.9`–`v0.44.1-0.nightly.11` | handled by installer normalisation from `v0.67.0` | JSON object without `schemaVersion`; root-level `cubismJvm`/`graalVmPath` | installer-side v0→v1 migration, then tolerant normalisation | none | **Evidence gap** (§14 G1) |
| `turboism-typed-config-v1` (`<doc>.cfg`) | 2 | `v0.42.0` @ `4f75113d7` | `v0.68.1-0.nightly.88` @ `e44df0290` | text envelope: header line, `schemaVersion=`, `revision=`, `count=`, then `base64url(key):base64url(value)` lines, sorted, trailing newline | per-plugin `ConfigMigration` chains | **`v0.68.1-0.nightly.88` only** (unreleased) | Confirmed emitted |
| `.recovery-points/<doc>.recovery/<v>-r<rev>-<sha256>.bak` | 2 | `v0.68.1-0.nightly.88` @ `e44df0290` (pre-release, 2026-11-03) | same | byte-identical copy of the `turboism-typed-config-v1` document | n/a | is the recovery point | Confirmed emitted, **never in a stable release** |
| plugin key-value `*.properties` | 3 | `v0.42.0` @ `4f75113d7` | `v0.68.1-0.nightly.88` @ `e44df0290` | `java.util.Properties.store` (`#Turboism plugin config`) | none | none | Confirmed emitted |
| first-party plugin private state (`state/`, `data/`, `cache/` per plugin) | 3 | `v0.42.0` @ `4f75113d7` | `v0.68.1-0.nightly.88` @ `e44df0290` | plugin-owned JSON/properties/text under a confined root | plugin-owned | none | Confirmed emitted per file; §5.2 |
| `turboism.plugin.pending` (`pending.json`, `failed-pending.json`) | 4 | `v0.42.0` @ `4f75113d7` | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON object, `schemaVersion:1` + random-UUID `revision` | none | none (journal is itself the recovery record) | Confirmed emitted |
| `catalog-state.json` (plugin directory v2) | 4 | `v0.67.0-beta.2` @ `7fea2a1f1` | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON object, `schemaVersion:2` | **none by design**: v1 is refused, not imported | none | Confirmed emitted |
| `cubism-installations.json` | 5 | `v0.42.0` @ `4f75113d7` | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON, `turboism.cubism.installation-state`, `schemaVersion:1` | tolerant key allow-list read | none | Confirmed emitted |
| `turboism.cubism.discovery-snapshot` (installer snapshot) | 5 | `v0.67.0` @ `7b52ef634` (commit `07d7471d9`, 2026-09-17) | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON, `schemaVersion:2` + `verifierSha256` | none | none (transient, digest-bound) | Inferred-only (§14 G4) |
| `installed-build.json` | 5 | `v0.67.0` @ `7b52ef634` (installer + ZIP assembly); runtime writer from `v0.68.1-beta.5` | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON `schemaVersion:1`; **two different field sets and three byte layouts** | none | backed up by the update journal | Confirmed emitted |
| `update-state.json`, `update-preferences.json` | 6 | `v0.44.0` @ `a8a81477c` | `v0.68.1-0.nightly.88` @ `e44df0290` | JSON object, `schemaVersion:1` | none (invalid → empty state + diagnostic) | none | Confirmed emitted |
| signed installation receipt index + immutable documents | 6 | `v0.68.1-beta.5` @ `93a7191e4` | `v0.68.1-0.nightly.88` @ `e44df0290` | index: `turboism-installed-receipt-v1`, `schemaVersion:1`; documents: signed `manifest.json` + `manifest.json.sig` (`turboism-update-manifest-sig-v1`) | none | rollback journal `journal.json` | **Verification** Confirmed; **issuance** impossible offline (§11, §14 G3) |
| `journal.json` / `pending.json` / `lease/*` (framework update transaction) | 6 | `v0.68.1-beta.5` @ `93a7191e4` | `v0.68.1-0.nightly.88` @ `e44df0290` | `turboism-update-journal-v1`, `turboism-framework-pending-v1`, `turboism-launch-lease-v1` | none | journal is the recovery record | Confirmed emitted |
| signed plugin catalog + signature envelope (network payload, cached in `catalog-state.json`) | 6 | `v0.67.0-beta.2` @ `7fea2a1f1` | `v0.68.1-0.nightly.88` @ `e44df0290` | `turboism.plugin.catalog` / `turboism.plugin.catalog.signature`, both `schemaVersion:2` | none | n/a | **Verification** Confirmed; **issuance** impossible offline (§14 G3) |

The `Format ID` column is the value persisted inside the document where one exists; for the
families without an in-band format string the identifier is the path + envelope name.

### 2.2 `config.json` field epochs

`config.json` never changed its `schemaVersion` (always `1`) and never migrated. It changed by
**admitting new optional fields**, and its schema is *closed*: an unknown field makes the whole
document unreadable (§3.4). Per-release field admission, derived from
`RuntimeConfigValidator`'s allow-lists:

| Release | Top-level additions | `launcher` additions |
| --- | --- | --- |
| `v0.42.0` (baseline) | `worktreeId`, `pluginDirs`, `disabledPlugins`, `logLevel`, `maxLogStorageMiB`, `locale`, `safeMode`, `diagnostics`, `hooks`, `launcher` | `cubismJvm` |
| `v0.43.3` (tagged, **unpublished**; first published release carrying it: `v0.43.9`) | — | `graalVmPath` |
| `v0.43.10` | `useTextIcon` | — |
| `v0.67.0-beta.1` (→ `v0.67.0`) | `textureAtlas`, `reduceAutoBackup`, `meshTriangulationHashFix`, `atlasTileBbox`, `atlasCacheReuse`, `meshTriangulationEdgeIndex` | `memoryProfile`, `zgc`, `modelUpdateSkip`, `incrementalUpdate`, `uniformLocationCache`, `uploadElision`, `mesaGlThread`, `inputPathElision` |
| `v0.68.0-beta.1` (→ no stable) | — | `meshInterpolation` |
| `v0.68.1-beta.1` (→ no stable) | — | `shaderUniform`, `matrixScratch`, `imageArchiveReuse`, `floatArrayParseCache`, `textureUploadPreparation`, `warpPositionProjection` |
| `v0.68.1-0.nightly.87` | — | `incrementalUpdateAffecters` |
| `v0.68.1-0.nightly.88` (current) | — | `clipMaskDeduplication`, `clipMaskScissorClear`, `extendedInterpolationIncrementalSetup`, `modelInstanceCoalesce`, `inspectorPropertyBypass`, `reinitPairElision` |
| `v0.70.0` (unreleased) | `guide` | — |

`hooks.startup` already carries `separateExportSaveDirectory` in `v0.42.0` and does not change after.
`textureAtlas` has exactly `algorithmId` and `parallel`.

Reproduce (per tag, no working tree needed):

```bash
for t in $(git tag -l 'v*' | sort -V); do
  printf '%-28s ' "$t"
  git cat-file -p "$t:runtime/src/main/java/dev/turboism/core/schema/runtimeconfig/RuntimeConfigValidator.java" \
    | sed -n '/ALLOWED_FIELDS = Set.of(/,/);/p' | grep -o '"[a-zA-Z.]*"' | tr '\n' ' '
  echo
done
```

### 2.3 `config.json` byte-layout epochs — four writers, four layouts

Byte identity is a property of **one writer**, not of the format. Four released writers emit
`config.json` and they disagree on whitespace, key order and trailing newline:

| Writer | Release range | Layout |
| --- | --- | --- |
| `RuntimeConfigRepository#writeLocked` (runtime) | all | Jackson `writerWithDefaultPrettyPrinter()`: 2-space indent, `"key" : value`, arrays inline as `[ "plugins" ]` (empty `[ ]`), **no** trailing newline |
| `ConfigMerge#write` (Java installer listener) | `v0.42.0`+ | `BoundedJson.serialize` **compact** + `"\n"` |
| `configure_turboism.ps1` (PowerShell configurator) | `v0.42.0`+ | PowerShell `ConvertTo-Json -Depth 16`, UTF-8 without BOM |
| `packaging/windows-installer/config.template.json` (shipped seed) | all | 2-space pretty, `"key": value`, **no space before colon**, **with** a trailing newline |

Proof that the layouts differ is committed: `validation/atlas-image-tile-patch/home-config-atlasoff.json`
shows the runtime's `"format" : "turboism.runtime.config"` / `[ "plugins" ]` shape, while
`packaging/windows-installer/config.template.json` shows the installer's `"format": "..."` shape.

**The existing `runtime-config-v1` fixture is not the runtime writer's byte layout.** It is
hand-written read-compatible JSON with `"format": "turboism.runtime.config"` and inline arrays —
that is the *installer template* shape, not the Jackson shape. It is a legitimate
read-compatibility fixture, but it is not evidence of any released writer's output and must not be
described as such. #110 added release-derived fixtures for both layouts
(`runtime-config-installer-template-0-42-0`/`-0-67-4` and
`runtime-config-runtime-writer-0-42-0`/`-0-67-4`, §13.1) while leaving this synthetic record
byte-identical; the writer-compatibility gap is therefore closed (§14 G2). #69-closure extended the
same corpus to the remaining published families (typed-config, plugin properties, plugin journal,
catalog state, installed build, update journal) — see §13.1 batch 2.

### 2.4 Typed config document epochs

The envelope (`turboism-typed-config-v1` + `schemaVersion=`/`revision=`/`count=` + base64url lines)
is **unchanged across every published release**. What changed is **which documents exist** and their
per-document `schemaVersion`. The typed config root is `config/<pluginId>/`, so the owning plugin id
is part of a document's identity.

| Document | Owning plugin id | Schema versions observed in published releases |
| --- | --- | --- |
| `texture-atlas/layout.cfg` | `dev.turboism.plugin.atlas-maxrects-bssf` | `3` in `v0.42.0`–`v0.44.1-0.nightly.11`; `4` from `v0.67.0` |
| `texture-atlas-dalsoo/layout.cfg` | `dev.turboism.plugin.atlas-dalsoo-polygon` | `2` from `v0.67.0` |
| `ui-theme/selection.cfg` | `dev.turboism.plugin.uitheme` | `1` in every release |
| `backup/webdav.cfg` | `dev.turboism.plugin.backup` → **`dev.turboism.plugin.webdav` at `v0.44.0`** | `1` in every release |
| `bounding-box/features.cfg` | `dev.turboism.plugin.bounding-box` | `1` in `v0.42.0`–`v0.44.1-0.nightly.11`; **document gone from `v0.67.0`** |
| `project-panel/state.cfg` | `dev.turboism.plugin.project-panel` | `1` in `v0.42.0`–`v0.44.1-0.nightly.11`; **document gone from `v0.67.0`** |

Reproduce:

```bash
for t in v0.42.0 v0.44.0 v0.67.4; do
  echo "== $t"
  git grep -l 'new ConfigSchema(' "$t" -- 'plugins/*/src/main/java/**' | sed "s|^$t:||" | while read -r f; do
    printf '  %-32s %-38s v=%s\n' \
      "$(git cat-file -p "$t:$f" | grep -oE '"[a-z0-9-]+/[a-z0-9-]+[.]cfg"' | head -1 | tr -d '"')" \
      "$(basename "$f")" \
      "$(git cat-file -p "$t:$f" | tr '\n' ' ' | grep -oE 'new ConfigSchema[(][^)]*' | grep -oE '[0-9]+' | head -1)"
  done
done
```

Two consequences that matter more than any envelope detail:

1. **A plugin id rename used to orphan its documents — closed.** `dev.turboism.plugin.backup` was
   renamed to `dev.turboism.plugin.webdav` in `v0.44.0` keeping the same `CONFIG_ID`
   (`backup.webdav`) and the same `CONFIG_PATH` (`backup/webdav.cfg`). Because the root is keyed by
   plugin id, the old document stayed at `config/dev.turboism.plugin.backup/backup/webdav.cfg` while
   the new plugin reads `config/dev.turboism.plugin.webdav/backup/webdav.cfg`, and the retired JAR is
   deleted on upgrade (`packaging/retired-plugins.txt`). `LegacyHomeMigration` now copies every
   document of a renamed id to the current id's config directory when the destination is absent; the
   retired document is never moved, rewritten or deleted, so it stays the recoverable pre-upgrade
   image. Recorded renames are the `RENAMED_PLUGIN_IDS` map; a symlinked document is skipped rather
   than followed. Closed gap G10, verified by `LegacyHomeMigrationTest`.
2. **Removed plugins leave documents with no reader.** `bounding-box/features.cfg` and
   `project-panel/state.cfg` have no registered schema after `v0.67.0`, so they are never read, never
   migrated and never deleted. They are inert, but they are also invisible user data that no support
   window covers.

Neither consequence is a format change, which is exactly why the inventory records them: a
version→writer→reader map that lists only schemas would miss both.

## 3. Family 1 — legacy global `config.json` and the settings facades

### 3.1 Path and ownership

`<turboism-home>/config.json`, at the home root, not under `config/`. `TurboismHomeLayout#globalConfig`
is the single authority for the path.

The "launcher/JVM settings" services named in the issue are **not** separate files. Each one is a
typed facade over this same document:

| Facade | Field written |
| --- | --- |
| `RuntimeSettingsFileService` | `logLevel`, `maxLogStorageMiB` |
| `CubismJvmSettingsFileService` | `launcher.cubismJvm`, `launcher.graalVmPath` |
| `OptimizationSettingsFileService` | every native-optimization key the catalogue declares: `launcher.<key>` for the 22 launcher rows, `<key>` at the root for `atlasCacheReuse`, `atlasTileBbox`, `meshTriangulationHashFix` and `meshTriangulationEdgeIndex` |

All of them delegate to `RuntimeConfigRepository#update`, so there is exactly one runtime write
path and one runtime read path for this family.

### 3.2 Writers and readers

| Direction | Class#method | Notes |
| --- | --- | --- |
| Runtime writer | `RuntimeConfigRepository#writeLocked` | temp sibling `.config-<uuid>.tmp` + `ATOMIC_MOVE`/`REPLACE_EXISTING`; 64 KiB cap; symlink chain rejected; `revision`-free (no version counter) |
| Runtime seeder | `RuntimeConfigRepository#read` → `defaults()` | a **missing** file is created from `defaults()` through the same write path; there is deliberately no in-memory fallback |
| Runtime reader | `RuntimeConfigRepository#readLocked` | bounded read, `NOFOLLOW_LINKS`, strict duplicate detection is **not** enabled here (see §3.4) |
| Runtime validator | `RuntimeConfigValidator#validate` (write) / `#validateForRead` (read) | read mode tolerates one unsupported `locale` and one unsupported `launcher.memoryProfile` |
| Installer writer | `packaging/java-installer/listener-src/…/ConfigMerge#write` | compact JSON + `"\n"`, temp + `ATOMIC_MOVE`, **no backup of the replaced bytes** |
| Installer reader/migrator | `ConfigMerge#normalizeToCurrent` (from `v0.67.0`) / `#migrateToCurrent` (`v0.42.0`–`v0.44.1-0.nightly.11`) | see §3.3 |
| PowerShell configurator | `packaging/windows-installer/configure_turboism.ps1` | validates then rewrites via `ConvertTo-Json`; records dropped field names |
| Shipped seed | `packaging/windows-installer/config.template.json` | installed verbatim as `config.json` by `assemble-release.sh` |

### 3.3 Three released installer policies for the same document

`config.json` has been rewritten by installers under three different policies, none of which is the
runtime's policy:

| Release range | Behaviour on a non-v1 / unknown-field document |
| --- | --- |
| `v0.42.0` – `v0.43.2` | **no schema concept**: the document is parsed and preserved; only installer-owned fields are forced (`worktreeId`, `pluginDirs`, `disabledPlugins` becomes the sorted union of already-disabled ids and bundled-but-unselected ids). Unknown fields **survive** the install. |
| `v0.43.9` – `v0.44.1-0.nightly.11` | **explicit v0→v1 migration** in both installer paths (`ConfigMerge#migrateToCurrent` and the PowerShell configurator): a document without `schemaVersion` is v0; only `V0_FIELDS` are allowed; root-level `cubismJvm`/`graalVmPath` are lifted into `launcher`; duplicates are rejected; the result is validated as v1. Any other schema, unknown field or invalid value **aborts the install**. |
| `v0.67.0` – current | **tolerant normalisation** in both paths (`ConfigMerge#normalizeToCurrent`, commit `ad2e0f3e9`, 2026-09-27; PowerShell equivalent): a document that fails the v1 contract is rebuilt field by field; recognised valid fields are carried forward (including legacy root-level JVM fields), every unknown field or invalid value is **dropped**, then the installer plugin selection is applied. Only a document whose `format` names a different product is refused. |

All three are atomic-replace-only and leave the original bytes intact when they fail; none keeps a
durable pre-migration image. `v0.43.9`–`v0.44.1-0.nightly.11` is therefore the only release range in
which a **`config.json` schema version migration** ever existed, and it ran inside the installers,
not the runtime. Reproduce the three policies:

```bash
for t in v0.42.0 v0.43.9 v0.44.0 v0.67.0; do
  printf '%-10s listener-legacy=%-3s listener-normalize=%-3s ps-legacy=%-3s ps-normalize=%s\n' "$t" \
    "$(git cat-file -p $t:packaging/java-installer/listener-src/dev/turboism/installer/ConfigMerge.java | grep -ci 'legacy')" \
    "$(git cat-file -p $t:packaging/java-installer/listener-src/dev/turboism/installer/ConfigMerge.java | grep -ci 'normalized to v1')" \
    "$(git cat-file -p $t:packaging/windows-installer/configure_turboism.ps1 | grep -ci 'legacy')" \
    "$(git cat-file -p $t:packaging/windows-installer/configure_turboism.ps1 | grep -ci 'dropped')"
done
```

### 3.4 Read behaviour a fixture must pin

- Unknown top-level/`hooks`/`launcher`/`textureAtlas` field → `RUNTIME_CONFIG_UNKNOWN_FIELD`, the
  read throws, **the file is not modified**. The schema is closed; there is no unknown-field
  preservation in `config.json` (unlike `typed-config`, which is closed in a different way, §4.3).
- Wrong `format`, wrong `schemaVersion`, oversize, symlink, non-object → `RUNTIME_CONFIG_INVALID` /
  `_FILE_REJECTED` / `_UNREADABLE`; again fail-closed without a write.
- A tolerated value (`locale`, `launcher.memoryProfile`) is removed from the **returned** document
  with a diagnostic. From `v0.42.0` through `v0.68.1-beta.11` an unrelated `update()` then wrote the
  document back **without** that value — silent loss, filed as #85 and fixed by commit `82561575f`
  (`restoreToleratedValues`, 2026-11-03). The fix exists **only** in
  `v0.68.1-0.nightly.88` and later; every stable release, including `v0.67.4`, loses the value.
- `locale` tolerance exists from `v0.42.0`; `launcher.memoryProfile` tolerance exists from
  `v0.67.0-beta.1`. So the memory-profile loss window is `v0.67.0-beta.1`–`v0.68.1-beta.11`.

### 3.5 Sensitive fields

`locale`, `graalVmPath` (may contain a user profile path), `diagnostics` and `pluginDirs`
(may contain user-chosen directories), `disabledPlugins` (reveals installed plugins). No
credentials. A fixture must use synthetic values only.

### 3.6 Evidence status

Confirmed emitted for v1 in every release. The v0 legacy shape is **Inferred-only from the reader**
(§14 G1): the migration code is the only surviving description, no v0 document is tagged, and the
v0 writer predates `v0.42.0`.

## 4. Family 2 — typed plugin configs and recovery points

### 4.1 Path and envelope

`<turboism-home>/config/<pluginId>/<relativePath>` (root supplied as `paths.configDir()` by
`KernelPluginServicesFactory`). Envelope, exactly as produced by `TypedConfigDocumentStore#encode`:

```
turboism-typed-config-v1
schemaVersion=<n>
revision=<n>
count=<n>
<base64url-no-padding(key)>:<base64url-no-padding(value)>
… (count lines, sorted by decoded key, ascending)
```

Rules that a hand-written sample must satisfy or the reader refuses it
(`TypedConfigDocumentStore#parse`):

- the last line is empty (the encoded document always ends with `\n`);
- `schemaVersion ≥ 1`, `revision ≥ 0`, `count ≥ 0`, and `lines.length == count + 5`;
- exactly one `:` per entry, keys non-blank and unique;
- **re-encoding the parsed document must reproduce the input byte for byte** ("noncanonical"
  otherwise) — so unsorted keys, padded base64 or a stray blank line are rejected, not normalised;
- UTF-8 only, malformed input rejected; 1 MiB cap; files are owner-only (`rw-------` on POSIX,
  restricted ACL on Windows).

Value encoding (`TypedConfigCodecSupport#encode`): booleans as `true`/`false`, integers decimal,
strings verbatim (length-bounded by the codec id), enums by **constant name**, string lists in an
escaped list encoding. Unknown codec ids are rejected at registration.

### 4.2 Writers and readers

| Direction | Class#method |
| --- | --- |
| Public writer | `RuntimeTypedPluginConfigRegistry#write` → `TypedConfigDocumentStore#writeAtomic` |
| Public reader | `RuntimeTypedPluginConfigRegistry#read` → `#loadCurrent` → `TypedConfigDocumentStore#read` |
| Migration runner | `RuntimeTypedPluginConfigRegistry#migrate` (per-schema `ConfigMigration` chain) |
| Schema registration | `RuntimeTypedPluginConfigRegistry#registerSchema` |
| Recovery capture | `TypedConfigDocumentStore#recordRecoveryPoint` (called from `#loadCurrent` and `#loadCurrentForWrite`) |

Registered schemas in the current tree (four documents, §2.4) with their migration chains:
`texture-atlas/layout.cfg` registers `V1_TO_V2`, `V2_TO_V3`, `V3_TO_V4`; `texture-atlas-dalsoo/layout.cfg`
registers `V1_TO_V2`; `ui-theme/selection.cfg` and `backup/webdav.cfg` register none.

### 4.3 Migration semantics a fixture must pin

- `stored.schemaVersion > schema.version()` → **refuse**: read returns
  `DEFAULT_FUTURE_VERSION` / `FUTURE_SCHEMA_VERSION`, write returns `PERSISTENCE_FAILED`; the file is
  not touched.
- a missing step in the chain → `MIGRATION_GAP`; a migration that throws → `MIGRATION_FAILED`; a
  migration whose output version or encoded values are invalid → `MIGRATION_FAILED`. In all three
  cases the original bytes survive.
- **read-time migration is a write**: it atomically replaces the document with the migrated one at
  `revision + 1`, and in the current tree it first captures a recovery point (§4.4). A second read
  does not migrate again.
- **unknown keys fail closed**: `validEncodedValues` requires every stored key to exist in the
  current schema *and* decode. A document carrying a key the schema dropped is refused, so removing
  a key requires an explicit migration step that drops it — `texture-atlas/layout.cfg` V3→V4 is the
  approved example (it retains only `layout-mode` and hands the legacy algorithm/parallel choice to
  the runtime-owned selection instead of silently discarding it).

### 4.4 Recovery points — released coverage

`RECOVERY_POINT_RETENTION = 3`, location
`<config root>/.recovery-points/<document path>.recovery/<schemaVersion>-r<revision>-<sha256>.bak`,
contents are the pre-migration document bytes verbatim. Landed in `0f03f6c7a` (2026-11-03, #84) and
shipped **only** in `v0.68.1-0.nightly.88`. Verified per tag:

```bash
for t in v0.44.0 v0.67.4 v0.68.1-beta.11 v0.68.1-0.nightly.88; do
  printf '%-28s ' "$t"
  git cat-file -p "$t:runtime/src/main/java/dev/turboism/config/RuntimeTypedPluginConfigRegistry.java" \
    | grep -c recordRecoveryPoint
done
```

Every published release up to and including `v0.68.1-beta.11` — and therefore every **stable**
release — replaces the only copy of a migrated typed config with **no durable pre-upgrade image**.
That is the real destructive transition #111 has to name, and it is why a "recovery point
preservation" fixture can only be verified against current code, not against release behaviour.

### 4.5 Sensitive fields

`backup/webdav.cfg` persists `url`, `username`, **`password`**, `remote.path`, `verify.tls`,
timeouts and retry policy — the only typed config that carries a credential. `ui-theme/selection.cfg`
carries a theme id list; the atlas documents carry layout policy only. Any committed
`backup/webdav.cfg` sample must use a synthetic host and a placeholder secret, and must be labelled
as de-identified.

### 4.6 Evidence status

Envelope: Confirmed emitted in every release. Recovery points: Confirmed emitted, unreleased
channel only. Migration chains: Confirmed emitted for `texture-atlas/layout.cfg` v3→v4 (a real
`v0.42.0`–`v0.44.x` document upgraded by a `v0.67.0`+ build); v1→v2 and v2→v3 are
**Inferred-only** (§14 G5) because no published release wrote schema 1 or 2 of that document.

## 5. Family 3 — first-party plugin private state and key-value config

### 5.1 Legacy key-value config (`PluginConfigRegistry`)

`RuntimePluginConfigRegistry` writes `java.util.Properties` under the plugin's **data** directory,
via `Properties.store(writer, "Turboism plugin config")`. It is permission-gated twice (permission +
an explicit read/write scope) and all I/O runs on one bounded lane with a 1 s wait, so a wedged
filesystem degrades to an empty read (diagnostic) or a thrown `PluginConfigException` (write).
`Properties.store` emits a timestamp comment line, so **two writes of the same values are not
byte-identical**; a fixture for this family must pin parsed semantics, not bytes.

First-party plugins still using it: `external-psd-edit` (`external-editor.properties`),
`demo` (`demo/config.json` — a properties file despite the name), plus the core shell's
`config/turboism.core/save-directory-history.properties` (`projectRecentDirectory`,
`exportRecentDirectory`; the shell is admitted under the reserved `turboism.core` identity).

### 5.2 Plugin private state (`PluginStorage` / `StorageRoot`)

`RuntimePluginStorage` is a permission facade over `ConfinedStorageBackend`, which is confined to
three roots per plugin (`TurboismHomeLayout#plugin`): `data/<pluginId>`, `state/<pluginId>`,
`cache/<pluginId>`. Writes go through `writeBytesAtomicLocked` → `StorageFiles#writeDurably`
(`dev.turboism.core.io.DurableFile`, create-once + `force`) → `StorageFiles#moveAtomically`
(atomic replacement, fail-closed when the filesystem cannot do it). There is **no format layer**:
the bytes are whatever the plugin encodes.

Persisted first-party state found in the current tree:

| Plugin | Path | Root | Format |
| --- | --- | --- | --- |
| `parameter-lock` | `locks/<sha256>.json` | state | plugin JSON |
| `parameter-sync` | `parameter-sync/<hash>.json` | state | plugin JSON |
| `palette-label-style` | `palette-label-style/colors-<projectId>.properties` | state | properties |
| `scene-palette-enhancer` | `manual-order-<scopeId>.txt` | state | line list |
| `ui-theme` | `themes/<themeId>.zip` (+ `theme.properties`, `colors.properties`, `generator-metadata.properties` inside the package) | data | ZIP package |
| `recent-preview` | cache index + entries | cache | plugin JSON, **discardable** |
| `mcp` | `mcp-connection.json` | state | plugin JSON |
| `acp` | `session-index/<digest>.json` | state | plugin JSON |

Runtime-owned, plugin-adjacent state files that belong to this family's threat model:

| File | Writer | Reader | Notes |
| --- | --- | --- | --- |
| `state/runtime/project-work-time.json` | `RuntimeProjectWorkTimeService#persist` (`DurableFile#replace`) | `RuntimeProjectWorkTimeService#loadPersistedProjects` | `turboism.project-work-time/v1`; closed root (`format`, `projects`), 1 MiB read cap, `NOFOLLOW_LINKS`, strict duplicate-key/trailing-token parse; a save that follows an unreadable or forward-written document keeps one `project-work-time.json.corrupt.bak` image first |
| `state/runtime/keybindings.properties` | `RuntimeKeybindingService`/`KeybindingStore` | same | properties, user key overrides |
| `state/turboism.core/auto-backup-baseline.properties` | `CoreShell#saveBackupBaseline` | `CoreShell` | **write-once**: an existing file is never overwritten, so a crashed session's baseline survives |
| `<jvm tmpdir>/turboism-theme-runtime.properties` | `ThemeRuntimeProperties#write` | `ThemeRuntimeProperties#path` | **outside the home**, non-atomic, deliberately ephemeral; never a fixture candidate |

### 5.3 Evidence status

Confirmed emitted per file, but each is plugin-owned and versioned only by the plugin. Fixtures are
only meaningful for files whose parser is in `runtime/` or in a first-party plugin that declares a
schema; the rest should be covered by behaviour tests, not by a golden byte corpus (§13.2).

## 6. Family 4 — plugin install / enable / pending journals and distribution state

### 6.1 `pending.json` / `failed-pending.json`

`<turboism-home>/state/runtime/plugin-management/pending.json` and `failed-pending.json`.
Writer/reader: `PendingPluginOperations`. Both files use the same in-band format id
`turboism.plugin.pending`, `schemaVersion:1`, a **random-UUID `revision`** (so re-staging
byte-identical operations still changes the document — ABA protection) and an `operations` array
sorted by `pluginId`; each operation carries `type` (`INSTALL`/`UNINSTALL`), `pluginId`,
`stagedJar`, `version`, `rawSha256`, `descriptorSha256`, `jarSha256`, `jarSize`. Bytes are Jackson
pretty-printed, capped at 64 KiB, written through an owner-only `CREATE_NEW` channel with
`force(true)` then an atomic move. `failed-pending.json` is the same journal moved aside, not a
second format. `CoordinatedPluginUpdateService` and
`PluginUpdateGate` treat a present `pending.json` or `journal.json` as "recovery required" and refuse
new staged work rather than racing an interrupted transaction. The journal **is** the recovery
record; there is no second copy.

### 6.2 `catalog-state.json`

`<turboism-home>/state/runtime/plugin-management/catalog-state.json`, `SCHEMA_VERSION = 2`,
`RELATIVE_PATH` constant in `PluginCatalogStateStore`. Fields: `catalogVersion`, `publishedAt`,
`catalogSha256`, `etag`, `lastAutomaticAttempt`, `cachedCatalogBase64`, `cachedSignatureBase64`,
`stagedUpdates`. Up to 16 MiB, strict duplicate detection, `FAIL_ON_TRAILING_TOKENS`.

A wrong `schemaVersion` is an error, not a migration: the class comment states v1 state is "never
imported or trusted", and the class first appeared (commit `740e5a47c`, 2026-10-13) already at
version 2 — **no published release ever wrote `catalog-state.json` v1** (§14 G6). A corrupt or
unreadable file yields `PLUGIN_CATALOG_STATE_CORRUPT` + empty state; the previous file is left in
place.

`cachedCatalogBase64` / `cachedSignatureBase64` hold the signed catalog payload and its envelope —
this file is where Family 6 bytes land on disk (§8.4).

### 6.3 Evidence status

Both Confirmed emitted. Neither has a migration, so the compatibility question is only
"accept / refuse / preserve original", which is testable offline.

## 7. Family 5 — launcher, bootstrap and install state

### 7.1 `cubism-installations.json`

`<turboism-home>/cubism-installations.json`, format `turboism.cubism.installation-state`,
`schemaVersion: 1`. Writer `Write-CubismInstallationState` in
`packaging/windows-installer/cubism-launch-common.ps1` (compact JSON, UTF-8 no BOM, temp + atomic
replace with a `replacement-backup` that is deleted only after a successful replace); reader
`Read-CubismInstallationState` with a **required-key** check (`format`, `schemaVersion`,
`installations`, `managedShortcuts`), an optional-key allow-list (`launchMode`, `shortcutTakeovers`,
`managedShortcutHashes`, `batIntegrations`) and an **exact** `schemaVersion` equality check. Entries
carry `root`, `version`, `selected`; shortcut records carry `path`, `backupPath`, `originalSha256`,
`managedSha256`, `root`, `variant`, `status`. Caps: 64 KiB, 256 installations, 512 shortcuts.

### 7.2 Installer discovery snapshot

Format `turboism.cubism.discovery-snapshot`, `schemaVersion: 2`, fields
`{format, schemaVersion, verifierSha256, installations[{root, version, status, applicationSha256,
javaSha256, d3dName}]}`. Written by the installer scan, read by `Read-CubismInstallerSnapshot`,
which additionally requires the caller-supplied SHA-256 of the bytes **and** that
`verifierSha256` equals the SHA-256 of the installed `turboism-agent.jar`. It is a transient
handoff, not user state.

### 7.3 `installed-build.json`

`<turboism-home>/installed-build.json`, `schemaVersion: 1`, explicitly **excluded** from the update
payload (`packaging/framework-update-layout.json` → `excludedStateFiles`). Three writers with
different field sets:

| Writer | Bytes |
| --- | --- |
| `assemble-release.sh` (ZIP) | `{"schemaVersion":1,"variant":"full","version":"<v>"}` + `\n` |
| `installer.nsi` | same shape with `$\r$\n` (CRLF) |
| `FrameworkUpdateApplier` / `FrameworkUpdateHelper#identity` | Jackson pretty, adds `channel` and `buildNumber` |

### 7.4 Framework update transaction state

| Location | Contents | Format |
| --- | --- | --- |
| `state/runtime/update/helper-transaction.lock` | cross-process transaction lock (parent of the work dir) | lock file |
| `state/runtime/update/helper/lease/` | launch/apply lease entries, ≤256 files, ≤4096 bytes each, `{kind: LAUNCHING or USING, pid, started, root, token}` | `turboism-launch-lease-v1` |
| `state/runtime/update/helper/pending.json` | descriptor of an update staged for the next controlled launch | `turboism-framework-pending-v1` |
| `state/runtime/update/helper/journal.json` | rollback journal: `format`, `state`, `version`, `root`, `receiptTracked` | `turboism-update-journal-v1` |
| `<user home>/.cache/turboism/self-update/{resources,stages}` | download cache, quota-bounded, `owner` marker + `writer.lock` + staged payload | update objects and staged `manifest.json` |

`FrameworkUpdateHealth#workDirectory` is the single authority for `state/runtime/update/helper`, and
`FrameworkUpdateCoordinator` refuses any work dir that is not that exact path. The download cache is
deliberately **outside** the installation (`RuntimeFrameworkUpdates` rejects a cache under the root),
so it is per-user discardable state and never a fixture candidate. `FrameworkUpdateApplier` backs up
the previous receipt index and `installed-build.json` before replacing payload files.

The pending descriptor is explicitly **untrusted state**: `FrameworkUpdatePendingTask` carries no
signature authority, no keys and no hashes, and the helper re-authenticates the staged manifest, the
old receipt and every staged payload byte before replacing anything.

### 7.5 Evidence status

`cubism-installations.json`: Confirmed emitted, but the writer is PowerShell and its output depends
on `ConvertTo-Json`, so a fixture is only reproducible on a Windows/PowerShell host or by freezing a
recorded document (§14 G7). Discovery snapshot: Inferred-only for the exact bytes. `installed-build.json`:
Confirmed emitted, three layouts.

## 8. Family 6 — signed receipts, catalog and activation metadata

### 8.1 Two independent authorities

| Authority | Trusted keyring resource | Envelope format | Revocation |
| --- | --- | --- | --- |
| Framework self-update | `/META-INF/turboism/framework-update-trusted-keys.json` (`schemaVersion:1`, `keys{}`, `revoked[]`, ≤8 keys, ≤32 revocations) | `turboism-update-manifest-sig-v1` | explicit `revoked` list |
| Plugin directory | `/META-INF/turboism/plugin-catalog-trusted-keys.json` (present in this repo, public keys only) | `turboism.plugin.catalog.signature` | via catalog publication |

`FrameworkUpdateTrust` states the separation explicitly: plugin catalog keys never authorize agent
replacement, and `FrameworkUpdateResources` reads the keyring **only** from the defining runtime
JAR, never from a stage-supplied or feed-supplied file. When the keyring is absent (a development
class-directory build has none), installation is disabled rather than borrowing another key.

### 8.2 Receipt layout

`<turboism-home>/state/runtime/update/`:

- `installed-receipt.json` — the mutable index: `{format: "turboism-installed-receipt-v1",
  schemaVersion: 1, layout: "windows-standard-zip", manifestSha256, rootIdentity}`;
- `receipts/<sha256>/manifest.json` + `manifest.json.sig` — immutable signed documents, appended
  only; a crash may leave harmless unreferenced directories;
- the index is advanced by the applier's rollback journal, never by `FrameworkUpdateReceipt`.

Verification (`FrameworkUpdateReceipt#prepare` → `UpdateManifestVerifier#authenticate`) happens on
**cloned** caller bytes before persistence, so the authorized array and the persisted array cannot
diverge. `initialize` refuses to replace a different existing receipt
(`"An existing different receipt must not be silently replaced"`) and re-verifies every manifest
entry against the on-disk payload before adopting it.

### 8.3 Issuance — release side only

| Document | Issuer | Command |
| --- | --- | --- |
| framework manifest + detached signature + object inventory | protected release signing job | `scripts/release/prepare-framework-release.py sign --private-key-env <VAR> …` |
| payload inventory | release pipeline | `{format: "turboism.framework-payload-inventory", schemaVersion: 1, layout, identity, files, excludedPaths}` |
| incremental admission | release pipeline | `{format: "turboism.incremental-candidate", schemaVersion: 1, …}` |
| updates service manifests | release pipeline | `scripts/release/build-updates-manifests.py` |

The signer's own contract states the key "arrives through the environment of a single step and is
never written into the workspace, a Gradle invocation, a log line or an artifact". Signature
envelope bytes are
`{format, schemaVersion:1, algorithm:"Ed25519", keyId, manifestSha256, signature: base64}` with a
64-byte signature, produced by `openssl pkeyutl -sign -rawin` over the canonical manifest bytes and
immediately re-verified with `-verify`.

### 8.4 What lands on disk from this family

`catalog-state.json#cachedCatalogBase64` and `#cachedSignatureBase64` are the only Family 6 bytes a
normal installation persists without a framework update; the framework receipt exists only after an
`--initialize-receipt` bootstrap or a completed update. Both are **signed evidence**, and the rules
in §11 apply to any test that touches them.

### 8.5 Evidence status

Verification paths: Confirmed emitted and offline-testable **with a reproducible test key**
(§11.3). Real historical receipts and real catalog signatures: **Evidence gap** (§14 G3) — issuing
them requires the protected private key, and re-issuing historical evidence is forbidden.

## 9. Sensitive field inventory

| Format | Fields that must be synthetic in any committed sample |
| --- | --- |
| `config.json` | `locale`, `graalVmPath`, `pluginDirs`, `diagnostics`, `worktreeId`, `disabledPlugins` |
| `backup/webdav.cfg` | `url`, `username`, `password`, `remote.path` |
| `ui-theme/selection.cfg` | theme ids (may embed a project name) |
| `cubism-installations.json` | `root`, `shortcutPath`, `backupPath` (contain user names and installation paths), `version` |
| discovery snapshot | `root`, `applicationSha256`, `javaSha256` (identify a licensed host build) |
| `installed-build.json` | `version`, `buildNumber`, `channel` (a fixture should use a real historical value, not a user's) |
| `save-directory-history.properties` | `projectRecentDirectory`, `exportRecentDirectory` |
| `auto-backup-baseline.properties` | none, but it sits next to user paths |
| plugin private state | any project id, path, parameter name or lock file name |
| receipts / catalog | `rootIdentity`, `keyId`, `signature`, `manifestSha256` — **never** a real signature |
| `<user home>/.cache/turboism/self-update/**` | out of corpus scope: per-user download cache and staging area outside the home |

No format in this inventory stores a password outside `backup/webdav.cfg`, and none stores a
credential in plaintext outside that file. No licensed Cubism asset is persisted by any of them:
`cubism-installations.json` and the discovery snapshot store **paths and digests of an installed
host**, never host content.

## 10. Safe generation and de-identification guide

The corpus must be generated by a released production writer. Two methods are acceptable; a third
(hand-writing bytes) is acceptable only for *synthetic* fixtures, never for a release-derived one.

### 10.1 Method A — released binary writer (preferred)

The release asset contains `turboism-agent.jar` at the archive root
(`packaging/framework-update-layout.json` → `frameworkRootFiles`), and the public writer entry
points are constructible from it in every release:

- `RuntimeConfigRepository(Path home, Consumer<String>)`, `read()`, `update(UnaryOperator)`,
  `static defaults()` — public since `v0.42.0`;
- `RuntimeTypedPluginConfigRegistry(PluginConfigRegistry legacy, String pluginId, Path configRoot,
  Set<String> permissions, RuntimePluginTaskScheduler tasks, DisposableScope scope)` plus
  `registerSchema` / `read` / `write` — public since `v0.42.0`.

```bash
V=0.67.4                       # the release whose writer must produce the bytes
gh release download "v$V" --pattern "turboism-$V.zip" --dir "$PWD/release-$V"
unzip -p "release-$V/turboism-$V.zip" turboism-agent.jar > "agent-$V.jar"

# A 20-line driver calling only the public entry points above, compiled against agent-$V.jar
javac -cp "agent-$V.jar" -d driver Driver.java
java -cp "agent-$V.jar:driver" Driver "$PWD/out-$V"
sha256sum "out-$V/config.json"
```

The driver is a *throwaway*: it is not committed, it lives outside the repository, and the fixture
records the command that produced it. For a release with the legacy `-full`/`-lite` asset naming
(`v0.42.0`–`v0.44.x`) download `turboism-$V-full.zip` instead.

Advantage over checking out the tag: the bytes provably come from the *published* artifact, not from
a rebuild of the same source, and no historical toolchain has to work.

### 10.2 Method B — tagged source worktree

```bash
git worktree add --detach /tmp/turboism-gen "v$V"
# add ONE throwaway generator under runtime/src/test/java/dev/turboism/config/ (same package, so
# package-private TypedConfigDocumentStore is reachable), run it, copy the bytes out, remove the
# worktree. Nothing is committed in that worktree.
git worktree remove /tmp/turboism-gen --force
```

Use Method B when a package-private writer must be called directly
(`TypedConfigDocumentStore#writeAtomic`, `PendingPluginOperations`), or when the release asset
naming is unknown. A historical tag must compile for this to work; if it does not, record that as a
gap rather than substituting a rebuild of `main`.

### 10.3 Method C — documents a released installer emits

`config.json` seeded by `packaging/windows-installer/config.template.json` (installed verbatim by
`assemble-release.sh`) and `installed-build.json` written by `installer.nsi` are release-emitted
documents whose exact bytes are in the repository at that tag:

```bash
git show "v0.42.0:packaging/windows-installer/config.template.json"
git show "v0.67.0:packaging/windows-installer/config.template.json"
```

These are byte-identical to what a fresh install of that release writes (modulo the NSIS CRLF
choice for `installed-build.json`), so they are legitimate release-derived samples — and they are
the only ones that can be produced without running anything.

### 10.4 De-identification rules

1. Generate into a scratch home; never point a generator at a real `$TURBOISM_HOME`.
2. Replace every value in §9 with a synthetic value **before** the writer runs, so the produced
   bytes were never secret.
3. Never post-process a real file into a fixture: a byte that was once real user data must not enter
   the repository, and post-processing breaks byte-identity with the writer anyway.
4. Keep `revision` values that the writer actually produced — they are part of the format, and the
   existing `typed-config` fixtures show why (a fixture at `revision=7` migrates to `8`).
5. Record in the manifest: release tag, commit SHA, writer class#method, the exact command, the
   SHA-256, and the expected semantic state. `scripts/check_golden_snapshots.py` already enforces
   the hash and the file inventory; `origin` must be `verified-release-artifact` for these, never
   `synthetic-format-v1`.

### 10.5 What cannot be generated offline

| Artifact | Why | Consequence |
| --- | --- | --- |
| a real historical signed receipt (`manifest.json` + `.sig`) | needs the protected Ed25519 private key; re-issuing is forbidden | verification can only be tested with a reproducible test key (§11.3) |
| a real historical signed catalog | same, and the catalog is published per market release | same |
| a pre-`v0.42.0` legacy `config.json` **v0** | the writer predates the first tag; only the installer's reader survives | Inferred-only (§14 G1) |
| `cubism-installations.json` byte-exact | writer is PowerShell `ConvertTo-Json`; formatting depends on the host PowerShell version | freeze a recorded document and label the provenance, or generate on Windows |
| `turboism-theme-runtime.properties` | lives in the JVM temp dir and is deliberately ephemeral | out of corpus scope |
| typed-config schema 1/2 of `texture-atlas/layout.cfg` | no published release wrote them | Inferred-only (§14 G5) |
| a document from `v0.43.3`–`v0.43.8` or `v0.43.10-0.nightly.2` | those tags were never published, so no artifact exists | no release-derived fixture is possible; schema history only |

## 11. Verification versus issuance

This is the boundary the issue calls out, stated as three separate claims:

### 11.1 Verification is in scope

`UpdateManifestVerifier#authenticate` and `PluginCatalogVerifier` verify **existing bytes**:
signature format and schema, `algorithm == "Ed25519"`, `keyId` present in the trusted set and not
revoked, `manifestSha256` equal to the SHA-256 of the supplied manifest bytes, then the Ed25519
signature over those exact bytes. Nothing in either verifier can create a signature, and neither
mutates its input.

### 11.2 Issuance is out of scope and forbidden as evidence

Creating or re-creating a receipt, manifest or catalog signature — including "refreshing" a
historical one so it verifies under a current key — is **not** verification, destroys the historical
claim, and requires a private key that must never exist in this repository or in CI for a test. A
fixture produced that way is not historical evidence even if it is byte-plausible.

### 11.3 The allowed substitute

For #111's receipt test, generate a receipt with a **clearly marked reproducible test key**:
a fixed Ed25519 key pair committed as a test-only resource (or generated deterministically in the
test), a receipt whose `keyId` says so, and a test that drives the production verifier and the
production applier. The fixture must state in the manifest/README that it is
`test-key-reproducible`, not historical. Real historical receipts stay an explicit open gap (§14 G3).

## 12. Compatibility support window and deprecation policy (decided)

**Decision: Option B is adopted.** This section is normative. It replaces the earlier "proposed,
decision required" draft; the three proposals are recorded in §12.7 so the reasoning stays
reviewable.

### 12.1 Read window

Every on-disk format written by **any published release from `v0.42.0` onward** is inside the read
window. A document in the window must have exactly one of three outcomes, in this order of
preference:

1. **read** by the current production reader without touching the bytes
   (`RuntimeConfigRepository`, `RuntimeTypedPluginConfigRegistry`, `RuntimePluginConfigRegistry`,
   `PendingPluginOperations`, `PluginCatalogStateStore`, `InstalledBuild`,
   `FrameworkUpdateApplier`, `FrameworkUpdateLaunchLease`, `UpdateStateStore`,
   `UpdatePreferencesStore`);
2. **migrated** by an approved step that first leaves a durable pre-image of the pre-upgrade bytes
   (the typed-config recovery point, the update journal's `backup/` tree, or the installer's
   pre-normalisation image); or
3. **refused** fail-closed with a diagnostic while 100% of the original bytes stay on disk.

There is no fourth outcome. "Reset to defaults and report success" is not a supported outcome for
any epoch inside the window, and silent removal of unknown data is only allowed through an explicit
migration step that names the dropped keys (the approved example is `texture-atlas/layout.cfg`
v3→v4).

Because the window starts at the first published release, it currently covers the **whole** published
set — §2.1 contains no epoch that is outside it. A future narrowing has to follow §12.5.

Pre-`v0.42.0` untagged `config.json` **v0** (no `schemaVersion`) is **outside the runtime read
window**: it is normalised by the installer `ConfigMerge#normalizeToCurrent` (and by the PowerShell
configurator) before the runtime runs, and the runtime refuses a document without a `schemaVersion`
without modifying it. v0 therefore stays installer-owned and needs no runtime fixture; that is the
resolution of the register's open question 1 and leaves G1 as an installer-lane fixture gap only.

### 12.2 Write window

Only the current schema of each family is written. Two obligations accompany that:

- a **destructive read-time transition** (a migration that replaces the stored document) requires a
  durable pre-image *before* the replace — for Family 2 that is
  `TypedConfigDocumentStore#recordRecoveryPoint`, bounded to `RECOVERY_POINT_RETENTION`;
- a save that follows an **unreadable** read (corrupt bytes, an unknown field, or a `schemaVersion`
  newer than this build) must keep one bounded recovery image of the previous document before the
  atomic replace. The image is the sibling `<file>.corrupt.bak`, retention is exactly one and a
  normal overwrite of a readable document writes no image. Implemented for `catalog-state.json`,
  `update-state.json` and `update-preferences.json`; the mechanics are described in the non-typed
  audit §3.3/§3.4, and gap G9 tracks shipping it in a stable release.

### 12.3 Pre-image inventory

| Family | Durable pre-image | Retention | Gate |
| --- | --- | --- | --- |
| typed plugin config (`<doc>.cfg`) | `.recovery-points/<doc>.recovery/<schema>-r<rev>-<sha256>.bak` | 3 | `PersistenceGoldenSnapshotsTest` |
| framework update payloads | `state/runtime/update/helper/backup/` + `journal.json` | until committed | `FrameworkUpdateApplierTest`, `SignedReceiptAndUpdateJournalCompatibilityTest` |
| `catalog-state.json` | `catalog-state.json.corrupt.bak` | 1 | `PluginPersistenceJournalCompatibilityTest` |
| `update-state.json` / `update-preferences.json` | `<file>.corrupt.bak` | 1 | `SignedReceiptAndUpdateJournalCompatibilityTest` |
| renamed plugin config (G10) | the untouched document under the retired plugin id | indefinitely | `LegacyHomeMigrationTest` |

### 12.4 Unknown-field and unknown-data rule

The three released `config.json` policies (§3.3) are reconciled by reader, not by a single global
rule:

- the **runtime** refuses an unknown field and never rewrites the file;
- the **installer** normalises (drops unknown fields) from `v0.67.0` and must capture a
  pre-normalisation image first (tracked as N1 in the non-typed audit);
- the runtime and the installer never both apply to the same document in the same run.

For every other family, unknown fields in a *closed* schema are refused with the bytes preserved
(`pending.json`, `catalog-state.json`, receipt index and documents); *permissive* readers
(`update-state.json`, `update-preferences.json`) ignore unknown fields but must never widen what the
document can cause to happen.

### 12.5 Deprecation policy

A format epoch may leave the read window only when **all** of the following hold:

1. the epoch has had **no published writer for at least two minor releases** — that is, the first
   release that no longer reads it must come no earlier than the second minor release after the last
   release that wrote it;
2. a release-blocking entry is added to `CHANGELOG.md` **and** to this inventory (§2.1 row plus a
   removal note) naming the epoch, the last release that wrote it and the first release that no
   longer reads it;
3. the post-deprecation behaviour is a **refusal with a diagnostic that preserves the original
   bytes** — never a silent reset to defaults, never a delete, never a rewrite.

Deleting a fixture from `testing/fixtures/snapshots/` is a policy change, not test cleanup: the
manifest gate fails when a sample disappears, and the policy above is the only accepted reason.

### 12.6 What the policy does not cover

- signatures that cannot be re-issued (real historical receipts and catalog envelopes, G3):
  verification of existing bytes is covered with the documented `test-key-reproducible` key, and
  issuance stays forbidden (§11);
- bytes from a tagged-but-never-published build (G8) and from a pre-`v0.42.0` build (G1);
- formats written by the host (Cubism) or by a plugin that declares no runtime-owned schema;
- the per-user self-update download cache, which lives outside the installation and is discardable.

### 12.7 Enforcement

| Rule | Gate |
| --- | --- |
| fixtures are immutable, path-safe and complete | `scripts/check_golden_snapshots.py` + `scripts/test/test_check_golden_snapshots.py` |
| every fixture is replayed by a production reader | `PersistenceGoldenSnapshotsTest`, `PluginPersistenceJournalCompatibilityTest`, `GoldenUpdateStateSnapshotsTest` under `:runtime:persistenceGoldenSnapshotsTest` |
| a read leaves released bytes untouched | per-fixture `assertArrayEquals(original, ...)` |
| destructive transitions keep a pre-image | recovery-point, `*.corrupt.bak` and journal-backup tests |
| the policy text tracks the corpus | this document (§13/§14 are updated in the same change) |

### 12.8 Recorded alternatives (not adopted)

- **Option A** — "everything ever published stays readable", including pre-`v0.42.0` v0
  `config.json`: rejected because no released writer produced v0, so it could only be covered by a
  non-release-derived fixture, which would weaken the meaning of `verified-release-artifact`.
- **Option C** — "latest two stable releases only" (`v0.67.x`, `v0.68.x`): rejected because a user
  who skipped from `v0.44.0` would lose data with no migration, which contradicts the premise of
  #69.

## 13. Follow-up tasks

### 13.1 Release-derived fixtures — #110 batch 1 and the #69 closure batch

#### 13.1.1 #110 batch 1 — candidates a released writer can produce offline today

Ordered by evidence strength. Each line is a fixture to add, with its provenance source; none of
them requires Cubism, a running host, or a private key. Method A generation needs a one-time
download of the published release asset; replay and verification stay offline.

| # | Fixture | Source writer | Provenance |
| --- | --- | --- | --- |
| 1 | `runtime-config/schema-v1/installer-template-0.42.0.json` | shipped seed installed verbatim | `git show v0.42.0:packaging/windows-installer/config.template.json` |
| 2 | `runtime-config/schema-v1/installer-template-0.67.4.json` | shipped seed, later field set | `git show v0.67.4:packaging/windows-installer/config.template.json` |
| 3 | `runtime-config/schema-v1/runtime-writer-0.42.0.json` | `RuntimeConfigRepository#read` on a missing file | Method A with the `v0.42.0` agent jar |
| 4 | `runtime-config/schema-v1/runtime-writer-0.67.4.json` | same, later defaults | Method A with the `v0.67.4` agent jar |
| 5 | `typed-config/schema-v3/texture-atlas-layout-0.42.0.cfg` | `TypedConfigDocumentStore#writeAtomic` (the writer behind `RuntimeTypedPluginConfigRegistry#write`) | Method A/B at `v0.42.0`; expectation `migrate-v3-to-v4-retain-layout-mode` |
| 6 | `typed-config/schema-v4/texture-atlas-layout-0.67.4.cfg` | same, current schema | Method A/B at `v0.67.4`; expectation `v4-reads-without-migration` |
| 7 | `typed-config/schema-v1/backup-webdav-0.44.0.cfg` | same, credential-bearing document | Method A/B at `v0.44.0`, de-identified per §10.4 |
| 8 | `plugin-journal/schema-v1/pending-0.42.0.json` | `PendingPluginOperations#stage` | Method B: the class and its constructor are public, but `stage(...)` is package-private, so the generator must sit in `dev.turboism.pluginmanagement` |
| 9 | `update-state/schema-v1/update-state-0.44.0.json` | `UpdateStateStore#save` | Method A at `v0.44.0` |
| 10 | `update-state/schema-v1/update-preferences-0.44.0.json` | `UpdatePreferencesStore#save` | Method A at `v0.44.0` |

**Landed in #110 batch 1** (`origin: verified-release-artifact`, nine fixtures; the four existing
`synthetic-format-v1` records are unchanged):

| Fixture ID | Corpus path | Release | Production replay and oracle |
| --- | --- | --- | --- |
| `runtime-config-installer-template-0-42-0` | `runtime-config/schema-v1/installer-template-0.42.0.json` | `v0.42.0` @ `4f75113d7` | `RuntimeConfigRepository#read`: schema 1, `worktreeId=turboism-runtime`, read leaves bytes unchanged, an update preserves the seed's fields. |
| `runtime-config-installer-template-0-67-4` | `runtime-config/schema-v1/installer-template-0.67.4.json` | `v0.67.4` @ `d9d630c65` | same reader, plus the later `useTextIcon`/`hooks.startup` fields. |
| `runtime-config-runtime-writer-0-42-0` | `runtime-config/schema-v1/runtime-writer-0.42.0.json` | `v0.42.0` @ `4f75113d7` | Method A agent-jar `read()` on an empty home; the Jackson layout (`"key" : value`, no trailing newline) is now pinned. |
| `runtime-config-runtime-writer-0-67-4` | `runtime-config/schema-v1/runtime-writer-0.67.4.json` | `v0.67.4` @ `d9d630c65` | same, later defaults. |
| `typed-config-texture-atlas-layout-0-42-0` | `typed-config/schema-v3/texture-atlas-layout-0.42.0.cfg` | `v0.42.0` @ `4f75113d7` | Registry read migrates v3→v4, retains only `layout-mode`, bumps revision 3→4, and the `.recovery-points/` image equals the fixture byte for byte. A second read is idempotent; a future schema is refused without mutation. |
| `typed-config-texture-atlas-layout-0-67-4` | `typed-config/schema-v4/texture-atlas-layout-0.67.4.cfg` | `v0.67.4` @ `d9d630c65` | v4 read with no migration and no recovery point; bytes unchanged; an unknown key and a future version are refused without mutation. |
| `typed-config-backup-webdav-0-44-0` | `typed-config/schema-v1/backup-webdav-0.44.0.cfg` | `v0.44.0` @ `a8a81477c` | v1 read of all ten settings with synthetic endpoint values; no migration, no recovery point; bytes unchanged; future version refused. |
| `update-state-0-44-0` | `update-state/schema-v1/update-state-0.44.0.json` | `v0.44.0` @ `a8a81477c` | `UpdateStateStore#read`: attempt and ETag survive, no cached result, bytes unchanged. |
| `update-preferences-0-44-0` | `update-state/schema-v1/update-preferences-0.44.0.json` | `v0.44.0` @ `a8a81477c` | `UpdatePreferencesStore#readAll`: `automaticChecksEnabled=false` and the missing plugin field defaults to enabled; bytes unchanged. |

The release-derived bytes were produced by the released agent JARs (Method A) or read verbatim
from the tagged tree (Method C), never hand-written. `plugin-journal/schema-v1/pending-0.42.0.json`
(#8) is **not** in this batch: its package-private `stage(...)`/`operations()` reader would need a
helper in `dev.turboism.pluginmanagement`, which was outside that PR's file boundary; the closure
batch below lands it with a same-package replay, so that deferral is resolved. No fixture in this
batch claims to be a signed receipt or a real catalog signature.

Implemented prerequisites (checker and replay gate):

- `ALLOWED_FAMILIES` now includes `update-state`; `verified-release-artifact` records are rejected
  unless `releaseTag`, `releaseCommit` (40-char lowercase hex), `writer`, `producerInvocation` and
  `deidentification` are non-empty and well formed. Existing hash/path/symlink/unregistered-file
  fail-closed checks are unchanged.
- `PersistenceGoldenSnapshotsTest` dispatches the new `update-state` family and the release-derived
  expectations; per-document `documentPath` is validated by the manifest checker.
- The four original `synthetic-format-v1` records are byte-identical and unchanged.

#### 13.1.2 #69 closure batch

Nine further `verified-release-artifact` fixtures bring the corpus to 22 records (4 synthetic + 18
release-derived); the thirteen existing records are byte-identical and unchanged. Two of the nine
close families the earlier PRs had registered as gaps (`plugin-journal`, and the launcher/update
state pair `installed-build` + `update-journal`).

| Fixture ID | Corpus path | Release | Production replay and oracle |
| --- | --- | --- | --- |

| Fixture ID | Corpus path | Release | Production replay and oracle |
| --- | --- | --- | --- |
| `typed-config-ui-theme-selection-0-42-0` | `typed-config/schema-v1/ui-theme-selection-0.42.0.cfg` | `v0.42.0` @ `4f75113d7` | `RuntimeTypedPluginConfigRegistry#read`: `selected-theme` reads as the synthetic theme id at revision 1, no migration and no recovery point, bytes unchanged, unknown key and future version refused. |
| `typed-config-texture-atlas-dalsoo-layout-0-67-4` | `typed-config/schema-v2/texture-atlas-dalsoo-layout.0.67.4.cfg` | `v0.67.4` @ `d9d630c65` | all eleven stored values read back (enums, bounds, per-item policy list); bytes unchanged; unknown key and future version refused; the approved v1→v2 rule adds the three preset defaults and leaves the v1 bytes in the recovery point. |
| `plugin-properties-external-editor-0-42-0` | `plugin-properties/schema-v1/external-editor-0.42.0.properties` | `v0.42.0` @ `4f75113d7` | `RuntimePluginConfigRegistry#read`/`#writeString`: the released values read back, a retired key survives both the read and a later production write, the read leaves the bytes untouched. |
| `plugin-journal-pending-0-42-0` | `plugin-journal/schema-v1/pending-0.42.0.json` | `v0.42.0` @ `4f75113d7` | `PendingPluginOperations#operations`/`#pendingChanges`/`#recoveryRequired`: both released operations are read (staged install of the shipped `ui-theme` plugin plus an uninstall), the document predates `revision`, nothing is rewritten. Closed gap G11. |
| `catalog-state-0-67-4` | `catalog-state/schema-v2/catalog-state-0.67.4.json` | `v0.67.4` @ `d9d630c65` | `PluginCatalogStateStore#read`: watermark, ETag, attempt, staged update and both synthetic cached payloads read back; bytes unchanged; a save round-trip keeps the meaning. |
| `installed-build-zip-0-67-4` | `installed-build/schema-v1/installed-build-zip-0.67.4.json` | `v0.67.4` @ `d9d630c65` | extracted from the published ZIP; `InstalledBuild#inferVariant` reports `full` and the helper's policy check treats `0.67.4` as the installed version. |
| `installed-build-runtime-0-68-1-beta-5` | `installed-build/schema-v1/installed-build-runtime-0.68.1-beta.5.json` | `v0.68.1-beta.5` @ `93a7191e4` | the runtime writer's identity document; same replay, plus the numeric-core comparison a beta installation needs. |
| `update-journal-0-68-1-beta-5` | `update-journal/schema-v1/journal-0.68.1-beta.5.json` | `v0.68.1-beta.5` @ `93a7191e4` | a journal left in `APPLYING` by the released applier; `FrameworkUpdateApplier#inspectJournal` validates it like `#recover` does, and `#recover` refuses it for any other installation while the bytes stay. |
| `update-journal-lease-0-68-1-beta-5` | `update-journal/schema-v1/lease-0.68.1-beta.5.json` | `v0.68.1-beta.5` @ `93a7191e4` | `FrameworkUpdateLaunchLease#readAll` reads the released `LAUNCHING` entry with its pid, start instant and token; the entry is never rewritten. |

Checker and replay gate after this batch:

- `ALLOWED_FAMILIES` covers all eight registered families;
- `PersistenceGoldenSnapshotsTest` replays the families whose readers are reachable from
  `dev.turboism.config` and **fails when a fixture's family has no owner**, so a new family cannot
  enter the corpus unreplayed;
- the two package-private families are replayed by same-package suites
  (`PluginPersistenceJournalCompatibilityTest`, `GoldenUpdateStateSnapshotsTest`), which also assert
  that every case of their family was covered;
- `:runtime:persistenceGoldenSnapshotsTest` runs all three suites.

### 13.2 #111 — non-typed state, journals and signed receipts

| # | Target | Why it needs a test | Production path to drive |
| --- | --- | --- | --- |
| 1 | `config.json` unknown-field / future-format behaviour | closed schema: an unknown field makes the file unreadable; a fixture proves the file is **not** rewritten | `RuntimeConfigRepository#read` / `#update` |
| 2 | `config.json` tolerated-value preservation (#85) | the loss existed in every published release; the fix is unreleased | `RuntimeConfigRepository#update` with an unsupported `locale`/`memoryProfile` on disk |
| 3 | typed-config recovery point | a destructive read-time migration must leave the pre-upgrade bytes; **no release does this** | `TypedConfigDocumentStore#recordRecoveryPoint` via the registry read path |
| 4 | migration failure / future version | original bytes must survive and no default must masquerade as success | already covered for the synthetic v1 case; needs a **release-derived** v3 input |
| 5 | plugin install journal (**done**: release-derived fixture replayed in `PluginPersistenceJournalCompatibilityTest`) | interrupted install must stay recoverable and refuse new work | `PendingPluginOperations` + `PluginUpdateGate` |
| 6 | framework update journal + lease | crash at each journal state must not lose payload or receipt | `FrameworkUpdateApplier#recover` / `#apply` with the `Faults` seam (both package-private, so the test must live in `dev.turboism.update`) |
| 7 | signed receipt verification | reject tampered bytes; never re-sign | `UpdateManifestVerifier#authenticate` + `FrameworkUpdateReceipt#prepare`, with the test key of §11.3 |
| 8 | `catalog-state.json` schema 2 (**done**: release-derived fixture plus a corrupt-image test) | wrong `schemaVersion` must be refused, not migrated, and the file preserved | `PluginCatalogStateStore#read` |
| 9 | installer normalisation vs runtime strictness (**decided**, §12.4) | the installer drops unknown fields since `v0.67.0`; the runtime refuses them; `v0.42.0`–`v0.43.2` preserved them. The three are reconciled as a per-reader rule, and the installer still owes a pre-normalisation image (N1) | `ConfigMerge#normalizeToCurrent` (installer harness) vs `RuntimeConfigRepository#read` |
| 10 | plugin-id rename orphaning (§2.4) (**fixed**) | `dev.turboism.plugin.backup` → `dev.turboism.plugin.webdav` at `v0.44.0` used to leave the old `backup/webdav.cfg` unread and the new document absent, silently resetting WebDAV settings | `LegacyHomeMigration#migrate` copies the retired document to the current id without consuming it; `LegacyHomeMigrationTest` covers absence, idempotence, an authoritative destination and a symlinked source |
| 11 | orphaned documents of removed plugins (§2.4) | `bounding-box/features.cfg` and `project-panel/state.cfg` are never read, migrated or deleted | behaviour test asserting the files are left untouched |

### 13.3 Deliberately out of scope for both

- No change to `schemaVersion` of any format.
- No new format, no mass rename, no module extraction.
- No re-signing or mutation of any signed byte.
- No fixture may be labelled `verified-release-artifact` unless §10 was followed.

## 14. Evidence gap register

Status legend: **closed** = a fixture plus a production replay now pin the behaviour; **closed by
policy** = §12 makes the remaining question a documented decision instead of an open gap; **open** =
still needs evidence this repository cannot produce offline.

| ID | Gap | Status | Impact and what remains |
| --- | --- | --- | --- |
| G1 | legacy `config.json` **v0** bytes (no `schemaVersion`) | closed by policy (§12.1) | v0 is installer-owned and outside the runtime read window: `ConfigMerge#normalizeToCurrent` normalises it and the runtime refuses a document without `schemaVersion` without modifying it. Remaining work is installer-lane only (N1: the pre-normalisation image). |
| G2 | the existing `runtime-config-v1` fixture is not any released writer's byte layout | **closed** (#110 and the #69 closure batch) | the four release-derived fixtures `runtime-config-installer-template-0-42-0`, `runtime-config-installer-template-0-67-4`, `runtime-config-runtime-writer-0-42-0` and `runtime-config-runtime-writer-0-67-4` pin both the installer seed and the runtime Jackson layout. The synthetic record stays byte-identical as a read-compatibility case only. |
| G3 | real historical signed receipts and catalog signatures | **open, closed by policy (§12.6)** | verification of existing bytes is covered with the documented `test-key-reproducible` key; issuance stays forbidden, so a "real historical" sample cannot be produced without the protected key. Never re-sign. |
| G4 | `turboism.cubism.discovery-snapshot` exact bytes | **open** | the installer handoff format is only exercised by PowerShell tests; closing it needs a Windows host or a labelled frozen document. It is a transient, digest-bound handoff, not user state, so §12.6 keeps it outside the read window. |
| G5 | typed-config `texture-atlas/layout.cfg` schema 1 and 2 | **open for the fixture, closed for the chain** | no published release wrote schema 1/2, so no release-derived sample exists. The released v3→v4 path is pinned by `typed-config-texture-atlas-layout-0-42-0`; the dalsoo document's v1→v2 step is exercised from a store-written v1 document (`typed-config-texture-atlas-dalsoo-layout-0-67-4`), which is a chain claim, not release evidence. |
| G6 | `catalog-state.json` schema 1 | **closed by design** | the reader refuses v1 by design, no release ever wrote it, and the refusal is pinned by `catalogStateV1IsRefusedRatherThanMigrated`. |
| G7 | `cubism-installations.json` byte-exact generation | **open** | the writer is PowerShell-formatting dependent; closing it needs a Windows host or a labelled frozen document. PowerShell contract tests cover the reader's required-key and exact-version behaviour meanwhile. |
| G8 | `v0.43.3`–`v0.43.8`, `v0.43.10-0.nightly.2` were tagged but never published | **closed by policy (§12.6)** | those intermediate states have no downloadable artifact, so they stay schema history only and are never release evidence. |
| G9 | recovery points exist in no stable release | **closed by policy (§12.2), still to ship** | the current tree writes a recovery point before a destructive migration and preserves a `*.corrupt.bak` image before overwriting an unreadable document; §12.2 makes both hard requirements, so the next stable release must carry them. Users on `v0.67.4` remain exposed until it does. |
| G10 | a plugin-id rename silently orphans its typed config (§2.4) | **closed** | `LegacyHomeMigration#migrate` now copies every document of a recorded rename (`RENAMED_PLUGIN_IDS`: `dev.turboism.plugin.backup` → `dev.turboism.plugin.webdav`) to the current id's config directory when the destination is absent, keeps the retired document byte-identical as the pre-upgrade image, never follows a symlink and is idempotent. `LegacyHomeMigrationTest` covers the copy, the preserved original, idempotence, an authoritative destination and a symlinked source. A future rename only needs a new map entry. |
| G11 | release-derived `plugin-journal/schema-v1/pending-0.42.0.json` is not in the corpus | **closed** | the fixture exists (`plugin-journal-pending-0-42-0`), the `plugin-journal` family is registered in `ALLOWED_FAMILIES`, and `PluginPersistenceJournalCompatibilityTest` replays it through `PendingPluginOperations` from the same package, so no production API had to widen. |

## Appendix A — reproduction commands

Everything below runs offline against this repository; no build and no network.

```bash
# 1. Family epoch fingerprint: which format signals changed in which tag range
for t in $(git tag -l 'v*' | sort -V); do
  printf '%-28s ' "$t"
  git cat-file -p "$t:runtime/src/main/java/dev/turboism/config/RuntimeConfigRepository.java" \
    | grep -oE 'put\("(format|schemaVersion)", *"?[a-z0-9.]+' | tr '\n' ' '
  git cat-file -p "$t:runtime/src/main/java/dev/turboism/config/TypedConfigDocumentStore.java" \
    | grep -oE 'HEADER = "[^"]+"' | tr '\n' ' '
  echo
done

# 2. Which update/receipt classes exist in a release (Family 6 epoch boundary)
for t in v0.44.0 v0.67.4 v0.68.1-beta.1 v0.68.1-beta.5; do
  printf '%-22s ' "$t"
  git ls-tree -r --name-only "$t" -- runtime/src/main/java/dev/turboism/update \
    | grep -cE 'FrameworkUpdateReceipt|FrameworkUpdateLaunchLease|FrameworkUpdateApplier'
done

# 3. Recovery point coverage per release
for t in v0.44.0 v0.67.4 v0.68.1-beta.11 v0.68.1-0.nightly.88; do
  printf '%-24s ' "$t"
  git cat-file -p "$t:runtime/src/main/java/dev/turboism/config/TypedConfigDocumentStore.java" \
    | grep -c RECOVERY_POINT_RETENTION
done

# 4. Installer config policy per release (migration vs normalisation)
for t in v0.44.0 v0.67.0 v0.67.4; do
  printf '%-10s ' "$t"
  git cat-file -p "$t:packaging/java-installer/listener-src/dev/turboism/installer/ConfigMerge.java" \
    | grep -oE 'legacy v0 config|normalized to v1' | head -1
done

# 5. Release-emitted config.json seeds
git show v0.42.0:packaging/windows-installer/config.template.json
git show v0.67.0:packaging/windows-installer/config.template.json
```

## Appendix B — keeping this inventory current

This document is a *snapshot of the published set*, not a live view. It must be updated in the same
PR that:

- adds a new persisted format or bumps a `schemaVersion` (add an epoch row and a fixture);
- changes a writer's byte layout (add a row to §2.3);
- changes a migration or a recovery-point rule (update §4.3/§4.4 and the #111 table);
- publishes a release that closes a gap in §14 (delete the row, cite the fixture);
- makes a support-window decision (§12) — record the decision and the release that carries it.

A fixture addition without a §14 update, or a §14 update without a fixture, is an incomplete change.
