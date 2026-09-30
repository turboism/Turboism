# 全安装版本、全现有能力验证

目标：持续推进版本扩展，直到当前安装的所有版本都验证目前框架能力；最终交付每个版本的支持能力集与不支持能力集。

范围基线为 `7d7de1de4` 的 SDK、运行时能力、插件与现有验证场景。后续为实现兼容性而提交的修复必须进入相应复测包。不能把目标缩成启动、Core 或 Workspace 验证，也不能因为当前任务目录遗漏某个能力而排除它。

## 当前范围

- 20 个实际安装的 Editor/Core 已检查文件存在与 Editor 摘要；安装目录别名 `5.2` / `5.3` 分别绑定 5.2.03 / 5.3.02，不能按目录名猜版本。
- `inventory.json` 索引静态能力、46 个可选 SDK 服务、保证存在的上下文成员、SDK 源文件、30 个插件、128 个目录场景，以及未登记的验证脚本。
- `verification-table.md` 是中间台账，尚不是最终支持表。无精确静态记录不等于不支持，兼容准入路径也需要调查。
- `campaign-jobs.json` 保存新增实机任务标识；提交状态不是验收结果。运行态必须重新查询共享队列，终态必须核对 supervisor 证据。

## 工作顺序

1. 补全能力到 SDK 操作、插件行为、hook 和实机场景/断言的覆盖映射；审核目录外脚本。服务存在只能证明对象注册，不能证明其所有方法有效。
2. 补全跨版本验证工具的版本门禁、精确摘要和断言差异。任务目录列出版本不代表包装脚本真正支持执行。
3. 建立可重复的宿主启动和适合目标版本/许可模式的模型、PSD、动画、纹理图集夹具。始终使用任务副本；不修改原始模型、官方安装或 golden prefix。
4. 完成每个不同 Core 指纹的 native tuple 观察，审核 API 差异并建立精确配置或明确能力边界。现有 Core acquisition 成功仅证明其身份/模型观察断言，NO_PUBLIC_BRIDGE 不证明 Core 结构读取。
5. 按覆盖映射在每个安装版本上执行实际读、写、撤销/恢复、持久化、UI/hook 和插件场景。静态不匹配项也要调查是否可适配；无可用适配路径时记录具体不支持证据。
6. 修复发现的问题，重新构建不可变输入，再复测受影响能力/版本。其他版本仍持续推进，不因一个夹具或某个测试基础设施失败停下整个目标。
7. 输出最终支持矩阵、每版本支持/不支持能力明细、限制条件和证据索引。完成前逐项审计是否覆盖完整范围。

## 证据标准

- 支持：针对该精确版本和实际产物的能力断言通过，绑定 job、prepared digest、产物摘要、实际宿主身份与最终 supervisor 结果。
- 不支持：有具体 API/selector/运行时功能缺失或有明确不支持响应的证据。框架错误需要修复，不能为了完成表格改标不支持。
- 超时、未注册许可、夹具不适用、测试脚本不接受版本、缺少场景均为待处理项，不能冒充不支持。
- 验证专用 provider、人工辅助、FREE 限制、性能开关、NO_PUBLIC_BRIDGE 等必须显式记录其影响，不能用于推导更宽的生产能力通过。
- 完成需要所有版本、所有范围内能力的充分结论；PENDING 或间接证据仍代表未完成。

## 本轮进展

全版本清单和防误报回归已加入。Core acquisition 包装脚本已从四个版本扩到 20 个精确安装版本；新增 native 版本值观察而不猜测 tuple。已提交 5.3.01 和 5.2.02 探针；下一步收取终态、处理发现的问题，然后推进其余版本及能力覆盖映射。

范围生成命令：`python3 scripts/dev/cubism_validation_inventory.py --install-root <golden-prefix>/pfx/drive_c/Program\ Files`。这只刷新范围与静态记录索引，不生成实机支持结论。

已收取首批终态：5.3.01 / Core 6.0.257、5.2.02 / Core 5.0.256 的身份与模型探针均 supervisor PASS，夹具未变、正常退出、清理安全；均 NO_PUBLIC_BRIDGE。其余能力并未因此标为通过。接续提交 5.0.00、5.1.03、5.2.00、5.3.00，准确 job 标识见 campaign-jobs.json。

后续覆盖映射优先审计 MCP host client：已有 document、Core、Workspace、layout、模型统计、纹理图层/像素与保存重开、Glue读写/事务/Undo/Redo/恢复、参数读写、纹理删除、层级、历史等细粒度断言。客户端原有版本清单已包含全部 20 版本；本轮扩展的是包装脚本、队列依赖白名单、纹理持久化探针的精确记录，以及关闭路由。必须逐项审查跨版本期望，不能直接删除断言或把目录版本列表当作支持结论。

新增终态：5.1.03 / Core **5.0.0**、5.2.00 / Core **5.0.256**、5.3.00 / Core **6.0.257** 均 supervisor PASS，模型稳定观察 3 次、NO_PUBLIC_BRIDGE。原生 tuple 来自实际 API 返回，不能按 Editor 系列猜测。

5.0.00 首轮 FAIL，没有终态 probe 文件，不能判定框架不支持。控制台有读取 5.2.03 模型时的原始形状扩展类缺失；需要继续调查夹具适用性。已提交带弹窗辅助的复测，以及 5.0.01、5.1.00、5.2.01 Core 探针。共享 helper 仅在显式开关启用时匹配实际留存的英文原生提示：高版本模型警告选 Load/Yes，更新下载提示选 Cancel，记录文字和动作；不接受保存、删除、试用确认。

已提交 5.3.04、5.2.02、5.3.01 的完整 MCP client 场景，尚未收取结果。MCP 的 `assertion.cubismCore` 仅校验诊断结构；Workspace/layout 断言允许 UNAVAILABLE；命令也存在 UNAVAILABLE 分支。即使整体 PASS，这些分支也不能被改写为对应生产能力已支持。纹理持久化还包含验证专用的原生检查/保存重开，最终表必须保留辅助范围。

本轮工具门禁：共享 Core helper 自检 PASS；MCP 持久化/关闭路由定向回归 40 tests PASS；队列隔离回归 114 tests PASS（需要本地 Unix socket 权限）；Python lint 与修改脚本语法检查 PASS。不能把这些测试计为实机行为覆盖。

5.0.00 r2 已收取 supervisor FAIL，probe 原因 `TIMEOUT_NO_USABLE_DOCUMENT_OR_CLASSES`。实际自动选 Load / Cancel 的日志和 FREE 参数上限信息截图保存在 `ui-evidence/`。曾手动确认该任务的参数上限信息（窗口 PID/cgroup 和焦点均先核对），但仍未获得稳定模型/Core 身份，不宣称支持。后续 helper 增加这个精确信息的 OK 匹配并记录加载类状态，不强制加载或放宽模型断言；5.0.01 旧排队任务已在启动前取消，以 r2 不可变探针替代。5.3.04 MCP 已进入运行态，等待完整断言和 supervisor 收尾。

5.3.04 MCP r1 最终 supervisor FAIL，清理 safe，但 identity/fixture/normalExit 门禁未通过。客户端协议目录、模型/纹理读取和验证专用原生纹理像素 Undo/Redo 部分断言 PASS；持久化阶段在 `verifyPersistence` 的 EDT 调用超时。宿主日志同时留存 `Information` FREE 参数上限 modal，且原辅助线程正在执行长时间纹理探针，无法继续处理该 modal。不能用客户端的 `modelMutation=NONE` 推导整个探针未修改任务副本，也不能把部分 PASS 扩展成整体支持。原始结果保存在 `mcp-evidence/5.3.04-allversions-r1/`。

修复验证辅助：MCP modal 处理改为独立、随插件生命周期停止的线程，仍只在 EDT 点击精确已知按钮；同时允许已观察到的 Swing `Information` 标题。客户端、纹理/Undo/持久化断言和超时均未放宽。5.2.02 / 5.3.01 的 r1 在启动前取消，以 r2 新包替代；5.3.04 r2 一并入队。共享 helper 自检和 MCP 独立编译 PASS，实机修复验收仍待 r2 结果。

补收 Core 预检：5.1.00 / 5.0.0 supervisor PASS。5.2.01 probe 观察到精确 Editor/Core 指纹和原生 **5.0.256**，但没有稳定模型实例，最终 FAIL；不能当作该版本完整 Core 支持。5.0.01 r2 的加载诊断为 `app/document/source/model/view=true, coreModel=false`，即 Editor 模型类已加载而公共 Core 模型类未加载；最终超时 FAIL。Load、更新 Cancel、FREE 参数信息 OK 都已实际执行，后续必须调查独立 Core 准入与适合许可/版本的夹具。

全能力覆盖审计发现 `OwnedMoc` 没有实际生产 SDK 场景。补探针时复现生产缺陷：`CubismFacadeAdapters` 的 Core 权限包装漏了 `mocLoader()`，插件调用默认不可用实现；旧测试只测后端，未覆盖公开路径。新增 facade 路径回归首先以 `Owned MOC loading is unavailable` 失败，修复后 54 项 Core/facade/OwnedMoc 定向回归 PASS；保留的 loader、MOC、模型句柄逐次检查作用域，停用后仍可释放自有分配。`devCheck` 与 Core 三项门禁 PASS。

新增可选 production SDK owned-MOC 探针：固定官方 Haru MOC 的 Git 提交与 SHA256，通过公开 SDK 验证版本/一致性、独立实例、全部结构 getters、不可变副本、evaluation、显式先子后父释放和关闭后拒绝操作。空集合与未覆盖的父句柄级联关闭明确保留限制；没有强行绕过权限或直接使用 host Core 反射替代 SDK。5.3.04 的 `allversions-owned-r1` 已用修复后的 agent 入队，准确不可变输入见 campaign-jobs.json；实机仍待验收。MOC 二进制只进入本地验证产物，仓库记录官方来源/条款链接，不进入生产插件。

进一步补齐 SDK 原有的父关闭契约：新增回归复现父 MOC 关闭时子模型没有被关闭；runtime 现在跟踪 live children，先关闭全部子模型再释放 MOC，显式关闭子模型会摘除跟踪。所有 owned native 读取、evaluation 与内部参数写入共用父句柄锁，父关闭等待在途读取完成，不在读取中释放 native memory。父关闭/幂等/关闭后拒绝读取与并发读取保护回归已通过；定向测试现为 **56 tests PASS**，`devCheck` 与 Core 三门禁 PASS。原生 MOC 探针增加两个 live children 的父级联关闭断言，旧 owned-r1 在运行前取消，新的 owned-r2 已入队；不能把尚未运行的 native 断言记为 PASS。

5.3.04 MCP r2 最终仍 FAIL，但失败从未响应 modal 超时变为原生 `Native save rejected`。FREE 参数信息已可响应，当前夹具仍受许可限制，后续需要适配可保存夹具。完整断言未放宽，也没有把保存失败改标 unsupported 或把客户端 `modelMutation=NONE` 当作整个验证探针无变更。原始结果与逐项 PASS 断言见 `mcp-evidence/5.3.04-allversions-r2/` 和 campaign-jobs.json。

5.2.02 / 5.3.01 MCP r2 也已收取 FAIL。5.3.01 同样为 `Native save rejected`；5.2.02 则在公开 SDK `ModelTextures.removeRawImage` 被版本门禁拒绝，目前仅声明 5.2.03 / 5.3.00–.04。检查精确 5.2.02 Editor-model 记录确实只有 texture.read，没有 texture.write，因此不是仅删除 SDK 注解即可解决。后续需要审查该版本的写入/Undo ABI 并补精确证据或适配；尚未证明没有可用适配路径，不能直接记为最终不支持。两版各自协议/读取等部分断言与最终门禁均按原始结果保留。


纹理写入兼容审计完成一项修复：5.0.00–5.2.02 均具有旧版 non-dialog native Undo 工厂所需完整 63-alias 依赖闭包；LayerInput、RawImage、ACSimpleUndoable、GroupUndo、Undo_AddOrRemove 五个 class 在全 20 个版本中字节一致，原生工厂仅构造编辑对象，adapter 仍先注册再 forceRedo。精确 legacy 版本集合同步用于 Java/Python 依赖规则，14 个旧版记录只新增 texture.write，SDK 五个写入方法扩为 20 个精确版本。全 20 记录通过 Java 静态 verifier；286 texture tests、55 dependency tests、32 SDK gate tests、7 SDK annotation tests 全 PASS，devCheck/Core 三门禁 PASS。缺任一 legacy factory 时保留读取、拒绝写入。仍未把新增静态准入标为实机支持，逐版实机 Undo/Redo/保存验收待执行。摘要与限制见 texture-write-static-audit.json / texture-write-regression.json。

5.3.04 owned-r2 已运行，但探针因 Haru MOC 未声明资源根以 PLUGIN_RESOURCE_UNDECLARED 安全拒载，没有执行 native Core 断言。已通过官方队列取消，supervisor 终态 cancelled/FAIL、cleanup safe；identity/fixture/normalExit 最终门禁为 false，不能当作支持证据。复制终态和拒载报告见 mcp-evidence/5.3.04-allversions-owned-r2。修复只在打包 MOC 时将 validation/moc/ 声明到打包 descriptor；实际 JAR 通过生产 PluginDescriptorParser + PluginJarContract 校验。

新增独立 core-owned-moc 队列任务，复用官方 contained Runner、原始夹具副本/摘要门禁、精确 prompt helper 与 normal-close 安全路径；在任务文档可见且没有 modal 后执行生产 SDK MOC 结构读取、evaluation、独立 model、显式释放、父级联释放断言。该任务不使用 MCP client，也不触发 Editor texture persistence，完整 MCP 验证仍保留为另一项必需覆盖。仅接受有实机观察 tuple 的版本，不按系列猜测 native tuple。5.3.04 owned-r3 输入已冻结，具体 job 与 prepared digest 见 campaign-jobs.json；尚不宣称 Core 通过。

后续已提交 5.2.02（观察 tuple 5.0.256）和 5.1.03（5.0.0）的独立 owned-MOC 任务，未猜测 native tuple；job 明细见 campaign-jobs.json。调度回归 28 / 队列隔离回归 114 PASS，20 个 Editor-model mapping pack 的 inventoryRef 记录摘要一致。
