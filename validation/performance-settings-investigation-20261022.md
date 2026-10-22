# 性能设置与渲染异常调查

日期：2026-10-22。范围：调查、获授权后的实现及验证。下述调查证据保留修复前状态；实现结果见文末。

用户补充的故障环境：Cubism 5.3.02、本地 main 构建、原生 Windows。
原生 Windows 的 inputPathElision 和 mesaGlThread 默认关闭，因此优先检查
modelUpdateSkip、incrementalUpdate 和 uploadElision；仍需记录显式配置和实际 Hook 状态，
不能仅凭平台默认值排除某项。当前 main 源码包含 10 月 12 日的拖动修复，
应优先验证该修复未覆盖的实际拖动路径，同时记录运行 JAR 的构建提交以确认产物一致。

## 性能页面完整范围（首次交付后的缺口记录，后续统一见文末）

性能页共有 **15 个可配置项**（不含说明文字、下载操作和新增诊断按钮），此前的“六项”仅指 `NativeOptimizationConfig` 管理的六个原生优化开关，不能视为整页覆盖。

| 范围 | 页面项目 | 持久化与应用路径 | 本轮覆盖程度 |
| --- | --- | --- | --- |
| 六项原生优化 | 跳过未更改的模型更新、增量更新、着色器参数位置缓存、GPU 上传省略、输入焦点/光标调用省略、Mesa GL 多线程 | 全局 `config.json` 的 `launcher` 字段；下次启动应用 | 本轮实现统一快照、安装诊断及相关渲染基线修复 |
| 四项网格/纹理集优化 | 修复网格三角化哈希退化、索引网格三角化边查询、纹理集 tile 边界限定、复用未更改纹理集页面 | 同一全局文件顶层 `meshTriangulationHashFix`、`meshTriangulationEdgeIndex`、`atlasTileBbox`、`atlasCacheReuse`；独立 Preference 在启动安装 Hook 时读取 | 已核对设置贡献、FileService 与 Hook 读取链路；未纳入新增六项快照/安装状态面板，也未完成这四项对用户症状的真实宿主排除 |
| 五项运行/会话设置 | 自定义 GraalVM 路径、Cubism Java 运行环境、关闭自动备份、ZGC、内存档位 | 同一全局文件；前两项及 ZGC/内存档位由启动器应用，自动备份经会话服务应用 | 原有实现保留，本轮未声称重构或逐项完成实际生效验证 |

当时的完整集成检查通过不等于整页 15 项已逐项验证实际应用；当时新增“查看当前性能优化状态”目前仅覆盖上述六项原生优化。四项独立 Preference 的错误配置/相对路径行为、自动备份的即时应用与整页“下次启动”提示一致性，以及启动器最终 JVM 参数，仍属完整设置审计需要补充的范围。

## 结论与证据等级

### 1. 已复现：输入优化关闭后仍执行

`CubismJvmSettingsContribution.createOptimizationToggle` 保存全局配置后调用
`System.setProperty(enableProperty, ...)`。但 `InputPathElisionBridge.testFocus/testCursor`
只检查 `active` 和安装时设置的 `armed`，不检查生产开关。

使用真实生产 Bridge 和仓库已有的合成宿主，通过已发布的 cursor callback 连续调用：

```text
enabled skips=true
disabled still skips=true
```

因此当前进程的属性与执行状态可能不一致。类似地，GPU 上传 Bridge 的
`test` 也未检查 `ENABLE_PROPERTY`；这是代码确认，尚未在本次探针中执行该 Bridge。
Mesa 开关包含进程启动前环境变量，运行中修改 Java 属性无法改变整个选项。

注意：设置页面已有“下次启动生效”的提示。这些发现确认了内部热切换意图未完整实现，
不表示已证实重启后的持久化失效。

### 2. 已复现：增量更新首次进入即可跳过计算；视觉影响待宿主确认

`IncrementalUpdateBridge.beginUpdate` 的 documentChanged 只覆盖“同一个文档的修改时间改变”，
没有把首次更新、不同文档或模型身份改变列为强制完整更新条件。
`authoringFull` 依赖当前/上次 editing 以及这个 documentChanged。

真实生产 Bridge + 现有合成模型测试夹具的首次更新结果：

```text
epochFull=false, epochUnsafe=false
epochs=1, markCalls=2, markedClean=2, markedDirty=0
meshSkipped=1, meshDeformed=0, failures=0
```

说明优化没有要求先建立由完整计算得到的基线。夹具的输出表单本身已预建，
此结果证明“缺少首次完整更新保护”，不直接证明真实宿主首帧一定错误。
仅在 incrementalUpdate 实际启用时才可能解释用户现象；该选项默认关闭。

### 3. 拖动刷新曾有同症状修复，当前源码已包含

提交 `c4d88f950`（2026-10-12）已让模型更新跳过和增量更新识别
`ACEditMode.isEditing()`，并让增量路径在拖动结束后再完整更新一次。

本次执行的现有测试包含编辑模式拖动、父子变形器脏传播等，均通过。
不能直接把相同旧问题当作当前根因。需要核对运行包是否包含修复，以及实际失败拖动期间
`CModelSource.isModelEditing` / `ACEditMode.isEditing` 的值。
增量路径目前只检查后者；整帧跳过路径检查二者，这是需要宿主验证的覆盖差异。

### 4. 全局文件已经存在，缺的是统一解析和应用状态

`CubismJvmSettingsFileService` 通过 `RuntimeConfigRepository` 读写
`<TurboismHome>/config.json` 的 `launcher` 节点。保存已有校验、临时文件和原子替换。

但设置 UI 读取文件偏好，启动策略优先读 JVM 属性，再读文件，Bridge 又有各自的
缓存默认值/armed 状态，启动器还处理平台默认值和依赖。UI 勾选不等价于 Hook 已安装或已生效。

另一个语义问题是 `saveOptimization` 在用户选择等于平台默认值时删除字段。
这样无法区分“用户明确选择”与“跟随默认值”；跨平台或默认值升级后可能改变行为。
这是配置表达能力问题，不是本次已复现的文件读写错误。

## 修复顺序

1. **统一全局配置与生效模型**
   - 保留现有全局 config.json 和 launcher 字段，避免不必要的迁移。
   - 设置页只提交持久化选择；每次启动重新读取并解析完整优化快照。
   - 集中定义默认值、平台条件和依赖；启动器与运行时用契约测试保持一致。
   - 显式保存用户 true/false；缺省才表示跟随默认值。
   - 区分已保存值、当前生效值、待重启、未安装/不支持/依赖未满足，并记录原因和配置路径。
   - 常规变更统一下次启动生效，移除当前零散的 System.setProperty 热切换。
     若保留紧急关闭，应提供完整、独立的禁用入口，保证清缓存、恢复原生路径。
   - JVM 参数只作为明确的诊断覆盖，显示其覆盖来源；不得静默覆盖用户选择。

2. **补齐计算基线与失效条件**
   - 首次载入、模型/文档代际切换、重新载入、优化重新启用时，先完整计算，成功后才允许复用。
   - 完整计算失败不得建立基线；关闭文档和异常路径清理相应缓存。
   - 联合识别 source editing、edit-mode editing 及真实拖动生命周期；未知状态回退完整计算。
   - 验证所有后代（含网格）在拖动每一帧更新；结束、取消、Undo/Redo 后再完整刷新。
   - 若单项复现指向上传或 uniform 缓存，再针对 GL 上下文、资源重建及生命周期修复失效边界。

3. **先回归测试，再修改实现**
   - 首次 epoch、A→B→A 文档切换、重载和重新启用必须完整计算。
   - 两种 editing 信号分别单独为真时，父子变形器和网格必须更新。
   - 全部优化偏好覆盖 true/false/缺省、原生 Windows/Proton、保存失败、重启读取、诊断覆盖及依赖不足。
   - 保留全局配置的原子写入行为，不在每帧做磁盘读取。

4. **真实宿主验收与归因**
   - 固定 Cubism 5.3.02、原生 Windows、本地 main 构建与实际故障模型，记录运行 JAR 的构建提交。
   - 首先读取实际配置与 Hook 生效状态：若 incrementalUpdate 未启用，首次增量更新缺口不能解释本次视觉故障，
     优先验证 modelUpdateSkip，再比较其与 uploadElision 的组合。
   - 每一组均重启：全部关闭基线 → 单开 modelUpdateSkip → 单开 incrementalUpdate →
     modelUpdateSkip+uploadElision → 单开 uniformLocationCache → 按平台测试 inputPathElision/Mesa → 全开组合。
   - 无点击载入首帧、非聚焦载入、A/B 切换、父子多层弯曲拖动、松手/取消、Undo/Redo。
   - 对照原生路径检查每帧几何/渲染结果及截图；仅耗时、计数或零异常不能作为正确性证据。

## 调查阶段验证与限制（实现前）

- 使用缓存依赖，直接 javac 编译相关生产类和四个现有测试类，以临时反射执行器运行
  `@Test` 并执行 `@AfterEach`；49 项全部通过。未运行 Gradle 全量测试。
- 两个定向探针使用同一套真实 Bridge、现有合成宿主及其发布回调。
  临时脚本和结果位于 `/tmp/turboism-perf-investigation/`，不进入发布产物。
- 未在本次调查中运行实际 Cubism，未复现用户视觉故障；视觉根因仍需上述宿主对照确认。
- `/root/.codex/RTK.md` 普通读取及提升权限读取均被文件系统拒绝，未能读取。


## 已实施的修复

### 全局配置与安装诊断

- 保留 `<TurboismHome>/config.json` 的 `launcher` 字段，无需迁移文件。
- 新增 `NativeOptimizationConfig`：一次启动共用六项优化的不可变快照；每次启动重新读取文件，运行帧不读磁盘。无配置时按平台默认值解析，非法配置关闭优化。
- 设置页修改只写全局文件，统一下次启动生效，移除不完整的运行时属性切换。明确保存布尔值，避免跨平台或升级时显式选择随默认值漂移。
- 诊断 JVM 参数仍可显式覆盖启动请求，不回写用户配置；状态标明 `jvm-override` 来源。
- 性能页面增加“查看当前性能优化状态”，展示文件路径、已保存值、启动请求值、来源、实际安装状态和原因。拒绝安装不再错误报告 `COMPLETE`。
- 状态反映安装结果，不声称每帧都执行该优化；依赖不满足时保留原生执行路径。

### 完整计算基线与拖动保护

- 增量更新首次运行、模型/上下文/文档/source/参数容器切换、文档修改、重新启用以及编辑开始/结束时强制完整计算。
- 仅正常返回的匹配更新可以提交可复用基线；中断或回调异常不复用旧基线。首次完整计算前不启用宿主插值跳过。
- 增量更新同时识别 `CModelSource.isModelEditing()` 与 `ACEditMode.isEditing()`，缺失或未知状态保守执行完整计算；补齐经过审查的依赖及类指纹。
- 额外复现并修复默认开启的整帧跳过问题：宿主原地修改 selection 列表时旧快照被同步改写，以及完整更新中断后仍可能使用旧基线。现在保存独立 selection 快照，检查 source/参数容器身份，未完成或重新启用后重新建立基线。
- 关闭 Bridge 时清理基线引用和缓存，并恢复宿主插值标志。

## 实现后的验证

回归测试覆盖首次完整更新、A→B→A 切换、中断后重试、禁用/重新启用、原地 selection 修改、仅 source editing 为真的多层子物体/网格逐帧更新，以及配置快照、显式值持久化和启动/保存状态分离。

- 定向测试及 Java 格式检查通过，`git diff --check` 无错误。
- 使用真实 Cubism 5.3.02 JAR，执行 `ModelUpdateSkipArtifactTest.cubism5302EntryAndDependenciesAreAdmitted` 与 `IncrementalUpdateArtifactTest.cubism5302EntryAndDependenciesAreAdmitted`，两项均通过且未跳过。覆盖目标和新增依赖准入及字节码变换，不等同于画布运行验证。
- JAR SHA-256：`988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`。
- headless 模式的 `checkIntegration`（包含 `devCheck`）、`:core-contract:test`、三个模块 Java 格式检查及上述真实 JAR 测试全部通过，`BUILD SUCCESSFUL in 6m 8s`。本次完整测试报告中的主要套件：
  - `runtime/admissionUnitTests`：3857 项，失败 0，错误 0，跳过 6。
  - `bootstrap/test`：232 项，失败 0，错误 0，跳过 27。
  - `core-contract/test`：29 项，失败 0，错误 0，跳过 0。
  - `runtime/legacyCubismEvidenceTest`：2 项，失败 0，错误 0，跳过 0。
- 完整命令输出：`/tmp/perf-integration.log`；测试 XML 位于 `build/worktree/scary-shrimp/<module>/test-results/`。跳过项包含宿主/桌面环境约束，不能作为 Windows 视觉验证通过。
- 首次有桌面的全量测试阻塞于现有 `ScreenshotCaptureHostOperationsTest` 的 Linux 原生桌面抓取；终止该测试进程后以 headless 模式重跑，避开本环境无法完成的屏幕捕获。

## 原生 Windows 仍需验收

当前机器无法完成用户所述原生 Windows 视觉复现。因此已经确认并修复独立可复现的实现缺陷，但尚不能断言用户的两个视觉症状均已解决，也不能仅凭类指纹或单元测试确定最终归因。

测试包：`build/preview/scary-shrimp/turboism-agent.jar`。在原生 Windows Cubism 5.3.02 中使用该构建重启后，先打开性能状态入口核对保存值、请求来源和安装结果，再按本报告的真实宿主矩阵检查：未点击画布首帧、文档切换、弯曲变形器拖动全过程、松手/取消与 Undo/Redo。设置变更需重启后对照。


## 后续统一：性能页全部 15 项

用户要求“请你统一”后，完成以下补充实现：

- 新增 `PerformanceSettingsConfig`，集中定义全部 15 个字段的位置、类型、默认值及启动快照。保留旧字段位置：网格/纹理集/自动备份在全局文件顶层，其余在 `launcher`，无需迁移，也继续兼容现有 Windows/Proton 启动器。
- 全页 FileService 使用相同字段定义和经过校验的原子保存入口，布尔值及内存档位的显式选择不再通过删除字段表达。空 GraalVM 路径仍表示自动发现，按既有 schema 省略字段。
- 十项原生优化共用一次启动快照；原来的四个独立 Preference 成为共同读取器的兼容入口，修复相对 home 路径读取错误，并统一非法文件/错误类型为关闭优化。设置页也不再把损坏配置显示成默认开启。
- 自动备份编辑只保存全局偏好，不在当前会话立即应用。启动时使用已捕获的请求，统一整页“下次启动生效”的规则；记录真实宿主备份状态或应用失败。
- 十项优化均记录实际安装尝试结果，拒绝安装和失败不作为安装成功。安装状态仅代表 Hook/Transformer 安装，不保证宿主目标类后续变换成功或每帧执行了优化。
- 状态窗口展示全部 15 项的保存值、启动快照请求、来源、当前观测与待重启标记。Java 显示当前 VM/vendor 和 java.home，GC 显示真实收集器名称，内存显示实际堆上限及有关 JVM 参数，不根据配置猜测这些选项已成功应用。未观察到应用时明确显示未知/未观察。
- 状态窗口增加滚动、选择与复制，容纳完整清单。待重启标记表示当前保存值与启动请求不同；若存在 JVM/环境覆盖，来源会同时显示，需处理覆盖才能让保存值生效。

### 补充验证

- 配置相关 130 项定向测试通过；bootstrap Hook/快照定向测试与 core-contract 测试通过。日志：`/tmp/perf-unify-test.log`。
- 新测试覆盖全部 15 项原路径读写、更新其中一项不丢失其他字段、不可变启动请求和下次启动重新读取、四项优化与六项优化共用快照、全页状态清单、实际 JVM 路径、待重启状态、自动备份不热切换，以及相对路径读取。
- 原生 Windows 的画布症状仍需实际宿主复测；本补充不改变前文的证据限制。
- 最终 `checkIntegration`（含 `devCheck`）、`:core-contract:test` 及 runtime/bootstrap/core-contract 格式检查全部通过，`BUILD SUCCESSFUL in 4m`。日志：`/tmp/perf-unify-integration.log`。
- 全量 bootstrap 测试 232 项，失败/错误 0，环境限定跳过 27 项；runtime admission 套件 3857 项，失败/错误 0，跳过 6 项。新增配置测试已通过前述单独定向执行，不能把环境跳过项视作实机通过。
- 已重新生成 `build/preview/scary-shrimp/turboism-agent.jar`，可用于 Windows 5.3.02 实机验收。源码未提交、未推送，未替换实际安装包。
