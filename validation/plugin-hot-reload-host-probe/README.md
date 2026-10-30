# 插件热更新实机验证

在真实 Windows Cubism Editor 会话中运行验证代理。测试目录、插件及配置均位于当前工作区的 `build/host-validation/plugin-hot-reload/<RunLabel>/`，不修改现有 Turboism 安装目录，不打开或保存用户模型。

先构建 `:bootstrap:jar`，然后执行：

```powershell
./validation/plugin-hot-reload-host-probe/run.ps1 `
  -CubismRoot 'F:/Live2D/Live2D Cubism 5.3.02' `
  -JdkRoot 'C:/Program Files/Java/jdk-17' `
  -WorktreeId short-wolverine `
  -RunLabel my-run
```

脚本启动宿主后返回。等待 `result.txt` 出现 `status=PASS` 或 `status=FAIL`；启动成功不代表测试通过。`launch.json` 记录准确 PID、宿主及代理 SHA-256。验证后可关闭该 PID 对应的测试宿主。

验证流程：

1. 从真实宿主的运行实例获得插件管理服务，确认初始 0.1.0 已加载。
2. 调用生产 `PluginMarketService.installOrUpdate`，验证同路径 0.1.0 → 0.2.0 返回 `PLUGIN_HOT_APPLIED`，新类加载器生效、旧上下文失效。
3. 调用真实管理服务禁用、启用插件，验证立即卸载、加载。
4. 调用自动更新器的下载、校验、暂存执行入口，验证 0.4.0 返回 `HOT_APPLIED`，并清除对应待处理记录。
5. 注入初始化必定失败的 0.3.0，验证运行版本及磁盘安装 JAR 均恢复为 0.4.0；更新返回 `PLUGIN_HOT_UPDATE_ROLLED_BACK`，不再安排重启时安装失败版本。

HTTP 连接使用确定性的本地夹具字节，仍经过生产下载大小、SHA-256、描述符及归档校验。自动更新探针通过反射调用执行入口，不覆盖线上目录签名获取、定时调度或市场按钮点击；插件窗口用于截图观察版本切换。验证代理使用反射访问内部状态，仅属于验证工具，不打入产品。

2026-10-31 在 Cubism 5.3.02 / NVIDIA RTX 4060 Laptop GPU 上的结果：

- 修复前：`baseline-files-only/result.txt`，替换正在使用的安装 JAR 被 Windows 拒绝，更新退化为 `PLUGIN_INSTALL_PENDING`。
- 修复后：`fixed-1/result.txt`，手动热更新、启停、回滚通过。
- 完整复测：`fixed-complete/result.txt`，额外覆盖自动更新热应用与待处理记录清理。
- 最终复测：`fixed-final/result.txt`，额外确认故障回滚后拒绝失败版本并清除其待处理记录。
- 截图：对应运行目录的 `before.png`、`after.png`、`automatic.png`、`rollback.png`。

根因是更新服务在卸载之前替换安装文件。修复将文件替换放到热生命周期协调器内：先验证、备份并安全卸载，再替换、加载；失败时先恢复安装路径的字节，再加载旧版本，避免回滚类加载器占用备份文件。

相关自动化回归共 42 项：41 通过，1 项因 Windows 符号链接权限条件跳过，无失败。测试类为 `PluginHotReloadIntegrationTest`、`PluginMarketServiceTest`、`RuntimePluginAutoUpdaterTest`、`PendingPluginOperationsSecurityTest`。
