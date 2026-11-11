# DRAFT projections

`materializeDraftContracts` runs after `materializeVerificationRecords`, without
host jars. It expands the retained observation/legacy evidence and generates the
124 migrated packs plus all profiles into `build/generated/draft/`.

## Retained evidence layout

`compatibility/cubism/mapping-packs/draft/` holds one baseline per family plus one
override table per family:

- `baseline/<family>.json` — the retained pack for one representative version.
  Record-derived literals appear as `${version}`, `${artifactSha256}`,
  `${artifactName}`, `${artifactSize}`, `${profileId}`, `${verificationId}`,
  `${selectorCount}`, `${capabilityCount}` and `${verificationRecordSha256}`.
- `overrides/<family>.json` — names the `baselineVersion` and lists ordered
  JSON-pointer `replace` operations for every other version of that family.
- `frozen.json` — SHA-256 pins for the shipped `5.0.x` and `5.1.x` rows.

Expansion substitutes the tokens from the matching verification record, then
applies the override operations. The result is the exact per-version pack bytes.
A selector or observation change therefore edits one baseline, not twenty copies.

## Freeze of shipped rows

`5.0.x` and `5.1.x` are shipped compatibility rows. `materializeDraftContracts`
compares their expanded digests with `frozen.json` and fails on any difference.
Change those pins only through a reviewed compatibility decision; do not edit them
to make a build pass.

## Family declarations

The family declarations retain only DRAFT annotations, optional descriptor
presence, and exact release applicability. Generated objects use sorted keys. Selector identities, runtime names,
descriptors, order, artifact facts and metadata hashes come from verification
records. `editor-model-read` projects the upstream `editor-model` family.
Templates are shared; the per-alias rules select annotations, never selectors.
Profiles use the reviewed table and family availability, with explicit historical
semantic reference exceptions (including the extra 5.3.02 Core reference in
5.3.03). These exceptions do not admit runtime evidence.

For an intentional family/release change, extend its declaration and the
applicable annotation ranges. For each new alias, add `entries.<alias>` with a
template index and a `versions` range in `draft-src/<family>.json`
(`editor-model-read.json` for upstream `editor-model` selectors).
The generator checks alias-set equality, profile identity and pack references;
existing import and metadata checks validate the generated contracts.
`--check-against <checkout>` compares all packs and profiles byte for byte when
the checkout uses the same rendering. The pre-migration byte proof preceded the
independent switch to canonical rendering. No generated pack/profile byte pins
remain; record-byte hashes in metadata still follow the verification records.

Consumers must depend explicitly on `materializeDraftContracts`. Standalone
Python tools require both materializers first. Generated projections must not
be edited as evidence: change the upstream verification source or retained
observation pack, then regenerate. `--write-freeze` re-pins the shipped rows and
belongs to a reviewed compatibility change only.
