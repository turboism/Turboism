# 5.3.02 editor-model 别名回填证据

七个成员均存在。原缺口属于验证记录遗漏。

本次只读检查 JAR。未启动 Cubism。

- JAR：`/opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar`。
- 大小：`41922739` 字节。
- SHA-256：`988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`。
- 身份与 `ReviewedHostArtifacts.CUBISM_5_3_02` 一致。
- 身份与 `reviewed-releases.json` 的 5.3.02 行一致。

核查命令：

```bash
stat -c %s /opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar
sha256sum /opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar
javap -p -s -classpath /opt/dev/projects/turboism/cubism-ref/5.3.02/Live2D_Cubism.jar <下表 owner>
```

每个 alias 均以 `cubism.editor-model.` 开头。
表内名称均未混淆。无需用符号映射推断名称。

| Alias 后缀 | owner | member + descriptor | required / forbidden access | classfile 结果 |
| --- | --- | --- | --- | --- |
| `morph-target-set.remove` | `com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTargetSet` | `remove(Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTarget;)V` | `17 / 8` | `VERIFIED_STATIC` |
| `morph-target.set-parameter` | `com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget` | `setParameter(Lcom/live2d/type/CParameterGuid;)V` | `1 / 8` | `VERIFIED_STATIC` |
| `morph-target.set-parameter-and-key-value` | `com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget` | `setParameterAndKeyValue(Lcom/live2d/type/CParameterGuid;Ljava/lang/Float;)V` | `1 / 8` | `VERIFIED_STATIC` |
| `parameter-controllable-source.internal-set-parent` | `com.live2d.cubism.doc.model.ACParameterControllableSource` | `internal_setParent(Lcom/live2d/cubism/doc/model/parts/CPartSource;)V` | `17 / 8` | `VERIFIED_STATIC` |
| `parameter-group.guid` | `com.live2d.cubism.doc.model.param.group.CParameterGroup` | `getGuid()Lcom/live2d/type/CParameterGroupGuid;` | `17 / 8` | `VERIFIED_STATIC` |
| `part-source.remove-child` | `com.live2d.cubism.doc.model.parts.CPartSource` | `removeChild(Lcom/live2d/cubism/doc/model/ACParameterControllableSource;)V` | `17 / 8` | `VERIFIED_STATIC` |
| `part.id` | `com.live2d.cubism.doc.model.parts.CPart` | `getId()Lcom/live2d/cubism/doc/model/id/CPartId;` | `1 / 8` | `VERIFIED_STATIC` |

`scripts/dev/cubism_matrix/bytecode.py` 的 `verify_selector` 逐项通过。
`javap -p -s` 的输出含下列成员：

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

外推命令：

```bash
python3 scripts/materialize_verification_records.py
python3 scripts/materialize_draft_contracts.py
python3 scripts/dev/cubism_version_matrix.py propagate --family editor-model --from 5.3.01 --to 5.3.02 --dry-run
python3 scripts/dev/cubism_version_matrix.py propagate --family editor-model --from 5.3.01 --to 5.3.02 --write
```

外推结果：1130 → 1137 个 selector。能力数保持 119。
两处无关冲突保留 5.3.02 的原定义：

- `editable-mesh.class` 的 requiredAccessFlags：17。
- `parameter-controllable-source.selection` 的 requiredAccessFlags：1。

将外推结果回写到现有七个 selector 的范围。
在 5.3.02 的 selectorOrder 追加七个索引。
回写后的记录与外推记录内容相同。
同步 5.3.02 的 DRAFT 观察包及 metadata。
同步 manifest 的 propagated alias 集合。
更新两项集成测试的 selector 数量预期。
删除对应单调性例外。
该记录的全部 1137 个 selector 已通过 Java `verifyStaticHostSelectors`。

此证据只证明静态成员契约。它不证明实机 readiness。
