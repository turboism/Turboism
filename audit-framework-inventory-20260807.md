# Framework Inventory — 2026-08-07

> Worktree: `framework-inventory-20260807` @ `f2a75243` (main HEAD)
> 只读盘点，未修改任何代码。

## 1. 模块结构（settings.gradle.kts）

```
:bootstrap      Java Agent 入口 + Verified*HookInstaller（字节码 transformer）
:runtime        插件运行时、策略、版本化 Adapter/Provider、mapping、hook、事务、诊断
:sdk            插件唯一公共依赖（compileOnly）
:plugins:*      23 个一等插件（与三方插件同边界，只依赖 :sdk）
:testframework  假宿主/夹具/测试支撑
:tests          跨模块与打包集成测试
```

依赖方向：`Plugin -> SDK -> Runtime policy -> versioned Adapter/Provider -> Cubism/Editor`

规模：SDK main 342 文件 / Runtime main 622 文件（10KB+ 巨型文件 85 个）/ 插件 205 文件 / 测试 378 个（runtime 280 + 插件 70 + tests 69 + sdk 48+2）。

## 2. SDK 能力清单（dev.turboism.sdk.*，342 文件 / 18 包）

| 包 | 能力 |
|---|---|
| plugin | 生命周期（init/enable/disable/shutdown）、PluginContext（~20 服务入口）、Descriptor、Logger、DisposableScope、CancellationToken |
| cubism | CubismFacade 统一入口、全套快照 DTO（Project/Document/Model/Animation/PSD/ClipMask/…） |
| cubism.model | 统一对象 API：CubismModel/Parameter/Part/Drawable/Deformer/Glue/WarpGrid、ParameterBinding、ModelStatistics、ModelEditLevel |
| cubism.service.read | CubismReadCapabilityService（快照读全家桶） |
| cubism.service.query | Parameter/Selection/ModelHierarchy 查询服务 |
| cubism.service.clipmask | CubismClipMaskService |
| cubism.command | EditorCommandService、参数化命令、文件命令 |
| cubism.write / transaction | 窄写命令 DTO（Preview）+ 遗留 TransactionManager（Preview） |
| cubism.hook / event | 覆盖式生命周期 hook（before/on/after）、语义操作事件 |
| cubism.textureatlas | 布局服务/编辑器会话/算法注册 |
| cubism.physics / psd / recentfile / recentpreview / screenshot / boundingbox | 各自域服务 |
| cubism.id | 类型化 ID 家族（ParameterId/PartId 在 model 包） |
| ui | PanelView、UiHostCapabilityService、对话框、工具栏、Workspace、ContextMenu、Filter、appearance（label 颜色）、UserFile（位于 ui 包，命名错位） |
| config | 类型化插件配置注册/编解码/迁移 |
| task / storage / hostread | 任务调度 API、插件存储、异步宿主读 |
| runtime | RuntimeSettingsService（全局运行配置） |
| appearance / theme | 主题应用/恢复 + 主题状态快照 |
| i18n / menu / action / event / diagnostics / permission | 本地化、菜单/动作注册、EventBus、诊断、权限 |

@PreviewApi 标记：166 处。稳定面受 ADR 0026 约束。

## 3. Runtime 能力清单（dev.turboism.*，622 文件）

| 包 | 能力 |
|---|---|
| adapter/cubism | CubismFacadeImpl(80KB)、Verified*HostOperations、CoreBacked/EditorBacked 模型访问、textureatlas、physics、lifecycle coordinators（8 个）、write/transaction 实现 |
| adapter/host | HostSession、VerifiedHostAdapterConnector、Dynamic 回退、宿主身份验证证据 |
| adapter/ui | StatusToolbar、ThemeStatus、Screenshot、RecentFile 适配 |
| mapping/{draft,schema,verification} | 选择器契约、VerifiedMemberResolver、验证 manifest、静态验证 CLI（Lane C 反射安全） |
| ui/* | UiHostCapabilityService 实现、对话框、上下文菜单、面板、工具栏、表、overlay、appearance/control |
| core/* | CorePluginContext(38KB)、action/menu/event 注册、RuntimeScheduler、work executor、sidecar（隔离进程）、schema/descriptor/version |
| config / task / storage / hostread / userfile | 各 SDK 接口的 runtime 实现 + 线程/配额/清理 |
| preview | PreviewRuntime、插件加载器、故障报告、discovery |
| pluginmanagement / distribution / home | 插件安装/卸载服务、包检查、home 布局 |
| hook/ingress | 有界 hook 邮箱、ingress 注册 |
| runtime/log | CubismLogServiceHost |

## 4. 插件清单（23 个，已按行数排序）

ui-theme(31) log-filter(15) clip-mask(15) core(14) atlas-maxrects-bssf(14) recent-preview(13) render-opt(12) clipmask-viewer(12) psd-import(10) project-panel(10) parameter(10) perf-opt(9) palette-label-style(8) context-menu(8) bounding-box(6) scene-palette-enhancer(4) mesh(4) project-inspector(3) texture-atlas-stats(2) physics-editor(2) cubism-tab-filter(2) demo(1)

## 5. 冲突 / 重复项（按严重度）

### C1. SDK 双读取入口：CubismFacade vs CubismReadCapabilityService
- `CubismFacade.activeProject/activeDocument/activeModel/activeAnimation/activeImageDocument/activeProjectContent` 与 `CubismReadCapabilityService` 同名 6 方法**逐字节相同**（含相同 default 实现）。
- 两个入口同时暴露在 PluginContext（`cubism()` 与 `cubismRead()`），runtime 在 DefaultCubismServicesFactory 中并行构造两份。
- 建议：cubismRead() 改为 CubismFacade 的派生视图（delegate），或 CubismFacade 实现 CubismReadCapabilityService；单一事实源。

### C2. 死代码：runtime 内 SDK 类型的平行副本
- `runtime/core/runtime/PluginTaskPriority`（IMMEDIATE/NORMAL/BACKGROUND）：**0 引用**；运行时实际使用 `sdk.task.PluginTaskPriority`（NORMAL/LOW，枚举值还不一致）。删除。
- `runtime/ui/panel/` 的 CollapsibleSectionContribution(接口,中文 javadoc) + CollapsiblePanel + CollapsibleSection + CollapsibleSectionRegistry：整簇**无外部引用**；活路径是 sdk record + PanelCollapsibleContentCoordinator。删除 4 文件。

### C3. 孤立测试树：sdk/test/java
- `sdk/test/java/dev/turboism/sdk/cubism/{CubismFacadeContractTest,SnapshotImmutabilityTest}` 不在任何 sourceSet（默认 src/test），是孤儿。接入或删除。

### C4. 未接线的 API 门禁（ADR 漂移）
- ADR 0026 声称 `checkSdkV2ExactApiCompatibility` + `docs/sdk/baselines/sdk-api-v2-exact.json` 是生产门禁，**两者当前都不存在**；只剩 `sdk_api_tiers_trust.py`（205B 信任锚存根）。现有 `checkPackageLayout` 只禁老 callback 包。
- 建议：补接线或修正 ADR/文档，并把 C2 死包纳入 checkPackageLayout。

### C5. 插件级近似重复
- **clip-mask vs clipmask-viewer**：各自实现独立 ClipMaskAnalyzer + b1/domain（同为 SDK CubismClipMaskService 的消费者），一个做问题分析/表格，一个做重复检查/可视化。同一域两套分析。建议合并域模型或二选一。
- **perf-opt vs render-opt**：同构（ConfigBindingResult + b1/application + overlay 服务），FPS overlay vs render-status overlay，同为 fake-ready。建议合并为单一 overlay 能力。
- **log-filter vs cubism-tab-filter**：都碰 Log 面板（级别切换 vs 关键字过滤框），同一面板两处 UI 挂载点，存在视觉/行为冲突风险。
- project-inspector（调试窗口）vs project-panel（迁移壳，无 UI）：命名冲突但功能不重叠；project-panel 是纯壳。
- scene-palette-enhancer vs cubism-tab-filter：都操作 palette UI（排序 vs 过滤框）。

### C6. Preview 死面：write/transaction API
- `sdk/cubism/write`（Write*Command）与 `sdk/cubism/transaction`（TransactionManager）**生产插件 0 使用**（仅测试引用）；runtime 用 main 源码里的 `FakeHostWriteAdapter`(14KB) 支撑。建议：砍掉或转入真实用途；Fake 不应留在 main。

### C7. 命名/归属错位（轻微）
- `UserFileAccessService` 全家位于 `sdk/ui` 包下（不属于 UI）。
- `PartId/GlueId` 在 model 包，其余 ID 在 cubism/id 包，ID 家族被拆两处。
- 三个 "core"：plugins:core（内置插件）、dev.turboism.core（框架核心）、sdk cubism.core（Core 运行时信息）。
- 三处 "appearance" 概念：sdk/appearance（主题应用）、sdk/theme（状态）、sdk/ui/appearance（label 颜色）。
- 两套调度栈（core/runtime 的 RuntimeScheduler+sidecar vs task/ 的 RuntimePluginTaskScheduler）均活跃，服务对象不同（adapter 内部 vs 插件 API），建议在架构文档明确边界或收敛。

### C8. 有意的双路径（非缺陷，但需管理）
- Verified*(39) / Dynamic*(5) / Unavailable*(3) 三态适配；模型访问 4 实现（CoreBacked/EditorBacked/Dynamic/Unavailable）。这是设计（verified 优先 + dynamic 回退），但 Dynamic 回退是永久 2 倍面，建议定期验证其真实可达性。

## 6. 建议优先级

1. C2 删除死代码（低风险、立即可做）
2. C1 统一 SDK 读取入口（API 面收敛，涉及 baseline 门禁，需先解决 C4）
3. C4 门禁接线或文档修正（合规风险）
4. C5 插件合并决策（clip-mask/viewer、perf/render-opt 二选一或共享域模块）
5. C3/C6 清理孤立测试与 Preview 死面
6. C7 命名与归属整理（可随大版本）
