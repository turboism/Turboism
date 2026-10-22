# DRAFT projections

`materializeDraftContracts` runs after `materializeVerificationRecords`, without
host jars. It copies the remaining observation/legacy packs and generates the
124 migrated packs plus all profiles into `build/generated/draft/`.

The family declarations retain only DRAFT annotations, their historical key
order, and exact release applicability. Selector identities, runtime names,
descriptors, order, artifact facts and metadata hashes come from verification
records. `editor-model-read` projects the upstream `editor-model` family.
Templates are shared; the per-alias rules select annotations, never selectors.
Profiles use the reviewed table and family availability, with explicit historical
presentation/reference exceptions (including the extra 5.3.02 Core reference in
5.3.03). These exceptions preserve bytes; they do not admit runtime evidence.

For an intentional family/release change, extend its declaration and the
applicable annotation ranges; new aliases need an annotation template rule.
The generator checks alias-set equality, profile identity and pack references;
existing import and metadata checks validate the generated contracts.
`--check-against <old-checkout>` compares all packs and profiles byte for byte.

Consumers must depend explicitly on `materializeDraftContracts`. Standalone
Python tools require both materializers first. Generated projections must not
be edited as evidence: change the upstream verification source or retained
observation pack, then regenerate. To restore the old layout for rollback, copy
`build/generated/draft/mapping-packs/*.json` and `profiles/*.json` into their old
compatibility directories, or revert the three migration commits in reverse.
