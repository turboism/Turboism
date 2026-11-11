# Persistence golden snapshots

Immutable **byte-for-byte** fixtures for backwards-compatibility regression.

**Provenance warning:** four cases are `synthetic-format-v1`, derived from the production v1
encoding and current runtime tests. They are **not** historical customer archives, they are kept
byte-identical and they must never be described as release coverage. Every other case is
`verified-release-artifact`: its bytes were produced by the writer named in the manifest from the
release named in the manifest (a published agent JAR, a published ZIP entry, or a document shipped in
a release), and its `producerInvocation` records the exact command.

Each manifest entry names a file, records its SHA-256, declares its origin and binds it to an
explicit behavior. The manifest checker fails if a sample is changed, removed or omitted from the
inventory. Three JUnit suites execute all registered expectations with the production readers:

| Family | Replay suite |
| --- | --- |
| `runtime-config`, `typed-config`, `plugin-properties`, `catalog-state`, `update-state` | `dev.turboism.config.PersistenceGoldenSnapshotsTest` |
| `plugin-journal` | `dev.turboism.pluginmanagement.PluginPersistenceJournalCompatibilityTest` |
| `installed-build`, `update-journal` | `dev.turboism.update.GoldenUpdateStateSnapshotsTest` |

`:runtime:persistenceGoldenSnapshotsTest` runs all three. `PersistenceGoldenSnapshotsTest` also fails
when a fixture's family has no owning suite, so a new family cannot enter the corpus unreplayed. The
split is by package-private production readers, never by convenience: no reader was widened for a
test.

The compatibility contract these fixtures enforce is `docs/architecture/PERSISTENCE_FORMAT_INVENTORY.md`
§12: every format written by a published release since `v0.42.0` is read, migrated with a durable
pre-image, or refused with its bytes preserved.

Do not update snapshots to make an incompatible implementation pass. Add a fixture for every newly
supported on-disk version, and retain all preceding fixtures indefinitely. Changes to historical
bytes require an explicit correction of a proven provenance error, with review.

Never commit private user data, secrets, tokens, credential-bearing typed config, real installation
receipts, or Cubism-licensed artifacts. Release-derived fixtures must be generated or sanitized with
verifiable provenance. Two shapes need care when generating:

- a generated document may record an absolute path (a staged plugin JAR, a journal's installation
  root). Generate it under a clearly synthetic scratch location (the fixtures use
  `/tmp/turboism-golden-corpus/...`) and say so in `deidentification`;
- signed evidence is never re-issued. The cached catalog/signature bytes in `catalog-state` fixtures
  are literal placeholders, and the only signed fixture in the repository is the clearly marked
  `test-key-reproducible` receipt under `runtime/src/test/resources/fixtures/update/`.
