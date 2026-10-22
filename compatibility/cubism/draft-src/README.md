# DRAFT projections

`materializeDraftContracts` runs after `materializeVerificationRecords`, without
host jars. It copies the remaining observation/legacy packs and generates the
124 migrated packs plus all profiles into `build/generated/draft/`.

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
observation pack, then regenerate. To restore the old layout for rollback, copy
`build/generated/draft/mapping-packs/*.json` and `profiles/*.json` into their old
compatibility directories, or revert the three migration commits in reverse.
