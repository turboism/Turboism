# 实机验证：框架自更新 / 插件市场下载安装更新 / 插件热更新（2026-11-05）

本机（Windows，2560×1440 @150%，Cubism 5.3.04）使用**官方已发布的正式 Beta 包**完成一次完整
自更新与插件市场实测。除下载官方制品外，未构建、未签名、未发布任何新版本；本机既有
`%LOCALAPPDATA%\Turboism`（0.68.0-beta.1）安装与 Cubism BAT 集成未被改动。

## 结论

| 能力 | 结果 | 证据 |
| --- | --- | --- |
| 框架自更新（检查→下载→校验→排队→重启生效） | **通过** | Beta.10 / Build 84 → Beta.11 / Build 85，36/36 框架文件哈希与签名清单一致 |
| 插件市场下载与安装 | **通过** | 参数锁定插件 0.68.0 下载、校验并**立即热加载**，本地安装 32 → 33 |
| 插件更新（市场 / 自动） | **通过（需重启生效或热替换）** | 裁剪蒙版查看器、命令面板 0.68.0 → 0.68.1 排队后重启生效；性能统计、图形网格镜像在会话内热替换到 0.68.1 |
| 插件热更新（运行中卸载/装载/替换） | **通过** | `hot unload succeeded` → `hot load succeeded version=0.68.1`，`pending.json` 为空，无重启 |
| 插件制品镜像（`api`/`dl` 的 `/plugins/...` 路由） | **失败（服务端）** | 所有抽样制品均 404，客户端只能回退 GitHub Release |

## 环境与实验根

- 实验根：`build/native/real-machine-20261105/`
  - `pkg-beta10/`：官方 ZIP 解包（SHA-256 `a479afa6d5657383beb5138f11b187c160ae2e7d001fbaa84f2a2038bc20b516`，与 `v0.68.1-beta.10` 资产一致）
  - `home/`：独立 Turboism 安装根（复制自 `pkg-beta10`），托管 GraalVM 25.2.4 取自本机既有安装的**副本**
  - `backup/original-plugins/`：被替换插件 JAR 的原件
  - `launcher*.stdout.log`、`screen-*.png`、`upd-*.png`、`mkt-*.png`：启动与界面证据
  - `home/logs/runtime/2026-11-05/`：四次会话运行日志
- 实验配置：`disabledPlugins=[mcp, acp]`，其余 31 个官方插件启用；`skipUpdateCheck=false`；
  更新缓存位于 `~/.cache/turboism/self-update`（产品规定缓存在安装目录之外）。

## 1. 框架自更新

1. 完成后台检查：`UPDATE_HINT_SENT id=turboism-update-available identity=0.68.1-beta.11 (Build 85)`，
   更新窗口标题行为 `0.68.1-beta.10 (beta, Build 84) → 0.68.1-beta.11`（`upd-03.png`）。
2. 点击“下载更新”：`正在下载并验证`，`合计 102.75 / 102.75 MB`，`下载与验证耗时 00:29`
   （`upd-08.png`、`upd-09.png`）。本机未获准增量更新
   （`TURBOISM_INCREMENTAL_UPDATE installation=NOT_ADMITTED`），按整包下载。
3. 校验通过后状态为 `验证完成，等待安装`；点击“下次启动时安装”后变为 `将在下次启动时安装`，
   底部提示 `框架及选中的 2 个插件已排队，将在下次启动时更新。`（`screen-22.png`），
   并写入 `state/runtime/update/helper/pending.json` 与预置的 `helper-runner.jar`。
4. 正常退出（文件 → 退出，无强制结束）后由托管启动器拉起：
   `[turboism-helper] AWAITING_HEALTH: 0.68.1-beta.11`（`launcher2.stdout.log`）。
5. 重启后运行身份：`Version : 0.68.1-beta.11 (beta, Build 85)`，`plugins=31, failures=0`；
   `home/installed-build.json` 为 `{"version":"0.68.1-beta.11","channel":"beta","buildNumber":85,"sourceRevision":"ff068c4dee67bd93b464f88ccf905c7bddafdc70"}`。
6. 安装树完整性：按 `state/runtime/update/receipts/<manifestSha>/manifest.json`（签名清单）逐项核对，
   **36/36 文件哈希一致**，无缺失；健康确认后 helper 目录仅剩租约文件，旧备份已按协议丢弃。

## 2. 插件市场下载与安装

- 市场头部：`市场插件总数：33 | 当前本地安装：32`。
- 安装未安装的“参数锁定插件 0.68.0”：状态变为 `已安装 (0.68.0)`，本地安装数 33，
  底部 `已立即生效。（Plugin installed and applied immediately.）`（`mkt-08.png`），
  运行日志：`ParameterLockPlugin enabled` + `Plugin lifecycle: hot load succeeded version=0.68.0`，
  文件落地为 `plugins/dev.turboism.plugin.parameter-lock.jar`。**无需重启**。
- 下载路径：客户端先试 `api.turboism.dev/v1/plugins/artifacts/...`，再试 `dl.turboism.dev/plugins/...`，
  两者均 404 后回退签名目录中的 GitHub Release URL 并成功（见下文问题 1）。

## 3. 插件更新

- 自动更新（启动 15 秒后）发现两个落后插件，下载 0.68.1 并写入
  `state/runtime/plugin-management/pending.json`（`rawSha256`/`descriptorSha256` 与签名目录一致），
  同时给出画布提示 `PLUGIN_UPDATE_HINT_SENT count=2`。
- 重启后排队项生效：`clipmask-viewer.jar`/`command-palette.jar` 被替换为 0.68.1 并改名为
  `dev.turboism.plugin.clipmask-viewer.jar`、`dev.turboism.plugin.command-palette.jar`；
  市场显示 `已安装 (0.68.1)`。
- 市场内对“已安装”插件点击“更新”会写入 `pending.json` 并提示重启生效（实测图形网格镜像
  0.68.0 → 0.68.1：点击后 `pending.json` 出现 INSTALL 项、JAR 未变；下次启动后变成 0.68.1）。

## 4. 插件热更新（运行中）

三次独立观测均在同一 Cubism 会话内完成，无重启：

1. `perf-stats` 0.68.0 → 0.68.1：`Plugin lifecycle: hot unload succeeded` 5 秒后
   `load succeeded name=Performance Statistics version=0.68.1` + `hot load succeeded version=0.68.1`，
   随后 `pending.json` 的 `operations` 为空（完全热应用，未留待重启项）。
2. `artmesh-mirror` 0.68.0 → 0.68.1：`hot unload succeeded` → `hot load succeeded version=0.68.1`。
3. 市场“禁用/启用”：`禁用` 触发 `disable → shutdown → unload succeeded`（`hot unload succeeded`），
   `启用` 触发 `hot load succeeded`，底部提示 `已立即生效`。

## 发现的问题

1. **插件制品镜像不可用（服务端，优先级高）。** 抽查 `command-palette 0.68.1`、`clipmask-viewer 0.68.0`、
   `parameter-lock 0.68.0` 等：
   - `https://api.turboism.dev/v1/plugins/artifacts/<sha256>/<file>` → `{"error":{"code":"NOT_FOUND"}}`
   - `https://dl.turboism.dev/plugins/<sha256>/<file>` → `404 Not found`
   代码侧 `distribution/plugin-jars.mjs` 要求 `PLUGIN_JAR_MIRROR_ENABLED=true` 且 R2 中存在
   `plugins/index-v1.json` 与 `plugins/objects/<sha>/<file>`；当前清单缺失或镜像关闭。
   现网客户端只能靠 GitHub Release 兜底，这对需要镜像的用户（GitHub 不稳）等于插件下载不可用。
2. **市场“更新”对随包安装的插件不会热替换（优先级中）。** 这些 JAR 的文件名是 slug
   （如 `clipmask-viewer.jar`），而热替换目标路径是 `plugins/<plugin-id>.jar`，判定为“未安装”后
   走排队安装，需下次启动；界面上点击后没有即时反馈，容易被误认为无响应。
   （性能统计/图形网格镜像在文件名为 `<plugin-id>.jar` 时确实是热替换。）
3. **市场本地版本快照只在启动时扫描。** 会话内直接改动 `plugins/*.jar` 后，市场“当前状态”仍显示旧版本，
   “刷新”只重取目录，不重扫本地文件；需要重启才会更新显示。
4. **更新窗口在 150% 缩放且最大化时底部按钮会被任务栏遮住**（窗口 2582×1462 于 2560×1440 屏），
   需要手动移动窗口才能点到“检查更新/下载更新/下次启动时安装”。属 DPI/布局问题。
5. 备用观察：本机 `api.turboism.dev` 等域名由本地代理以 fake-IP（198.18.0.x）解析，直连解析行为未在本机复现。

## 复现入口

```powershell
# 1) 解包官方 Beta.10 ZIP 到实验根并复制为安装根
# 2) 配置 home/config.json：skipUpdateCheck=false；disabledPlugins=[mcp,acp]
# 3) 初始化签名安装收据
powershell -NoProfile -ExecutionPolicy Bypass -File <lab>\home\bootstrap-receipt.ps1 -HomePath <lab>\home
# 4) 托管启动（结束语/重启均用同一命令）
powershell -NoProfile -ExecutionPolicy Bypass -File <lab>\home\launch-cubism-turboism.ps1 `
  -HomePath <lab>\home -CubismRoot "C:\Program Files\Live2D Cubism 5.3.04" -Variant normal
```

## 未覆盖

- 安装器（NSIS）路径、签名密钥轮换、回滚/断电恢复、Linux/Proton 未在本轮验证。
- 新一轮 Beta/Nightly 构建与发布未执行；本轮仅使用线上已发布制品。

## 后续修复（2026-11-05 同日）

本轮针对问题 2、4 做了代码改动，并在同一台实机上复核。

### 修复 1：更新/市场窗口在缩放显示下不再被任务栏遮挡

- `CoreDialogs.create` 现在把请求尺寸夹到 `GraphicsEnvironment.getMaximumWindowBounds()`（屏幕减去任务栏），
  并把窗口居中到可用区域内；`CoreDialogs.show` 在 `setLocationRelativeTo(owner)` 之后再次把窗口移回可用区域。
- `FrameworkUpdatesWindow` 的分栏最小尺寸由 400×160 / 280×160 降到 240×96 / 200×96，窗口最小尺寸也按可用区域夹取，
  这样窗口被压小时中间区域先收缩，底部“检查更新 / 取消更新 / 安装更新”始终可见。
- 实机复核：150%（2560×1440 → 1707×960 逻辑像素）下，更新窗口与插件市场窗口均完整落在可用区域内，
  底部按钮与状态栏可见（`screen-50.png`、`screen-51.png`），无需再手动拖动窗口。

### 修复 2：热替换失败原因写入日志（问题 2 的诊断缺口）

- `PluginHotReloadCoordinator` 的失败日志现在附带异常原因：
  `Plugin replacement failed; restoring installed generation: <原因>`。

### 关于“市场更新不热替换”的结论（未改变行为，附证据）

曾尝试放宽到“任意单一已安装 JAR 就地热替换”，实机验证失败并已回退：

- 现象：`hot unload succeeded` 后立刻出现
  `Plugin replacement failed; restoring installed generation` → `REPLACEMENT_FAILED: Previous installed generation restored.`，
  插件以旧版本重新载入，市场回落到“排队安装”，两次尝试均可复现。
- 原因：Windows 上旧 JAR 仍被 Cubism JVM 持有（`[IO.File]::Open(...,'None')` 返回“文件正被另一进程使用”），
  `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 无法覆盖，`copyAtomic` 抛 `IOException`。
  这正是 `PendingPluginOperations.canHotApply` 只允许规范化 `<id>.jar` 的原因：改名/合并必须交给启动事务。
- 因此保留原设计：随包安装（slug 命名）的插件更新走“排队 → 下次启动生效”，且市场状态栏会明确提示
  `重启 Cubism 以生效。（Plugin installation is pending; restart Cubism to apply it.）`（`mkt-33.png`）。
- 热替换仍然生效的场景（已实测）：JAR 名为 `<plugin-id>.jar` 的插件（性能统计、图形网格镜像）。

### 本轮改动清单

- `runtime/src/main/java/dev/turboism/shell/CoreDialogs.java`
- `runtime/src/main/java/dev/turboism/shell/FrameworkUpdatesWindow.java`
- `runtime/src/main/java/dev/turboism/kernel/PluginHotReloadCoordinator.java`（仅日志）
- `gradle/test-skip-allowlist.txt`（登记 Windows 无符号链接权限时的既有跳过）
- 聚焦测试：`FrameworkUpdatesWindowTest`、插件市场/自动更新/待安装事务、`PluginHotReloadIntegrationTest` 全部通过；
  `:runtime:spotlessCheck` 通过。

### 另一个现场观察

市场下载在镜像 404 后回退 GitHub 时，本机出现过
`PLUGIN_DOWNLOAD_CONNECTION_FAILED: Cannot connect to release-assets.githubusercontent.com`（`mkt-32.png`），
重试后成功。这从另一面说明问题 1（插件镜像不可用）对真实用户的影响：一旦 GitHub 不可达，插件下载即失败。

## 插件制品镜像上线后复核（2026-11-05 13:0x）

镜像上线前 `api`/`dl` 的 `/plugins/...` 路由全部 404（问题 1）。上线后重新验证：**已修复，191/191 制品可用**。

### HTTP 契约

| 检查 | 结果 |
| --- | --- |
| `GET https://api.turboism.dev/v1/plugins/artifacts/<sha>/<file>` | `302` + `Location: https://dl.turboism.dev/plugins/<sha>/<file>`，`Cache-Control: private, no-store`，`Referrer-Policy: no-referrer` |
| 跟随 302 后内容 | `200`，字节数与目录一致，SHA-256 与目录一致 |
| `https://dl.turboism.dev/plugins/<sha>/<file>` | `200`，`Content-Type: application/java-archive`，`Content-Length` 精确，`Content-Disposition: attachment; filename="<file>"`，`ETag: "<sha256>"`，`Accept-Ranges: bytes` |
| `Range: bytes=0-1023` | `206` + `Content-Range: bytes 0-1023/72797` |
| `If-None-Match: "<sha256>"` | `304` + 同一 ETag |
| 未知 sha | `api` → `404 {"error":{"code":"NOT_FOUND"}}`，`dl` → `404 Not found` |
| `HEAD` | `dl` 支持；`api` 返回 `405`（客户端只用 GET，无影响） |

### 覆盖率（全部 191 个目录制品）

- 按签名目录逐条 `HEAD https://dl.turboism.dev/plugins/<sha>/<file>`：`191/191` 返回 `200`，
  `Content-Length` 与目录 `size` 完全一致，无缺失、无尺寸偏差。
- 9 个条目首次探测因本机 TLS/schannel 抖动失败，单独重试后全部 `200`（属本机代理问题，非镜像缺失）。
- 内容校验：5 个制品完整下载后 SHA-256 与目录一致；`mcp-0.68.2.jar`（1,139,247 B）连续两次下载哈希一致。

### 客户端实测（同一台实机，Cubism 5.3.04 实验安装）

在市场中对“历史记录面板插件”执行 0.67.5 → 0.68.0 更新：

- 下载成功并暂存 `dev.turboism.plugin.historypanel-32ec4d23…jar`（52,635 B，哈希与目录一致），
  状态栏显示 `重启 Cubism 以生效。（Plugin installation is pending; restart Cubism to apply it.）`。
- 下载期间对 Cubism JVM 采样连接：仅出现 `198.18.0.105`（api）与 `198.18.0.107`（dl），
  未出现 GitHub 相关端点（`release-assets` = `198.18.0.98`、`objects` = `198.18.0.134`、`github.com` = `198.18.0.39`）。
  说明客户端已走“api 302 → dl”镜像链路，不再依赖 GitHub 兜底。

### 速度对比（`mcp-0.68.2.jar`，1.14 MB，本机代理环境）

| 源 | 结果 |
| --- | --- |
| 镜像 `dl.turboism.dev` | 0.51 s / 0.48 s（约 2.2–2.4 MB/s），两次哈希一致 |
| GitHub Release | 1.14 s（约 1.0 MB/s）；第二次重试先耗 19.5 s 拿到 302，随后 TLS 握手失败 |

镜像链路在当前网络下约为 GitHub 的 2 倍吞吐，且没有出现 GitHub 侧的连接抖动。

### 结论

问题 1（插件制品镜像不可用）在镜像上线后消除：路由、签名绑定的 ETag、大小、Range、条件请求与
客户端回退顺序都符合预期，客户端实机下载已实际走镜像。此前记录的“只能靠 GitHub 兜底”不再成立。
