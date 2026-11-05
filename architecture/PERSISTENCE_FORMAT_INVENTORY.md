# Persistence format inventory: released writers, readers and provenance

Analysis for **#69 / #109**. This document is the provenance inventory the golden-corpus work
(#110) and the non-typed-state audit (#111) depend on. It is **analysis only**: it changes no
runtime format, no migration, no support claim and no fixture.

Read it with the two existing contracts:

- `ARCHITECTURE_GOVERNANCE.md` — golden snapshot contract and recovery point contract.
- `tests/fixtures/snapshots/manifest.json` + `scripts/check_golden_snapshots.py` — the immutable
  fixture inventory and its checker.

## 0. What this document owns, and what it does not

| Question | Authority |
| --- | --- |
| Which on-disk formats has a **published release** ever written? | this inventory |
| What bytes does a given release's writer emit, and what reads them back? | this inventory (paths + commands); the **code** is authoritative |
| Is a stored document accepted, migrated or refused? | `RuntimeConfigRepository`, `RuntimeTypedPluginConfigRegistry`, `TypedConfigDocumentStore`, `ConfigMerge` — never this document |
| Is a fixture authentic and unmodified? | `scripts/check_golden_snapshots.py` + `manifest.json` |
| Is a signed artifact authentic? | `UpdateManifestVerifier` / `PluginCatalogVerifier` against bundled public keyrings |
| Which old versions must keep working? | **open maintainer decision**, §12 |

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
described as such (§14 G2).

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

1. **A plugin id rename orphans its documents.** `dev.turboism.plugin.backup` was renamed to
   `dev.turboism.plugin.webdav` in `v0.44.0` keeping the same `CONFIG_ID` (`backup.webdav`) and the
   same `CONFIG_PATH` (`backup/webdav.cfg`). Because the root is keyed by plugin id, the old document
   stays at `config/dev.turboism.plugin.backup/backup/webdav.cfg` while the new plugin reads
   `config/dev.turboism.plugin.webdav/backup/webdav.cfg` — absent, so it starts from defaults. The
   retired JAR is deleted on upgrade (`packaging/retired-plugins.txt` lists
   `dev.turboism.plugin.backup`), but nothing migrates the settings: `LegacyHomeMigration` only moves
   the pre-layout `plugin-data/<id>/data/typed-config` tree, not a renamed plugin's config directory.
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
| `AtlasCacheReuseSettingsFileService` | `atlasCacheReuse` |
| `AtlasTileBboxSettingsFileService` | `atlasTileBbox` |
| `MeshTriangulationSettingsFileService` | `meshTriangulationHashFix` |
| `TriangulationEdgeIndexSettingsFileService` | `meshTriangulationEdgeIndex` |

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
(`CREATE_NEW` + `force`) → `StorageAtomicMover#move` (`ATOMIC_MOVE`, fail-closed when the
filesystem cannot do it). There is **no format layer**: the bytes are whatever the plugin encodes.

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

## 12. Proposed support window and deprecation policy (decision required)

Nothing here is implemented, and no production support is narrowed by this document. Three options
for the maintainer to choose; the recommendation is **B**.

### Option A — "everything ever published stays readable" (widest)

Support every format epoch in §2.1, including the v0 `config.json` shape and every typed-config
schema ever shipped. Consequence: the corpus must either carry a v0 `config.json` fixture with an
explicitly non-release provenance (so the "release-derived" label stops being universal), or leave
that epoch uncovered; and the closed-schema behaviour of `config.json` (unknown field → unreadable)
has to be accepted as permanent.

### Option B — "supported = every published release of the current major line; older data must be readable or refused without loss" (recommended)

- **Read window**: every on-disk format written by any release from `v0.42.0` onward must be
  *readable*, *migrated*, or *refused with the original bytes preserved*. That is the whole
  published set, so the window is currently "all of it".
- **Write window**: only the current schema is written. A read-time migration is allowed only with a
  recovery point (already true in the current tree, unreleased) — this becomes a hard requirement.
- **Deprecation**: a format epoch may be dropped from the read window only by an explicit
  documented release decision in `CHANGELOG.md` plus a removal note in this inventory, and only
  after the epoch has had no published writer for two minor releases. Dropping it means: refuse the
  document with a diagnostic and keep the bytes; never migrate to defaults.
- **Corpus**: every epoch in §2.1 that has a released writer gets a fixture; epochs that never had a
  released writer stay listed as gaps rather than invented.

### Option C — "latest two stable releases only" (narrowest)

Support `v0.67.x` and `v0.68.x` only. Consequence: a user who skipped from `v0.44.0` to a future
release has unreadable data with no migration, which contradicts #69's premise. Not recommended.

### Decision points the maintainer must resolve explicitly

1. Is the v0 `config.json` shape (pre-`v0.42.0`) inside or outside the read window?
2. Does the **installer's** normalisation (which drops unknown fields, since `v0.67.0`) count as an
   approved unknown-data rule, or must it return to the `v0.42.0`–`v0.43.2` preserve-unknown
   behaviour / start refusing? Three released policies exist (§3.3) and only one can be "the" rule.
3. Must a recovery point become mandatory for every destructive migration *before* the next stable
   release (today it exists only in a nightly)?

## 13. Follow-up tasks

### 13.1 #110 batch 1 — candidates a released writer can produce offline today

Ordered by evidence strength. Each line is a fixture to add, with its provenance source; none of
them requires Cubism, a network, or a private key.

| # | Fixture | Source writer | Provenance |
| --- | --- | --- | --- |
| 1 | `runtime-config/schema-v1/installer-template-0.42.0.json` | shipped seed installed verbatim | `git show v0.42.0:packaging/windows-installer/config.template.json` |
| 2 | `runtime-config/schema-v1/installer-template-0.67.4.json` | shipped seed, later field set | `git show v0.67.4:packaging/windows-installer/config.template.json` |
| 3 | `runtime-config/schema-v1/runtime-writer-0.42.0.json` | `RuntimeConfigRepository#read` on a missing file | Method A with the `v0.42.0` agent jar |
| 4 | `runtime-config/schema-v1/runtime-writer-0.67.4.json` | same, later defaults | Method A with the `v0.67.4` agent jar |
| 5 | `typed-config/schema-v3/texture-atlas-layout-0.42.0.cfg` | `RuntimeTypedPluginConfigRegistry#write` | Method A/B at `v0.42.0`; expectation `migrate-v3-to-v4-retain-layout-mode` |
| 6 | `typed-config/schema-v4/texture-atlas-layout-0.67.4.cfg` | same, current schema | Method A/B at `v0.67.4`; expectation `v4-reads-without-migration` |
| 7 | `typed-config/schema-v1/backup-webdav-0.44.0.cfg` | same, credential-bearing document | Method A/B at `v0.44.0`, de-identified per §10.4 |
| 8 | `plugin-journal/schema-v1/pending-0.42.0.json` | `PendingPluginOperations#stage` | Method B: the class and its constructor are public, but `stage(...)` is package-private, so the generator must sit in `dev.turboism.pluginmanagement` |
| 9 | `update-state/schema-v1/update-state-0.44.0.json` | `UpdateStateStore#save` | Method A at `v0.44.0` |
| 10 | `update-state/schema-v1/update-preferences-0.44.0.json` | `UpdatePreferencesStore#save` | Method A at `v0.44.0` |

Prerequisites for that PR (in the same change, because the checker is the gate):

- `scripts/check_golden_snapshots.py` currently hard-codes
  `ALLOWED_FAMILIES = {"runtime-config", "typed-config"}`. New families
  (`plugin-journal`, `update-state`, …) must be added there **and** to the `switch` in
  `PersistenceGoldenSnapshotsTest#verify`, which currently throws `Unrecognized golden fixture
  family` for anything else.
- `PersistenceGoldenSnapshotsTest#allRegisteredSnapshots` asserts `cases.size() >= 4`; the matrix
  must only grow, so no change is needed, but the per-family expectations need new branches.
- New manifest fields (release tag, commit, writer, command) must be optional-but-validated so the
  four existing `synthetic-format-v1` records stay byte-identical and semantically unchanged.

### 13.2 #111 — non-typed state, journals and signed receipts

| # | Target | Why it needs a test | Production path to drive |
| --- | --- | --- | --- |
| 1 | `config.json` unknown-field / future-format behaviour | closed schema: an unknown field makes the file unreadable; a fixture proves the file is **not** rewritten | `RuntimeConfigRepository#read` / `#update` |
| 2 | `config.json` tolerated-value preservation (#85) | the loss existed in every published release; the fix is unreleased | `RuntimeConfigRepository#update` with an unsupported `locale`/`memoryProfile` on disk |
| 3 | typed-config recovery point | a destructive read-time migration must leave the pre-upgrade bytes; **no release does this** | `TypedConfigDocumentStore#recordRecoveryPoint` via the registry read path |
| 4 | migration failure / future version | original bytes must survive and no default must masquerade as success | already covered for the synthetic v1 case; needs a **release-derived** v3 input |
| 5 | plugin install journal | interrupted install must stay recoverable and refuse new work | `PendingPluginOperations` + `PluginUpdateGate` |
| 6 | framework update journal + lease | crash at each journal state must not lose payload or receipt | `FrameworkUpdateApplier#recover` / `#apply` with the `Faults` seam (both package-private, so the test must live in `dev.turboism.update`) |
| 7 | signed receipt verification | reject tampered bytes; never re-sign | `UpdateManifestVerifier#authenticate` + `FrameworkUpdateReceipt#prepare`, with the test key of §11.3 |
| 8 | `catalog-state.json` schema 2 | wrong `schemaVersion` must be refused, not migrated, and the file preserved | `PluginCatalogStateStore#read` |
| 9 | installer normalisation vs runtime strictness | the installer drops unknown fields since `v0.67.0`; the runtime refuses them; `v0.42.0`–`v0.43.2` preserved them. The three must be reconciled into one documented rule or deliberately kept distinct | `ConfigMerge#normalizeToCurrent` (installer harness) vs `RuntimeConfigRepository#read` |
| 10 | plugin-id rename orphaning (§2.4) | `dev.turboism.plugin.backup` → `dev.turboism.plugin.webdav` at `v0.44.0` leaves the old `backup/webdav.cfg` unread and the new document absent; the user's WebDAV settings silently reset | `TurboismHomeLayout#plugin` + `RuntimeTypedPluginConfigRegistry` (no migration exists; test would pin current behaviour, not fix it) |
| 11 | orphaned documents of removed plugins (§2.4) | `bounding-box/features.cfg` and `project-panel/state.cfg` are never read, migrated or deleted | behaviour test asserting the files are left untouched |

### 13.3 Deliberately out of scope for both

- No change to `schemaVersion` of any format.
- No new format, no mass rename, no module extraction.
- No re-signing or mutation of any signed byte.
- No fixture may be labelled `verified-release-artifact` unless §10 was followed.

## 14. Evidence gap register

| ID | Gap | Impact | What would close it |
| --- | --- | --- | --- |
| G1 | legacy `config.json` **v0** bytes (no `schemaVersion`) | the only `config.json` schema migration ever shipped cannot be tested against real input | a v0 document from a pre-`v0.42.0` installation, or an explicit decision that v0 is out of the read window (§12.1) |
| G2 | the existing `runtime-config-v1` fixture is not any released writer's byte layout | it proves read-compatibility, not writer compatibility | add the installer-template and runtime-writer fixtures (§13.1 #1–#4); leave the synthetic one unchanged |
| G3 | real historical signed receipts and catalog signatures | verification is tested only against a reproducible test key | keep as an explicit open gap; never re-sign |
| G4 | `turboism.cubism.discovery-snapshot` exact bytes | the installer handoff format is only exercised by PowerShell tests | freeze a recorded snapshot with provenance, or generate on a Windows host |
| G5 | typed-config `texture-atlas/layout.cfg` schema 1 and 2 | two migration steps have no real input | none available; keep as a documented chain-only claim |
| G6 | `catalog-state.json` schema 1 | the reader refuses v1 by design and no release ever wrote it | none needed; record the refusal as the contract |
| G7 | `cubism-installations.json` byte-exact generation | writer is PowerShell-formatting dependent | Windows-host generation, or a labelled frozen document |
| G8 | `v0.43.3`–`v0.43.8`, `v0.43.10-0.nightly.2` were tagged but never published | those intermediate states have no downloadable artifact | treat as schema history only; never as release evidence |
| G9 | recovery points exist in no stable release | the destructive-migration window is open for every user on `v0.67.4` | ship the current behaviour in the next stable release (§12 decision 3) |
| G10 | a plugin-id rename silently orphans its typed config (§2.4) | WebDAV backup settings reset on upgrade from `≤v0.43.9` to `≥v0.44.0`; the same class of loss will recur on any future rename | a migration that moves `config/<old-id>/` to `config/<new-id>/` when the new directory is absent, plus a fixture pair |

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
