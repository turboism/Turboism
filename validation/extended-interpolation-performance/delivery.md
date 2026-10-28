# 拓展插值优化交付记录

日期：2026-10-27。目标先验证 5.3.04，再覆盖仓库收录的 20 个稳定版。当前可交付的是默认关闭、用户重启后可选择启用的安全优化；没有部署到官方安装，也没有改变原始模型。

## 提交与主分支整合

功能提交 `7370133c2`，主分支整合提交 `7693e560c`。合并后 agent SHA `52dc5a6d0d5430cbfbe49e5d6c3db10133329a4d443d16fa96fbe3a2db1aa51b`；runtime 116、bootstrap 241（27 skipped）、recent-preview 49、配置往返 1 通过，真实六语言标签和安装器语言一致性通过。合并包宿主 job `b4188e89-4d94-414d-b27c-0d41ef04934e` succeeded，局部命中 7,348，原始 fixture 不变、身份/正常退出/清理通过。完整版本矩阵源指纹仍匹配。原始本机证据不提交，仓库保留 [脱敏验收摘要](p3-local-refresh/production-acceptance.json)。下方保留功能合并前各阶段包的身份。

## P3 局部刷新生产候选

[Normal 切片固定点跳过已接入生产](p3-local-refresh/production.md)，保留实际 `setUpdated()` 轨迹、force、Part 广播及完整网格。最终生产包 SHA `52e0df22a51115c809caace6e72f5fe5d9043a5cf3aa422a716e8c8930499fc8`；20 版 × 360 个生产局部刷新快照及常规/旧曲线缓存矩阵通过，runtime 111、bootstrap 236（27 skipped）、安装器配置往返 1 无失败。性能与退化见链接报告；本包 5.3.04 真宿主 job `2c582221-78c0-470b-9e7f-dd172e79f444` 和独立重开 job `65079e23-4ef0-48af-9e59-3d15dac32eb2` 均成功，局部跳过命中 7,348 次，fixture 不变、官方身份/正常退出/清理通过。以下旧包记录保留历史身份。

## P3 续推状态

新增[曲线数值复用](p3-curve-cache.md)，保留全部原生更新通知。20 版新增缓存差分与常规差分通过；runtime 106、bootstrap 236（27 skipped）、配置往返 1 均无失败。当时 bundle SHA `044b0148e395b94ad7d7b76ebe4b3a997796b9659fb318197f0918c15f26d5b4`，本包真实拖动/全 bank 历史/级联/像素/保存与独立 JVM 重开均通过（job `52a49f7b-0304-4b65-93ed-f2fb878ece10`、`775e8517-d9ad-4f83-ac9d-753664beec91`），数值缓存命中 5,302 次。保留默认关闭，未部署到官方安装。下列旧宿主与延迟记录保留原包身份。

## 已落地与验收

| 项目 | 结果与证据 |
| --- | --- |
| 根因 | 插入点增加派生形态及组合切片数量；逐顶点包装、SNS 初始化和 GUID 线性查找放大刷新成本。源形态编辑仍可能触发对象全量刷新及直接父 Part 的直接子对象广播。普通调参通常求值已有网格，不能混同源形态刷新。 |
| 三类对象、两种曲线 | ArtMesh / Warp 原始数组路径、Rotation 原点 setter 适配；ELLIPSE 复用系数计算，SNS 保留官方计算器与求值顺序，单 common 内复用。 |
| 遍历与临时缓存 | 首元素检查免完整 bank 拼接；单次原生结构重建 GUID 索引，上限 65,536 条，超限回原生，正常/异常退出即释放。 |
| 版本 | 5.0.00–06、5.1.00–03、5.2.00–03、5.3.00–04，共 20 个真实工件。每版 164 个 common/拒绝用例及 18 个结构用例通过。没有覆盖 4.x、beta 或未来工件。 |
| 5.3.04 活体文档 | 六组合源点 raw bits、属性、EditVersion 增量与官方一致；真实 mesh 拖动、全部 mesh bank Undo/Redo、父级旋转导致子网格实际变化且原生/优化坐标逐位一致。 |
| 渲染与保存 | 官方绘制后的 MSAA FBO 像素一致，原生重复帧稳定，姿态变化会改变画面；官方 CAFF 保存、同 JVM 新文档及独立 JVM 重开验证通过。捕获包含辅助线，不能表述为仅模型纹理对照。 |
| Warp 补充交互 | 最新包两曲线各 12 组配对真实拖动及全 bank 历史恢复通过；p50 为 205.9→187.0 ms、194.7→169.9 ms；ELLIPSE p95/p99 本轮变慢，未宣称尾延迟全面改善。详见宿主报告。 |
| 重复交互 | ELLIPSE、SNS 各 12 组配对真实 mesh 拖动，全 bank 位值及历史恢复一致；含准备与官方更新等待的 p50 分别 250.3→218.2 ms、231.6→198.8 ms。单模型经验数据，不是 FPS 或通用延迟保证。 |
| 设置 | 顶部菜单→设置→性能的复选框，六种语言，保存并独立重启生效已通过。配置键 `launcher.meshInterpolation`，默认 false，需重启。 |
| 回归 | runtime 102 tests、bootstrap 236 tests（27 skipped）、安装器配置往返 1 test 均无失败，previewBundle 通过。Windows Python 配置检查 CF2b 在原 HEAD 同样失败，未计作通过。 |

生产 bundle：`build/preview/perf-cubism-extended-interpolation/turboism-agent.jar`。
P3 之前已完成宿主验收的 SHA-256：`923d33d61a3b2e3a2c0beea43dc21aa83b979ffb7b628e9a444fd9bb52c5e51e`。上一轮宿主包 SHA：`ab298611c9d9cc82c4a3f2ab945b79dd5271954b2a82ab0ba6ac884e069e6519`。
既有宿主证据属于上一轮 bundle。本轮新增辅助缓存统计及退出 token 引用清理，已重新通过 20 版本、240 场景生命周期验证及 20 版本安装差分（`evidence/budget-installed-matrix/`，19 个源码指纹匹配）；新 bundle 已通过 Warp 双曲线各 12 组配对真实拖动、全 bank 历史恢复、六组合源点差分、级联、像素和保存重开；独立进程重开也已通过（job `944d3022-b079-4a8c-91c1-49640b7dfc57`）。旧宿主证据不冒充新包验证。

本轮相关 runtime 102 项、bootstrap 236 项（27 skipped）、配置往返 1 项均无失败，previewBundle 成功，见 `evidence/budget-checks/`。额外整套 runtime 尝试停在无关系统屏幕捕获，线程栈确认后结束该测试进程（exit 143），未计作通过；未修改该无关功能。

## 证据索引

- [P0–P5 验收核对](plan-acceptance.md)
- [实现、内核测量、版本矩阵与回归](implementation.md)
- [局部刷新依赖图与实现前门槛](p3-dependencies/REPORT.md)
- [缓存预算与生命周期](cache-lifecycle.md)
- [刷新范围调查及原始探针](refresh-scope-investigation.md)
- [5.3.04 宿主验收、重复拖动 A/B](../extended-interpolation-host/README.md)
- [真实设置点击及独立重启](../extended-interpolation-settings-host/README.md)
- 宿主最终任务：`6593f1c5-42b6-4d1f-8504-73f699678bdb`。
- 独立进程重开：`e72ef3ea-83eb-4afa-8c2c-f14bb42b0b9f`。
- 重复拖动 A/B：`075b36c1-6204-4049-b05e-d8f111f53acb`，最终 probe SHA `d31726de3cd5a72d9a517961a3556bc249177048689eadda10136b5b0091d8a3`。
- 最新包 Warp 复验：`ce38cb88-834a-4f38-89f9-fc9a32cfb0d2`，probe SHA `844cc76538b672847b952a09ef81918fcc4544531db38f1956dbe2ab9a0be788`，证据 `../extended-interpolation-host/evidence/warp-budget-pass/`。队列成功、fixture 不变、身份/正常退出/清理通过。
- 上述三项旧包任务均获队列成功、fixture 不变、官方身份正确、正常退出和 containment 安全清理结论；具体 outcome 与源码指纹在宿主 `evidence/` 对应目录。

## 条件任务与后续顺序

本轮没有根除完整 dense grid 的 Cartesian 增长，也没有实现对象内部局部失效图。以下仍是有条件的后续实现，不能宣传成已覆盖：

1. **P3 广播跳过与 Morph 局部刷新**：Normal 固定点跳过现已生产接入，最新证据以上述报告为准。以下为早期依赖图设计的历史局限：[依赖图和最小切口设计](p3-dependencies/REPORT.md) 已完成，5.3.04 三个 Normal/ELLIPSE 合成用例的局部输出位值一致，但版本增量存在差异，尚不能安装。后续先记录普通/Morph 源数据、绑定、曲线类型、点数、scale、拓扑与历史恢复的变化，再形成可验证的对象刷新签名和依赖图。需要证明所有原生读取依赖都被覆盖；未知变化继续全量刷新。现有证据只能证明普通 GUID/EditVersion 不足以直接替代 force，不能证明每次兄弟刷新都必要或都冗余。因此本轮保留 `force=true`，不把静态推测当作已复现的损坏。
2. **P4 原生缓存预算与惰性结构**：已控制辅助索引预算并验证作用域释放；宿主记录了形态数、坐标长度、堆池峰值与 GC。三轴 6,832 forms 的结构构建仍有约 514 MB 线程分配；这不是 retained heap 或峰值堆。新增 5.3.04 生命周期探针确认同场景显式 GC 后整 JVM 活堆增量约 3.23–4.19 MB，缩减网格后的全部旧形态、释放后的 owner/extension/当前形态以及强持有已退出 token 的宿主引用均可回收；该值不是 retained graph size。参见 [缓存生命周期](cache-lifecycle.md)。下一步仍须以真实大型工程测 retained heap 和峰值，再审计参数求值、Morph、保存/导出、Undo/Redo 的全部原生形态访问。如果无法在每个访问点保留完整语义，就保持完整网格，不驱逐形态、不减少作者点数。禁止把索引 65,536 条上限误称为原生网格内存上限。
3. **扩大默认开启的放行证据**：补更多生产模型、长时间交互及其他平台/版本 UI；当前保持默认关闭。参数面板 Slider 的鼠标拖动、专门 Morph Target 创建/编辑历史和全部版本 UI 均未声称覆盖。现有 Morph bank 比较不等同于上述编辑链验收。

Paseo Fast 在同 Project、同 Workspace 中完成刷新范围、像素辅助类和后续边界复核；已归档完成代理，没有新建或遗留专属工作树。其复核支持保留原生刷新/存储语义；不将“可能损坏”表述为已经动态复现。
