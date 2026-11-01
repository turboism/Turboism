# 插件联动修复与更新窗口补充（2026-11-01）

用户要求修复正式 Beta 验收发现的插件联动故障，同时显示更新日志、MB 下载大小和计时。

## 实现与复现

- 官方 Plugin Directory v2 的 `lib/catalog-v2/catalog.mjs` 对权限及依赖的 reason 允许 2000 个 Unicode code points；客户端此前限制为 500。MCP 正式目录包含长度 508/1021 的说明，因此整份签名目录被客户端拒绝。
- 客户端两个 reason 字段对齐到 2000，保留空字符串、超限、签名、哈希及整个目录大小检查。签名回归覆盖 508、1021、2000 个字符及多字节 Unicode，权限和依赖分别检查空值与 2001 字符拒绝。
- 使用未改动的线上目录和正式签名重放：旧 Beta.7 报 `reason length is out of bounds`；新客户端完整验证 33 个插件，最长权限说明 1021 字符。未修改目录数据或客户端信任密钥。
- 更新日志直接来自既有发行 API 的 `notes` / `notesByLanguage`，随候选完整缓存，按界面语言选择并回退英文。旧数据缺少日志时显示占位，格式异常的可选说明不影响更新资格。当前安装已是最新版本时也可显示对应日志。
- 使用已有 Markdown 子集渲染器显示日志，不执行 HTML 或加载说明中的远程图片。插件选择表、更新日志和操作状态分区显示。
- 进度条改为十进制 MB（1 MB = 1,000,000 bytes），明确标记为框架文件大小；下载与验证耗时使用单调时钟，覆盖整个联动下载任务。成功、取消、失败和关闭时停表，重试重新计时，超过一小时显示时分秒。

## 已完成的验证

- 原始签名目录回归先复现失败，修复后通过；同一线上正式目录分别在旧、新校验器重放，得到预期差异。
- Windows Java 17 聚焦运行时测试最初 77 项全部通过；内部契约 33 项全部通过。随后新增当前版本日志回归并重跑完整更新服务测试；最终窗口 14 项通过，覆盖取消、成功、失败、重试和日志显示。
- 格式检查、四语 changelog 一致性通过；Linux 代码质量门禁通过，Javadoc backlog 为 0。
- 发布说明工具 11 项在 Linux 通过；Windows 原测试有两项既有 CRLF 字节不一致失败，未改断言。
- Swing 实际组件截图检查通过。该截图使用明确模拟的进度数据，仅证明布局，不替代正式 Cubism 下载和插件升级验收。

本机证据位于 `build/agent-tasks/update-window/`，包括 `public-catalog-beta7-red.log`、`public-catalog-fixed.log`、`focused-tests-final.log`、`current-notes-final.log`、`window-final-checks.log`、`update-window-preview.png`。

## 正式发布与线上验收

- PR [#63](https://github.com/turboism/Turboism/pull/63) 的全部检查通过后，已合入 main `2dcc4248173221c5a4e81941116c057ffbf0b069`；实现提交为 `88f26fe17fb367b7aea03b96fb0fe6887a1fc244`。
- 合并后主 CI [41301741316](https://github.com/turboism/Turboism/actions/runs/41301741316)、三通道打包 [41301741318](https://github.com/turboism/Turboism/actions/runs/41301741318)、历史检查 [41301741333](https://github.com/turboism/Turboism/actions/runs/41301741333) 均通过。
- 真实签名候选 Beta.8 / Build 82 [41301760558](https://github.com/turboism/Turboism/actions/runs/41301760558) 仅作为本机验收起点；Beta.9 / Build 83 候选 [41301836205](https://github.com/turboism/Turboism/actions/runs/41301836205) 已通过完整发布验证、正式签名和最终包一致性检查。两者均来自上述 main 提交，没有改写 CI 身份。
- 受保护发布 [41303542375](https://github.com/turboism/Turboism/actions/runs/41303542375) 成功，已发布 [0.68.1-beta.9](https://github.com/turboism/Turboism/releases/tag/v0.68.1-beta.9)，并准入正式自更新服务。自动同步 [41303962299](https://github.com/turboism/Turboism/actions/runs/41303962299) 成功。
- 两个候选完整 ZIP 在本机分别验签并校验 36 个框架文件。Beta.9 ZIP SHA-256 为 `b8227c3122b35f68c8f416a1acf615acac9cac6ea635303f92fa4f1928a30392`，正式清单 SHA-256 为 `4dc3ba642eaec9db0a0d2d8843269847eca2c8959327f92333e9a8d5f3bd06d5`。
- `2026-11-01T11:56:31Z` 线上检查：服务及三个通道均 ready；Beta 返回 Beta.9 / Build 83、正确源码身份和完整自更新描述。正式签名、36 个对象的 HEAD 大小/ETag 与单字节 Range 206 检查通过；完整内容哈希由发布任务读回验证。中英日韩日志均非空且在客户端长度限制内，中文包含 MB、耗时和插件修复说明。Stable、Nightly 身份及资产哈希未变。
- 本次仅发布客户端，不需要修改或重新部署服务端。

## 本机进度与恢复入口

独立目录 `build/native/issue50-host/framework-update-ui-20261101/home` 使用未修改的正式 Beta.8 框架包。为验证真实插件升级，仅在首次启动前把剪切蒙版检查器和命令面板替换为官方目录中的 `0.68.0` 包，两个旧包的 SHA-256 均与已验证签名的目录一致；原包已在实验 tools 目录备份。

真实 Cubism 5.3.02 已启动 Beta.8 / Build 82，30 个启用插件、0 failures；MCP/ACP 仅在实验配置中显式禁用。随后发现《守望先锋》占用前台，用户明确回复“正在游戏，先继续后台发布”。因此**新版原生窗口的下载、插件联动应用和重启健康验收尚未执行**。没有将 Swing 模拟截图、源码测试或本次成功启动冒称完整联动验收。

本轮空白实验 JVM 38076 已正常关闭；正式 helper `--recover` 返回 `10 / NOTHING: no journal`，回收已退出实例的启动租约。目录与证据保留，没有强杀编辑器、热替换已加载文件或修改官方 BAT。

用户结束游戏后，从 `build/native/issue50-host/framework-update-ui-20261101/context.json` 恢复该实验：正常托管启动 Beta.8，原生检查发现已发布 Beta.9，保留插件联动选项并选择上述两个 `0.68.0 → 0.68.1` 插件，核对日志/MB/计时，下载并明确排队，正常退出后再托管启动验证。无需重新构建、签名、发布或配置生产服务。此前 Beta.7 的两次完整框架升级证据保持原范围。

本机新增证据位于 `build/agent-tasks/update-window/`：`beta8-package-verification.json`、`beta9-package-verification.json`、`beta9-public-acceptance/`、`beta9-public-notes-and-health.json` 和 `rollout-state.json`。实验快照、日志和截图位于 `build/native/issue50-validation/framework-update-ui-20261101/`。精简发布证据见 [update-window-release-evidence.json](update-window-release-evidence.json)。

仍在 Beta.7 的用户首次升级到 Beta.9 时，需要先取消“同时更新选中的兼容插件”；旧客户端的目录限制无法在其运行中修复，升级并重启后才会使用新校验器和新窗口。
