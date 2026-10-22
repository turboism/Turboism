# 测试裁剪批次 1–2 执行记录

日期：2026-10-22。起点：`f9c4f5e19`。仅执行批次 1、2 及维护者的转发审计决策。

## 接线与覆盖

- 保留独立 `:runtime:test` 的完整离线覆盖。完整任务入图时，四个聚焦 Test 任务通过 `onlyIf` 跳过；admission 单测入图时也覆盖 preflight/core provider 子集。任务描述说明该规则，实际接线运行确认四个任务均为 `SKIPPED`。
- 迁入现有 legacy 证据名单的是 12 个完整类（49 例）及混合类中的 5 个方法，共 54 例；混合类的合成行为测试保留。证据 JVM 接收显式提供的 jar 属性。
- 删除 5 个 properties 完整性用例；MemoryProfile 的 Note 控件行为断言保留。Python 删除 4 个指定的文档用例及 verifier 用例中的 runbook 断言，保留 workflow/candidate 断言。
- 保留 Java SDK 转发守卫；移除重复 shell 审计及其任务。HostIngress 的 10 例仍只在完整 runtime 套件运行，本次累计 24.178 秒。
- 等待参数通过既有不可变执行器配置或普通不可变 policy 传入。生产默认等待时限和备份退避序列保持不变；测试使用 latch、队列排空、状态检查及明确握手。未添加测试专用系统属性或静态可变开关。

## 验证与数量对账

| 项目 | 之前 | 之后 |
|---|---:|---:|
| runtime 用例 | 6116（原始起点的 Gradle 缓存 XML） | 6057 |
| runtime 类 | 700 | 688 |
| skipped | 56 | 4 |
| 墙钟 | 453 秒（提供的测量基线） | 244.280 秒（本次实跑） |

计划写的 6113 比原始起点的完整报告少 3 例。实际数量差为 **6116 − 6057 = 54 迁移 + 5 删除 = 59**。原先 56 个 skip 中，52 个属于迁移的 jar 用例，另外 4 个是显式 opt-in 的 Swing 显示测试，保留。54 个迁移用例中的另外 2 个在此环境可执行。

相对提供的墙钟基线减少 **208.720 秒（46.1%）**；不把基线缓存恢复耗时当成执行耗时。三个主要慢类的累计时间从提供的 127.2 秒降至本次 6.121 秒。CI 去重收益未单独计时。

- 三轮定向执行均 `BUILD SUCCESSFUL`，每轮 runtime 138 例、FPS 12 例、BackupPlugin 13 例。
- 最终 `spotlessApply devCheck spotlessCheck`、随后 `:runtime:test` 均 `BUILD SUCCESSFUL`，runtime 无失败或错误。
- release-tooling 的 34 例、CI coverage 的 63 项检查通过；CI coverage 测试文件保持原样。
- 未执行批次 3/4、Python 接线或 CI coverage 缩减；这些按任务范围留待后续。原子存储、权限、事务、字节码合成真值等保护保留。

## 实现提交

`b15d708ff` CI 去重；`4c1276e7a` 证据类隔离；`cb0921d16` 文档断言删除；`727e8be71` 重复 i18n/shell 守卫删除；`29c5d9307` 混合类证据方法隔离；`d18c35c65` 执行器关闭配置与同步夹具；`97fe2bf8c` 生命周期 policy；`ace8679a9` FPS、备份与 Graal 同步。
