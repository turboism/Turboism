# 第一方插件版本覆盖审核（2026-10-22）

范围：当前 checkout 的 32 个第一方插件；Cubism 5.0.00–5.0.06、5.1.00–5.1.03、5.2.00–5.2.03、5.3.00–5.3.04，共 20 个精确版本。包含 demo。

## 结论

本次未发现已提交静态映射的混淆名、成员描述符、访问标志或类字节码哈希与对应原始 JAR 不匹配，也未发现插件版本 sidecar 与生成矩阵不一致。

这不等于 32 个插件在全部版本都具备完整功能：按当前声明，19 个插件全部版本完整覆盖，11 个插件存在功能边界，2 个插件仅支持 5.1.00 及以上。下面的“完整功能”是仓库兼容性模型的计算结果，不代表本次逐项实机验收。

## 验证证据

- `python3 scripts/check_version_set_completeness.py`：20 个版本在 12 处重述一致。
- `python3 scripts/generate_compatibility_matrix.py --check`：矩阵、各插件 sidecar、README 和市场发布清单没有过期。
- 对本机 `cubism-ref` 的每个版本，实测 Editor/Core JAR 大小与 SHA-256，与记录完全一致；279 份记录共 32,941 个选择器，由 Python 元数据检查和当前 checkout 编译的生产 `StaticVerificationCli.verify` 分别校验，均无失败。
- 14 张 class-pin 表共 956 项版本/类 SHA-256 与对应原始 Editor JAR 匹配。
- `./gradlew --offline :runtime:classes verifyFirstPartyPluginMetadata`：32 个构建插件的生产元数据验证通过。
- 32 个插件的 `:plugins:<module>:test` 全部成功，部分任务使用 Gradle 缓存。XML 合计 1,536 个测试，1,498 个通过，38 个跳过，0 失败、0 错误。
- 版本准入、选择器、mesh、warpalt、model 适配测试任务成功；生命周期测试 83 个通过，无跳过。
- `TURBOISM_CUBISM_REF_ROOT=<cubism-ref> ./gradlew --offline :runtime:warpAltMirrorArtifactTest`：20 个原始 JAR 的 Alt 镜像钩子变换与按钮表面测试通过，无跳过。
- 从当前生产 `MeshMirrorHostProfile` 提取主入口、旧版绘制路径、移动与关联删除选择器，261 个非空方法目标与各版本原始 JAR 匹配；5.0/5.1 的 11 个专用绘制入口为空，按其 legacy draw 路径检查。此项验证目标存在性，不声称执行了全部网格变换。
- 通过当前生产 `ProjectLifecycleHostProfile`，额外逐一校验全部 20 个版本、每版 7 个生命周期入口，均匹配原始 JAR 的方法名与描述符。

## 发现的覆盖缺口

以下为初检结果。参数锁两项能力的生成器覆盖已在后续修复中补齐，并新增逐版本原始 JAR 钩子变换检查，见[参数锁版本兼容性](../parameter-lock/VERSION-COMPATIBILITY.md)。机器可读 JSON 保留初检快照。

1. `parameter-lock` 的 `cubism.model.observe`、`cubism.model.intercept` 出现在 `unmappedCapabilities` 中。生成器没有建模这两项钩子声明，当前“完整功能 20/20”不能单独作为这两项钩子的证明。本次生命周期入口校验及相关回归通过，但没有据此捏造 capability alias 或扩大运行时准入。
2. 插件测试有 38 个跳过：ACP 3 个、3D 导入 34 个、性能统计 1 个。跳过项未计为通过；本次没有补齐其外部 CLI、模型资源或图形环境要求。
3. 本次未启动 20 个 Editor 做 32×20 实机功能验收。既有 `campaign-jobs.json` 的 PASS、边界和空白维持原始含义；静态匹配不能证明交互语义、像素结果或所有运行时分支正确。

## 逐插件结果

“通过 / 跳过”为插件测试数；版本数仅在上述 20 个精确版本集合内计算。完整功能列同时受 SDK 与功能边界约束。

| 插件 | 支持版本数 | 完整功能版本数 | 测试通过 / 跳过 | 版本差异 / 覆盖缺口 |
|---|---:|---:|---:|---|
| `acp` | 20/20 | 20/20 | 198 / 3 | 无已声明版本差异 |
| `atlas-bitmask-nesting` | 20/20 | 9/20 | 18 / 0 | dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService |
| `atlas-dalsoo-polygon` | 20/20 | 9/20 | 45 / 0 | dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService |
| `atlas-folder-layout` | 20/20 | 13/20 | 16 / 0 | cubism.editor-ui.top-menu |
| `atlas-maxrects-bssf` | 20/20 | 20/20 | 38 / 0 | 无已声明版本差异 |
| `boundingbox-warp-mirror` | 20/20 | 20/20 | 6 / 0 | 无已声明版本差异 |
| `clipmask-viewer` | 20/20 | 13/20 | 46 / 0 | cubism.editor-ui.top-menu |
| `command-palette` | 13/20 | 13/20 | 17 / 0 | cubism.editor-ui.top-menu；dev.turboism.sdk.cubism.command.EditorCommandService |
| `cubism-tab-filter` | 20/20 | 20/20 | 5 / 0 | 无已声明版本差异 |
| `demo` | 20/20 | 20/20 | 2 / 0 | 无已声明版本差异 |
| `external-psd-edit` | 20/20 | 9/20 | 57 / 0 | dev.turboism.sdk.cubism.model.ModelTextures#exportRawImagePsd()；dev.turboism.sdk.cubism.model.ModelTextures#replaceRawImagePsd()；dev.turboism.sdk.cubism.psd.PsdEditFile |
| `frame-animation-import` | 20/20 | 5/20 | 23 / 0 | dev.turboism.sdk.cubism.model.Part#setOpacity()；cubism.editor-ui.top-menu |
| `history-panel` | 20/20 | 20/20 | 38 / 0 | 无已声明版本差异 |
| `mcp` | 20/20 | 13/20 | 239 / 0 | cubism.editor-ui.top-menu；dev.turboism.sdk.cubism.command.EditorCommandService；turboism.editor_commands.execute；turboism://host/editor-commands |
| `mesh-edit-mirror-axis-enhance` | 20/20 | 9/20 | 9 / 0 | cubism.geometry.read |
| `model-import-3d` | 20/20 | 20/20 | 271 / 34 | 无已声明版本差异 |
| `palette-label-style` | 20/20 | 20/20 | 56 / 0 | 无已声明版本差异 |
| `parameter` | 20/20 | 20/20 | 34 / 0 | 无已声明版本差异 |
| `parameter-batch-transfer` | 20/20 | 20/20 | 30 / 0 | 无已声明版本差异 |
| `parameter-lock` | 20/20 | 20/20 | 42 / 0 | 无已声明版本差异；observe/intercept 未被生成器映射 |
| `perf-stats` | 20/20 | 20/20 | 21 / 1 | 无已声明版本差异 |
| `physics-editor` | 20/20 | 20/20 | 2 / 0 | 无已声明版本差异 |
| `project-inspector` | 20/20 | 20/20 | 16 / 0 | 无已声明版本差异 |
| `protected-export` | 13/20 | 13/20 | 38 / 0 | cubism.protected-export.orchestration；dev.turboism.sdk.cubism.export.ExportSettingsContributionService |
| `psd-clip-mask-import` | 20/20 | 20/20 | 43 / 0 | 无已声明版本差异 |
| `recent-preview` | 20/20 | 20/20 | 44 / 0 | 无已声明版本差异 |
| `scene-palette-enhancer` | 20/20 | 20/20 | 16 / 0 | 无已声明版本差异 |
| `selection-brush` | 20/20 | 9/20 | 6 / 0 | cubism.modeling-tool.selection-brush |
| `texture-atlas-stats` | 20/20 | 9/20 | 1 / 0 | dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel；dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession；dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi |
| `ui-theme` | 20/20 | 20/20 | 47 / 0 | 无已声明版本差异 |
| `warp-deformer-alt-symmetry` | 20/20 | 20/20 | 8 / 0 | 无已声明版本差异 |
| `webdav-backup` | 20/20 | 13/20 | 66 / 0 | dev.turboism.sdk.cubism.backup.EditorAutoBackupService；dev.turboism.sdk.cubism.backup.EditorAutoBackupService#artifacts()；dev.turboism.sdk.cubism.backup.EditorAutoBackupService#backupAfterSave() |

机器可读统计见 [audit-20261022.json](audit-20261022.json)。未修改插件实现、运行时准入、版本清单或既有实机验证结果。
