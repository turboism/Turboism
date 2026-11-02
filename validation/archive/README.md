# validation/archive/ — 已结束的一次性实验与探针

此处存放**已结束的一次性实验/探针**。保留历史证据（源码、证据文件、结论文档），不再被任何构建任务或 CI 直接引用；不再新增内容，复活某个探针时请整体移出 `archive/` 并重新接入。

归档目录仍位于 `validation/` 之下，因此会继续被 `validation/` 的通用扫描覆盖（`checkValidationToolsSync` 的 `fileTree("validation")`、`check_code_quality` 的 startup-dialogs 等规则）；这是有意的——归档不改变这些检查的输入，但也没有任何 Gradle 任务/CI 步骤点名它们。

Python lint（`scripts/check_python_lint.py`）**不检查** `validation/archive/`（见 `ruff.toml` 的 `extend-exclude`）。

## 归档清单

| 目录 | 用途（详见各自 README） | 归档原因 |
| --- | --- | --- |
| `atlas-image-timing/` | spec 020/T029/FR-01 的 per-call 图集路径计时 agent：向官方 `CTextureAtlas.updateTexture` 等已复核方法织入 enter/exit 采样。 | 一次性计时取证已完成，结论已收录；后续方案不复用其采样代码。 |
| `extended-interpolation-host/` | 5.3.04 拓展插值宿主验收探针：活体文档内核 API 差分 + 视图拖动 Undo/Redo + 保存重开。 | 该版本宿主验收已结束，仅被同批归档的 performance 探针互相引用。 |
| `extended-interpolation-performance/` | 拓展插值性能调查/优化计划、P3 局部刷新与曲线缓存的探针、离线与安装态证据。 | 优化已落地并由生产侧测试覆盖；证据与一次性探针整体归档。 |
| `extended-interpolation-settings-host/` | 拓展插值设置页 UI 验收探针：经真实 `Ctrl+Alt+S` 设置动作点击复选框并验证重启后生效。 | 两次隔离启动的 UI 验收已完成，仅与同批归档的插值探针互相引用。 |
| `issues54-55-host-acceptance/` | #54/#55 合并宿主验收客户端：复用 `host_validation.py` 的 prepare/submit/wait，不自建调度。 | 两个 issue 的验收已结案，外部零引用。 |
| `model-import-3d-official-core/` | 独立 JVM 的官方 Core 录制与导出离线回归：classpath 仅官方 `Live2DCubismCore.jar` + 自有 recorder JAR。 | 官方 Core 录制/导出验收已结案，外部零引用。 |
| `model-import-3d-official-viewer/` | 官方 Viewer 显示验证：调用已安装 Viewer 主入口打开导出的 `cube.model3.json`，记录截图与 Swing 组件树。 | 官方 Viewer 显示观察已结案，外部零引用。 |
| `perfbench/`（原 `scripts/perfbench/`） | 合成宿主 snapshot-read 路径 A/B microbenchmark（stub Cubism 类 + `bench.*` owner），不进 Gradle 构建。 | 性能实验使用完毕；数字与结论已写入 `scripts/preview/NATIVE-PERFORMANCE-EXPERIMENTS.md`。 |
| `plugin-hot-reload-host-probe/` | Windows 真实 Cubism Editor 会话内的插件热更新实机验证代理（fixture 打包 + premain 探针）。 | 2026-10-31 实机验证已出具结论，外部零引用。 |
| `plugin-version-audit/` | 2026-10-22 第一方插件版本覆盖审核：32 个插件 × 20 个精确版本的静态映射比对。 | 一次性审核快照，结论已并入兼容性矩阵；外部零引用。 |
| `triangulation-dmatch/` | T029-DMATCH：`h.c()` Phase-3 匹配窗口索引化的离线差分实验。 | 实验结论为拒绝，未进入生产；仅被同批归档的 dweave 源码注释提及。 |
| `triangulation-dweave/` | T029-DWEAVE：`h.c()` Phase-3 `MatchList` 单点织入离线切片。 | 离线切片已定论，仅与同批归档的 triweave 互相引用。 |
| `triangulation-identity-probe/` | T029-IDENTITY：`TriangleList.iterator` 静态 vs 运行时集合实现矛盾的 use-site 身份证据。 | 身份取证已出具结论，外部零引用。 |
| `triangulation-k-membership/` | T029-KBUILD：`TriangleList.b()` 局部边成员查询差分实验。 | 结论已定论且被 triweave 泛化吸收，仅与同批归档目录互相引用。 |
| `triangulation-weave-ab/` | T029-TRIAB：官方类织入 A/B 定论腿（`dump-only` vs `dump+weave`，离线切片）。 | A/B 定论腿已出具结论；仅被同批归档目录与文档引用。 |

## 未归档（留原处的原因）

- `validation/triangulation-tlindex/` —— 仍被 `scripts/preview/run-atlas-image-shadow-host-validation.sh` 引用（`home-config-tlindexoff.json`），属活动脚本依赖，不移动。
- `validation/texture-atlas-folders/` —— `git grep texture-atlas-folders` 的目录外命中是 `dev.turboism.plugin.texture-atlas-folders` 这个 **pluginId 字符串**（`plugins/atlas-folder-layout`、`compatibility/cubism/compatibility-matrix.json`、`packaging/windows-installer/plugin-sections.nsh`），不是路径引用；但按“引用需全部来自 validation/ 或 .md”的归档准则从宽保留原处，随时可再评估。
