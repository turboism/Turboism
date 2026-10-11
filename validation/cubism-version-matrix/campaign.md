# 全安装版本、全现有能力验证

目标：持续推进版本扩展，直到当前安装的所有版本都验证目前框架能力；最终交付每个版本的支持能力集与不支持能力集。

范围基线为 `7d7de1de4` 的 SDK、运行时能力、插件与现有验证场景。后续为实现兼容性而提交的修复必须进入相应复测包。不能把目标缩成启动、Core 或 Workspace 验证，也不能因为当前任务目录遗漏某个能力而排除它。

## 当前范围

- 20 个实际安装的 Editor/Core 已检查文件存在与 Editor 摘要；安装目录别名 `5.2` / `5.3` 分别绑定 5.2.03 / 5.3.02，不能按目录名猜版本。
- `inventory.json` 索引静态能力、46 个可选 SDK 服务、保证存在的上下文成员、SDK 源文件、30 个插件、当前目录场景，以及未登记的验证脚本。
- `sdk-method-coverage.json` 从当前 SDK classfile 索引公开／受保护方法、构造器、字段及嵌套类型，包含 incubating API；owned-MOC 的21个方法、MCP纹理/夹具准备/历史的14个方法及MCP Glue/参数/绑定的27个方法已关联明确场景，其余映射待审计。方法映射本身不产生版本支持结论。
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

5.3.04 独立 owned-r3 的探针已成功加载，但 Runner 在 native Core 断言前拒绝执行：mesaGlThread effective on / deferred GL error check inactive（T22 安全门禁）。supervisor failed/FAIL、cleanup safe；没有 native MOC result，不推导 Core unsupported。修复独立 Core wrapper 显式使用 mesaGlThread=false 与 mesa_glthread=false，关闭此非 Core 优化；保留原 Runner 防护和所有 Core 断言。同批 5.2.02 / 5.1.03 的旧输入在运行前取消替换。Core 任务不能证明 Mesa 优化支持，后者仍需专门验收。

5.3.04 owned-r4 越过渲染门禁，任务模型加载完成并确认 FREE 参数提示，但未生成 native MOC result，最终 supervisor failed/FAIL、cleanup safe。检查 standalone readiness 发现局部 EDT FutureTask 只等5秒，实际加载文档在 EDT 用了8.5秒，启动期间即可提前退出；修复为已定义的120秒整体 startup deadline（Core native 调用120秒超时不变），并让 startup failure 发布 status=FAIL/nativeAssertions=NOT_STARTED，而不是静默没有结果。自检验证此 failure publication 不调用 cubism/Core 服务；探针编译与自检 PASS。owned-r5 已以新 probe 摘要冻结/提交，终态尚待。5.2.02旧包启动与取消发生竞态，已由官方 supervisor 收尾；5.1.03旧包在运行前取消，均不能作为 native 能力验收。

5.3.04 owned-r5 **supervisor PASS**：finalizedBy=contained-supervisor、succeeded/PASS、identityVerified/fixtureUnchanged/normalExit=true、cleanup=safe。生产 SDK 上原生 Core **6.0.513** 使用 pinned Haru MOC（V3_0；Core latest V5_3）完成读取、update、独立实例、不可变投影、显式 child→parent 释放、两个 live children 的父级联关闭、关闭后拒绝操作。实际读到42 parameters、19 parts、84 drawables、97 deformers、0 glues。明确不证明非空 Glue 语义、Editor→Core bridge、完整 MCP/保存或 Mesa 优化支持；render configuration 显式 mesaGlThread=false。终态与原生结果已复制到 mcp-evidence/5.3.04-allversions-owned-r5。

已以 owned-r5 同一成功探针接续提交 5.2.02 / 5.1.03 / 5.3.01，分别验证已实测 Core 5.0.256 / 5.0.0 / 6.0.257；仅提交，不等于通过。

5.2.02 owned-r3 / Core5.0.256 与5.3.01 owned-r1 / Core6.0.257 已收取 supervisor PASS：身份/夹具/正常退出均true，cleanup safe。均以生产 SDK 读取42参数、19部件、84 drawable、97 deformer、0 Glue并完成update/独立实例/不可变副本/显式及级联释放/关闭后拒绝操作。5.2.02 parameterRepeat=false、latestMocVersion=V5_0；5.3.01 repeat=true、latest=V5_3。非空Glue、Editor acquisition、MCP保存及Mesa优化仍未证明。

5.1.03 owned-r3 最终 supervisor failed/FAIL、cleanup safe；探针报 Core runtime metadata unavailable，尚未获得原生 owned-MOC 支持结论。全20版元数据审计显示5.0/5.1均匹配参考5.2.03的62/80 selectors，缺18个Deformer/Glue/Drawable关系selectors；基础读取和MOC生命周期selectors存在。当前 OwnedModel getter 每次投影全结构，会让缺失新版接口阻断基础读取。后续必须分别准入owned-MOC基础能力与完整geometry能力，保留父锁、不可变副本和关闭检查，不伪造缺失数据。审计见core-selector-delta-audit.json。

已冻结并提交5.2.00 /5.2.01 /5.3.00独立owned-MOC实机任务；采用各版本已观察tuple，尚待终态。

5.2.00 /5.2.01 owned-r1 已确认 supervisor PASS，Core均实测5.0.256；身份/夹具/正常退出门禁均通过，cleanup safe，全部owned-MOC断言通过，计数与5.2.02相同，parameterRepeat=false。5.2.01此前模型身份探针的失败不代表独立owned-MOC不可用。5.3.00仍在运行，尚未标通过。

5.3.00 owned-r1 /Core6.0.257 已收取 supervisor PASS，身份/夹具/正常退出均true、cleanup safe，全部owned-MOC断言通过，结构计数同5.3.01，parameterRepeat=true/latestMocVersion=V5_3。当前六个版本的owned-MOC行为验收通过；全能力矩阵仍未完成。Core预检目录新增显式assisted变体，选择FREE并依照已授权规则处理跨版本Load/Yes及更新Cancel，保留标准变体，不证明Mesa优化支持；29项调度回归通过。

已提交5.3.02 /5.3.03 /5.2.03 assisted Core预检，逐版观察tuple后再扩独立owned-MOC任务白名单；具体job/prepared digest见campaign-jobs.json。未提交无FREE辅助的5.3.02 prepared输入，避免已知启动流程不匹配。

5.2.03 /5.3.02 /5.3.03 assisted Core预检均已确认supervisor PASS，native tuple分别5.0.256/6.0.257/6.0.257，identityVerified/fixtureUnchanged/normalExit=true、cleanup safe，均NO_PUBLIC_BRIDGE。已原样复制结果并保留raw lifecycle摘要，见core-preflight-evidence/与campaign-jobs.json；只证明身份和模型观察，不等同owned-MOC或全能力通过。

正在适配5.1.03精确Core：62个实际存在selectors、独立capabilityIds仅cubism.core.owned-moc.read、native tuple固定实测5.0.0。基础Canvas/Parameters/Parts按family读取，完整Drawable关系/Glue/Deformer明确UnsupportedOperationException；较小profile不能准入geometry。已有直接公开生产admit路径、不可变/新鲜副本、完整geometry拒绝、显式/父级联关闭及旧版在途读取保护回归；实机适配验收尚待，不能记为PASS。

5.1.03适配已通过131项相关Java回归、devCheck、Core三门禁和生成源一致性检查。回归包括较小legacy selector计划的直接生产准入、geometry拒绝、新鲜/不可变副本、父关闭等待Canvas原生读取；该并发夹具的阻塞点位于Canvas getter，已按真实调用family修正。record/pack及resource/index/family声明、manifest、selectorpolicy、SDK文档同步；新agent与probe已重建。5.1.03 owned-r4、5.2.03/5.3.02/5.3.03 owned-r1已冻结/提交，未在终态前记为通过。旧版探针固定要求42参数/19部件，精确拒绝缺失三族；allStructuralReadMethods=PARTIAL_EXPECTED，避免完整结构误报。

5.2.03 /5.3.02 /5.3.03 独立owned-MOC均已收取supervisor PASS，Core分别5.0.256/6.0.257/6.0.257，身份/夹具/正常退出均true，cleanup safe；目前九版完整结构owned-MOC通过，仍不等于全能力通过。证据已更新owned-moc-verification.md。

5.1.03 owned-r4失败由验证记录残留geometry capabilityCondition导致；专用licensed启动回归实际复现，删除残留条件并刷新record/pack SHA后6项真实产物回归通过。此前131项仅默认任务的合成测试，不包含licensed产物factory测试，范围已修正。owned-r5随后暴露HostSession无条件获取evaluatedJoin，bootstrap在plugin前失败，native断言未开始；supervisor FAIL/cleanup safe，保留启动stack证据。共享生产Editor access构造改为仅在完整geometry可用时创建join，235项宿主/Core合成回归+6项真实产物回归/devCheck通过；owned-r6已冻结入队，尚待native验收。见legacy-startup-regression.json。

5.1.01 /5.1.02 assisted身份预检均supervisor PASS，实测Core5.0.0，身份/夹具/正常退出true、cleanup safe、NO_PUBLIC_BRIDGE。原生tuple证据已复制；不提升为owned-MOC或完整框架支持。5.0.02身份预检已提交，尚待结果。

新增显式mcp free-fixture变体：在精确任务副本内用公开SDK移除超过两个的keyform参数绑定，验证每操作Undo、完整绑定Undo/Redo、纹理未变及最终绑定数，再保留原生五类纹理操作保存重开；保存状态额外核对参数绑定，最终原始文件重开必须恢复原始绑定与纹理。未放宽native save/client断言，不绕过FREE许可。7项Java/115项队列/29项调度回归、devCheck/探针编译自检通过；5.3.04实机已提交，尚待验收。见free-fixture-regression.json。

5.3.04 FREE MCP r1已收取supervisor FAIL/cleanup safe，夹具准备阶段拒绝：SDK投影初始最多每对象4个绑定参数，尝试修剪keyform后仍超限；native保存断言尚未开始。原始properties已留存，client modelMutation=NONE仅覆盖client，不能覆盖验证插件对任务副本的SDK操作。新增调整前后族计数与修剪数量诊断，r2已入队；不删除两参数限制，不跳过保存或恢复断言，不记为unsupported。

5.1.03 owned-r6已确认supervisor PASS：Core5.0.0、身份/夹具/正常退出true、cleanup safe。生产公开SDK完成基础Canvas/42参数/19部件读取、update、独立实例、不可变投影、显式child→parent关闭及两个live children父级联关闭、关闭后拒绝；完整Drawable关系/Glue/Deformer明确UnsupportedOperationException，allStructuralReadMethods=PARTIAL_EXPECTED。此前r4/r5失败保留，不将旧版基础PASS提升为geometry/bridge或全能力。21个公开SDK方法到实际断言/证据键映射见owned-moc-coverage.json，明确保留非空Glue及其他缺口。

5.0.02 assisted身份预检supervisor PASS，实测Core5.0.0，身份/夹具/正常退出true、cleanup safe、NO_PUBLIC_BRIDGE；5.0.03/.04/.05/.06已冻结入队，尚待tuple与身份验收。

5.0.03–5.0.06 assisted预检现均supervisor PASS，实测Core5.0.0，三个最终门禁true、cleanup safe、NO_PUBLIC_BRIDGE；原始properties与supervisor摘要已留存。真实产物factory回归新增循环5.0.02–5.0.06、5.1.00–5.1.02，全部严格兼容准入5.1.03基础profile，绑定各自Core指纹，仅授权owned-MOC并明确拒绝geometry。7项真实产物测试、29项调度回归、devCheck通过；八版基础owned-MOC已逐一冻结入队，最终结果仍待收取。

5.3.04 FREE MCP r2诊断明确：KEYFORM三个参数与BLEND_SHAPE一个参数共四个，旧planner仅将KEYFORM减到两个而留下总数三个。planner现先预留全部非KEYFORM参数额度，再按稳定顺序保留KEYFORM，总distinct参数不得超过二；非KEYFORM本身超过二仍拒绝，保留Morph。7项Java回归与实机r3证明最多参数4→2、解绑两个KEYFORM、BLEND_SHAPE保留、Undo/Redo与纹理未变断言通过。但r3后续原生保存调用超时，supervisor FAIL/cleanup safe，不记为unsupported或保存支持。r4保持全部原断言并加入只读弹窗、保存阶段及超时线程栈诊断，已冻结入队。

5.1.00/.01/.02 owned-r1已各自supervisor PASS，身份/夹具/正常退出true、cleanup safe，实测Core5.0.0，基础42参数/19部件与update、独立实例、不可变副本、显式/父级联释放、关闭后拒绝均PASS；扩展三族明确拒绝。现在13版owned-MOC验收：九版完整结构getter、四版基础family。5.0.02 owned-r1在原生加载5.2.03夹具时返回File load interrupted，Load与更新Cancel均实际执行，后续readiness超时、nativeAssertions=NOT_STARTED；预检PASS不替代本轮SDK验收，不作unsupported结论。

5.0.03/.04/.05 owned-r1与5.0.02/.06 owned-r2现均确认supervisor PASS，基础Canvas/42参数/19部件与update、独立实例、不可变副本、显式/父级联释放及关闭后拒绝通过；缺失完整Drawable/Glue/Deformer明确UOE。现在18版owned-MOC：九版完整结构getter、九版基础family；5.0.00/.01仍需适用夹具与实测tuple。首轮加载中断证据保留。

5.3.04 FREE MCP r4保存失败原因明确为PRO-only Screen Color，原生modal观察与runtime log摘要见mcp-evidence/5.3.04-allversions-free-r4/dialog-observations.json。r5通过公开SDK将任务副本全部存储关键形Screen Color归零（非黑RGB 6→0），核对SDK写入Undo/Redo、纹理未变；五类纹理操作原生保存、重开、参数/颜色投影一致及原始文件重开恢复全部PASS。但随后完整MCP因夹具没有非空Glue关系而FAIL；supervisor failed/FAIL、cleanup safe，三个整体最终门禁false。只保留保存单项通过证据，不提升为整体能力支持。client status与nativeTextureStatus已分开记录，防止后读的PASS覆盖整体FAIL。

只读官方ArchiveReader检查发现Downloads同名模型的SHA为2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10，与原夹具不同；含21 ArtMesh、12 Deformer、9 Part、一个Glue及不同ArtMesh端点，结构证据见mcp-glue-fixture-audit.json。已以这个原始文件的不可变任务副本提交5.3.04 free-r6、5.3.01/5.2.02 free-r1；全部完整断言保留，终态尚待。新probe按精确版本审核全部Drawable/Deformer存储颜色，5.3额外审核Part；5.0–5.2实际没有Part Screen Color字段，不伪造读取。增加原生containScreenColor独立只读guard，稳定ID使用序列化getIdString；九项Java回归/devCheck/探针编译自检通过。

官方Simple Model已下载并按实际编辑文件格式4.0.05核对；17 ArtMesh、1 Deformer、16 Part、24 Parameter，无Original Shape扩展。官方页面的SDK3.2说明不用于猜测编辑格式。下载来源与SHA见official-simple-fixture.json，二进制仅存在本地验证存储。5.0.00/.01使用不可变任务副本的assisted身份预检已入队，尚待native tuple与最终门禁，不宣称支持。

SDK成员索引现支持多个明确场景和精确classfile descriptor，35个方法已映射（owned-MOC 21、MCP纹理/参数准备/颜色/历史14）；缺失、重载歧义与重复/交叠映射均拒绝，四项防误报回归与Python lint通过。纹理保存单项PASS与MCP/supervisor整体FAIL明确保留，方法映射不产生支持结论。

5.3.04 free-r6已收取完整MCP **supervisor PASS**（job eb9c1f5f-bfe1-4e4a-8471-e70bce2c88ad）：非空Glue读写/readback/no-op/stale/Undo/Redo/transaction/rollback/原始恢复、参数读写恢复、bindings scope与Undo/Redo、五类纹理写入和原生保存重开、hierarchy/history、command实际执行恢复及协议场景全部PASS。Workspace control明确UNAVAILABLE，Core断言仅诊断结构，native保存仍是验证辅助；这不是框架全部能力通过。证据见mcp-evidence/5.3.04-allversions-free-r6/。

5.0.00/.01官方旧版Simple夹具预检均supervisor PASS，Core实测5.0.0、stableSnapshots=3、NO_PUBLIC_BRIDGE；身份/夹具/正常退出true、cleanup safe。仅以这两版正式tuple证据扩owned-MOC任务白名单到20版，基础family实机断言尚待。5.3.01 free-r1保存FAIL已由原生日志定位Multiply Color PRO限制；新验证副本准备将用公开SDK归一白色，审核所有stored forms及独立native guard、Undo/Redo、保存重开、原始恢复，保留完整client和保存断言。5.2.02 free-r1在插件前遭Core准入拒绝，不能算不支持。

Core真实产物启动回归复现5.2.00–.02基础/完整profile共存时owned-MOC被误标ambiguous。修复只在完整Core profile全部selectors验证且严格包含相同旧绑定/条件/能力时保留完整profile；真正绑定/条件分歧仍fail-closed。20版真实产物factory回归已通过，旧版geometry仍拒绝；修复后的实机复测尚待。

本轮冻结提交八项新实机任务：5.0.00/.01独立owned-MOC；5.2.02/5.3.01修复后完整MCP free-r2；5.0.00/5.2.02/5.3.04纹理关系图；5.3.04 PSD可读导出观察。准确job/prepared digest见campaign-jobs.json。正式worker在线，但一个其他工作树的旧协议运行任务无version/resources声明，队列按安全规则串行阻挡新claim；未干预其会话或释放其锁。排队不算PASS。新纹理/PSD入口有显式assisted变体，FREE/Load/Cancel只处理任务内已授权提示；纹理关系保留非空连贯图断言，PSD只证明readable export/issued handle/stop，不能提升为保真/replace/external-edit。目录现135场景，缺口保持显式待审计。

新增27个Glue/参数/绑定SDK方法精确映射，总62个。5.3.04 free-r6正式通过仅绑定当时冻结产物；Multiply Color新断言仍待实机。46组已复制终态的lifecycle SHA/所有最终门禁/原始properties字节已逐项与官方队列核对一致；15个早期历史终态没有本地sanitized副本，不用于本轮新支持结论。

本轮离线门禁：真实产物Core factory回归8项（覆盖20版）PASS；Core候选/歧义回归24项PASS（新增完整扩展正向及绑定/条件变更负向）；MCP夹具probe10项PASS；调度30项、隔离队列115项、SDK覆盖索引4项、范围防误报5项均PASS；纹理关系/PSD观察9+8项自检及权限/生产包隔离PASS；devCheck、Core三门禁、Python lint与修改wrapper语法检查PASS。实机新任务尚未执行，不以这些检查替代native验收。

本轮终态：5.0.00/.01 owned-MOC、5.3.01完整MCP及5.0.00/5.2.02/5.3.04关系图均supervisor PASS；owned-MOC目前20版（11基础、9完整getter；Core Glue仅空getter）。54组摘要与57份properties已核对官方SHA与原始字节。

5.2.02 free-r2停在已激活Trial欢迎modal，新增精确Confirm/OK信息确认与许可观测（不激活Trial）；free-r3实际许可观测FREE，完成模型投影与参数4→2准备，但Screen Color写入被Inspector精确准入拒绝。20版静态审计显示完整no-alpha合同全部存在，依赖表错误包含现代AlphaComposition；修复明确15版旧合同、同步Python求解与runtime，14份记录只增一项Inspector能力、selectors不增。126项相关Java回归、devCheck和Core三门禁PASS；实机新包仍待复测，不将静态声明作为行为支持。

5.0.02完整MCP首轮未发布client结果，runtime显示MCP启动无条件访问仅5.2+支持的可选authoringTransactions；不是模型损坏或全部能力不支持。修复MCP只对精确SDK不可用异常返回typed UNAVAILABLE服务，其他错误继续失败；保持完整client断言，不能用事务UNAVAILABLE冒充旧版事务通过。

5.3.04原PSD readable-v2场景FAIL，原因native返回SAVED_UNVERIFIED；SDK没有bytes/path接口，不能独立证明PSD内容可读。新增明确save-only场景保留旧场景缺口，finally释放issued handle并保留primary/cleanup错误。save-only-r1已supervisor PASS、三个门禁true、cleanup safe，EXPORTED/issued baseline/STOPPED；内容、结构、保真、replace/external edit仍未验证。原结果不改判。

已逐项冻结提交35项新任务（准确job/digest见campaign-jobs.json）：MCP10、PSD save-only5、其余17版纹理关系图、5.3.04参数批量迁移/动画时间线/工作区3。官方worker在线，按FIFO持续运行，不干预其他会话；排队和部分断言不算PASS。新增非MCP assisted入口只用绑定queue任务home的启动agent，禁止普通profile/其他task，启动观测为OBSERVATION_ONLY。动画目录对齐实际wrapper九版范围，旧版保留工具缺口。全能力验收远未完成。

追加5个修复后MCP新包复测（5.2.02 r4、5.0.02/5.1.03/5.2.00/5.2.01 r2），本轮共40项冻结提交。126项runtime回归+38项MCP回归全部PASS、无跳过；MCP新增HTTP transport启动/连接发布/关闭清理回归，旧版optional事务不可用不会执行工作，SecurityException和未知backend故障不隐藏。Python generator2/调度32/覆盖索引9项通过，开发与Core门禁PASS。终态仍待持续收取，见capability-admission-regression.json。

检查点证据审计更新：59组sanitized lifecycle SHA及门禁、62份properties与官方源字节一致。最新devCheck PASS；本轮40项新任务中五项已终态（PSD save-only5304 PASS；MCP5202/5002/5103/5200旧冻结包FAIL），35项仍运行/排队，包括五版修复后新冻结包复测。未停止或干预其他会话，未把queued、readiness或UNAVAILABLE当作支持。

本批35项全部终态：30成功5失败。MCP完整场景新增5.2.00/5.2.01/5.2.02/5.2.03/5.3.00/5.3.02/5.3.03，连同5.3.01/5.3.04共9版；纹理关系图新增16版达19/20（5.0.03夹具就绪超时、宿主WebKit对话框异常，非能力拒绝）；PSD保存观察5.3.00–04五版（SAVED_UNVERIFIED，不含内容验证）；动画时间线、工作区5.3.04通过。5.0.02/5.1.03 MCP其余断言全过但authoring事务execute返回UNAVAILABLE（旧版能力缺口，整体FAIL）。5.2.01 MCP r1失败被r2 PASS取代，旧记录保留。

5.3.04参数批量迁移r1的row.clamped.duplicate FAIL经复查为探针语义过期：探针按纯clamp预测[0,0,1]重复值，文档与实现为源范围线性重映射后clamp得[0,0.5,1]，实现正确。修正探针mapPoints后r2：keyform行全过（含真实重映射传输/撤销/重做/关联/还原），duplicate正确SKIPPED，仅apply.morph.transfer因多点morph夹具缺失仍BLOCKED（夹具缺口）。5.0.03纹理关系retry-r2 PASS，纹理关系图覆盖全部20版。全能力验收仍远未完成。

apply.morph.transfer经r3–r10诊断-修正-复测收敛至PASS（job fdec41d5，95断言全过、3项SKIPPED：无combined参数、fallback快路径未用、无碰撞候选）。诊断链：①raw-owner反射被main合并引入的JDK代理层拦截（jdk.proxy1.$Proxy77，逐候选诊断定位handler=PluginGenerationGuard$Handler，探针按白名单解包guard/availability两层代理）；②PermissionCheckedDrawable/SessionDrawable已由内部类迁移为顶层类，白名单类名更新；③scaffold夹具成功搭建后r7实证morph迁移写入源值[-30,0,30]到[-10,50]目标参数——SDK契约"transferMorphClamped原样搬移，仅invert取负"，探针morph期望模型（目的范围clamp）再次过期并修正；④narrow行改为选取排除源点的目标范围以实证不clamp；⑤唯一遗留FAIL为association读回依赖参数扫描、对超范围点无效，改为raw morph target→getKeyForm→positions的存储态form关联校验。morph/keyform语义分离保持：keyform仍验证重映射+clamp，morph验证原样搬移+取负。夹具任务域内搭建、黄金安装与fixture哈希均未变。

cov-r1覆盖批次45项全部终态（PBT18+时间线8+工作区19；PBT5302因专属夹具env未配置跳过）。终态分层：①PASS：PBT 5201/5202/5203/5301/5303（全场景含morph scaffold）；动画时间线 5200/5201/5202/5203/5302/5303；工作区 5100/5103/5201/5203/5301/5302/5303。②FAIL_ADMISSION：PBT 5002–5006/5101–5103 参数值编辑无验证证据被准入拒绝（UOE，读路径断言仍过），写路径morph行BLOCKED。③FAIL_LICENSE：5100/5200/5300 PBT与5300/5301时间线因FREE每对象2参数上限夹具加载被取消（许可边界，非API拒绝）。④FAIL_READINESS：工作区5000–5006/5101/5102/5200/5202/5300 宿主180s未达可接纳状态（lastObserved=null）待复测。⑤FAIL_INFRA：PBT 5000/5001模型加载超时（WebKit对话框异常）待复测。边界结论：参数值编辑在5.0.x/5.1.x无验证证据被准入墙拒绝；FREE许可安装（5100/5200/5300/5301）无法加载超2参数/对象夹具；工作区与PBT能力边界依版本/许可逐层不同，无任何版本可宣称全能力。


L0/L1 prepare期静态门禁与字节码等价簇接入调度器（scripts/preview/host_validation_static_gates.py + host_validation.py run/prepare/submit）。三类L0门禁在prepare阶段执行、拒绝不入队：①准入墙预判——parameter-batch-transfer要求cubism.editor-model.write、animation-timeline要求animation-timeline.write在目标版本editor-model记录中有VERIFIED_STATIC证据，缺失即SKIP_STATIC(reason=admission)，与实机FAIL_ADMISSION边界一致（≤5.1.03全部缺证、≥5.2.00有证）；②许可门禁——实机观测固化的FREE安装表（5100/5200/5300/5301）×超限夹具任务→SKIP_LICENSE，未知许可不阻塞；③夹具版本路由——.cmo3混淆容器不可靠解析，按sha256↔文档版本表（共享夹具=5.2.03，源自原生"Version of the file"对话框）高于目标版本时自动路由到任务的*-assisted变体（NativeStartupAssistanceAgent确认"新版本文件"对话框），无assisted变体仅告警不阻塞。L1簇表compatibility/cubism/clusters.json由scripts/dev/cubism_reachability_clusters.py --emit-json产出（strict=方法级可达闭包指纹、shallow=selector成员自身指纹），依赖外部cubism-ref jar属onboarding期工件，prepare只读不重算。实测core-*族20版本收敛为3个strict簇（[5.0.00–5.1.03]/[5.2.00–5.2.03]/[5.3.00–5.3.04]），editor侧族18–19簇不收敛（编辑器单体），簇等价仅用于标注不跳过：spec映射到strict簇后若同簇成员已有该任务FINAL PASS台账→covered-by-equivalent=<版本>注释级标记，--full-matrix关闭；shallow只作排序提示永不吞任务；缺簇表完全回退不报错。诚实约束：strict等价只证明该族selector闭包字节码相同，不证明原生运行时行为；13项门禁单测+32项调度测试+118项队列测试通过。

勘误（许可归因修正）：cov-r1 ③ FAIL_LICENSE 分层与 L0 许可门禁表均建立在错误的许可模型上。复核 console 证据后确认：①所有安装均为 FREE-start——startup assistance agent 在许可对话框出现时一律选择 "Start as FREE version"（含 PBT 全过的 5201/5304 run）；②FREE "每对象2参数上限"对话框是确认后模型仍继续加载的便利性限制提示，不是加载拒绝；③cov-r1 中 5100/5200/5300 PBT 与 5300/5301 时间线的实际失败原因是 `Runtime-owned shell failed to start`（turboism 运行时基础设施边界，可重试），非许可取消；④backup 5100-5103/5200-5202 实机 FAIL 中 5.1.x 的真实原因是 "dirty via Parameter.setValue failed: IllegalStateException"——即 editor-model.write 准入墙（与静态门禁一致，backup 已加入该能力门），5202 另含 `Cubism host admission failed before plugin loading` 基础设施失败。许可门禁已降级为非阻塞（INSTALL_LICENSE_TIER 清空、拒绝路径保留备用），被误跳/误取消的 14 项 spec 已以 cov3-relicense 重投，infra 失败的 PBT 5200/5300、timeline 5300/5301、clipmask-viewer 5304 以 cov3-retry 重投。

离线准入预检落地（adapt-admission-preflight 分支）：实机 `Cubism host admission failed before plugin loading`（7ade4e071 修复的 ui.mesh-toolbar.contribute 记录缺口）本可由记录 JSON + 生成 manifest 确定性算出。新增 `VerificationManifestAdmissionPreflightTest`（runtime 测试源集）：对全部 190 份 pinned 记录 × 12 个生成 *VerificationManifest 复用生产链路——`PinnedVerifiedResolverWorkflow` 新抽出的 `requireReviewedRecord`（schema + 记录字节 sha pin + 字段全等，含 capabilityIds）、`forArtifact` 产物绑定与 `VerifiedAccessPlan` 的 authorizes/authorizesFeatureSet 别名能力覆盖判定；双向覆盖断言：manifest pin 必须有对应记录文件、记录必须被某 manifest 认领或登记为不走该路径（core-model-read / performance-render-scene）。失败报告形如 `版本 × manifest × 失配字段`。换入 7ade4e071^ 的 cubism-5.2.01-ui-main-toolbar.json 实测报 `5.2.01 x MainToolbarVerificationManifest: verification record fields do not match the reviewed manifest [fields: capabilityIds (missing [ui.mesh-toolbar.contribute])]`，与历史异常一致。入口：`./gradlew :runtime:admissionPreflightTest`（亦接入 devCheck 与常规 :runtime:test）。后续任何实机 host-validation 任务提交前必须先跑该预检，不再需要靠实机发现此类确定性失配。

## rep-r1 代表版本实机确认轮（2026-10-10，mundane-stingray 集成快照）

按"能力族 × 版本代际代表"而非全量矩阵执行：每代取两个代表（5.0.x→5000/5006、5.1.x→5100/5103、5.2.x→5200/5203、5.3.x→5300/5304），perf 族仅 5203/5304，六个只读步骤族（texture-relations、psd-export-observation、status-bar、clipmask-viewer、separate-save-path、core-owned-moc）并入 session-standard 每代表版本一次。计划 117 个 spec，其中 13 个在 prepare/submit 门禁被静态拒绝（backup/parameter/parameter-batch-transfer 在 5.0/5.1 缺 cubism.editor-model.write 的 VERIFIED_STATIC 证据 → SKIP_STATIC admission；edit-protocol 三个版本 admission 白名单不含 @standard 变体形式），104 个 job 实际入队。去重依据为 2026-10-09 18:00（本地）后队列内同代码 PASS（startup-suppression 5000/5103、workspace 5300、session-standard 5203）。rep-r2 对 7 个 INFRA_RETRYABLE 各重投一次，6 个转 PASS，update-check:5200@safe-assisted 仍 FAIL（infra-edt-dispatch）。

终态：63 PASS / 48 FAIL（含 rep-r2 修正后），全部 cleanup=safe、无 quarantine、无 recover confirm。

### 任务 × 代际结果矩阵（P=PASS，B=许可/能力边界，F=失败，-=未覆盖）

| 任务 | 5.0.x | 5.1.x | 5.2.x | 5.3.x |
|---|---|---|---|---|
| mcp | F(新版夹具加载中断) | F(5100 保存边界,5103 加载中断) | F(5200 加载中断,5203 夹具无Glue) | B(保存受限,两版) |
| atlas | - | - | F(启动无Java) | - |
| backup | -(门禁跳过) | -(门禁跳过) | P(5200备份功能失败为边界,5203 P) | P(r2后两版) |
| boundingbox-warp-mirror | - | - | F(5203无Warp接受LEFT_TO_RIGHT) | - |
| clipmask-viewer | P(会话) | P(会话) | P(会话) | F(会话文档未就绪) |
| core-acquisition | F(加载中断) | P | F(5203启动无Java) | F(启动无Java) |
| core-owned-moc | P(会话) | P(会话) | P(会话) | F(会话文档未就绪) |
| edit | - | - | P(5203) | P(5304;5300文档中途NPE) |
| edit-protocol | - | - | -(admission门禁) | -(admission门禁) |
| fps | - | - | P(5203) | P(5304 r2) |
| host-locale | P | P | P | P |
| incremental-update | - | - | P(5203) | - |
| model-update-skip | - | - | P(5203) | - |
| parameter | -(门禁跳过) | -(门禁跳过) | P(5203,5200探针版本白名单F) | F(探针版本白名单) |
| parameter-batch-transfer | -(门禁跳过) | -(门禁跳过) | P(r2 5200,5203) | P |
| protected-export | - | F(加载中断/文档未就绪) | F(5200文档未就绪,5203启动无Java) | F(启动无Java) |
| psd-clip-mask | P | P | P | P |
| psd-export-observation | - | - | - | F(会话文档未就绪) |
| recent-preview | - | - | F(5203保存阶段受限) | - |
| selection-brush | - | - | F(5203 Robot校准未送达) | - |
| selection-lag | - | - | P(5203) | F(5304模型未激活) |
| separate-save-path | P(会话) | P(会话) | P(会话) | F(会话文档未就绪) |
| session-standard | P | P | P | F(5300/5304文档未就绪) |
| startup-suppression | P | P | P | P |
| status-bar | -(会话仅5203覆盖) | - | P(会话5203) | - |
| texture-relations | P(会话) | P(会话) | P(会话) | F(会话文档未就绪) |
| theme | P | P | P | P |
| update-check | P(safe) | P(5103;5100加载中断) | P(5203;5200 edt-dispatch F) | F(就绪条件未达) |
| warp-deformer-alt-symmetry | - | - | P(5203) | - |
| workspace | F(布局provider缺) | P(5100 r2,5103) | P(5203;5200就绪超时) | F(5304就绪超时;5300去重) |

### 失败根因分组与建议修复点（不改代码，仅记录）

1. **共享夹具为 5.2.03 文档，低于该版的宿主走"较新文件"确认弹窗路径且加载常被中断**（File load interrupted / MODEL_DOCUMENT_NOT_READY）：mcp 5000/5006/5103/5200、core-acquisition 5000/5006、protected-export 5100/5103/5200、update-check 5100。同版本他任务有 PASS，确认为竞态而非硬边界。建议：按代际配备匹配文档版本的夹具（TURBOISM_HOST_VALIDATION_FIXTURE_<ver> 覆盖），或审查 `dev.turboism.validation.NativeFreeStartup.select()`/`NativeStartupAssistanceAgent.assist()` 在 Progress modal 竞态下的 Load 点击时序。
2. **文档打开停滞（无任何弹窗记录、模型 240s 内未激活）**：session-standard 5300/5304（"host never reached a usable document"，六个步骤族 5.3.x 全部 NOT_RUN）、animation-timeline 5300/5304、selection-lag 5304、workspace 5200/5304、update-check 5300/5304（readiness 需活动模型）。建议查 `run-cubism-host-validation.sh` 文档打开投递路径在 5.3.x 上的可靠性及就绪超时。
3. **启动期 Java 未出现（javaProcessCount=0、无 console）**：atlas 5203、core-acquisition 5203/5300/5304、protected-export 5203/5300/5304。Proton cmd 已拉起但 java.exe 未被观察到，证据缺 turboism.log/cubism-console。建议 runner 在 Java 未出现时仍收集 launch.bat/Proton 输出；倾向 infra 瞬态，可择期重跑。
4. **PROBE_DRIFT**：`WindowsParameterValidationProbe.hostCloseRoute`（testing/integration-tests/.../WindowsParameterValidationProbe.java:946）版本白名单仅 {5203,5302,5303}，parameter matrix 在 5200/5300/5304 必抛 IllegalArgumentException —— 按代际补路由或限制任务版本集合；boundingbox-warp-mirror:5203 全部 Warp "BLOCKED UNAVAILABLE" 无 LEFT_TO_RIGHT 接受者（夹具/探针漂移，查 probe scan 准入条件与夹具 Warp 拓扑）；selection-brush:5203 "Robot calibration move was not delivered to the task view"（Xvfb 输入投递/焦点路径）。
5. **CAPABILITY_BOUNDARY（预期）**：mcp 5100/5300/5304 与 recent-preview 5203 保存被原生拒绝——FREE 安装边界；待 PRO 试用辅助代理合入后重跑。mcp:5203 "fixture contains no Glue relation"——共享夹具无 Glue，应换用带 Glue 的夹具（campaign 已知 2980dc8c）。backup:5200 backupNow IllegalStateException——5.2.00 原生备份能力边界。workspace 5000/5006 `workspace.layout.provider.unavailable`——5.0.x 无布局 provider，能力缺席。
6. **ADMISSION_STATIC（门禁，未绕过）**：backup/parameter/parameter-batch-transfer 在 5.0.x/5.1.x 缺 `cubism.editor-model.write` 的 VERIFIED_STATIC 证据（prepare 期 SKIP_STATIC，共 10 spec）；edit-protocol 三版本 admission 白名单缺 `"edit-protocol:<ver>@standard"` 形式（`host_validation_queue.py` `edit_protocol_client_dependency` 与 mcp 白名单对比缺变体项）；update-check 默认 `current` 变体要求 bundle==0.43.11，本快照为 0.44.0-SNAPSHOT，改以 `@safe` 变体代表。
7. **edit:5300**：会话中途 document 变 null（S4/S6 多处 NullPointerException，history UNAVAILABLE），5203/5304 PASS——疑似文档生命周期不稳，建议核对 edit 会话在 5.3.00 的 document 保持路径。
8. **残余 INFRA**：update-check:5200@safe-assisted 两轮均 `infra-edt-dispatch`（EDT 1000ms 未接受工作区布局操作），按规则不再重试；记录为已知瞬态。

待 PRO 试用辅助合入后需重跑项：所有 CAPABILITY_BOUNDARY 保存/导出受限项（mcp 5100/5300/5304、recent-preview 5203）、以及因 FREE 弹窗路径相关的加载中断项可按新行为复测；同时重建 `validation/shared/build-startup-assistance.sh` 并刷新依赖它的 bundle。

## stall-r1 停滞归因与修复确认轮（2026-10-10，adapt-53-open-stall）

针对 rep-r1 失败根因分组第 2、3 项实机复核。

**根因 #2（启动期 Java 未出现，NO_EVIDENCE×7）实为 runner 门禁缺陷，非宿主问题**：`run-cubism-host-validation.sh` 的 deferred-GL-check 门禁在 launch 后单次读取 runtime log；无 `--ready-marker` 的任务（atlas、core-acquisition、protected-export 等）在 ~50ms 内即被判 `deferredCheck=unknown` 而 fail，此时 protonfixes 尚未完成、java.exe 从未获得启动机会（launcher.out 仍只有 shorin wrapper 的 protonfixes 输出；java-process.ready.properties javaProcessCount=0 是截断瞬间的采样而非真实"Java 未出现"）。修复：门禁在 readiness 预算内轮询 marker，全部声明 ready-marker 已满足（bootstrap 行不可能再出现）或 wrapper 已死（host-exited）时提前结束；判定语义不变。同时 `collect_evidence` 新增 `collect_launch_diagnostics`：固定输出 launch-diagnostics.properties（wrapperExit/launcherOutBytes/runtimeLogPresent/deferredCheck/protonWrapperLog）并复制 launch.bat/launch.sh 与 Proton wrapper 自身日志。triage 新增 evidenceProperty 从句，`deferredCheck=unknown|host-exited`→INFRA_RETRYABLE、`inactive`→ADMISSION_STATIC，该 7 格不再落 NO_EVIDENCE。

**根因 #2（5.3.x 文档打开停滞）确认为"无人应答的启动弹窗"，已修复并实机验证**：rep-r1 的 `session-standard:5300/5304@standard` 与 standalone 未辅助变体（animation-timeline/selection-lag/workspace/update-check@非assisted）共同的结构性差异是会话/启动全程没有任何 audited prompt responder——会话步探针明确将自己的 responder 关断（mcp-host-probe 注释"session 由 coordinator 独掌"），而 coordinator 的辅助线程又门控于 `session.startFree=true`，`standard` 变体不传该属性。5.3.x 启动链 Start→FREE 参数上限模态框在无人点击 OK 时持有命令行打开请求，activeDocument 永不成立（600s 全步 NOT_RUN）。修复：SessionCoordinatorPlugin 默认运行审计 responder（显式 `-D...startFree=false` 可关）、循环记录 `SESSION_DIALOG_OBSERVATION`、启动超时中止时落 `SESSION_STARTUP_DIALOGS` 证据。

**stall-r1 实机结果**：`session-standard:5304@standard`（job 3a62ee23，runId queue-738cb750…，证据 ~/TurboismValidation/session-standard/5304-stall-r1/）：`SESSION_DIALOG_OBSERVATION` 证实 Start 模态框与 `FREE version - The max number of Parameters allowed per object: 2` 参数上限模态框真实出现并被应答（`SESSION_START_FREE_SELECTED`→`SESSION_FREE_WELCOME_CONFIRMED`→`SESSION_FREE_PARAMETER_LIMIT_ACKNOWLEDGED`），`SESSION_HOST_READY` 于 ~5s 达成（rep-r1 同 spec 为 600s 超时）；5 步中 4 步 PASS（texture-relations/clipmask-viewer/separate-save-path/core-owned-moc），仅 psd-export-observation FAIL——与 rep-r1 fix 轮 `standard-assisted` 已知 5.3.04 边界一致。新增证据文件全部落盘（launch-diagnostics.properties、launch.bat、launch.sh、proton-wrapper.log）。

**本轮工具链事件（与本改动无关，记录为已知缺口）**：后续两 job（session-standard:5300、animation-timeline:5304@assisted）的 supervisor 在运行中同时崩溃于 sqlite `locking protocol`（supervisor-error.json），无 outcome → `durable_outcome` 判 "no durable final supervisor verdict" → 队列 quarantine（NATIVE-PERFORMANCE-EXPERIMENTS.md 已载此形态无自愈路径）；按规程未做 recover/abandon，5300 同型修复以 5304 证据类推，待队列恢复后可补跑。`scripts/test/test_cubism_host_validation_interactive.py` 在干净基线同样 9 项失败（runner 无 `--interactive`，测试-特性错配），与本次改动无关。

## trial2 统一启动应答确认轮（2026-10-10，adapt-pro-trial-startup）

针对分叉的许可/新版警告应答与用户观察的"点了试用却不确认"实机复核，三轮内收敛。

**统一实现清单**：启动许可/试用确认/活跃试用欢迎/更新提示/Home 新闻窗/较新文件警告的全部应答收敛到 `dev.turboism.validation.NativeFreeStartup`；clipmask 探针的本地 `answerNewerModelWarning`（枚举窗口+按 "Load" 字面 doClick）与参数探针 `inspectBlockingModal` 的 Load 检索改为调用共享实现；core-acquisition、mcp、session-coordinator 此前已走共享实现。`scripts/test/check_code_quality.py` 新增 `startup-dialogs` 规则并入 `checkCodeQuality` 默认规则集：任何同时持有 audited 提示签名与 UI 执行原语（doClick/setValue/Robot/键盘/dispatch/dispose）的源文件被拒，仅放行 NativeFreeStartup 本身与有文档说明的 close-path 例外。

**trial2-r1 根因（两独立缺陷）**：clipmask-viewer:5103@assisted 与 mcp:5304 均在 START_PRO_TRIAL_SELECTED→PRO_TRIAL_CONFIRMED 后停在 "Welcome! Trial license will expire in 33 day(s)" Confirm 模态，文档未就绪。原因一：`isActiveTrialWelcome` 用整串 `matches()`，而 5.1.03 的欢迎框多出一个 "Confirm" 标题标签，签名整串比对失败 → 从未被识别应答；改为标记合取（Welcome! + expiry 天数短语 + OS-update caution）。原因二（加固）：assist 循环每次迭代新建结果槽，EDT 停顿期超时后迟到 pass 的观测与动作整组丢弃；改为循环外结果槽，迟到结果由下一轮消费。同时给所有应答加有界重试（800ms 后仍 showing 则解除 actioned 标记，上限 8 次），杜绝"队列化点击被模态转换吞掉后永久不再重试"。

**trial2-r3 实机结果**：`clipmask-viewer:5103@assisted`（job c9157694，证据 ~/TurboismValidation/clipmask-viewer/5103-trial2-r3/）supervisor PASS，动作序列完整：START_PRO_TRIAL_SELECTED→PRO_TRIAL_CONFIRMED→ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED→NEWER_MODEL_OPEN_CONFIRMED→CLIPMASK_VIEWER_MATRIX_RESULT PASS。`mcp:5304`（job 8470fc51）启动链同样完成（含 ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED），assertion.document PASS，`texturePersistence=FIVE_OPERATION_KINDS_SAVED_AND_REOPENED`——此前被 FREE 保存限制挡住的写路径在试用下打通；唯一 FAIL 为已知夹具内容缺口 `fixture contains no Glue relation`（rep-r1 分组 #5，应换带 Glue 的夹具 2980dc8c，与本改动无关）。`core-acquisition:5100@assisted`（job 9b099855，r1 即 PASS）动作序列 START_PRO_TRIAL_SELECTED→PRO_TRIAL_CONFIRMED→NEWER_MODEL_OPEN_CONFIRMED。


## pro-r1 默认启动辅助 + PRO 试用整轮重跑（2026-10-10，adapt-assisted-default-rerun）

rep-r1/r2 全部非 PASS 格子在"启动辅助默认开启"下重投；排除门禁正确拒绝的 5.0/5.1 写能力项与他代理已确认 PASS 的格子（wl-r1 parameter:5304/selection-brush:5203、wl-r2 boundingbox-warp-mirror:5203、wl-r3 update-check:5203、trial-r4+ update-check:5100/5103、trial2-r3 clipmask-viewer:5103、stall-r1 session:5304 部分步）。37 spec（pro-r1）+1 INFRA_RETRYABLE 重投（pro-r2 update-check:5300），全部终态：19 PASS / 18 FAIL（会话步级另入账 8 PASS）。启动链证据：19 个 jar-assisted 任务的 native-startup-assistance.properties 全部记录 START_PRO_TRIAL_SELECTED→PRO_TRIAL_CONFIRMED（及 ACTIVE_TRIAL_WELCOME_ACKNOWLEDGED，5.0.x 对话框标题变体下个别未落 action.3 但不阻塞），全轮无 FREE 限制对话框；mcp/session/core-acquisition 等自带应答器任务走探针内 NativeFreeStartup，链同样完整。

**配套实现**：静态门禁改为凡存在 assisted 配对变体即默认路由（原因 startup-assistance-default；夹具更新时仍记 fixture-version-routing），--no-startup-assistance 保留刻意未辅助路径；补 protected-export 全 12 变体与 boundingbox-warp-mirror:analyse 的 -assisted（沿用 glthread off 惯例）；测量/性能变体（fps×5、atlas×34、model-update-skip×3、incremental-update×3、selection-lag×2、parameter perf/native×11）新增 -assisted-gl——辅助代理 + 显式 mesaGlThread=true，评审版本上与未辅助测量条件一致（未评审版本由 runner 同样强制关闭，与未辅助行为相同）；mcp/core-owned-moc 每变体自带应答器记为 self-responding 不路由不发警告；atlas wrapper 放行 --aux-agent/--jvm-option/--linux-env/--windows-env 转发使 -assisted-gl 可用；NativeFreeStartup 活跃试用欢迎匹配收紧为正数天数，core-acquisition 自检移除已被标记合取设计接受的追加文本用例。

**PASS 翻转（19 格，vs rep-r1 全为 F）**：animation-timeline 5300/5304、backup 5200（原 backupNow 边界在试用下通过）、core-acquisition 5000/5006/5203/5300/5304、edit 5300、parameter 5200/5300（hostCloseRoute 全版本白名单实机确认）、protected-export 5203/5300/5304、selection-lag 5304、update-check 5200/5304、workspace 5200/5304。

**仍失败分类与建议**：
- 夹具内容缺口（8 格，确定性，不重投）：mcp 5000/5006/5100/5103/5200/5203/5300/5304——启动链与原 FREE 保存限制路径全部打通（texturePersistence=FIVE_OPERATION_KINDS_SAVED_AND_REOPENED），唯 "the exact-host fixture contains no Glue relation"；建议按既有结论把对应版本夹具指向带 Glue 的 2980dc8c。
- 探针/UI 漂移（5 格）：protected-export 5100/5103/5200 `CHECKED_CONFIRM_BUTTON_MISSING`（桥 attach/cancel/decide 安装与候选复选框注入正常，原生确认按钮在 5.1.x/5.2.00 的形状与已审 5.2.03/5.3.x 不同）；atlas:5203 驱动器 "Expected one algorithm selector"（自动排版对话框选择器形态与探针假设不符；辅助代理与 glthread=on、deferredCheck=active 均正常）；recent-preview:5203 菜单保存 8 次点击无 savedEvent（menuPath=menu，菜单路径或保存事件绑定漂移）。
- 已知能力边界（4 格）：workspace 5000/5006 `workspace.layout.provider.unavailable`（5.0.x 无布局 provider，其余 32 断言 PASS）；session-standard 5300/5304 各为 4/5 步 PASS（texture-relations/clipmask-viewer/separate-save-path/core-owned-moc 全 PASS，rep-r1 的"会话文档未就绪"已消除），仅 psd-export-observation 步 SAVED_UNVERIFIED（可读导出无 runtime-issued edit handle，既定 SDK 缺口）。
- INFRA 系统性（1 格）：update-check:5300@safe-assisted `Shell startup exceeded PT30S`——pro-r1/r2 两次同征：许可/欢迎/Home 新闻链全部应答后 turboism.core 壳 30s 未就绪；按规则不再重试，建议调查 5.3.00 壳启动耗时段或放宽该版本 shell 预算。

## drift-r1 残余失败逐项修复确认轮（2026-10-10，adapt-remaining-drift）

针对 pro-r1/pro-r2 台账剩余的 19 格失败逐根因修复并实机复核（队列最多 2 并发；所有 job 以 drift-r* 请求键入账）。

**修复与实机结果**：

1. **mcp 8 格（`fixture contains no Glue relation`）**：mcp 任务级夹具覆盖到已验证含 Glue 的 2980dc8c 夹具（clipmask-viewer 同款），静态门禁 `fixture_path_for`/`FIXTURE_DOC_VERSION` 同步。实机：5200/5203/5300/5304 全量 PASS（连接/工具/资源/工作区/纹理/审计全绿）；5000（r2）/5006/5100/5103 其余断言全 PASS，唯 `turboism.transaction.execute` 返回类型化 `UNAVAILABLE`（`cubism.authoring.transactions.unavailable`，5.0.x/5.1.x 无验证过的事务后端）→ CAPABILITY_BOUNDARY。5000 第一轮另暴露 5.0.00 试用欢迎文案变体（`might cause to terminate trial mode`），`NativeFreeStartup.isActiveTrialWelcome` 放宽为共享词干 `terminate` 并补回归测试。
2. **protected-export 5100/5103/5200（`CHECKED_CONFIRM_BUTTON_MISSING`）**：确认按钮定位改为结构识别（底部 FlowLayout 面板恰好两个 JButton：首按钮=OK/确认，混淆类名按版本漂移），不再硬编码类名/版本。实机 3/3 PASS。
3. **atlas:5203（`Expected one algorithm selector`）**：时序竞态——探针打开自动排版对话框早于插件算法注册完成，`registry.algorithms()` 为空使注入按设计跳过。探针在缺选择器时关闭重开（有界）。实机 PASS（implementation=new count=100）。
4. **update-check:5300（`Shell startup exceeded PT30S`）**：30s 生命周期预算把主机启动期 EDT 竞争计入壳自身工作。`CoreShellRuntime.start` 在提交生命周期 lane 前有界等待 EDT 就绪。实机 PASS。
5. **triage 规则**：workspace 5000/5006（5.0.x 无布局 provider）与 session `psd-export-observation`（SAVED_UNVERIFIED）解码修正后正确归类 `capability-workspace-layout-provider-absent`/`capability-session-psd-export-saved-unverified`；新增 `capability-authoring-transactions-unavailable`；修复 `merge_step_entry` 不刷新既存步级 triage 字段的缺口（rep-r1/pro-r1 历史条已回填）。
6. **recent-preview:5203（保存链路）**：多缺陷链逐一消除——探针描述符补 `model.read/model.write`（脏化参数写）、`model.observe`（**根因**：生命周期注册表对缺该权限的插件过滤 `on*` 回调，`ModelFileHooks.onModelSaved` 永不分发，生产 recent-preview 插件同受此缺口影响）；菜单遍历改为递归 `JMenu.getPopupMenu()` 子树（`getComponents()` 不可达 popup 内容）；菜单开启检测改用 `JMenu.isPopupMenuVisible` + `setPopupMenuVisible` 兜底（宿主 LAF 在 release 时把 popup 收掉）；保存前 SDK 参数写置脏；菜单点击无保存时经 `EditorCommandService.execute(SAVE)` 升级真实保存（`sdkSave=EXECUTED`）；保存事件按会话 `contentId` 匹配（`model:<hash>`，实测 name 为内部模型名 `测试 混合模式` 而非文件名——原 `fixture.cmo3` 硬编码匹配永不命中）。实机现状：recent-list/直接截图/保存事件(savedEvent=true)/生产缓存(png+index) 全 PASS，**仅 popup 悬停预览未观测**：预览桥 `isCurrentItem` 要求条目处于 `MenuSelectionManager.selectedPath`（实测悬停时 depth=0），程序化开菜单路径不产生该状态——宿主 LAF 下真机点击会收掉 popup，hover 不开子菜单，选择路径无法经真实输入建立；归类 PROBE_DRIFT，证据 `recent-preview/5203-drift-r1/queue-f6b600d33b69429f86adc95adf3bed58/evidence`。

**实机确认汇总**：PASS×9（atlas:5203、update-check:5300、protected-export×3、mcp 5200/5203/5300/5304）；CAPABILITY_BOUNDARY×4（mcp 5000/5006/5100/5103，authoring-transactions 缺席）；PROBE_DRIFT×1（recent-preview:5203 popup 阶段残余）。

## refresh-r1 过期格子刷新轮（2026-10-11，mundane-stingray 集成快照）

目标：刷新 `hostValidation` 中 INFRA_RETRYABLE/NO_EVIDENCE/UNKNOWN/PROBE_DRIFT/FIXTURE_UNSUITABLE 的过期格子。筛选规则：manifest 可运行任务 × 代表版本（5.0→5000/5006、5.1→5100/5103、5.2→5200/5203、5.3→5300/5304）∩ 任务许可版本，排除 10-11 18:00（本地）后队列内同代码 PASS（实际无）。114 个目标类格子 → 31 候选 → 30 spec：26 独立 spec + 4 个 session-standard（合并 texture-relations/clipmask-viewer/separate-save-path/core-owned-moc 等只读步骤族）。bundle 与全部 manual-test 包重建并 LINKED；2 个 spec 在 prepare 静态门禁被拒（parameter-batch-transfer 5000/5100：5.0.00/5.1.00 缺 `cubism.editor-model.write` VERIFIED_STATIC 证据 → SKIP_STATIC admission，如实记录不绕过）。28 个 job 一次入队 FIFO 全部终态，cleanup 全 safe、无 quarantine、无 recover。

**终态：16 PASS / 12 FAIL**（另有 13 个会话步级 PASS 入账）。队列级聚合归因为 SYSTEMIC×4（mcp 5000/5103/5200/5304 同签名 `McpExecutionBridge.awaitUi` host-execution-timeout，4 版本同征且无 PASS → 不重投）、CAPABILITY_BOUNDARY×2、UNKNOWN×6；无"非 SYSTEMIC 的 INFRA_RETRYABLE"，故无 refresh-r2。

### 任务 × 代际结果对比（P=PASS 翻转，B=能力边界，F=仍失败，-=未涉及）

| 任务 | 5.0.x | 5.1.x | 5.2.x | 5.3.x |
|---|---|---|---|---|
| animation-timeline | - | - | - | P(5304) |
| atlas | - | - | P(5203) | - |
| clipmask-viewer | P(5000/5006 会话) | P(5100 会话) | - | - |
| core-acquisition | - | P(5103) | P(5203) | - |
| edit-protocol | - | - | F(5203) | F(5304) |
| fps | - | P(5103) | - | - |
| mcp | F(5000 SYSTEMIC) | F(5103 SYSTEMIC) | F(5200 SYSTEMIC,5203 夹具) | F(5304 SYSTEMIC) |
| parameter | - | - | P(5200) | - |
| parameter-batch-transfer | 门禁跳过(5000) | 门禁跳过(5100) | - | - |
| protected-export | - | - | P(5203) | - |
| psd-export-observation | - | - | F(5200) | B(5300 会话步) |
| selection-lag | F(5000/5006 输入) | - | - | - |
| startup-suppression | - | P(5100) | P(5200) | - |
| theme | - | - | P(5200/5203) | - |
| update-check | - | P(5103) | - | - |
| workspace | B(5000 布局provider缺席) | - | P(5200) | - |
| session-standard | 会话 PASS(5000/5006) | 会话 PASS(5100) | - | 会话 4/5 步 PASS(5300) |

矩阵 PASS 格计数：5.0.x 64→66、5.1.x 37→42、5.2.x 61→69、5.3.x 111→113（净 +17：16 目标格翻转 + 会话步级 texture-relations/separate-save-path/core-owned-moc 等附带刷新）。

**仍失败归因与建议修复点**：
- mcp 5000/5103/5200/5304（SYSTEMIC）：`McpExecutionBridge.awaitUi` 执行超时，四版本同签名 → 产品/基础设施层 EDT 派发停滞，建议查 MCP 桥 `awaitUi` 在启动辅助应答链后的卡死点。另注意 mcp:5203 单独失败为 `fixture contains no Glue relation`——本轮 prepare 环境缺 `TURBOISM_HOST_VALIDATION_FIXTURE_MCP`（drift-r1 用的 2980dc8c 带 Glue 夹具即 CLIPMASK 覆盖同款文件），该 override 未持久化进 `.env`；其余 20 项断言全 PASS（连接/工具/工作区/纹理保存重开/审计）。建议：把 FIXTURE_MCP(+SHA256) 写入 `.env` 或让 mcp 包装脚本默认选择带 Glue 版本。
- edit-protocol 5203/5304（UNKNOWN，`conn1 EditBegin → error_InvalidEditOperation`，全版本同征）：根因定位——`EditBegin` 走 `EditToggleApprovalGate` 活体分支（注入「编辑」复选框即批准面），复选框未勾选即拒绝且**永不弹批准对话框**（probe 插件的 "Turboism Edit Session" Swing 应答线程 `dialog.decisions=0`，该路径仅作用于 SwingEditApprovalGate 回退）。harness 缺口：无人驱动注入复选框。建议：probe 插件经 NativeEditToggleInjector 目标复选框程序化置位，或为 toggle-gate 路径加验证专用批准；非产品缺陷（fail-closed 为设计）。
- selection-lag 5000/5006（UNKNOWN）：`no mouse events observed during interaction (input did not reach the EDT)`，interactionMouseEvents=0——合成输入未达宿主 EDT（Xvfb 输入/窗口聚焦路径），非能力问题。建议查 input 注入与目标窗口映射。
- psd-export-observation 5200（UNKNOWN）：`SAVED_UNVERIFIED`/`Native readable export without a runtime-issued edit handle`——与会话步 5300 相同类（既定 SDK 可读导出缺口），独立任务规则未归 CAPABILITY_BOUNDARY，建议 triage 规则扩展或探针按 SAVED_UNVERIFIED 归类。
- workspace 5000（CAPABILITY_BOUNDARY）：5.0.x 无 workspace layout provider（其余断言 PASS），真实边界。
- parameter-batch-transfer 5000/5100（SKIP_STATIC admission）：5.0.x/5.1.00 无 `cubism.editor-model.write` 静态验证证据，格子维持待办；需先补对应版本 verification 记录再实机。
- session-standard 5.0.00/5.3.00 矩阵格仍显示旧终态：会话 job 可读步结果时台账只写步级条目、不产生 session-standard 行，该任务格按设计只在"会话无步证据"时更新（ cosmetic 台账覆盖说明，非失败）。


## mcpfix-r1：mcp 夹具回退修复实机复核

refresh-r1 mcp 四版"SYSTEMIC awaitUi 超时"经证据复核为台账误归因：同一 `ui.schedule TIMED_OUT LIGHTWEIGHT` + 单次 `modelSnapshotGet` awaitUi 超时在 drift-r1 的 PASS 运行中逐字出现（客户端在模态进度框解除后重试），且 mcp 属 `SELF_RESPONDING_STARTUP_TASKS`（standard 变体，探针自带应答器），与启动辅助默认化/注解/runtime 改动无关。真正 FAIL 为全部五版 `error=ValidationFailure: the exact-host fixture contains no Glue relation`——refresh-r1 prepare 环境缺 `TURBOISM_HOST_VALIDATION_FIXTURE_MCP`，回退到共享 57c4854b 夹具。

修复：mcp standard 变体夹具解析改为 `FIXTURE_MCP` → `FIXTURE_CLIPMASK`（同款 2980dc8c 带 Glue 文档）→ 版本化选择的顺序回退；`fixture_path_for` 静态门禁同步；新增 `fixture-no-glue-relation` triage 规则（FIXTURE_UNSUITABLE），避免结果文件 `error=` 行再被超时签名遮蔽。单测覆盖门禁回退与包装脚本顺序。

实机（mcpfix-r1，standard）：
- mcp:5304 PASS（job 717972f0；fixture 2980dc8c，unchanged/identity/normalExit 全 true，cleanup safe）
- mcp:5200 PASS（job e0dd8c3a；同上）
- mcp:5000 FAIL（job d92e248d）——`cubism.authoring.transactions.unavailable`，与 drift-r1 5000 完全同签名，为 5.0.00 既定能力边界（triage CAPABILITY_BOUNDARY），非回归；夹具源/副本 2980dc8c 未变。未重试（确定性边界）。
