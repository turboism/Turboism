# Persistence golden snapshots

Immutable **byte-for-byte** fixtures for backwards-compatibility regression.

**Provenance warning:** the initial four cases are synthetic, derived from the
production v1 encoding and current runtime tests. They are **not** historical
customer archives and must not be described as v0.1-to-current release coverage.
The follow-up issue tracks collecting independently verified release artifacts.

Each manifest entry names a file, records its SHA-256, declares its origin and
binds it to an explicit behavior in `PersistenceGoldenSnapshotsTest`.
The manifest checker fails if a sample is changed, removed or omitted from the
inventory. The JUnit suite executes all registered expectations.

Do not update snapshots to make an incompatible implementation pass. Add a
fixture for every newly supported on-disk version, and retain all preceding
fixtures indefinitely. Changes to historical bytes require an explicit
correction of a proven provenance error, with review.

Never commit private user data, secrets, tokens, credential-bearing typed config,
real installation receipts, or Cubism-licensed artifacts. Release-derived
fixtures must be generated or sanitized with verifiable provenance.
