# Circle：真实宿主入口冒烟结果

## 结论边界

**四档模型已走真实 Cubism 自动排版对话框和生产插件，并成功写回。** 这解决“是否只测了假 planner、生产代码是否被实际调用”的疑问；不是完整正确性证明，也不是原生或 legacy 加速比。

| 本次原生输入数 | 当前页 | placements / overflow | 最终 scale | snapshot ms | plan ms | apply ms |
|---:|---|---|---:|---:|---:|---:|
| 100 | 4096×4096 | 100 / 0 | 1 | 4.9251 | 10.8462 | 12.9194 |
| 500 | 4096×4096 | 500 / 0 | 1 | 1.4428 | 53.8843 | 94.1990 |
| 1000 | 8192×8192 | 1000 / 0 | 1 | 8.5575 | 66.6138 | 90.2816 |
| 2500 | 8192×8192 | 2500 / 0 | 1 | 18.4559 | 158.7416 | 250.5531 |

每档仅一次调用，未做标准化预热和重复采样。100/500 共用进程，1000/2500 分别启动新进程；**不可把这些数字称为稳定 P50、冷启动基准或性能保证**。前三个耗时分别是服务快照、规划和写回阶段，不包含全部原生投影、纹理生成及 UI 刷新。

对话框为网格、margin 3 px、自动倍率、允许旋转、MaxRects-BSSF、parallel 关闭。四档均可在 scale=1 放入，因此没有覆盖实际缩小、overflow、跨页不变性或分治并行的实机路径。PSD 的 4096² 画布不能当作 CMO3 的纹理页尺寸：后两档实际纹理页是 8192²。

## 生效证据

- 使用本工作树重新构建的 `:bootstrap:jar :plugins:atlas-maxrects-bssf:jar :plugins:mcp:jar`，构建成功。
- 框架日志：`TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK installation=COMPLETE`。
- 插件生命周期 enable 成功，明确记录原生 automatic layout 使用 current-page，PART_BUCKET 只用于显式完整图集 SDK 请求。
- 四次调用均记录 `scope=current-page`、对应输入/placement 数，并紧随 `writeback status=APPLIED failureCode=none`。
- 100 档通过真实编辑器“复原/重做”：纹理视口裁剪 `(10,237)-(558,784)` 在复原后不同，重做后与排版后逐像素一致。这只是视口验证，不等同于完整模型/所有页状态哈希验证。
- 100 档关闭纹理编辑窗口并提交，生产 MCP `save` 返回 EXECUTED；任务副本哈希改变。**尚未关闭重开验证持久化结果**。

## 环境及隔离

- 本机 Cubism 5.3.03，内置 Windows Java 17.0.3.1，GE-Proton10-34。
- `${GOLDEN_PREFIX}` 无 Wine 宿主运行时，通过 `cp -a --reflink=always` 克隆为独立 prefix；只删除克隆的锁文件（历史操作记录，不是当前入口）。
- 从克隆安装的 `CubismEditor5.bat` 启动，独立 Turboism home，仅 atlas/mcp 两个外部插件。`JAVA_TOOL_OPTIONS` 限制堆 4 GiB；额外 UI 探针只用于验证，不是产品组成部分。
- 自有 Xvfb `:97`，Mesa llvmpipe 软件渲染。未控制或截图用户桌面 `:0`。
- 源模型来自 `test-assets/texture-atlas-layout/cmo3/circle/`，只打开任务目录里的副本。100 副本用于保存试验；其余三个副本结束时与源哈希相同。
- 启动前后记录的原安装 JAR、BAT 和 100 源模型哈希一致；原安装、源模型不作为写入目标。
- 测试结束已停止任务 prefix 的 wineserver 和自有 Xvfb；无等待用户操作的遗留测试会话。
- GE runner 对复制的旧 DW-Proton prefix 提示升级，只影响克隆。现有安装控制台含第三方授权组件标记；未修改或绕过授权，也不据此认证整个安装为纯净官方发行版。若要求纯官方端到端基准，需独立核验发行版所有依赖，不仅核心 JAR。

## 原始证据位置（本机，不提交二进制）

任务路径：`build/texture-host-szx2a9uu/`。

- `home/logs/runtime/`：全部本轮运行日志，含四档实际调用。
- `evidence/1000-runtime.txt`、`2500-runtime.txt`：大样本对应进程日志副本。
- `evidence/*auto-dialog*.png`、`*after-layout.png`：对话框及结果截图。
- `evidence/100-undo.png`、`100-redo.png`、`100-save.json`、`integrity-check.json`。
- `evidence/source-hashes.json`：启动前身份，结束时重新核对。
- `evidence/console*.txt`、`wrapper*.txt`：启动与异常原始日志。

`HostUiProbe.java` 是验证专用 agent：在 EDT 枚举 Swing 控件，只按已捕获 ID 和精确标签执行按钮；从任务 evidence 目录读取请求。不能发布或装入正式环境。编译时使用其包名 `dev.turboism.validation.texture.HostUiProbe` 作为 Premain-Class。最初无包名版本与宿主默认包签名冲突导致启动失败，已加独立包名并在后三次成功启动中验证；失败日志保留，不能算作通过。

## 下一轮仍必须做

1. 增加验证专用、同边界的方法计时，确认原生模式确实执行原生函数；不要用生产 `planMs` 与原生 UI 总耗时相除。
2. 每组恢复同一模型/当前页输入，轮换 native/new/legacy，记录输入摘要、质量与冷/热重复样本。
3. 缩小当前页使其必须自动缩放，覆盖固定比例 overflow、旋转开关和 parallel 开关；检查其他页不变。
4. 保存副本后关闭重开，核对 placement、比例、图像与 Undo/Redo 的完整状态，而不止截图。

在以上完成前，不能承诺“相对原生加速多少”或“所有场景至少不慢于 legacy”。
