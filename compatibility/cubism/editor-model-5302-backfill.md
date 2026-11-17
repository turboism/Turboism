# 5.3.02 editor-model Alias Backfill Evidence

All seven members exist. The original gap was an omission in the verification record.

This round reads the JAR only. Cubism was not started.

- JAR: `/opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar`.
- Size: `41922739` bytes.
- SHA-256: `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`.
- The identity agrees with `ReviewedHostArtifacts.CUBISM_5_3_02`.
- The identity agrees with the 5.3.02 row of `reviewed-releases.json`.

Check commands:

```bash
stat -c %s /opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar
sha256sum /opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar
javap -p -s -classpath /opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar <owner from the table below>
```

Every alias starts with `cubism.editor-model.`.
No name in the table is obfuscated. No symbol mapping is needed to infer a name.

| Alias suffix | owner | member + descriptor | required / forbidden access | classfile result |
| --- | --- | --- | --- | --- |
| `morph-target-set.remove` | `com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTargetSet` | `remove(Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;)V` | `17 / 8` | `VERIFIED_STATIC` |
| `morph-target.set-parameter` | `com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget` | `setParameter(Lcom/live2d/type/CParameterGuid;)V` | `1 / 8` | `VERIFIED_STATIC` |
| `morph-target.set-parameter-and-key-value` | `com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget` | `setParameterAndKeyValue(Lcom/live2d/type/CParameterGuid;Ljava/lang/Float;)V` | `1 / 8` | `VERIFIED_STATIC` |
| `parameter-controllable-source.internal-set-parent` | `com.live2d.cubism.doc.model.ACParameterControllableSource` | `internal_setParent(Lcom/live2d/cubism/doc/model/parts/CPartSource;)V` | `17 / 8` | `VERIFIED_STATIC` |
| `parameter-group.guid` | `com.live2d.cubism.doc.model.param.group.CParameterGroup` | `getGuid()Lcom/live2d/type/CParameterGroupGuid;` | `17 / 8` | `VERIFIED_STATIC` |
| `part-source.remove-child` | `com.live2d.cubism.doc.model.parts.CPartSource` | `removeChild(Lcom/live2d/cubism/doc/model/ACParameterControllableSource;)V` | `17 / 8` | `VERIFIED_STATIC` |
| `part.id` | `com.live2d.cubism.doc.model.parts.CPart` | `getId()Lcom/live2d/cubism/doc/model/id/CPartId;` | `1 / 8` | `VERIFIED_STATIC` |

The `verify_selector` of `scripts/dev/cubism_matrix/bytecode.py` passes item by item.
The `javap -p -s` output holds the following members:

```text
com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet:
  public final void remove(com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget);
    descriptor: (Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;)V
com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget:
  public void setParameter(com.live2d.type.CParameterGuid);
    descriptor: (Lcom/live2d/type/CParameterGuid;)V
com/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget:
  public void setParameterAndKeyValue(com.live2d.type.CParameterGuid, java.lang.Float);
    descriptor: (Lcom/live2d/type/CParameterGuid;Ljava/lang/Float;)V
com/live2d/cubism/doc/model/ACParameterControllableSource:
  public final void internal_setParent(com.live2d.cubism.doc.model.parts.CPartSource);
    descriptor: (Lcom/live2d/cubism/doc/model/parts/CPartSource;)V
com/live2d/cubism/doc/model/param/group/CParameterGroup:
  public final com.live2d.type.CParameterGroupGuid getGuid();
    descriptor: ()Lcom/live2d/type/CParameterGroupGuid;
com/live2d/cubism/doc/model/parts/CPartSource:
  public final void removeChild(com.live2d.cubism.doc.model.ACParameterControllableSource);
    descriptor: (Lcom/live2d/cubism/doc/model/ACParameterControllableSource;)V
com/live2d/cubism/doc/model/parts/CPart:
  public com.live2d.cubism.doc.model.id.CPartId getId();
    descriptor: ()Lcom/live2d/cubism/doc/model/id/CPartId;
```

Propagation commands:

```bash
python3 scripts/materialize_verification_records.py
python3 scripts/materialize_draft_contracts.py
python3 scripts/dev/cubism_version_matrix.py propagate --family editor-model --from 5.3.01 --to 5.3.02 --dry-run
python3 scripts/dev/cubism_version_matrix.py propagate --family editor-model --from 5.3.01 --to 5.3.02 --write
```

Propagation result: 1130 → 1137 selectors. The capability count stays 119.
Two unrelated conflicts keep the original 5.3.02 definition:

- The requiredAccessFlags of `editable-mesh.class`: 17.
- The requiredAccessFlags of `parameter-controllable-source.selection`: 1.

The propagated result is written back to the scope of the existing seven selectors.
Seven indices are appended to the selectorOrder of 5.3.02.
The written-back record matches the propagated record.
The DRAFT observation bundle and metadata of 5.3.02 are synchronised.
The propagated alias set of the manifest is synchronised.
The selector count expectation of two integration tests is updated.
The matching monotonicity exception is removed.
All 1137 selectors of that record pass the Java `verifyStaticHostSelectors`.

This evidence proves the static member contract only. It does not prove host readiness.
