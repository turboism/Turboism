# 自更新激活与首次发布记录（Beta 实机验收已完成，2026-11-01）

用户已允许启用自更新，并明确允许将验收通过的改动合入产品 main 后发布。本记录覆盖这一阶段；此前的“不合入 main”和“保持自更新关闭”是历史边界。

**最终结果：** PR #59–#62 已合入；生产 API、正式签名及发布均已启用。Beta.2 → Beta.5 → Beta.7 两次真实 Cubism 升级已通过，最新公开版本为 `0.68.1-beta.7` / Build 81。详见 [最终实机验收](production-beta-acceptance.md)，其中列明插件联动问题和旧安装首次迁移要求。下文保留各阶段过程，其中“待完成”“用户正在游戏”等描述仅对应当时状态。

## 已完成的发布准备

- 测试分支与 main `9767e2b611ea39009059c5d1fc5ad7ff8f1423eb` 的唯一冲突位于插件自动更新路径。整合保留自更新任务锁，并采用 main 的先关闭旧 JAR 再热替换的协调器；成功和已回滚的热更新都会正确消费对应待办。
- 聚焦 Java 验证：插件批次协调 12 项、插件自动更新 11 项、待办安全 16 项（含 1 个既有跳过），均无失败；清单验证 75 项无失败。新增回归覆盖框架任务阻断插件自动暂存、真实插件热替换及回滚、Beta 更新器基线。
- 最低更新器版本设为 `0.68.1`。客户端按三段数字核心比较，因此允许 `0.68.1-beta.2` 等同核心的启用客户端；后续候选保留这个基线。Beta 更新的严格顺序仍由版本发现/发布准入及真实构建号约束。
- 仓库 `FRAMEWORK_UPDATE_SIGNING_ENABLED=true`；正式签名私钥和公钥沿用 [签名配置验收](signing-provisioning-validation.md)，没有重新生成或替换。
- `production-release` 已限制为唯一的 `main` 分支规则，并配置 `CLOUDFLARE_API_TOKEN` 和 `CLOUDFLARE_ACCOUNT_ID` 两项环境 Secret。

## 发布凭据配置证据

用户明确批准将部署仓库现有 Cloudflare 凭据加密转存到产品发布环境。[一次性工作流 41276721538](https://github.com/turboism/turboism-learn/actions/runs/41276721538) 在部署仓库 main 的 `e1fc1f12ed732e38b3bc17e8c0a6cd227997383a` 上成功运行：确认现有 R2 桶可访问，然后用 GitHub 目标环境的公开加密键生成 sealed-box 密文。

根代理将密文提交给目标环境 Secret API，只重新读取名称与更新时间：令牌为 `2026-11-01T05:24:24Z`，账户配置为 `2026-11-01T05:24:25Z`。没有读取或记录令牌明文，也不声称 GitHub 提供了 Secret 明文读回。该令牌沿用原部署凭据权限，不是新建的专用 R2 令牌。

一次性工作流已在部署仓库提交 `2c330c88683d19a6c633b0f148d4d4f20ef51110` 中移除，远端密文产物和本机临时密文也已清理。公开操作记录保存在忽略目录 `build/agent-tasks/publisher-provisioning-record.json`。

## 最新发布进展

- PR [#59](https://github.com/turboism/Turboism/pull/59) 的全部检查通过后，已正常合入 main `9fc8392c70666ef98441ed636f956344ef3edadc`；合并后主 CI、三通道打包与历史检查全部成功。
- 服务端发布入口已部署并验收通过：[部署 41287404864](https://github.com/turboism/turboism-learn/actions/runs/41287404864)，部署仓库提交 `e574ebc9d5b2ab26f229314eaba71592753897a9`。无凭据的框架写请求返回 401；公开下载、完整镜像哈希、校验文件与 Range 验证通过。
- 真实签名候选 `0.68.1-beta.2` / Build 76、Beta.3 / Build 77、Beta.4 / Build 78 均构建成功。Beta.2 的 ZIP 在本机再次通过正式公钥验签、36 个框架文件核验及 agent 内嵌 CI 身份核验。
- Beta.2 首发发现插件目录固定版本过旧。完整 Git 树审查确认非目录数据差异仅涉及六个列表 UI、类型和测试文件，发布、签名及依赖代码未变。PR [#60](https://github.com/turboism/Turboism/pull/60) 更新四行固定版本，44 项 Linux 发布工具测试、工作流检查、全部 PR 与合并后 CI 均通过；产品 main 为 `206b8ed33f38eb9fc5f12e0bf961e29edb4d961f`。
- 恢复后的 [Beta.2 发布 41289321257](https://github.com/turboism/Turboism/actions/runs/41289321257) 已完成插件目录发布、正式框架对象入库、真实 OIDC 准入和公开完整对象校验，但在最终 `POST git/refs` 创建 GitHub 标签时返回 403，没有生成 Release。没有通过本地伪造 CI 或扩大令牌权限来绕过。
- [Beta.5 候选 41290510542](https://github.com/turboism/Turboism/actions/runs/41290510542) 已完整成功，真实 Build 79 / source `206b8ed33f38eb9fc5f12e0bf961e29edb4d961f`。[正式发布 41291393323](https://github.com/turboism/Turboism/actions/runs/41291393323) 已通过正式对象入库、OIDC 准入及全部对象读回校验，成功创建 [v0.68.1-beta.5](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.5)。此前 Beta.2 的 403 不再阻塞当前 main 的发布，但旧提交创建标签失败的具体权限原因未获证实。
- [Beta.6 候选 41291407318](https://github.com/turboism/Turboism/actions/runs/41291407318) 也已签名构建成功，仍未发布，留待首次实际升级完成后用于第二次连续升级。

## 首发后的 API 刷新故障

Beta.5 已发布，但 09:23 UTC 前后的公开 API 仍返回 Beta.1 / Build 73，快照时间停在 `2026-11-01T08:52:40.266Z`，健康为 `degraded`。此前通知工作流只检查通道是否 `unavailable`，因此在使用旧快照时仍显示成功。

[只读生产诊断 41292738844](https://github.com/turboism/turboism-learn/actions/runs/41292738844) 捕获 7 条记录、2 次 `Release refresh failed: GitHub HTTP 403`。本机用相同服务端校验器读取公开 GitHub 元数据，三个通道全部通过，识别 Beta.5 / Build 79；完整发布列表仅约 1.4 MB，没有触发 4 MB 上限。已证实生产 GitHub 请求被拒绝，尚未取得足以区分具体限流类型的响应头。

修复让既有受信 OIDC 通知携带该 job 的短期 `contents: read` 凭据，仅用于本次固定 GitHub API 读取。凭据放在有界 POST 请求体中，不写入快照、下载对象、客户端或请求 URL/自定义请求头。认证刷新可在匿名失败后立即重试，并等待正在进行的匿名刷新结束；普通公开调用仍不能提供凭据或触发内部刷新。新增每十分钟的受信通知，Cloudflare Cron 保留为过期快照的匿名回退。通知遇到 degraded、陈旧或不完整结果会在有界重试后失败。

Windows Node 24 的 19 项聚焦测试通过，包括 7 项新回归；主要恢复与调度身份回归已先在修改前失败、修改后通过。工作流 actionlint 与 diff 检查通过。完整远端 CI、修复部署及 Beta.5 在线发现/验签仍待完成。

本机已使用正式 Beta.2 签名 ZIP 完成首次安装收据初始化并启动 Cubism 5.3.02：30 个启用插件、0 个失败，MCP/ACP 仅在独立实验目录显式禁用。初次由工具沙箱账户解包、正常用户启动造成的目录 owner 不一致已留证；改为同一正常用户解包和启动后收据初始化成功，没有修改产品 ACL 检查。

用户随后告知正在打游戏，因此已暂停所有窗口激活、鼠标、键盘和原生 UI 操作，只继续后台检查与发布。**真实原生下载、下次启动应用、健康确认和连续第二次升级仍待完成，不能把签名构建及 API 准入等同于客户端升级整体验收。**

## CI 修复记录

PR [#59](https://github.com/turboism/Turboism/pull/59) 首轮完整 CI 发现两处 core-contract 格式问题及 54 项公开 API 文档缺口。本次已补齐实际行为文档和真实覆盖方法的 `@Override`，保持检查器与零积压基线不变。原始代码质量检查在 WSL 通过；runtime、core-contract 格式检查与编译通过。完整 PR CI 仍须在修复提交上重新通过。

第二轮 CI 已越过上述检查，在 6811 项 runtime 测试中发现 2 项跨 JVM 锁失败。Linux 最小复现确认：同一进程第二次抢锁失败后关闭新文件句柄，会释放原来持有的 POSIX 文件锁。事务锁与下载缓存写锁现共用打开文件前的进程内排他检查，并在失败和关闭时正确释放占用；原有错误诊断文本保持兼容。新增回归覆盖缓存锁、重复关闭、申请失败后的重试及独立安装之间的并行持锁。

修复后的 Windows Java 17 锁、健康确认、启动租约与跨进程恢复测试共 53 项通过。Linux OpenJDK 21 的原始两项失败及新增四项回归均通过；扩展到下载器及真实跨 JVM 恢复后，共 125 项通过、无跳过。真实 GitHub Java 17 完整 CI 尚待重跑。本机 Windows 下载器的两项符号链接测试缺少系统权限，不能计为通过，相关断言已在 Linux 验证中通过。

随后完整主 CI [41280948148](https://github.com/turboism/Turboism/actions/runs/41280948148) 已通过；Windows 托管启动器 196 条行为断言也通过。三通道打包发现旧检查仍只接受无启动 token 的 agent 参数，已将检查对齐到实际 token 协议，没有改变启动器或放宽行为验收。该修正后的主 CI [41281917243](https://github.com/turboism/Turboism/actions/runs/41281917243) 同样通过。

Nightly 打包另发现取消下载后的工作线程退出超时。Linux 原测试重复到第 27 次复现；仅在失败后抓栈的复查在第 23 次再次复现，确认下载线程等待 JDK `Keep-Alive-SocketCleaner` 持有的流锁。取消调度器与下载线程并发关闭响应时，后台清理会继续读取已取消的响应，导致退出超过原有 7 秒上限。仅发送 `Connection: close` 的对照仍会复现，因为服务器可能不回送关闭标志。最终改为由下载线程在有界连接/读取返回后统一清理；取消仍立即完成 future 并中断工作线程，不在调用方或共享调度器执行阻塞关闭。保留原 7 秒断言，追加调度器在 1 秒内响应、取消/超时后立即重试成功、旧 future 保持失败的验证。新增调度器断言在 Windows Java 17 修复前失败、修复后通过，实际最终实现重复 10 次全绿；Linux 完整下载器 72 项全绿、无跳过。更长的取消对照与新的完整 CI 仍在执行。

原计划从 `0.68.1-beta.2` 开始；其正式签名完整包已产生并完成本机首装，但 GitHub 发布遇到上述标签权限错误。当前发布进度以上方“最新发布进展”为准，Stable 保持原发布。

旧客户端必须先通过包含签名安装收据的新完整包升级一次；启用服务器和仓库变量不会改变已经安装或加载中的旧客户端。
