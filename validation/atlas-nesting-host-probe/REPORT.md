# Bitmask Nesting 验证记录

日期：2026-10-21。工作树：`poor-crab`。本记录区分离线规划时间、宿主冷启动方法时间和 UI 等待时间。暂未发布或安装到正式 Cubism。前文性能数据对应 v5 构件；最新国际化与多版本集成见文末。

## 交付与算法

- 独立模块：`plugins/atlas-bitmask-nesting`，插件 ID `dev.turboism.plugin.texture-atlas-nesting`，算法 ID `bitmask-nesting`，菜单 **Bitmask Nesting**。
- 插件 JAR：`build/worktree/poor-crab/atlas-bitmask-nesting/libs/atlas-bitmask-nesting-0.67.1-SNAPSHOT-poor-crab.jar`。
- 最终 v5 构件 SHA256：`620b0fab321754d50c172fa34fab4c7ebf1e95658a17e4fab47771c994b7097a`；此前 v3 构件为 `aadc0bae812e817f68ee5f937801d2b8f2b0fcd8ed009c018ac718b41e7b1573`。
- Java 17；API `[0.67.1,1.0.0)`；生产代码不依赖其他排版插件。BSSF 模块仅作为测试基线。
- 精确轴对齐矩形（含 issued 90° 倍数旋转）且无固定障碍时，直接使用独立矩形 BSSF 内核。异形输入使用 material-local 多区域 union、扫描线填充、边缘胶囊（法向外扩矩形＋端点圆盘）直接 supercover 栅格、`long[]` 碰撞、最长实心段和分散行段预筛、证明安全的跳步及相同行缓存。
- 默认网格最多 512 格/边；4096 页为 8px/格，8192 页为 16px/格。粗候选漏放时细搜。边距独立于栅格；宿主 margin=3 对应部件间至少 6px。
- 默认旋转候选为 issued 方向的 4 个直角加最小面积盒方向；DENSE SDK 请求增加 30° 候选并使用最多 1024 格/边。默认按放入现有高度即可停止额外角度搜索，以控制耗时。
- 固定倍率绝不暗中缩放；自动倍率不超过 100%，最多 10 轮并保留完整放入的结果。固定/排除部件保留 issued 矩阵。

## 验证方法

实机仅使用仓库统一队列 `atlas-nesting:5303@VARIANT`，Cubism 5.3.03 的任务隔离副本，每次新宿主仅触发一次排版。捕获包装器调用真正注册的生产插件，写出原始输入与计划；捕获 IO 不计入 `plannerMs`，但包含在宿主方法和 UI 时间内。独立校验器不依赖 SDK 或排样器，检查原始轮廓、6px 间距、页边界及实际写回的六元矩阵。

源工程、任务副本、黄金启动器与宿主 JAR 的哈希由队列在任务回收后复核；通过任务还要求正常退出。失败任务不计为算法通过。没有修改源工程。

离线重放预热 5 次、测量 9 次，输出中位数及范围；读取捕获、独立几何检查和 PNG 绘制不计时。BSSF 与 nesting 同一进程串行测量。离线 BSSF 使用外接矩形，nesting 使用捕获轮廓；放入数与倍率必须和耗时一起比较。

## 已通过的v3 实机用例（矩形内核在 v5 中未改动）

| 输入 | 页 | 部件 | 倍率 | 生产 plannerMs | 宿主 methodMs | UI 总耗时 ms |
|---|---|---:|---:|---:|---:|---:|
| Geometry500 nesting，最终冻结版 | 8192² | 500/500 | 1 | 185.627 | 434.636 | 812.604 |
| Geometry500 BSSF，独立冷宿主 | 8192² | 500/500 | 1 | 未独立计时 | 340.714 | 708.297 |

这是不同新宿主的单次冷启动观测，不能据此宣称稳定的速度比。Geometry500 输入哈希一致：`715429961888bf58875e07671b8a4e5d5aebfedd907bdbee56aaf1a35369424d`。

最终版任务：`27ce6639-f9d3-48f7-813b-1d88e4dc77ff`，`queue-84d6776b22af4209a616974692048c8f`，终态 succeeded、safe、normalExit=true。捕获 500 个真实宿主轮廓、2000 顶点；矩阵一致、轮廓重叠 0、间距通过。对照 BSSF 任务：`a419a61c-950b-4092-8e23-ef09710eeae9`。

**样例证据限制：Geometry100、Geometry500、Circle500 的宿主轮廓都是每项四顶点矩形。圆形图片不等于圆形网格；这些用例只能证明注册、矩形路径与写回，不能证明异形实机嵌合。** 旧掩码版另有 Geometry100/500、Circle500 实机通过记录，但不作为最终快捷路径的全部覆盖。

## heavy 诊断记录

源：本地 `TURBOISM_HOST_VALIDATION_FIXTURE_HEAVY` 指定的 `heavy.cmo3`，447432485 字节，SHA256 `029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`。

BSSF 任务 `d7e9e22e-deff-4e13-a8e1-08098b84d15b` 已通过：当前页 101 项，4096²，倍率 1；methodMs=150.414，UI=849.782ms。此轮未限制堆，maxHeap=16.59GB，不能与之后 4GB 堆的轮次混为同配置重复基准。

此前 nesting 的 heavy-v2/final-v3 任务宿主以 1 退出，未调用 planner、未捕获输入，没有异常堆栈；不能当作算法通过。bootstrap 已完成启动，180 秒参数只是 host class lookup 超时，不是已启动宿主的总寿命。新增 heavy-only 加载线程/退出现场记录；诊断轮次将隔离 JVM 限制为 4GB，并将 readiness 设为 600 秒，算法 deadline 保持 120 秒。

诊断 v4 已通过：任务 `ebfa043a-5fb3-4abf-a785-3f19ebef2ed4`，`queue-d4d8ac89f9024bcd8e9b0a3d97fe55b0`，succeeded / safe / normalExit / fixtureUnchanged / identityVerified 全部通过。101 项真实异形轮廓、6582 顶点，全部以 1.0 倍率放入；原始外接框相交 173 对，独立扩大轮廓检查 184 对，实际轮廓及 6px 间距无冲突，宿主矩阵全部与计划一致。cold planner=3812.327ms，method=5480.354ms，UI=6707.421ms；排版窗口内无 GC，heap peak 2.42GB，maxHeap=4GB。

加载采样进一步定位到打开纹理编辑器时的原生 `TAE__EditLayer_ModelImage.setupEditLayer → GEditableMesh2.updateIndices → triangulation`，以及更早的纹理上传。退出记录确认本次是驱动正常点击退出。4GB 限制下成功，但没有足够证据证明此前 exit=1 的唯一原因，因此不作因果断言。捕获后 JFR 显示主要额外开销在 `Area(BasicStroke(...))` 边界归并。v5 改为直接填入边缘胶囊，不对外扩路径做布尔归并；仍然保留形状 union、独立验证及精确间距。真实轮廓重放从带 JFR 的 v3 296.903ms 降至 v5 72.621ms（两轮配置/负载不同，仅作优化线索）；同一 v5 重放的 BSSF 为 6.142ms，均 101/101、倍率 1。最终无本任务背景负载的重复数据另列。

相同 4GB 配置 BSSF 对照任务 `b54ab7c5-664d-47b4-8d22-1ad0c37bd8f1`、`queue-d661cb13ad1d4d4e81fd74c9cd25eed0` 已 succeeded/safe/normalExit/fixtureUnchanged/identityVerified；method=116.382ms、UI=1507.749ms，输入哈希与 nesting 相同：`ff36324cd350a23aa867dd65c7009b6150d3cec63387461e979aeea7da666f59`。两个独立冷宿主不构成稳定速度比。

v5 实机任务：`97784dfe-ca88-4d56-a253-68932935ff2e`，`queue-68d5c93a52304676b8bfc773817df002`。算法与独立几何校验已 PASS：101/101、倍率 1、6582 顶点、173 对外接框相交、184 对精确间距检查、零重叠、矩阵一致。planner=854.396ms，method=2437.585ms，UI=3157.180ms；整个隔离宿主的堆峰值 2.30GB（包括模型、探针和框架，不是插件单独内存），maxHeap=4GB，测量窗口无 GC。与 v3 的宿主输出哈希完全相同：`856b1d1398ec0e5732ec30d570a5e5c06c85ef63dd4d855318728d9f14da4e47`。队列最终 succeeded / safe / normalExit / fixtureUnchanged / identityVerified 全部通过；源文件与隔离副本、黄金启动器及宿主 JAR 哈希复核通过。

**结论边界：矩形快捷路径可接近 BSSF；真正异形场景仍有额外开销，尚未全面追平。heavy 的单次冷 UI 约 3.16 秒，对照 BSSF 约 1.51 秒；热规划时间不能代替首次 UI 响应。**

对照轮廓图：`build/nesting-replay/heavy-comparison.png`（同一 4096² 页、101 项、100% 倍率）。这是对捕获的原始轮廓的离线绘制，不是 Cubism 截图；BSSF 图将材料局部原点归一化到其 issued 外接框。nesting 使用计划的完整仿射变换。两者都是轮廓图，不含源纹理颜色。

保留输入：`build/nesting-replay/inputs/heavy.bin`（SHA256 `141e794b1419290455d7846a194343d67e39242245e528cf57c97446a04c8a01`）与 `geometry-500.bin`（SHA256 `72349a5a9e0ebd6457e91b7cb5b1cbe0713df1c7aac8da098fed6d4a01b0a457`）；均为几何，不含源图像。

## 最终离线重复基准

本任务的实机宿主已正常退出；测量时本任务未并行启动其他构建或排版。共享主机仍可能运行其他工作树任务，没有关闭或修改他人的进程。以下均为 5 次预热后 9 次测量的中位数；原始 CSV 同时保留最小/最大值。不同轮次出现约两倍波动，因此不能把这些数字当作稳定 SLA，或拼接不同轮次计算加速比。

| 输入 | BSSF ms | nesting ms | 放入数 BSSF / nesting | 倍率 BSSF / nesting |
|---|---:|---:|---:|---:|
| rectangle 500 / 4096² / fixed | 26.400 | 30.987 | 500 / 500 | 1.000000 / 1.000000 |
| concave 500 / 2048² / fixed | 31.630 | 223.168 | 230 / 449 | 1.000000 / 1.000000 |
| concave 500 / 2048² / auto | 294.068 | 1198.206 | 500 / 500 | 0.747205 / 0.956250 |
| concave 500 / 4096² / fixed | 25.969 | 120.170 | 500 / 500 | 1.000000 / 1.000000 |
| heavy 101 / 4096² / auto | 11.988 | 138.455 | 101 / 101 | 1.000000 / 1.000000 |
| Geometry500 / 8192² / auto | 31.362 | 69.908 | 500 / 500 | 1.000000 / 1.000000 |

heavy 最终 nesting 范围 91.372–243.503ms，BSSF 8.077–28.798ms。该输入上 nesting 仍约 11.5 倍热内核耗时；其收益是允许外接框相交、保持真实轮廓和间距安全，并形成更集中的排布。合成凹形固定倍率用例多放入约 95% 部件；自动倍率用例线性尺寸大约提高 28%。这些是指定样本的启发式结果，不代表所有输入。

CSV：[完整合成矩阵](synthetic-benchmark.csv)、[heavy 真实轮廓](heavy-benchmark.csv)、[Geometry500 真实输入](geometry-500-benchmark.csv)。新插件完成后本任务的队列无活动项，已温和停止自己创建的 worker。

复现（在仓库根目录，输入保留在 build/nesting-replay/inputs）：

```sh
rtk proxy ./gradlew :plugins:atlas-bitmask-nesting:nestingBenchmark --no-parallel --max-workers=1 --offline
rtk proxy ./gradlew :plugins:atlas-bitmask-nesting:capturedNestingBenchmark -PnestingCapture="$PWD/build/nesting-replay/inputs/heavy.bin" -PnestingOutput="$PWD/build/nesting-replay/heavy-final" --no-parallel --max-workers=1 --offline
```

## 检查与已知限制

- 插件 13 项测试已通过，含 U 形嵌合、多区域、薄斜边、锁定、固定间距、倍率、矩形快捷路径和跨 64 位边界随机碰撞 oracle，以及独立圆角外扩逐格覆盖 oracle（含裁切与三个直角旋转）；runtime QUARTER issued-angle 与 AABB 预筛测试通过。
- `checkModuleBoundaries checkPluginBoundaries checkPluginPermissionAudit verifyFirstPartyPluginMetadata :plugins:atlas-bitmask-nesting:spotlessCheck` 已通过。
- 验证探针 64 项离线断言及独立捕获校验正反例通过，脚本语法和 `git diff --check` 通过。
- 新插件三语言 README 检查通过。全库 README 检查仍有既存 ACP 三份文档的重复权限声明；本次未更改。
- 仅当前页排版；完整 atlas SDK 请求明确拒绝。串行确定性启发式，不保证最优密度或最大倍率。原生并行开关禁用。
- 轮廓缺失时使用明确标记的矩形回退；SDK 会保守填实封闭孔洞；不从图片 alpha 重建轮廓。粗网格会损失细窄凹槽。
- 开发构件尚未发布；实机支持验证限定 5.3.03，版本清单中的其他版本未在本轮全测。


## 国际化与多版本集成（v6/v7）

- 当前插件 JAR SHA256：`130a23633bd798bf8a1b731c9eb80ca0a03e9d9b0ea43a979b48b9dfcfac72ba`。v7 仅更改验证任务为功能模式，生产内核未改动。
- API 下限与主线兼容策略统一为 `[0.67.0,1.0.0)`。插件名称、说明、算法菜单、矩形回退说明和常见布局拒绝信息均覆盖英文、日文、韩文、简体中文和繁体中文。
- 版本清单、三语言 README 兼容块和全仓矩阵由 `scripts/generate_compatibility_matrix.py` 生成。清单覆盖全部 20 个审核版本（5.0.00–5.0.06、5.1.00–5.1.03、5.2.00–5.2.03、5.3.00–5.3.04）；完整轮廓功能为 5.2–5.3 的 9 个版本。轮廓服务不可用时注册矩形 planner，由原生矩形入口调度，不调用缺失的轮廓接口。
- 插件 18 项测试通过（含五种语言的真实资源解析、错误参数替换和缺失轮廓服务时的矩形路径）；runtime 排版验证测试和 `admissionPreflightTest` 通过；探针 71 项离线契约断言及独立捕获校验通过。计时入口、设置对话框和变换三个 selector 的符号与全部 20 个审核记录一致；这只是静态证据。
- 实机通过见 [兼容性证据](compatibility-evidence.json)：5.2.03、5.3.02、5.3.03 均为 Geometry100，100/100、倍率 1、6px 间距、零轮廓重叠、宿主矩阵一致；队列全部 succeeded / safe / normalExit / fixtureUnchanged / identityVerified。这些输入仍是四顶点矩形，只证明多版本入口与写回；真实异形实机证据仍是前述 5.3.03 heavy。
- 版本线首末补丁的实机矩阵未完成：当前仅安装上述三个宿主。5.2.00 队列在启动前发现缺少精确安装的 JAR 并拒绝，没有启动宿主或修改源工程；未将缺少安装解释为插件不兼容，也未缩小生成式支持范围。
- 5.3.03 第一轮在排版后因进度窗口计时顺序断言失败而未通过；兼容任务现使用明确的 `--functional` 模式，保留原始性能模式，重跑通过。功能验证不作新的 UI 性能比较。

- 最终全仓验收通过：`rtk proxy env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 xvfb-run -a ./gradlew --no-daemon checkCompletedCommit --max-workers=2`，13m24s，384 个任务（83 executed、67 from cache、234 up-to-date）。其中包含官方插件五语言完整性、README、元数据、兼容矩阵、版本登记、边界、SDK、格式及全部子项目测试。
- 默认 Wayland 桌面上的截图测试出现过 PipeWire 超时；该截图用例在独立 X11 显示中单独通过，随后完整验收也在同环境通过，未用 `-x` 排除检查或修改截图实现。早期报告提到的 ACP README 问题已随最新主线修正，当前全库 README 检查通过。
- 三个宿主均正常退出；全部任务终态后，已温和停止本任务创建的空闲队列 worker。没有发布、推送或改动正式 Cubism 安装。
