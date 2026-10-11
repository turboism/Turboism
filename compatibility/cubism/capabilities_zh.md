# Cubism Editor 兼容性 —— 能力矩阵

> 由 `scripts/generate_compatibility_matrix.py` 生成；版本覆盖变化后请重新生成（`--check` 已由 `devCheck` 强制执行）。

由审核数据生成的能力/SDK 表面在各已审核 Editor 构件上的准入视图。

## 能力族 × 已审核版本

图例：✓ 组内全部能力均准入 · ◐ 部分准入 · ✗ 该版本无准入。准入 = 静态验证记录在该版本承认该能力，且所有收窄它的选择器契约/类 pin 也承认该版本。

| 能力组 | 5.0.00 | 5.0.01 | 5.0.02 | 5.0.03 | 5.0.04 | 5.0.05 | 5.0.06 | 5.1.00 | 5.1.01 | 5.1.02 | 5.1.03 | 5.2.00 | 5.2.01 | 5.2.02 | 5.2.03 | 5.3.00 | 5.3.01 | 5.3.02 | 5.3.03 | 5.3.04 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 动画与时间线 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ArtMesh | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ◐ | ◐ | ✓ | ✓ | ◐ |
| 自动摆动读取 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 自动备份 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 剪贴蒙版 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Cubism Core | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 变形器编辑 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 编辑级别 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 编辑会话 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 编辑器 UI 表面 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 几何读取 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 编辑历史 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 集成桥接 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 网格工具会话 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 网格工具栏贡献 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 其他模型表面 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ◐ |
| 模型实例读取 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 建模自定义工具 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 对象层级 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 参数与绑定 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 参数键编辑 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 参数结构编辑 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 部件与检查器 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 部件对象编辑 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 性能统计 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 物理 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 插件 UI 表面 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 项目读取 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 受保护导出 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| PSD 原始图像 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 选区编辑 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 启动抑制 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 纹理与原始图像 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ◐ | ◐ | ✓ | ✓ | ◐ |
| 纹理图集 | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ◐ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 撤销恢复 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 曲面变形器镜像 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 工作区 | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

- `cubism.editor-model.animation-eval`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.animation-timeline.write`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.art-mesh.evaluated-read`: 无已验证记录 5.0.00–5.2.02, 5.3.00–5.3.01, 5.3.04
- `cubism.editor-model.art-mesh.morph-authoring`: 无已验证记录 5.0.00–5.2.02, 5.3.00–5.3.01, 5.3.04
- `cubism.editor-model.art-mesh.parameter-interpolation.write`: 无已验证记录 5.0.00–5.2.02, 5.3.00–5.3.01, 5.3.04
- `cubism.editor-model.auto-yure.read`: 无已验证记录 5.0.00–5.0.06
- `cubism.autobackup.backup` (自动备份创建): 无已验证记录 5.0.00–5.0.06
- `cubism.autobackup.settings`: 无已验证记录 5.0.00–5.0.06
- `cubism.editor-ui.embedded-panel` (嵌入式面板停靠): 无已验证记录 5.0.00–5.0.06
- `cubism.editor-ui.top-menu` (顶部菜单集成): 无已验证记录 5.0.00–5.0.06
- `cubism.geometry.read` (网格几何读取): 无已验证记录 5.0.00–5.1.03
- `cubism.editor-history.semantic-read` (语义化历史解读): 无已验证记录 5.0.00–5.1.03
- `cubism.integration.edit-api-bridge.dispatch`: 无已验证记录 5.0.00–5.1.01
- `cubism.integration.external-app-settings.edit-toggle` (外部应用编辑开关): 无已验证记录 5.0.00–5.0.06
- `cubism.editor-model.default-keyform-lock.write`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.moc-export.read`: 无已验证记录 5.0.00–5.3.01, 5.3.04
- `cubism.editor-model.native-control-appearance.write`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.render.read`: 无已验证记录 5.0.00–5.2.02, 5.3.00–5.3.01, 5.3.04
- `cubism.editor-model.source-triangulation.read`: 无已验证记录 5.0.00–5.2.02, 5.3.00–5.3.01, 5.3.04
- `cubism.editor-model.write` (编辑器模型写入): 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.model-instance.read` (活动模型实例读取): 无已验证记录 5.0.00–5.1.03
- `cubism.modeling-tool.selection-brush` (建模选区笔刷): 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.object-hierarchy.apply-deformer-to-children`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.parameter-combined.write`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.parameter-definition.write`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.parameter-structure.write` (参数结构写入): 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.add-parameter`: 无已验证记录 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.add-parameter-group`: 无已验证记录 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.delete-parameter`: 无已验证记录 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.delete-parameter-group`: 无已验证记录 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.edit-parameter`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.edit-parameter-group`: 无已验证记录 5.0.00–5.1.01
- `cubism.editor-model.edit.parameter-structure.move-parameter`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.edit.parameter-structure.move-parameter-group`: 无已验证记录 5.0.00–5.1.01
- `cubism.editor-model.part-inspector.write`: 无已验证记录 5.0.00–5.2.03
- `cubism.editor-model.part-opacity.write`: 无已验证记录 5.0.00–5.2.03
- `cubism.protected-export.orchestration` (受保护导出编排): 无已验证记录 5.0.00–5.0.06
- `cubism.editor-model.psd.raw-image.export`: 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.psd.raw-image.replace` (PSD 原始图像替换): 无已验证记录 5.0.00–5.1.03
- `cubism.editor-model.texture.register-bind`: 无已验证记录 5.0.00–5.2.02, 5.3.00–5.3.01, 5.3.04
- `cubism.texture-atlas.auto-layout-hook`: 无已验证记录 5.0.00–5.1.03
- `cubism.texture-atlas.native-layout-invocation`: 无已验证记录 5.0.00–5.1.03

## 带版本约束的 SDK 表面

仅列出 `@CubismEditor` 解析为已审核集合真子集的表面；未列出的 SDK API 在全部已审核版本上可用（仍受能力准入约束）。

| API | 种类 | 版本 |
|---|---|---|
| `dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#edit()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorSession()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasEditorUi()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.CubismFacade#textureAtlasPolygonLayouts()` | method | 5.2.03–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#artifacts()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupAfterSave()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupNow()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#registerSyncTarget()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#settings()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#statuses()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.backup.EditorAutoBackupService#updateSettings()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.command.EditorCommandService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.edit.CancelSource` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.DeformerOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditAlphaBlend` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditArtMeshData#EditArtMeshData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditCancelledException` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditColorBlend` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditDeformerAttachMode` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditGlueData#EditGlueData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditLabelColor#EditLabelColor()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditLabelColorType` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectData` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectKind` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectNode#EditObjectNode()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditObjectSnapshot#EditObjectSnapshot()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterGroupNode#EditParameterGroupNode()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterKeyCondition#EditParameterKeyCondition()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterNode#EditParameterNode()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditParameterStructureEntry` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditPartData#EditPartData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditRectangle#EditRectangle()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditRotationDeformerData#EditRotationDeformerData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSession` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionCloseOutcome` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionCloseResult#EditSessionCloseResult()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionException` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionOpenResult#EditSessionOpenResult()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionOptions#EditSessionOptions()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionService` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditSessionState` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditUnavailableException` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.EditWarpDeformerData#EditWarpDeformerData()` | method | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.ParameterKeyOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.ParameterStructureOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.PartObjectOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.edit.SelectionOps` | type | 5.1.02–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsContribution#ExportSettingsContribution()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsContributionService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsContributionService.Unavailable` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.ExportSettingsDecision#ExportSettingsDecision()` | method | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.export.Outcome` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshBrush` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditParticipation` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditUiService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshEditor` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshToolContext` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.mesh.MeshToolRegistry` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AlphaComposition` | type | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot#ArtMeshAuthoringSnapshot()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot#ArtMeshEvaluationSnapshot()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshKeyform` | type | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder#ArtMeshKeyformDrawOrder()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions#ArtMeshKeyformPositions()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform` | type | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform#setDrawOrder()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation#ArtMeshSourceTriangulation()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ArtMeshTextureInput#ArtMeshTextureInput()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.AuthorizedImageContent` | type | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.AutoYure` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AutoYureBinding` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.AutoYureConfig` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.CubismExportTexture#CubismExportTexture()` | method | 5.3.02 |
| `dev.turboism.sdk.cubism.model.CubismMocExport#CubismMocExport()` | method | 5.3.02 |
| `dev.turboism.sdk.cubism.model.CubismMocExportSettings#CubismMocExportSettings()` | method | 5.3.02 |
| `dev.turboism.sdk.cubism.model.CubismModel#exportMoc()` | method | 5.3.02 |
| `dev.turboism.sdk.cubism.model.CubismModel#renderPixels()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.CubismRenderedPixels#CubismRenderedPixels()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Deformers#applyToChildren()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Drawable#authoringSnapshot()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Drawable#createMorphKeyform()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Drawable#evaluationSnapshot()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Drawable#morphKeyform()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Drawable#ordinaryKeyform()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Drawable#setAlphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Drawable#setOrdinaryKeyformPositions()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ModelImageBinding#ModelImageBinding()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ModelInstance` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#bindModelImage()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ModelTextures#exportRawImagePsd()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#registerImageAndBind()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ModelTextures#replaceRawImagePsd()` | method | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.ModelTextures#textureInput()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.ParameterInterpolation#ParameterInterpolation()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.Part#alphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#maskIds()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setAlphaComposition()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setMaskIds()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.Part#setOpacity()` | method | 5.3.00–5.3.04 |
| `dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot#RotationAuthoringSnapshot()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.RotationKeyformForm#RotationKeyformForm()` | method | 5.2.03, 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot#WarpAuthoringSnapshot()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.WarpDeformer#authoringSnapshot()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.WarpDeformer#setOrdinaryKeyformPositions()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.WarpKeyformPositions#WarpKeyformPositions()` | method | 5.3.02–5.3.03 |
| `dev.turboism.sdk.cubism.model.YureDeformConfig` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.modeling.ModelingBrush` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.modeling.ModelingToolContext` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.modeling.ModelingToolRegistry` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.psd.PsdEditFile` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.recentfile.RecentFileService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService` | type | 5.1.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.transaction.AuthoringTransactionService` | type | 5.2.00–5.3.04 |
| `dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation` | type | 5.2.00–5.3.04 |

## 仅在部分版本可用的 Editor 命令

仅列出版本集合与完整已审核集不同的命令；运行时以 `EditorCommand#supports` 为准。

| 命令 | 枚举 | 版本 | 性质 |
|---|---|---|---|
| `ADJUST_BOUNDING_BOX_ANGLE` | EditorCommand | 5.2.03–5.3.04 | 真实边界 |
| `DELETE_DEFORMER_ORIGINAL_SHAPE` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `ENABLE_PLAYBACK_CACHE` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `EXPAND_WARPDEFORMER` | EditorCommand | 5.3.00–5.3.04 | 真实边界 |
| `HIDE_OR_RESTORE_PALETTE` | EditorCommand | 5.0.03–5.3.04 | 待确认 |
| `MESH_EDIT_RESET` | EditorCommand | 5.3.02–5.3.04 | 真实边界 |
| `MOVE_END` | EditorCommand | — | 真实边界 |
| `MOVE_END_WORKSPACE` | EditorCommand | — | 真实边界 |
| `OPEN_FAQ_PAGE` | EditorCommand | 5.0.00–5.2.03 | 真实边界 |
| `PARAM_CONTROLLER_CONVERT_TO_LINE_PARAM_CTRL` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `PARAM_CONTROLLER_LINE_PARAM_CTRL_REVERSE_DIRECTION` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `PARAM_CONTROLLER_PREVIEW_BAKE` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `PARAM_CONTROLLER_SPLIT_LINE_PARAM_CTRL` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `PART_PALETTE_CMD_CHANGE_VISIBLE_GUIDE_IMAGE` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `SELECT_CHILD_ELEMENTS_OF_DEFORMER` | EditorCommand | 5.2.03–5.3.04 | 真实边界 |
| `SELECT_OBJECT_BY_CURRENT_KEY_VALUE` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `SETTING_GUIDES_MODELING_ADD_CENTER` | EditorCommand | 5.3.00–5.3.04 | 真实边界 |
| `SHOW_ARTMESH_POINT` | EditorCommand | 5.1.00–5.3.04 | 待确认 |
| `SHOW_DEFAULT_ZOOM` | EditorCommand | 5.3.00–5.3.04 | 真实边界 |
| `SHOW_FULL_SCENE` | EditorCommand | 5.3.00–5.3.04 | 真实边界 |
| `SHOW_HOME_DIALOG` | EditorCommand | 5.1.00–5.3.04 | 待确认 |
| `SHOW_PARAM_CONTROLLER_TARGET_TRACKING` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `SHOW_PARAM_CTRL_MARKER` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `SHOW_POPUP_HOVERING_OBJECT` | EditorCommand | 5.2.03–5.3.04 | 真实边界 |
| `SWITCH_HIGHTLIGHT_CONFLICT_POLYGON` | EditorCommand | 5.3.00–5.3.04 | 真实边界 |
| `SWITCH_LOOP_EDITING_SUPPORT` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `SYNC_PARAMETERS_ACROSS_TABS` | EditorCommand | 5.0.05–5.3.04 | 待确认 |
| `UPDATE_DEFORMER_ORIGINAL_SHAPE` | EditorCommand | 5.2.00–5.3.04 | 待确认 |
| `AUTO_CREATE_DEFORMERS` | EditorParameterizedCommand | 5.1.00–5.3.04 | 待确认 |
| `CHANGE_ALL_MESH_DRAW_ORDER` | EditorParameterizedCommand | 5.2.03–5.3.04 | 真实边界 |
| `CHANGE_MESH_DRAW_ORDER` | EditorParameterizedCommand | 5.2.03–5.3.04 | 真实边界 |
| `CREATE_YURE` | EditorParameterizedCommand | 5.1.00–5.3.04 | 待确认 |
| `DISPLAY_MULTI_PASTE_SHAPE_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 待确认 |
| `EXTERNAL_APP_SETTING` | EditorParameterizedCommand | 5.1.00–5.3.04 | 待确认 |
| `FADE_SETTING` | EditorParameterizedCommand | 5.3.00–5.3.04 | 真实边界 |
| `OPEN_PARAMETER_GROUP_SETTING` | EditorParameterizedCommand | 5.1.02–5.3.04 | 待确认 |
| `PARAM_CONTROLLER_DISPLAY_SETTING_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 待确认 |
| `PARAM_CTRL_CALC_ORDER_SETTING_DIALOG` | EditorParameterizedCommand | 5.2.00–5.3.04 | 待确认 |
| `REFLECT_FADE` | EditorParameterizedCommand | 5.3.00–5.3.04 | 真实边界 |
| `SHOW_LABEL_COPY_DIALOG` | EditorParameterizedCommand | 5.3.00–5.3.04 | 真实边界 |
| `SHOW_ROTATE3D_SETTING_DIALOG` | EditorParameterizedCommand | 5.1.00–5.3.04 | 待确认 |
| `SHOW_SHOW_LABEL_COPY_DIALOG` | EditorParameterizedCommand | 5.0.00–5.2.03 | 待确认 |
| `IMPORT_SCENE_FROM_ANIMATION` | EditorFileCommand | 5.0.03–5.3.04 | 待确认 |
| `EXPORT_AS_TEMPLATE` | EditorFileCommand | 5.1.00–5.3.04 | 待确认 |

## 运行时准入 pin

将已验证能力收窄到特定版本的选择器契约与类 pin 表（功能存在，但适配尚未完成或在其余版本上确实不可用）。

| 来源 | 符号 | 版本 |
|---|---|---|
| class-pins/`float-array-parse.json` | `ClassPinTable` | 5.0.00–5.3.04 |
| class-pins/`image-archive-reuse.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`incremental-update.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`matrix-scratch.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`mesh-mirror.json` | `ClassPinTable` | 5.0.00–5.3.04 |
| class-pins/`mesh-triangulation-hash.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`model-update-skip.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`startup-suppression.json` | `ClassPinTable` | 5.0.00–5.3.04 |
| class-pins/`texture-upload-preparation.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`uniform-location-lifecycle.json` | `ClassPinTable` | 5.2.00–5.3.04 |
| class-pins/`warp-alt-mirror.json` | `ClassPinTable` | 5.0.00–5.3.04 |
| class-pins/`warp-position-projection.json` | `ClassPinTable` | 5.2.00–5.3.04 |

## 版本单调性原则

同一小版本线内，新补丁是旧补丁的超集：在 `5.x.y` 上准入的能力与命令，在 `5.x.y'`（`y' > y`）上同样准入。跨小版本线的差异（如 `5.2.03` 与 `5.3.00`）是真实宿主边界而非回退。

## 运行时能力查询

插件不应按版本号分支，而应查询能力：`CubismFacade` 的各访问器返回带 `isAvailable()` 的哨兵对象；`EditorCommand#supports(String)` / `supportedVersions()` 暴露逐命令版本集合；带注解的表面在宿主未准入时以 `CubismEditorApiUnavailableException` 失败关闭。

## 实机验证任务

取自 `validation/cubism-version-matrix/campaign-jobs.json` 中每个任务×版本的最新 FINAL 结果，按能力域归组。✓ 通过 · ◇ 确认边界（CAPABILITY_BOUNDARY/ADMISSION_STATIC）· ? 待确认（基础设施重试、探针漂移、夹具不适用、未知或未分类）。

| 任务 | 5.0.00 | 5.0.01 | 5.0.02 | 5.0.03 | 5.0.04 | 5.0.05 | 5.0.06 | 5.1.00 | 5.1.01 | 5.1.02 | 5.1.03 | 5.2.00 | 5.2.01 | 5.2.02 | 5.2.03 | 5.3.00 | 5.3.01 | 5.3.02 | 5.3.03 | 5.3.04 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `about-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `acp` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `animation` |  |  |  |  |  |  |  |  |  |  |  | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ✓ |
| `artmesh-mirror-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `atlas-image-observe` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `atlas-image-shadow` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `atlas-polygon` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `autobackup` |  |  |  |  |  |  |  | ◇ | ◇ | ? | ◇ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `boundingbox-warp-mirror` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `boundingbox-warp-mirror-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `clipmask` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `core` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `cursor-coalesce` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `edit-protocol` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ? | ✓ |
| `edit-session` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `editor-ui` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ? | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `external-psd-edit-pipeline` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `external-psd-fixture-preparation` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `glcanvas` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `guard-5302` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `incremental-update` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `integration` | ◇ |  | ? |  |  |  | ◇ | ◇ |  |  | ? | ✓ | ✓ | ? | ? | ✓ | ? | ? | ? | ✓ |
| `media-perf` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `mesh-edit` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `model-import-3d` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `model-import-3d-n2` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `model-import-3d-user-entry` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `model-update-skip` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `modeling-tool` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `native-resource` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `official-core` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `official-viewer` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `parameter` | ? | ? | ◇ | ◇ | ◇ | ◇ | ◇ | ? | ◇ | ◇ | ◇ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `performance` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |  | ✓ | ✓ | ✓ |  | ◇ | ◇ | ✓ |  | ◇ | ✓ | ✓ | ✓ |
| `performance-settings` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |
| `plugin-management-direct-jar-restart` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `plugin-management-direct-jar-stage` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `plugin-ui` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? |  |  |
| `project-workspace` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? |  |  |
| `protected-export` |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |  | ✓ | ✓ |  | ? | ? | ✓ |
| `psd` |  |  |  |  |  |  |  |  |  |  |  | ◇ |  |  | ✓ | ◇ | ✓ | ✓ | ✓ | ✓ |
| `review056` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `selection-brush-coordinate-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-brush-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-brush-round-manual` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `selection-edit` | ✓ | ? | ? | ? | ? | ? | ✓ |  | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `semantic-history-native-ui` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |
| `semantic-history-primary` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  | ? | ? |  |
| `separate-save-path` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ? | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `session-standard` | ? |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  | ◇ |
| `settings-edge-index` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `settings-page` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `startup` | ✓ | ? | ? | ✓ | ? | ? | ✓ | ✓ | ? | ? | ✓ | ✓ | ? | ? | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ |
| `texture` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `texture-atlas` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ? |  |
| `texture-relations-smoke` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5203-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |  |  |  |
| `tlprod-5203-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ? |  |  |  |  |  |
| `tlprod-5302-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5302-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  |
| `tlprod-5303-off` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `tlprod-5303-on` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `turbo-mesh` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `update-check` | ✓ | ✓ | ? | ? | ✓ | ✓ | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ◇ | ◇ | ✓ | ✓ | ◇ | ✓ | ✓ | ✓ |
| `vertex-upload-skip` |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |
| `warp-deformer` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `warp-deformer-alt-symmetry` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `warp-glue` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ? | ✓ |  |
| `warp-point-link` |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ✓ |  |  | ✓ | ✓ |  |
| `workspace` | ◇ | ◇ | ◇ | ◇ | ◇ | ? | ◇ | ✓ | ? | ? | ✓ | ✓ | ✓ | ? | ✓ | ✓ | ? | ✓ | ✓ | ✓ |
