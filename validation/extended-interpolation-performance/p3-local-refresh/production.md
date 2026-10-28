# Normal 切片局部刷新生产落地

目标版本 5.3.04；先目标验证，再验证已收录的 20 个稳定工件（5.0.00–06、5.1.00–03、5.2.00–03、5.3.00–04）。不外推至 beta、4.x、未来工件或所有版本 UI。

## 实现

原生仍按原顺序遍历所有轴和组合切片，保留 `force=true`、直接父 Part 广播和完整派生形态网格。新增的是 common 内的固定点计算跳过：先实测一次完整计算，只有全部目标坐标和属性前后 raw bits 完全不变，才允许后续完整输入及目标状态相同的调用跳过计算、混合和坐标写回。命中时按实际成功写回掩码、按 point-major/target-minor 顺序逐次调用官方 final `setUpdated()`，保持绝对版本和通知轨迹。

- `LocalFormState` 在类准入时绑定 MethodHandle，封闭字段 schema 校验，包含 `_drawOrder`、opacity、RGBA、Rotation origin/angle/scale/reflect 及其他基元字段；未知字段禁用本层优化。
- 完整数值签名包含源坐标、节点、插入标志/区间/权重、曲线模式、弧度常数、源属性，以及当前目标的全部坐标和属性；使用 raw bits 和完整数组相等判断，不依赖哈希唯一性或 EditVersion 充分性。
- 独立源几何准入表要求相同源输入至少出现 5 次，才扫描完整目标状态。避免瞬态编辑输入产生大快照，也避免曲线 LRU 在多轴扫描中自我逐出导致局部优化无法准入。
- ArtMesh/Warp 的 ELLIPSE/SNS 支持局部跳过。Rotation ELLIPSE 支持；Rotation SNS 继续既有单原点官方等价计算，基准表明该路径检测收益不足。
- 存在 Morph 源或 Morph 扩展 bank 时不局部跳过，继续既有全量更新路径。非有限元数据、可变颜色别名或无法识别 schema 不准入；非有限坐标在写入前退回官方路径。原有工件摘要、类加载器、拓扑和数组别名门禁保留。

## 生命周期与预算

缓存只持有基本类型数组，不保留模型、form、owner、Class 或类加载器。结果 LRU 最多 64 条、计费 8 MiB、单条 1 MiB；独立源准入 LRU 最多 128 条、计费 1 MiB；scratch 池最多 8 个数组、数组载荷合计 1 MiB。三者预算独立，总计费上界为 10 MiB，不是进程峰值或实际堆精确上限；调用中借出的临时数组及集合/对象头另计。

Scratch 独占借还，所有返回/异常路径通过 finally 归还；发布结果前深拷贝键与掩码，归还后复用不会污染缓存。禁用总桥时清空三类状态；超限不准入。`localRefreshStatistics()` 统计结果 LRU，8 MiB 指该 LRU 的预算，不含两项辅助预算。

产品仍由性能面板已有拓展插值总开关控制，默认 false、重启生效；总桥开启时本层默认可用。诊断属性 `turboism.meshInterpolation.localRefresh=false` 可单独关闭本层，保留此前优化。不改变官方安装和原始模型。

## 正确性证据

- [生产安装矩阵](evidence/production/local-refresh/manifest.json)：每版 12 用例、30 步、360 快照，共 7,200 快照。比较全部数值 raw bits、绝对 EditVersion、实际 setter 对象顺序；观察 agent 只插桩官方 setter，不实现 common 跳过。原生 oracle 全程关闭生产桥，不能由优化代码充当自身 oracle。
- 变更覆盖：连续普通源点、仅属性/颜色、不增加版本的直接坐标修改、直接派生形态修改、负 scale、曲线切换、插入点/结构重建、drawOrder 和 Rotation 特有属性；额外稳态步骤覆盖延迟准入后的实际命中。
- 每版共享颜色与 Morph 拒绝门禁、缓存启用且保有条目时 51 个宿主引用回收、禁用清空通过。Morph 门禁用例不等同于完整 Morph 编辑历史验收。
- [常规安装矩阵](evidence/production/regular/manifest.json)和[旧曲线缓存隔离矩阵](evidence/production/curve-cache/manifest.json)均为 20/20 通过；后者显式关闭局部刷新以证明原有功能仍可独立运行。
- [必要检查](evidence/production/tests.json)：runtime 111 项无失败，bootstrap 236 项（27 skipped）无失败，安装器配置往返 1 项通过。
- [代码只读审查](production-review.md)覆盖输入、实际通知、别名、借还、发布、预算与降级；不替代动态验收。

## 性能与宿主

最终性能与宿主结果如下。基准是单 common 的真实官方内核和实际生产 installer，不代表 UI/FPS；相对基线为此前 P1/P2/P3 曲线数值复用，不能把少算切片比例当耗时收益。

### 5.3.04 common 净收益

JDK 17、实际 installer；3 源锚点、每区间 64 插入点、256 几何点（Rotation 单原点），共 131 目标形态。每腿预热 300 次、5 轮每轮 100 次，表值为五轮 ns/call 的中位数。`prior` 显式关闭 local、保留此前全部优化；`local` 开启本轮。重复模式另起 JVM 复测一次，完整保留两轮，不挑选较好结果。

| 工作负载 | 对象/曲线 | 既有优化 ms/call | 局部刷新 ms/call | 变化 |
| --- | --- | ---: | ---: | ---: |
| repeat | ArtMesh / ELLIPSE | 1.012 | 1.062 | +4.9% |
| repeat | ArtMesh / SNS_CURVE | 0.941 | 0.613 | -34.9% |
| repeat | Warp / ELLIPSE | 0.961 | 0.619 | -35.6% |
| repeat | Warp / SNS_CURVE | 0.982 | 0.588 | -40.1% |
| repeat | Rotation / ELLIPSE | 0.190 | 0.186 | -1.9% |
| repeat | Rotation / SNS_CURVE | 0.159 | 0.162 | +1.7% |
| repeat-confirm | ArtMesh / ELLIPSE | 1.013 | 0.624 | -38.4% |
| repeat-confirm | ArtMesh / SNS_CURVE | 0.996 | 0.596 | -40.2% |
| repeat-confirm | Warp / ELLIPSE | 1.029 | 0.588 | -42.9% |
| repeat-confirm | Warp / SNS_CURVE | 0.924 | 0.614 | -33.6% |
| repeat-confirm | Rotation / ELLIPSE | 0.310 | 0.149 | -52.1% |
| repeat-confirm | Rotation / SNS_CURVE | 0.212 | 0.156 | -26.6% |
| dirty | ArtMesh / ELLIPSE | 1.252 | 1.428 | +14.1% |
| dirty | ArtMesh / SNS_CURVE | 2.325 | 2.272 | -2.3% |
| dirty | Warp / ELLIPSE | 1.248 | 1.259 | +0.9% |
| dirty | Warp / SNS_CURVE | 2.542 | 2.331 | -8.3% |
| dirty | Rotation / ELLIPSE | 0.257 | 0.326 | +27.2% |
| dirty | Rotation / SNS_CURVE | 0.079 | 0.078 | -1.2% |
| mixed | ArtMesh / ELLIPSE | 1.831 | 1.249 | -31.8% |
| mixed | ArtMesh / SNS_CURVE | 1.720 | 1.670 | -2.9% |
| mixed | Warp / ELLIPSE | 1.182 | 1.164 | -1.5% |
| mixed | Warp / SNS_CURVE | 1.720 | 1.690 | -1.8% |
| mixed | Rotation / ELLIPSE | 0.099 | 0.098 | -1.1% |
| mixed | Rotation / SNS_CURVE | 0.080 | 0.080 | +0.5% |

[原始基准与源码指纹](evidence/production/benchmark-repeat/manifest.json)、[独立重复复测](evidence/production/benchmark-repeat-confirm/manifest.json)、[连续变化](evidence/production/benchmark-dirty/manifest.json)、[每四次改一次](evidence/production/benchmark-mixed/manifest.json)、[机器摘要](evidence/production/benchmark-summary.json)。各腿顺序固定，JIT、调度、GC 噪声和轮间差异仍可能影响结果，不能作为统计显著性或尾延迟证明。Rotation SNS 当前不局部跳过，该项差异不能归因为局部计算复用。

大网格重复刷新分配约 30,144 B/call（此前 22,016），已消除初版全目标快照每次约 300 KiB 的额外分配；连续变化 ELLIPSE 约 40.9 KB/call（此前约 33 KB），SNS 约 2.281 MB（此前约 2.273 MB）。新门禁仍有几何签名和准入表成本：连续变化 ArtMesh ELLIPSE 本轮 +14.1%、Rotation ELLIPSE +27.2%，不能宣称所有场景均加速。产品总开关保持默认关闭，用户可关闭；长期真实大工程与其他平台 UI 仍需进一步覆盖。

该单 common 基准没有执行真实轴级联，不能用来声称真实单源编辑整体耗时改善同等比例，也没有 p95/p99 或 FPS 结论。

### 最终包真实宿主验收

生产 agent SHA-256：`52e0df22a51115c809caace6e72f5fe5d9043a5cf3aa422a716e8c8930499fc8`。验证探针 SHA：`a53065d2276c63253989dd6b357a5b7e3080ffd5704b4102369efca59f2d58c8`。托管 job `2c582221-78c0-470b-9e7f-dd172e79f444` 成功，fixture 不变、官方身份、正常退出与 containment 安全清理通过。[结果](../../extended-interpolation-host/evidence/p3-local-pass/result.properties)、[队列裁决](../../extended-interpolation-host/evidence/p3-local-pass/outcome.json)。

- 三类对象 × 两种曲线的源点、属性和版本对照通过；真实 ArtMesh/Warp 拖动与官方全 bank Undo/Redo 原生/优化对照通过。
- 父级旋转确实改变子网格，原生/优化计算坐标逐位一致。
- 原生连续帧稳定、原生/优化 GL 像素一致、另一个姿态实际改变像素；含辅助线，不声称仅模型纹理比较。
- 官方 CAFF 保存、同 JVM 独立文档身份及三类对象完整 bank 重开对照通过。
- 本包实际 `localRefresh.hitDelta=7348`，曲线缓存 `hitDelta=5302`，bridge accepted delta=110617、refused=0，证明本层在真实宿主中生效。
- 首次 job `71aab21d-1e28-4dff-94a7-618232e8d48b` 在文档就绪前超时，不计通过；探针增加等待 45 秒且模型文档列表为空时，经官方 `command_open` 打开任务副本的单次后备流程，未改生产包。失败证据保留在宿主 `evidence/p3-local-startup-failure/`。

该载体结果仍命名为 `SMOKE_PASS_PARTIAL_ACCEPTANCE`，不扩称所有 UI 操作验收。参数滑块鼠标、专门 Morph 创建/编辑历史、所有平台/版本 UI 和长期生产模型未覆盖。已有设置持久化验收保留历史证据，本轮不翻转默认值。

独立 JVM 重开 job `65079e23-4ef0-48af-9e59-3d15dac32eb2` 成功：三类对象全部 bank 的坐标 raw bits、属性、层级匹配；JVM 启动时间与源会话不同。队列确认正常退出、任务副本不变、官方身份与 containment 安全清理。[结果](../../extended-interpolation-host/evidence/p3-local-cold-pass/result.properties)、[队列裁决](../../extended-interpolation-host/evidence/p3-local-cold-pass/outcome.json)。

## 交付状态

生产源码、默认关闭的可配置构建包、20 稳定版安装矩阵、单 common 净收益/退化记录和 5.3.04 受控真实宿主及独立 JVM 重开均已完成。构建包位于 `build/preview/perf-cubism-extended-interpolation/turboism-agent.jar`，未部署到用户官方安装。功能已提交并完成主分支整合；合并复验见下文。现有性能面板总开关启用并重启后生效；Morph 局部跳过、原生网格裁剪、force/广播拦截与默认开启不在本次放行范围。

## 提交前 i18n 与兼容复核

新增实际资源测试，直接读取 base/en、en、ja、ko、简体、繁体六套 UTF-8 properties，构造真实设置 Toggle 并验证标签来自各自资源，禁止缺键/空值/乱码或隐式 fallback。测试、安装器语言一致性、设置/旧配置迁移/配置往返与 bootstrap 版本策略通过。旧实现的设置服务通过 default 方法兼容；未知/混合/未审查工件继续拒绝优化并保留官方路径。

安装器真实 `ConfigMergeRegression` 执行通过（使用实际生产 listener 类、规范模板与 retired-plugin 清单）。Windows 静态脚本有已存在失败：CF2b 仍要求字符串 `-Wait`，但基线和当前完全相同的提权 helper 已使用有界 `WaitForExit`。未修改该无关断言，也未把整套静态脚本记为通过。记录与日志见 [提交前兼容检查](evidence/production/premerge-compatibility.json)。清理探针注释尾空格后重跑 20 版局部刷新矩阵，当前源指纹匹配。

原始日志、outcome 与本机路径按仓库远端卫生规则仅保留本地忽略的 evidence 目录。提交内容包含 [脱离本机路径的验收摘要](production-acceptance.json)：逐版工件/源指纹、运行退出码、两次重复基准与连续/混合基准、必要检查和两次宿主队列裁决。上述 evidence 链接供本地复核，不是远端仓库附件。

## 主分支整合复验

功能提交 `7370133c2`，整合主分支提交 `7693e560c`，主分支基线 `f6c310bd1`。启动流程的冲突保留两侧行为：加载窗口阶段通知与固定的 launchOptimizationConfig 均保留。合并后原插值矩阵及基准的所有源指纹仍匹配。

合并后的 agent SHA `52dc5a6d0d5430cbfbe49e5d6c3db10133329a4d443d16fa96fbe3a2db1aa51b`。必要回归通过：runtime 116、bootstrap 241（27 skipped）、recent-preview 49、配置往返 1；六语言真实标签和安装器语言一致性通过。真实宿主 job `b4188e89-4d94-414d-b27c-0d41ef04934e` succeeded，六组合差分、真实拖动、全 bank 官方历史、级联、像素、保存/同 JVM 重开通过；局部命中 7,348、曲线命中 5,302。fixture 不变、官方身份、正常退出和清理通过。合并摘要记录在 [production-acceptance.json](production-acceptance.json) 的 mergeVerification 中。此前独立重开证据保持其原构建包身份。
