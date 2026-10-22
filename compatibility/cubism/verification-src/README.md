# Verification record sources

Reviewed releases (`../reviewed-releases.json`) and the JSON families in this
folder are the authoring inputs. Complete records are build outputs in
`build/generated/verification/`; do not hand-edit that directory. Gradle
`materializeVerificationRecords` runs before packaging, source generation and
verification gates. IDE tests that read repository files need this task first.
The agent still packages the same filenames under
`META-INF/turboism/verification/` and loads complete records unchanged.

For standalone Python tooling, first run:

```sh
python3 scripts/materialize_verification_records.py
```

Historical `evidencePath` and pack `inventoryRef` values retain the old
`compatibility/cubism/verification/` spelling as provenance. Repository readers
resolve those record references to the materialized directory.

For rollback or inspection, restore the former committed layout with:

```sh
python3 scripts/materialize_verification_records.py export-committed
python3 scripts/materialize_verification_records.py --check-against compatibility/cubism/verification
```

The export preserves bytes, field order, whitespace and original mapping IDs.
It does not change packaging back; revert the Phase 3 packaging commit for that.
Legacy version-matrix authoring tools now write scratch records into the
materialized directory. To fold such records back into the family sources,
use `python3 scripts/materialize_verification_records.py import --output
build/generated/verification`, review the family diff, then materialize again.
