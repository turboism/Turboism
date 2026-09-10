# 原生性能实验台账

最后更新：2026-09-10（I26 关闭期持有链只读切片轮）。当前主指标由用户确定为 **内存占用、CPU 占用、GPU 占用**。累计分配、调用次数和缓存命中率仅用于解释，不替代主指标。

本台账是本任务的统一检索入口，不是构建/运行时依赖，也不替代结构化 exact-host 证据。历史数据、失败和后续相反结果必须同时保留。所有实现位于独立分支；未授权合并或推送 main。

## 使用规则：防止无依据地重复试验

1. 实验前查编号、调用点、算法和参数范围；没有实质变化时，不重跑已经否定的方案来寻找有利样本。
2. 每次试验结束立即记录：**实现、验证方法、结果、原因/假设、证据、重试条件**。未成功完成的试验也要记录。
3. 明确区分 `PRIMARY_BENEFIT`（当前主指标获益）、`ALLOCATION_ONLY`、`NO_BENEFIT`、`INCONCLUSIVE`、`VALIDATION_PASS`、`VALIDATION_FAIL`、`PLANNED`。验证通过不等于优化成功。
4. 重试必须引用旧编号，并写明改变了什么：实现、输入、精确宿主、观测协议或可证伪假设。新观测器可能扰动 GC/时序，不跨批次拼接百分比。
5. 原因分为**已证实**和**可能/未定**；不能把一次成功重跑当成前一次失败的根因证明。
6. 终态判定包含 runner、身份、原文件 hash、正常退出和清理；辅助结果 `status=PASS` 不足以覆盖整轮 FAIL。只提交摘要/代码，不提交模型、官方 JAR 或含 licensing 信息的原始日志。
7. 性能证据必须先落盘到持久位置再登记：私有证据**禁止只存放在 `/tmp`**（临时目录会被清理，导致已登记 SHA256 无法复算，I24 已发生一次）。持久位置为性能 worktree 的 `build/` 与操作者本地状态目录下的性能证据归档；台账只写相对路径、用途和 SHA256，不写机器绝对路径。

## 快速索引

| 编号 | 方案 | 当前结论 | 无变化时的行动 |
|---|---|---|---|
| E01 | 图像/渲染方法计时诊断 | VALIDATION_PASS；不是优化收益 | 复用，不重建另一套探针 |
| E02 | PNG 原压缩表示复用 | 受控循环有效；自然工作流 NO_BENEFIT | 默认关闭，不重复强制循环宣传日常收益 |
| E03 | 提前安装 PNG hook | 初始观察补齐；自然复用仍为零 | 保留启动修复，不再以安装时机解释所有未命中 |
| E04 | 浮点解析 LRU | NO_BENEFIT，加载/分配回退 | 不恢复同一 LRU |
| E05 | 浮点固定槽 | 数值验证通过，ALLOCATION_ONLY | 默认关闭；不把命中率当 CPU 收益 |
| E06 | 纹理准备：初始范围 | ALLOCATION_ONLY，约8.6% | 已被扩展范围取代，保留比较工件 |
| E07 | 纹理准备：小图扩展 | ALLOCATION_ONLY，额外约164MB | 已验证范围，不重复相同阈值比较 |
| E08 | 纹理准备：大图扩展 | ALLOCATION_ONLY，总分配约-13.9% | 432/432 输入已覆盖；不能因此推荐为省 RAM |
| E09 | 真实 RSS/PSS 占用比较 | 原测量批次峰值增加约32%；稳态不确定 | 保留负面批次；不将分配下降说成占用下降 |
| E10 | 三指标同步 + 加载堆/GC 轨迹 | INCONCLUSIVE；未见稳定三指标净收益 | 后续实验使用该口径，继续按峰值阶段定位 |
| P01 | 变形器坐标只读投影 | 数值VALIDATION_PASS；NO_PRIMARY_BENEFIT | 三指标不支持启用；默认关闭，不重跑同一实现求好样本 |
| P02 | 局部上传/图集、更新合并等 | 未实施，契约证据不足 | 补精确失效/消费边界后才进入实现 |

## 公共实验条件与工件

- 精确 Cubism5.3.02 / bundled Java17 / Proton / Intel UHD630。JAR SHA：`988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`；JOGL SHA：`7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9`。
- 大模型：`heavy.cmo3`，447,432,485字节，SHA：`029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`。仅操作 runner 任务副本。
- 所有实际启动经 `run-cubism-host-validation.sh` 和官方 BAT；独立 CoW prefix/home/fixture；相同条件交替开/关；影子对照不计入性能样本。
- 当前纹理产品 Agent SHA：`469f322c1a8e9a56dfe9b43415b94bc99820b8c70ca4110f7e62247c008b5a13`。
- 初始范围 Agent：`9941bd68b8f52ff6f15a716d26430852c8d69818cfa3587415f3d2922a99ca31`；小图扩展 Agent：`e9cc756f5d4335fbab3865fe7cc8fca33ef13638515312e329a960af2d2463a7`。三份产品 ZIP 仅 `CanonicalRgbaImage.class` 内容不同。
- 当前占用口径：RSS/PSS 峰值和末段中位数；CPU 占用同时注明单核100%和整机12逻辑核归一化；GPU 使用实际 i915 DRM 客户端 engine busy，不拿 FPS/上传量代替；驱动缓冲驻留另列。

## E01 — 图像/渲染方法计时诊断

- **实现**：现有 `PerformanceProbe*`、有界64桶计时直方图、`performanceProbeScenario=images`；提交 `daecaf326`。计数 encode/decode/archive/atlas/redraw，保留非void返回值及异常出口。
- **验证**：focused Runtime32/Bootstrap23、Python7及旧 verifier；精确 JAR 的12目标转换。后续构建/预览包门禁补齐。说明：[README-native-image-performance.md](README-native-image-performance.md)。
- **结论**：诊断可复用，不是 CPU/GPU/RAM 优化成功；inclusive 时间不可相加，桶分位数不是精确总体分位数。
- **原因/限制**：这些方法计数并不等于磁盘 IO、GPU完成或资源驻留。**重试条件**：新增真实消费者/目标、精确版本变化或探针缺陷；不是重新建设平行探针。

## E02 — PNG 归档复用

- **实现**：`PngArchiveReuseCache`、`ImageArchiveReuseBridge/Transformer`、`VerifiedImageArchiveReuseInstaller`。弱身份和内容摘要校验后，仅替换 archive 中的 PNG encoder 调用，原流/计数/释放保持原生。开关 `turboism.optimization.imageArchiveReuse`，默认关闭。
- **验证**：真实PNG round-trip、原地像素修改和setUpdated回退、停用/字节码恢复；真实图像强制 decode+archive 循环；另做660秒不主动访问图像的自然窗口。后来另测原生图集创建、Undo/Redo、任务副本保存、跨会话重开及像素摘要。
- **结果**：强制循环约85.214→57.761ms、20.721→12.219MB线程累计分配；自然窗口2次archive检查、0次复用。原生工作流正确性通过不等于自然性能收益。
- **原因**：已观察到自然归档时无可复用旧PNG表示；摘要工作本身有成本。不能通过强持有全部图像或缩短原生归档计时器制造命中。
- **证据**：`image-archive-reuse-5302-pi-local-r2-20260905T062004Z-1758975`、`pi-local-natural-r4-20260905T062725Z-1771260`；后两种短后缀同此前缀。图集 `pi-atlas-workflow-r9-20260905T080744Z-1949783`、`pi-atlas-reopen-r12-20260905T082202Z-1971669`。完整表/命令：[README-image-archive-reuse.md](README-image-archive-reuse.md)。
- **重试条件**：先证明真实工作流有重复有效压缩表示，并计入摘要总成本；不再重复同一强制循环来证明日常可用。

## E03 — PNG hook 提前安装

- **实现**：`TurboismAgent` 在完整Runtime准入后、`PreviewRuntime.start` 前安装原生优化；`NativeOptimizationPolicy` 对无效配置 fail closed；javap 启动顺序测试，不给 Bootstrap 加ASM。
- **验证**：启动/失败恢复单测、初始计数快照、图集操作后660秒自然观察。
- **结果**：修复初始观察时机，但 `pi-atlas-natural-r13-20260905T082405Z-1975790` 仍2次回退、0复用；4个初始proof仅40,000像素。大模型后续加载/受控测试通过，仍无自然复用收益。
- **原因/重试条件**：此前把空闲窗口decode增量0误解为初始解码0是不正确的；现在已有初始快照。只有新证据证明有丢失的真实decode窗口才重查安装顺序。

## E04 — 浮点解析 LRU

- **实现**：精确 `serialize.impl.G.a(int,List)` 的纯解析替换；以字符串为键缓存 `Float.parseFloat` 结果，返回新数组。初版LRU已被固定槽替换，不能把当前固定槽代码说成旧LRU实现。
- **验证**：同大模型 off/on；独立shadow比较18,860数组/17,002,711值、零位差异；原生错误和数组所有权/恢复检查。
- **结果**：3,571,906命中，但加载102.953→115.754秒，累计分配14.8415→15.0435GB，负收益。
- **可能原因**：LRU节点/装箱、查找和淘汰抵消解析节省；后续固定槽去掉节点/Float装箱后分配变好，CPU仍未获得稳定改善。这不是所有开销的完整归因证明。
- **证据**：float前缀 `float-array-parse-cache-5302-`：`heavy-off-r1-20260905T104353Z-2165358`、`heavy-on-r1-20260905T104557Z-2170694`、`heavy-shadow-r2-20260905T103720Z-2155603`。`heavy-shadow-r1-20260905T103014Z-2145739` 是FAIL（见基础设施记录）。
- **重试条件**：新分布或不同算法必须先证明净成本，不恢复同一LRU、也不只展示命中数。

## E05 — 浮点固定槽

- **实现**：`FloatArrayParseCache/Bridge/Transformer`；8192直接槽、raw float bits、262144字符预算；miss仍调用原生parseFloat，每次新数组，不保留输入List/数组。关闭回调与parse同步，避免close后重新填充。
- **验证**：碰撞/位一致/边界/并发单测；同模型off/on；最终固定槽shadow覆盖全部18,860数组/17,002,711值，零位差异和正常恢复。
- **结果**：108.130→109.036秒、CPU180.950→185.590秒；分配14.8477→14.3321GB。只有分配改善，无可靠加载/CPU收益；默认关闭。
- **可能原因**：仍需对所有token做查找，多数miss要解析；不能从约321万命中直接推算CPU/RAM获益。
- **证据**：`heavy-off-slots-r2-20260905T110135Z-2196008` / `heavy-on-slots-r2-20260905T110345Z-2201316`；最终 `cfix-r5-20260907T012436Z-826925`，同float前缀。详见 [README-float-array-parse-cache.md](README-float-array-parse-cache.md)。
- **重试条件**：token分布/算法/有效净CPU假设发生变化，仍需真实主指标对照；不重复相同固定槽参数寻找单次快样本。

## E06 — 纹理准备初始范围

- **实现**：`CanonicalRgbaImage`、`TextureUploadPreparationBridge/Transformer`、`VerifiedTextureUploadPreparationInstaller`。在精确shader工厂只替换输入图像，原JOGL调用/GL状态/尺寸/mipmap/所有权不变；JDK颜色表生成等价预乘RGBA。初始下限65536像素、每维4096、面积<=16777216。
- **验证**：穷举channel/alpha、translated raster、exact-artifact和恢复；三轮off/on及独立真实JOGL字节/元数据/行跨度shadow。
- **结果**：163次准备/170,950,656字节零差异；累计分配中位数约-1.28GB/-8.6%。时间/CPU波动，没证明占用下降。
- **原因**：减少JOGL自定义转换中的逐像素临时对象。**重试条件**：真实遗漏输入/新格式证据；不要把旧范围分配数据当作RSS收益。
- **证据**：texture前缀 `texture-upload-preparation-5302-` 的 `heavy-shadow-r1-20260905T134143Z-2369670` 和三轮heavy-off/on；完整run表与命令：[README-texture-upload-preparation.md](README-texture-upload-preparation.md)。复核shadow `resume-texture-shadow-r1-20260906T160721Z-450525`。

## E07 — 小图范围扩展

- **实现变化**：下限65536→4096，其他边界不变。两个产品Agent仅CanonicalRgbaImage.class不同；新增小/细长/偏移图像差分测试。
- **验证**：`small-s1-20260907T014102Z-847410`、`small-s2-20260907T021302Z-897115` 影子；cb/cc三轮交替范围比较，另no/all三轮off/on。全部完整PASS；不是影子计时。
- **结果**：431次/191,545,344字节零差异；相对旧范围额外少163.687MB观测分配(1.21%)；另批off/on分配-9.81%。实际占用尚未由此证明。
- **原因/后续依据**：268个小输入被覆盖。shadow显示剩余输入是3840×5056的大图，不是小图；因此有依据进入E08。
- **重试条件**：真实输入分布或转换实现改变；当前小图范围已覆盖，不重做相同阈值矩阵。全部中间run IDs见纹理README，未删除不利计时。

## E08 — 大图范围扩展

- **实现变化**：每维<=8192、总面积<=33554432，RGBA临时栅格上限128MiB，仍不缓存源图。新增边界测试。
- **验证**：`large-s1-20260907T021859Z-904183` 比较全部432次/269,205,504字节零差异；ln/ls/lw三轮off/小图/扩展范围交替；完整门禁和交互use-r1正常退出。
- **结果**：分配14.840170→12.775706GB，-2.064464GB/-13.91%；大图部分相对小图方案额外少606.603MB。**这只是ALLOCATION_ONLY，不是当前三指标成功。**
- **原因/限制**：消除最后一个大输入的逐像素临时对象。没有新的GL调用或像素质量变化；之后E09实际占用出现负面结果。
- **证据**：提交`becbaf1c7`；ln1/ls1/lw1、lw2/ls2/ln2、ls3/ln3/lw3完整run IDs及产品SHA表在纹理README。
- **重试条件**：该工厂已432/432覆盖，无新输入时不继续扩大尺寸上限。新的主指标假设必须转向实际占用/CPU/GPU，而不是再优化覆盖率。

## E09 — 实际内存占用

- **实现/验证**：提交`5672db97d`，测试专用 `NativeMemoryObservation` + `measure-task-memory.py`，主产品不变。每秒smaps_rollup RSS/PSS/Swap及status HWM，加载后自然空闲120秒，末30秒中位数；不强制GC、不改heap cap。六轮同Agent/同观察器off/on/on/off/off/on。
- **结果**：该批RSS加载采样峰值中位数4.283→5.661GiB(+32.16%)，HWM同趋势。尾段2.561→2.419GiB但范围重叠，不证明稳定节省。off第3轮曾有约173MiB Swap，其他五轮为零；未剔除。
- **原因状态**：当时只记录ready后的heap/GC，不能归因峰值。GC/堆扩张、临时payload和驱动/压力都是假设，不能写成已证实根因。
- **证据**：mem-n1/y1/y2/n2/n3/y3（20260907T030729Z至032841Z）；辅助Agent SHA `5c1300e57207a8be54e7612e4dd742719c2eb1e7685871e61a987875a975f198`。所有行、命令、证据：[README-native-memory-occupancy.md](README-native-memory-occupancy.md)。
- **允许E10重试的变化**：用户改定三指标，增加CPU/DRM和加载期heap/GC轨迹来验证峰值假设；不是不加变化重复找好样本。E09数据永久保留，E10也不能倒写E09为PASS收益。

## E10 — 同步三指标与峰值阶段

- **实现**：测试侧`host_resource_counters.py`扩展proc sampler；CPU utime+stime明确单位；DRM按driver/device/client-id去重，临时倒退计数保留高水位，容量归一化，缺失/客户端变化区间不报零。`NativeMemoryObservation.startLoading`记录250ms heap/GC和texture prepared计数，另120秒空闲。未改产品Agent。
- **验证**：14项focused测试（包括JDK30秒烟测）、bundle及产品SHA；六次官方run完整PASS。实际device为i915 `0000:00:02.0`；加载GPU覆盖区间有缺口，idle-tail覆盖完整。

| Run后缀（仍为texture前缀） | off/on | RSS峰值GiB | 尾段RSS GiB | 加载CPU整机均值 | 加载render忙碌率（可计量区间） | 尾段GPU |
|---|---|---:|---:|---:|---:|---:|
| tri-n1-20260907T042033Z-1045450 | off | 4.377 | 2.570 | 14.74% | 0.148% | 0% |
| tri-y1-20260907T042435Z-1052870 | on | 3.231 | 3.176 | 14.78% | 0.172% | 0% |
| tri-y2-20260907T043355Z-1066110 | on | 5.436 | 2.375 | 15.65% | 0.183% | 0% |
| tri-n2-20260907T043800Z-1074117 | off | 5.527 | 2.501 | 15.56% | 0.170% | 0% |
| tri-n3-20260907T044215Z-1082717 | off | 4.095 | 3.552 | 14.75% | 0.204% | 0% |
| tri-y3-20260907T044620Z-1090845 | on | 3.732 | 3.021 | 14.53% | 0.156% | 0% |

- **结论**：没有可推荐的稳定三指标净收益。RSS范围重叠，未重复E09的统一峰值方向；CPU/GPU差异小。空闲CPU整机约0.068–0.263%，GPU render为零，不能把此场景当重GPU负载。
- **已观察到的原因线索**：三次on在RSS最高样本附近`preparedCount=0`；峰值位于前置加载，heap used/committed和GC时序变化很大。这削弱“转换输出直接造成峰值”的简单归因，但不是所有启动hook副作用或唯一GC原因的证明。
- **重要限制**：新增加载期观察器本身可能改变GC/时序，不能跨E09/E10平均来宣称RAM改善。GPU只归因该进程的DRM客户端，不是整个桌面/NVIDIA设备或显存。
- **复现变化**：在内存README命令上另stage `host_resource_counters.py:validation/host_resource_counters.py` 并传 `-Dturboism.validation.textureUpload.loadingTrace=true`。辅助Agent SHA `dea3ec4fe36334fa25ef199e58326263b4e64dc1ccf430bb963ace62131fbda0`；产品仍为公共条件中的同一SHA。每个run的JSONL+tar中jvm-loading.csv保留完整轨迹。
- **重试条件**：新的峰值归因假设或明确实现差异；下一步CPU候选P01与新的受控重绘场景，不能仅反复开关现有纹理flag。

## 已验证的基础设施方案与失败

这些项不计为Cubism性能收益，但避免后续重踩验证问题。

| 编号 | 实现/验证 | 成功与失败记录 | 原因状态及禁止重复的做法 |
|---|---|---|---|
| I01 | 通用runner显式local transport、CRLF终态解析；shell/解析回归；提交0dd80b73c | PNG pi-local-r1辅助PASS但runner FAIL；r2完整PASS | 已证实Windows行尾不匹配。当前就在宿主，不再寻找旧SSH密钥，也不回写r1 |
| I02 | 交互模式省略辅助agent，保留prefix/home/副本；前缀/PID/焦点验证后Alt+F4烟测 | interactive-smoke-r3-20260906T031754Z-3186734、use-r1-20260907T024041Z-940202正常结束 | windowclose/旧关闭尝试失败保留；SESSION_ENDED不是验证PASS。真实使用不传烟测关闭client |
| I03 | 清理器精确prefix/祖先排除/pidfd/历史记录；11项回归 | float resume-slots-close-fixed-r2清理身份BLOCKED，重试CLEANED但整轮FAIL | 本机自有nondumpable zombie可复现environ EACCES；原PID459846历史状态未知。只排除证实死亡/消失，活进程权限或身份不明继续BLOCKED，禁止raw-PID降级 |
| I04 | runner不再见shutdown日志就kill；等待实际wrapper退出；7项交互/退出测试 | cfix-r4数值PASS但过早kill导致143，整轮FAIL；cfix-r5完整PASS | 已由实际退出循环回归复现。强制清理、缺失/非零exit永不当PASS，不再用日志替代进程退出 |
| I05 | 使用短标签，保留路径失败 | resume-slots-cleanup-fixed-r3模型路径261字符，console报File name is too long，600秒后FAIL | 路径长度已核对，缩短标签后模型成功打开；不是解析算法失败。不延长timeout掩盖未打开模型 |
| I06 | FloatArrayParseBridge同步callback与close；确定性暂停回调回归 | 旧实现在close后重填keys，回归红；修复后绿及cfix-r5 shadow PASS | 已复现并修复生命周期竞态，不把旧LRU shadow冒充当前实现证明 |
| I07 | 仅修复三个既有测试和授权DRAFT schema；提交014c5a3bf | 截图Xvfb专项、生命周期awaitState、字体按平台参照；mapping导入及完整gate PASS | 不改对应生产行为、不降断言阈值；schema保留选择器/DRAFT，静态记录不是5.3.03实机准入 |
| I08 | 全量headless、截图单独Xvfb | 旧Xvfb全量等待真实modal/超时记录保留；正确环境完整gate PASS | RuntimeUiHostTransientStateTest验证headless拒绝，不能把全套放进Xvfb；不是性能优化失败 |

### 其他旧失败：保留现象，不编造根因

- PNG `pi-local-natural-r3-20260905T062530Z-1767117`：ready前退出、无terminal；同配置r4成功。根因未定；golden继承的8月3日crash文件不是该轮崩溃证据。
- PNG `pi-atlas-inspect-r7-20260905T073156Z-1872787`：`native atlas editor did not cancel`；属于工作流驱动失败，不能认证PNG性能。后续完整workflow r9通过，不代表已独立证明r7唯一根因。
- PNG `pi-atlas-reopen-r10-20260905T081342Z-1958682`、r11-20260905T081731Z-1964932：`native atlas image did not become ready`。后续r12经原生cached-image consumer物化lazy atlas后通过；不能直接把尚未物化的缓存当像素不一致。
- PNG `pi-heavy-r14-20260905T085833Z-2028265`：`active fixture is not a modeling document`。后续增加可靠document等待后大模型通过；原断言触发时机需与真实active-document区分，不删模型/放宽断言。
- Float `heavy-shadow-r1-20260905T103014Z-2145739`：NPE（对null调用getClass）；r2 shadow通过。原始详细根因本轮未独立复核，不将r1称作数值不一致，也不覆盖其FAIL。
- 初始handoff中的checkCompletedCommit曾因既有mapping草稿失败；后续最小schema修复后完整门禁通过。旧失败日志不是最新gate结果；不同批次的optional skip必须分别说明。

## P01 — 变形器坐标只读投影（已验证，未获主指标净收益）

- 用户已批准实施并验证：只替换精确 `CExtendedInterpolationExtension.updateInterpolatedForms_common` 第一段只读 `getAllPointRef().map(getPos)`，直接创建同样的新native GVector2列表。
- 不缓存WarpPointRef、不保留form/positions、不改第二段保留引用的写路径、插值数学、dirty、Undo或GL。默认关闭、精确版本/完整方法形状、异常回退和恢复必须保留。
- 依据是已有JFR中的实际临时点引用成本及只读字节码调查；约846MB是采样权重，不是RAM节省预测。
- **离线批次P01-a**：实现 `WarpPositionProjectionBridge/Transformer`、`VerifiedWarpPositionProjectionInstaller` 和早期安装/失败恢复。只替换第一投影子循环；对纯getter/vector构造和实际加载依赖做原始方法形状比对。命令：`:runtime:test --tests '*WarpPositionProjection*Test' :bootstrap:test --tests '*VerifiedWarpPositionProjectionInstallerTest' devCheck`，提供精确5302 test JAR。Runtime8/Bootstrap2，零失败/错误/跳过，BUILD SUCCESSFUL；日志 `build/native-warp-offline.log`。覆盖原生第二写循环继续执行、回退/异常、位保持、新对象、边界、close及其他方法不变。**仅离线验证通过，尚无实机或三指标收益结论。**
- **P01-b辅助件门禁**：新增JDK-only `NativeWarpPositionHostAgent`（不跨回调保留form/向量）、薄wrapper `run-warp-position-host-validation.sh`；复用既有内存/CPU/GPU采集和通用runner。`checkWarpPositionValidationBundle checkPreviewBundleLayout`通过，Bash语法及wp-s1 wrapper dry-run通过（`build/native-warp-bundle.log`、`build/native-warp-dry-run.log`）。没有以此宣称实机或性能成功；下一步独立untimed shadow。
- **P01-s1实机shadow**：`warp-position-projection-5302-wp-s1-20260907T054216Z-1152727`完整runner PASS、wrapper_exit=0，原模型/官方工件hash不变。真实调用6930，符合窄范围并完成投影5166，比较3,784,560点（x/y raw float bits）零差异；0次初始漏观察、0生产失败；849 images/6 atlases，启停/回调清理/类字节恢复通过。**功能差分成功，不是性能成功**。可能获益机制为省掉只读中间引用/list；三指标尚未测量。接下来off/on性能模式不安装不必要的shadow包装回调，产品实现不变；两组使用相同采样辅助件。

### P01-c — 三指标结果：不推荐启用

产品Agent SHA `ac3e7602ac1e70031fcdf7f77b0ea7201882939ce586366fda966e29afcd03e3`，辅助Agent SHA `bd677b66a24ce3386886e55bc4a7793d3c9b68360829b83673e2267d10d3012c`。关闭纹理/浮点/PNG其他优化；同大模型、同三指标采集、250ms加载heap/GC轨迹、120秒自然空闲；off/on/on/off/off/on。性能模式没有shadow包装回调。六轮runner和采样均PASS、正常退出、无原模型或官方工件修改。

| 完整run后缀（前缀warp-position-projection-5302-） | 模式 | RSS加载峰值GiB | 尾段RSS GiB | 加载CPU秒 | CPU整机均值 | GPU render有效区间均值 |
|---|---|---:|---:|---:|---:|---:|
| wp-n1-20260907T054951Z-1163472 | off | 3.772 | 3.280 | 229.15 | 13.22% | 0.100% |
| wp-y1-20260907T055439Z-1173127 | on | 4.488 | 4.487 | 210.87 | 13.74% | 0.131% |
| wp-y2-20260907T055912Z-1182627 | on | 3.663 | 3.629 | 223.27 | 12.89% | 0.138% |
| wp-n2-20260907T060410Z-1194983 | off | 3.764 | 2.465 | 235.12 | 12.79% | 0.150% |
| wp-n3-20260907T060915Z-1206823 | off | 4.340 | 3.046 | 225.32 | 13.72% | 0.140% |
| wp-y3-20260907T061400Z-1217612 | on | 4.463 | 2.503 | 232.00 | 13.86% | 0.111% |

- **判定**：没有稳定主指标净收益，不推荐启用。RSS峰值中位数3.772→4.463GiB（+18.3%），尾段3.046→3.629GiB（+19.1%）；两者都有明显波动。CPU总时间中位数229.15→223.27秒（约-2.6%），但范围重叠且CPU整机占用均值中位数反升约4%；GPU约0.14→0.13%的低负载差异不构成有用GPU优化，尾段均为0。
- **次要计数**：观测分配14.8536→14.7620GB，仅少约91.6MB/0.62%；不能拿它抵销主指标失败。load ready中位数143.20→138.89秒也不足以作为稳定加速承诺。
- **可能原因/限制**：只消除第一只读循环的符合条件引用，第二原生写路径及向量输出保留；调用路由/MethodHandle/准入开销、GC/堆时序可能抵销节省，未证明唯一根因。旧JFR约846MB是采样权重和更广调用归因，不能当本切片实际节省预测。此批系统MemAvailable最低1.25–2.42GiB，较此前批次更有压力；不能跨批次直接比较。六轮该进程Swap均为0。
- **重试条件**：仅在真实热点/输入分布、具体算法、准入开销或堆/GC归因出现实质变化时重试，并先说明差异。不要重跑相同helper/模型/实现来筛选好样本。不扩大到第二写循环，不缓存可变点引用，不通过强制GC或降画质制造收益。
- **保留策略**：实现仅作为隔离分支中的默认关闭实验和可复现证据；不是可推荐的产品优化，也未宣称完整交互/Undo/保存矩阵通过。后续完整自动门禁与本地提交单独记录。
- **P01-d收口门禁**：`checkCompletedCommit checkWarpPositionValidationBundle checkTextureUploadValidationBundle checkCubismHostValidationArguments checkPreviewBundleLayout` BUILD SUCCESSFUL；日志`build/native-warp-completed-commit.log`。Runtime2537/0失败/39环境或optional跳过，Bootstrap92/0失败/0跳过，Integration367/0失败/1跳过；精确P01 artifact测试已在P01-a单独配置且零跳过。最终产品SHA仍等于P01-c实测Agent，最后无Cubism/Wine残留。门禁成功不改变NO_PRIMARY_BENEFIT结论。实现/复现细节：[README-warp-position-projection.md](README-warp-position-projection.md)。
- 设计细节在当前任务本地冻结文档 `docs/agents/native-warp-position-projection-proposal-20260907.md`；该本地文档不构成构建依赖。实验完成立即追加本台账结果和重试条件。

## P02 — 尚不能宣称验证过的方向

Dirty rectangles、局部图集合成、属性级VBO更新、原生更新合并、全局WarpPointRef缓存、XML校验绕过都未通过本任务的完整实施/实机验收。缺少完整像素写范围、边缘失效、消费前flush或可变引用生命周期证明时不启用。不能将“考虑过”写成“算法已失败”，也不能每次从同一无证据假设重新开始。

旧 `run-cubism-camera-scenario.sh` 实际驱动窗口resize，不是真正camera/拖参场景；源码历史注释不能当成本轮GPU证据。新的交互GPU负载必须说明真实动作、任务窗口身份、恢复和采样窗口后再验证。

## P03–P07 — 用户批准的后续调查队列

2026-09-07 用户要求逐项验证并即时记录；下列均为调查方向，不是已实施或失败的优化。

| 编号 | 方向 | 首项验证目标 | 当前状态 |
|---|---|---|---|
| P03 | 交互重复更新/重绘 | 真实缩放+三指标/JFR | 3轮完整PASS；确认CPU模型更新热点，未实施跳过/合并 |
| P04 | 图片/纹理长期驻留 | 正常关闭+自然GC+weak/RSS/heap | Full GC后doc仍被保留，根持有者未定；无优化收益结论 |
| P05 | 局部替代全量处理 | 真实VBO采样点+精确dirty/消费审计 | STATIC_FEASIBILITY；缺少完整写区间证据，未实施 |
| P06 | 加载瞬时峰值 | RSS峰对齐heap/GC/JFR | INCONCLUSIVE；不原样重试旧分配缓存 |
| P07 | 热路径纯计算 | 新内部点Warp快路径原型差分/微基准 | 数值通过，但离线慢9.8–29.3%；不接产品Hook |

### I09 — 续接预检：其他任务占用宿主（BLOCKED，不是算法失败）

- **时间/工作区**：2026-09-07T08:40:54Z；`feat/cubism-native-performance-20260905`，HEAD `18f90f5aa`，续接时工作树干净。
- **实现/验证方式**：仅只读检查 `/proc/<pid>/{comm,stat,cmdline,environ}` 中进程状态、UID、启动ticks、Cubism主类及隔离prefix；不连接Editor、不发信号、不写进程状态。已读取通用实机runbook及runner `--help`。
- **观测结果**：四个存活Cubism JVM，UID均1000；PID/startTicks分别为 `1463014/9035826`、`1474315/9054406`、`1493893/9090441`、`1504607/9116250`。均属于另一任务族 `core-acquisition` 的5302工具栏验证，任务ID分别为 `core-acquisition-5302-toolbar-on-20260907T082646Z-toolbar5302on`、`core-acquisition-5302-toolbar-on-20260907T082951Z-toolbar5302on2`、`core-acquisition-5302-toolbar-on-20260907T083551Z-toolbar5302on3`、`core-acquisition-5302-toolbar-off-20260907T084008Z-toolbar5302off`；各有存活wineserver。
- **判定/原因**：BLOCKED。已证实存在非本次任务的Cubism会话，按runbook暂停实机操作；并发资源争用也不适合作三指标对照。不推断这些会话是泄漏或可清理残留。
- **本轮未做**：未启动新宿主、未实施优化、未运行新性能试验、未清理其他任务、未合并或推送。P03–P07保持未验证，不报为失败或完成。
- **恢复条件**：相关任务负责人正常关闭其会话，重新核对宿主空闲和精确身份后，先冻结P03/P04观测切片再运行。上述PID仅为历史证据，不能据此在未来清理进程。

- **I09恢复**：用户报告已清理，2026-09-07T12:08:26Z只读重新检查无Cubism/Wine会话；官方5302 JAR/BAT/heavy源文件SHA均未变化。没有代为清理其他任务。

### P03-a — 真实缩放/关闭后资源观测辅助件（离线通过）

- **实现**：JDK-only `NativeResourceHostAgent`，独立auxiliary JAR及薄wrapper，复用E10采样器。120次原生放大/缩小，每次检查实际camera scale变化、任务doc/view身份；通过原生scale同步恢复；验证dirty/Undo不变；正常关闭唯一干净任务模型，观察至少120秒。只保留弱document引用，不强制GC。记录阶段epoch以区分idle/zoom/restored/closed。可选JFR，plain/profile不当成优化off/on。
- **依据/边界**：精确JAR javap跟随CEAppCtrl.zoom→ar.m/l→canvas.f/e→原生scale-step及CEUpdateManager.setValueCameraScale；SDK当前无modeling zoom-in/out、close命令。测试侧没有新产品hook、SDK/API或像素写入；不足以宣称交互画面完整正确。具体协议：[README-native-resource-workload.md](README-native-resource-workload.md)。
- **验证**：`devCheck checkResourceValidationBundle checkCubismHostValidationArguments` BUILD SUCCESSFUL（`build/native-followup/offline.log`）；14项CPU/DRM/proc观察器测试通过，Bash语法、rs-n1 wrapper dry-run通过（`build/native-followup/dry-run.log`）。helper语法diagnostics零错误；这里只是编译/打包/静态及基础设施检查，不假冒实机结果。
- **工件**：产品Agent仍为P01的`ac3e7602ac1e70031fcdf7f77b0ea7201882939ce586366fda966e29afcd03e3`；新auxiliary SHA `a19251855348340c340eb177195a1bccc66054684f9cc1aaa85233bd140c0aaf`。产品所有实验优化关闭。
- **判定/下一步**：离线VALIDATION_PASS，尚无性能收益。先运行一轮plain验证驱动，成功后另加profile定位实际栈；若驱动失败记录为驱动问题，修复须明确变化。

### P03-b — 首轮驱动失败，保留结果

- **实机run**：`native-resource-5302-rs-n1-20260907T122423Z-2131764`，plain；120/120次原生缩放都实际改变camera scale，耗时129.21秒。恢复断言FAIL：7.5337915→7.533791（差1 ULP）；未进入模型关闭观察，不能据此判断P04。runner整体FAIL、wrapper exit143（失败后的task-owned清理），survivors0。没有把采样完整当作验证成功。
- **原因调查**：已检查`GCameraManager.getCameraScale()`实际返回派生`scaleComponentToDocument`，不是wrapper原始`cameraScale`；原生UI同步路径包含倒数浮点转换。因此不能从原生UI值恢复就声称派生量逐位恢复。唯一原因仍需修复对照，不改容差掩盖失败。
- **下一轮实质变化**：保存wrapper原始cameraScale，通过原生wrapper.setCameraScale（会通知原生scale监听器）+manager.updateCamera+repaint恢复，并继续要求原始/派生scale逐位相等。缩放次数改60次：本机120次实际耗时129秒，不足以在既有300秒观察窗内留下完整120秒关闭观察；保留500ms最小节奏和实际次数/耗时，不扩大采样器全局时间界限。辅助件重建/新SHA后再运行，不复用本轮作为收益证据。

### P03-c — 原始setter恢复仍失败；改用明确的原生档位负载前置条件

- **run**：`native-resource-5302-rs-n2-20260907T123538Z-2160595`，aux SHA `2f2d559a13c63fb680c28e228bc04b67e7e3dbbd5217ab078ba85f97a1653b4d`。60/60实际缩放、66.36秒；`raw camera scale not restored exactly`，整体FAIL、task-owned清理survivors0，未测试关闭后驻留。辅助件重建及dry-run通过，不能盖过实机FAIL。
- **已知/未定**：原生wrapper setter会发送`1.0f/scale`给监听器，并非纯无副作用字段赋值；调用后原始值仍有变化，尚未证明所有回写来源。禁止移除监听器或直接写私有字段制造逐位恢复。
- **新协议**：在采样ready前执行一次原生放大/缩小，将任务副本视图规范到原生缩放档位，记录规范前后scale；后续60次动作必须逐位恢复到这个明确的测试起点。原始任意fit比例不再被宣称逐位恢复；它所属任务副本视图最终正常关闭，不保存模型或用户视图设置。保留旧两轮FAIL，不放宽新起点恢复容差，也不把规范化前的两次动作混进idle/性能窗口。此变化是负载定义修正，不是产品优化。

### P03-d / P04-a — 首轮完整缩放与关闭观察 PASS（尚非优化）

- **run/工件**：`native-resource-5302-rs-n3-20260907T124833Z-2195008`，plain；aux SHA `50d5e99910c95d86a6981915db3488cf6ccb5c06671acc42dd93f4e130afb472`，产品未变。辅助件重建及新dry-run通过；完整runner PASS、exit0、原文件hash不变。初始fit7.5337915，规范化测试起点10.000001，60/60真实缩放，恢复后10.000001逐位一致，dirty/Undo不变，唯一模型正常关闭。
- **三指标（不是off/on）**：加载RSS采样峰值3.2075GiB；idle30秒RSS中位3.0615GiB，CPU整机12核0.162%，GPU render0%。zoom65.688秒、CPU75.66秒（单核等价117.65%，整机9.8045%）、GPU render1.9048%，RSS中位3.0630GiB。缩放GPU有效区间完整；加载约85.8%覆盖，其余不当零。
- **关闭后120秒**：RSS中位3.0743GiB，末30秒3.0744GiB；CPU整机0.1022%，GPU0%。heap used约1.545→1.066GB，committed保持2.642GB；GC计数41→43后关闭窗口不再变化。i915该客户端system0 resident从约579.6MB降到518.9MB，但不是完整显存/共享物理内存总账。进程Swap0。弱document未清除，不能据此认定泄漏（不知自然GC是否扫描到它所在代际）。
- **已知/假设**：真实缩放相较静置有明显CPU工作，GPU仍低，不支持盲目以GPU上传为主靶点。关闭后部分heap/driver资源下降但RSS未退，可能有堆保留、缓存或未回收引用；尚无唯一持有链证据。数据在`build/native-followup/rs-n3-summary.json`，从JSONL+tar内jvm.csv按phase epoch计算，CPU按进程ticks/真实区间，GPU按client busy差值，未累计inclusive方法时间。
- **下一步变化**：同一产品/负载另开profile模式记录JFR，按已知phase筛选真实CPU栈和GC事件；这是新增归因证据，不和plain拼成优化百分比。P05/P07只在实际热栈支持后选择切片；P06对加载样本单独归因。

### P03-e / P04-b — JFR归因轮 PASS，明确内存观测局限

- **run**：`native-resource-5302-rs-p1-20260907T125843Z-2222712`，同产品/aux SHA，profile=true；完整PASS、exit0、原文件hash不变。60次/65.496秒，CPU75.85秒（整机9.6843%），GPU1.9559%，缩放RSS中位2.9647GiB；闭合末段约2.974GiB。不能以plain与profile的RSS差异宣称优化。
- **P03发现**：缩放阶段118个Java execution samples中89个调用链包含`CEViewContext_ModelingView.updateScene`，经`view.ay.a`遍历模型、`CWarpDeformer.transformDeformer_testImpl`和`warp.o.a`执行变形；43个leaf样本落在warp.o.a。另25个execution样本在渲染路径，8个leaf为GTransform.getLocalToWorldMatrix。JFR线程CPU表显示EDT是主要CPU工作线程。NativeMethodSample里大量WToolkit.eventLoop是阻塞事件循环，不能把样本占比当CPU热点；原生GL buffer/readpixels也被采到，但不等于调用次数或GPU时间。
- **P04发现/局限**：原生模型关闭自己执行两次System.gc（JFR栈到CModelingDocument.closeFile及其文件组件），并不是辅助件主动GC。那时调用者/原生close仍可能持有doc/view，故120秒弱引用未清除不能证明泄漏。原生CImageResource线程约关闭120秒后又触发GC，发生在weak观测之后、300秒sampler结束之前；最后heap used仍约1.064GB、committed2.525GB、RSS2.974GiB。JFR OldObjectSample主要为byte/int/float数组，未记录referrer路径，不能据此确定它们的持有者或可释放性。
- **实现/验证方法**：使用JDK `jfr print --json --stack-depth 128`筛选phase时间窗；默认print仅显示5帧，不代表录制缺少更深栈，不为此无变化重跑。记录有界128MiB、没有新增产品hook；本地分析`build/native-followup/rs-p1-summary.json`、`rs-p1.jfr`及相关JSON，不提交私有原始记录。
- **后续条件**：P03重复model update是真实执行路径，但尚未证明可跳过（camera改变可能影响GUI/网格/选择）。P04需要在原生后续GC后检查weak，或完整持有链诊断，不能立即清缓存/降heap cap。P05继续审计实际VBO消费契约；P06/P07使用本轮已有事件，不以旧缓存方案重新碰运气。

- **P04-c预注册变化**：辅助件新增sampler300秒结束时的weak-reference检查（仍不强持有doc），保留120秒旧字段；在新的profile轮关联原生后续GC与最终弱引用。目的是区分仅观察过早和后续仍被引用，不改采样时长、不触发额外GC、不重复原有优化flag。另加Linux JDK17辅助件拒绝非法窗口/启用产品优化的离线烟测；不是启动Cubism。

### P04-c — 原生后续Full GC后仍有document引用，但未定位持有者

- **run/验证**：`native-resource-5302-rs-p2-20260907T131548Z-2264186`，aux SHA `a0e089f2aadb50240563266bff3564f681994a95121acea234ac2ffda244523c`；产品不变。bundle重建、3项准入烟测（6种拒绝输入）、新dry-run通过；实机完整PASS、exit0、原文件hash不变，60次缩放/67.489秒、CPU75.70秒、GPU2.0075%。
- **结果**：关闭后120秒和sampler结束（约关闭170秒）weak都未清除。JFR在关闭约113秒后记录原生CImageResource线程触发G1Full，GC52后heap used1,057,674,472B、committed3,565,158,400B，之后最终weak仍false。由此不能再仅解释为没有Full GC；确有可达/被保留引用的线索，但持有者可能为原生缓存、Runtime、宿主UI或观察器，未确定。不能直接称为产品泄漏或清除未知缓存。
- **内存变异**：该轮加载RSS峰4.4554GiB，zoom中位3.5795GiB，closed末30秒3.3987GiB；同协议plain/profile前轮差异仍大。没有任何RAM优化实施/收益；CPU工作量约75.7秒在三次成功轮一致得多。
- **重试条件**：下一步须取得具体GC-root/持有链，或明确的重复开关模型生命周期对照；不要重复同样120秒idle。辅助件已把最终weak检查保留下来，默认所有产品优化关闭。原始JFR/JSONL留在任务证据，摘要`build/native-followup/rs-p2-summary.json`。

### P05-a — 局部上传/图集/缓冲更新：静态消费边界调查

- **验证方法**：从P03-e真实native samples追到`graphics3d.mesh.a.b/c`，对精确JAR javap读取float/int buffer准备、容量分配、glBufferData/SubData及末尾dirty清除；本地`build/native-followup/buffers.javap`、`buffer-base.javap`。这不是新算法实机验收。
- **发现**：当前上传按整buffer的dirty/重新分配标志选择全量Data/SubData，未见写区间账本；修改时必须同时保留buffer创建、容量/位置、dirty消费和GL生命周期。真实缩放采到SubData，不证明所有内容重复，也不证明可省略。图集/纹理局部合成不是当前camera负载的主要已证实热点。
- **判定**：仅STATIC_FEASIBILITY，未实施，非算法失败。直接把全量上传改局部、跳过dirty，或按数组身份去重缺少写者/范围证据，拒绝实施。可继续的具体前提：先只读统计既有buffer与新数据逐位相同的比例；若比例高，再设计不额外持有大型buffer的相等性/更新策略并单独审阅。新缓存必须计入RAM成本，不能只比较GPU命中率。

### P06-a — 加载峰值：对齐现有RSS/heap/GC/采样栈

- **验证方法**：rs-p1按RSS最高加载sample的epoch匹配250ms jvm-loading.csv及JFR前后2秒栈/分配事件，不新增宿主轮。峰值2026-09-07T13:00:28.678Z，RSS3.7579GiB；附近heap used571,085,952B、committed2,071,986,176B，刚经过原生System.gc。
- **发现**：附近活跃栈集中于扩展插值、WarpPointRef/变形与deformer palette，采样分配含点引用/向量/列表。堆used已经下降而RSS仍高，说明不能把瞬时占用直接等同于活跃堆或把分配样本当驻留。已有E05/P01正覆盖部分相关分配但未获主指标收益，不因此原样重试。
- **判定/原因**：INCONCLUSIVE，峰值归因仍缺少具体存活/驻留来源及并发在途量；未找到可安全缩短的已证实中间结果生命周期。未调整加载并发、heap上限或强制GC。允许继续的变化是存活对象/驱动驻留归因或明确的加载队列时序证据，不是单纯降低分配数字。

### P07-a — 内部点Warp纯计算快路径：离线数值通过，但更慢，拒绝产品Hook

- **新假设/区别**：P03-e实际缩放CPU leaf为`warp.o.a`。其原生方法约3395字节，外部点外推占大部分字节码；尝试将内部点的双线性/三角插值提取为较小纯Java循环。这不是P01点引用投影缓存，也不跳过模型更新或数学步骤。
- **实现**：`testing/host-validation/experiments/WarpInteriorPrototype.java`，仅离线，不进入产品或辅助Agent。至多131072点/stride2..16，检查数组边界、维度/整数溢出、输出不与grid别名，完整预检所有缩放坐标后才写入；外部/边界/非有限输入回退。保留原生浮点操作顺序、in-place src/out语义，不缓存。未新增Runtime/Bootstrap seam。
- **验证**：Linux JDK17加载精确已审阅JAR的纯warp singleton（不是Cubism启动），4000个固定种子case、1,538,058个raw float值逐位相同，双线性/三角、offset/stride、原地输出及拒绝不写入测试PASS。JAR hash运行时验证。Java语法diagnostics0错误。随后相同MethodHandle调用，64/1024/16384点，预热5000轮，7轮交替测量，计入全量预检成本。
- **结果**：native/candidate中位ns每点分别20.0845/22.0542、21.0004/25.5399、13.8448/17.9026；候选分别慢9.8%、21.6%、29.3%。`NO_OFFLINE_BENEFIT`，不增加产品hook，不进行无意义实机A/B。不是用户端CPU占用回退测量，也不是RAM/GPU结论。
- **可能原因**：完整准入扫描额外遍历输入，成本超过缩短冷分支代码的收益；原始JIT已能有效处理内部点分支。没有通过移除安全预检、忽略alias或部分写入后不安全回退来制造快样本。原因是与机制一致的解释，不是完整汇编归因。
- **复现**：`javac --release 17 -d build/native-followup/prototype testing/host-validation/experiments/WarpInteriorPrototype.java`；Linux JDK17 `java -Djava.awt.headless=true -cp "build/native-followup/prototype:<reviewed5302-install>/app/lib/*" WarpInteriorPrototype "<reviewed5302-install>/app/lib/Live2D_Cubism.jar"`。全部7轮数值保留`build/native-followup/warp-interior-offline.log`。
- **重试条件**：需要新的算法/可证明不需重复扫描的调用契约或真实输入分布；不原样重复该guard+双遍历实现。另一个排序矩阵候选仅完成字节码阅读：getSortingZOrder取首顶点经父链矩阵再camera矩阵的Z，直接用位置Z或跨帧缓存会破坏父变换/相机语义；未实施，不称为失败。

### 本轮阶段性收口与下一准入点

- `checkCompletedCommit checkResourceValidationBundle checkCubismHostValidationArguments checkPreviewBundleLayout` BUILD SUCCESSFUL，2m24s、213 tasks（29 executed/184 up-to-date），日志`build/native-followup/completed-commit.log`；不会把缓存任务说成全部重新执行。另14项采样器测试、3项准入烟测及P07差分/微基准按各段记录。新增Java/Python diagnostics均0错误。
- 五方向首轮调查已逐项记录，但**没有新增可推荐的三指标优化**：P03找到真实CPU更新热点，P04确认Full GC后仍有引用但未定位所有者，P05仅静态契约调查，P06仍归因未定，P07新原型更慢所以不接Hook。不能宣称所有可能优化已穷尽。
- 下一步最有价值的内存证据是GC-root/持有链。完整堆快照可能含模型和宿主敏感字符串，且占用GB级本地空间；在获得用户对这种敏感诊断范围的明确确认前不采集、不上传、不清未知缓存。继续遵守task-owned官方启动/原件不变/不合并main。

### I10 — 收口时出现新的其他任务会话，暂停下一实机轮

2026-09-07T13:51:31Z只读发现新的非本次任务JVM `2299028/startTicks10933256` 和wineserver2298877，prefix属于另一工作区的`heavy-dinosaur/build/texture-host-szx2a9uu/prefix/pfx`，不是本任务native-resource族。没有连接或清理该会话。按/proc启动ticks估算其启动为13:43:09Z，在本轮全部native-resource会话及P07离线微基准之后（后者日志birth13:38:11Z、mtime13:38:17Z），没有据此倒写早先性能轮为并发失败。下一实机轮需重新等待宿主空闲；堆持有链诊断另需上文敏感采集确认。

- **敏感诊断授权已获得**：用户通过结构化确认选择“允许本地堆快照”。范围仅本任务隔离进程，可能含模型/宿主敏感字符串、GB级空间，仅本地分析、不上传、诊断结束删除。授权不包含其他任务进程、不允许清理未知缓存，也不改变实机安全规则。2026-09-07T13:58:24Z重新检查，I10的两个其他任务进程仍存活；尚未实现或采集堆快照，等待宿主空闲。


### I11 — 接入 main 统一队列（2026-09-08，离线整合尚待完整门禁）

- 用户选择将完整 main `951b6b97f855830684a39f8365064da05e6e5ffd` 合入当前性能分支，而不是只摘取管理器；起点为干净的`98b28395b`。仅在原worktree执行`git merge --no-commit --no-ff`，不反向合并main、不push、不启动宿主。六处文本冲突已解决。
- Runner、队列、transport及其参数测试采用main版本，不恢复旧SSH/lease/PID清理路径。插件隔离测试采用main的Future完成态等待；图表测试保留本分支实际字体ink-width断言；mapping采用main的DRAFT记录，不由此宣称readiness。保留子shell默认值回归，移除main已删除的SSH_KEY变量断言。全部性能候选代码及历史证据保留，默认关闭策略不变。
- 验证：`bash scripts/test/check_host_validation_scheduler.sh` PASS（11 scheduler、43 queue、12 evidence tests及配套shell checks）；local transport和arguments脚本PASS。日志`build/main-integration/scheduler.log`。headless `./gradlew --no-daemon --max-workers=1 devCheck checkCompletedCommit checkResourceValidationBundle --console=plain`已通过devCheck，但600秒工具超时中断在FX插件测试处；没有完整BUILD SUCCESSFUL，不能把提交门禁或resource bundle记为通过。日志`build/main-integration/gradle.log`，无遗留Gradle进程。
- 构建前共享队列idle且无非终态job；超时后只读复查发现另一任务`65687483-9d9e-47a3-9a75-fdd1e45ccf8b`（`atlas-final-geometry-2500-native-4bb38d8073cf339b`）正在运行，立即暂停后续构建。该任务在本次构建期间入场，可能存在测量干扰；不能假设其拥有全程安静环境。未连接、取消或清理它。
- 接入状态：代码冲突解决但合并尚未提交；等待测量结束后利用Gradle缓存继续剩余离线门禁，失败才针对修复。性能memory sampler自定义hook尚未完成队列dependency inventory准入，保持blocked；本次不开发Runner、不旁路、不新增实机授权。旧实机数据仅适用于原artifact hash，新整合产物需要独立获授权的验证。


### P08/P09 — 旧调查对照与Paseo只读复核（2026-09-08）

- **范围/验证方法**：两名Paseo pi/Luna max只读探索；父代理复核身份、报告摘要/hash及关键源文件。目标为待整合worktree（HEAD98b28395b + MERGE_HEAD951b6b97），不是main启动cwd。无代码实现、构建、实机或新性能测量；静态结论不能当作收益。父复核记录`/tmp/turboism-explore-review-20260908.md`。
- **P08 内存候选：Turboism自身的模型引用生命周期**。`EditorBackedCubismModelAccess.java:82-85,310-320,372-387`保存强document/source/model；无活动文档时binding先抛不可用，未清这些槽。该逻辑在合并前已存在。连接保留modelAccess；`VerifiedHostAdapterConnector.java:944-958`关闭UI/layout/Core而未清editor access。调用方保留的stale SessionModel也可能保留delegate：安全拒绝调用不等于释放引用。Core backend完整close已有清除借用引用的路径，不应误报整个Core缺少清理。**结果：发现可核实的强引用候选，不是已经定位实机GC root或证明整套图像仍占内存。**
- **P08 下一验证**：先用离线fixture复现bind A→无文档、owner仍存活，检查Turboism槽、stale wrapper和连接清理；随后才冻结失效/释放机制，覆盖在途调用、lease、Undo事务、同ID替换和失败重试。禁止dispose官方对象、删除host字段或靠失效包装器可调用制造通过。实机仍需队列准入和场景授权。
- **P09 CPU候选：重复快照读取**。父复核`CubismFacadeImpl.java:745-764`和`HostSessionSnapshotSource.java:42-91`：先生成runtime快照再读token；activeModel再读document，token再次读project/document。`HostSessionIdentityRegistry.java:18-45`弱引用registry每次清理和查找均线性扫描。**结果：重复工作结构存在，实际调用频率、CPU占比和RSS收益未测。**下一步用带计数fixture测稳定版本重复查询、扫描数和临时分配，任何缓存须保持一致的snapshot/version、权限、失效和常驻内存上界。
- **排除/降级**：旧tool/agent选择事件类不在当前runtime；当前live snapshot source返回EMPTY_SELECTION，不能照搬旧同步selection lag结论。GPU已有dirty/reallocation分支，不是无条件全量上传；JFR native sample不是调用次数或GPU耗时。继续沿graphics3d而不是影片轨jp.live2d管线归因；不重试既有负收益缓存/投影/内部Warp原型。
- **报告状态**：内存agent8283d169报告53行/11662字节/SHA256100a391ca8aea28958beb97bac908793725e44e4589377019fb04b3dde028f6c，父裁决ACCEPTED仅限静态范围；CPU/GPU agenta727d4a9原报告48行/11223字节/SHA256a4a1ccd94d94bd896c7826ba42e6655d1b4aba20a195172d5c68cd2ead46c824终态标记格式不符，CORRECTION_REQUIRED，已要求新task/artifact修正而非覆盖原报告。上述P09关键调用链由父独立复核，不依赖未验收报告作收益结论。
- **协议修正已回收**：CPU/GPU新task`turboism-explore-cpugpu-20260908T0610-r1`报告52行/12353字节/SHA25689fe384726cf0fb280282d30a72f20257255d9f51d13edf4f75ba78a62f98bd3，首末标记及RESULT摘要匹配，diff仅修正身份/协议并强调未测CPU占比，原报告保留。父裁决ACCEPTED限静态候选；不据此批准实现或实机，不认可任何尚未测量的收益。


### P08-a — 离线复现代码准备，尚未执行

- Paseo agent8283d169按冻结契约准备两项test-only characterization：bind A→无文档后检查三个owner槽是否仍保留；bind B后检查owner替换而旧wrapper仍失效且保留A。反射仅观察Turboism私有字段，fixture static状态finally恢复，无GC/睡眠/内存数字推断，无生产修改。
- Artifact `/tmp/turboism-p08-repro-20260908T0620/diagnostic.patch`（SHA256fe439c13672c26cdfe2e5cbb9317657f236dabbc29e43a8c9f347dbda8d3c157），仅对现有EditorBackedCubismModelAccessTest增加76行。报告35行/7128字节/SHA25683ebd800f2b264bc3cecd3a2799bff2c5b2cacfe2cabf489737a41e58cbbce25。父已核验身份/摘要/稳定hash、阅读全部patch并独立`git apply --check`通过。ACCEPTED仅指准备产物可应用，未应用checkout，**编译/测试NOT_RUN**。
- **重要归因限制**：原NativeResourceHostAgent自身走native反射，并未直接调用Turboism SDK binding；没有证明原实机轮触发过这些slots，故此候选不能直接解释之前的weak-reference未释放。下一实机归因必须同时记录实际绑定路径和持有链。
- 两个测试是当前行为诊断，不将保留引用永久固化成产品契约。未来修复后的回归应检查正确生命周期释放，并继续拒绝stale引用。未加入复杂连接fixture或生产失效seam。
- 复查共享队列job65687483-9d9e-47a3-9a75-fdd1e45ccf8b仍running，继续禁止构建/测试干扰。待空闲先完成整合，再应用/执行最小headless batch；使用`env -u DISPLAY -u WAYLAND_DISPLAY JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./gradlew --no-daemon --max-workers=1 :runtime:test --tests dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccessTest`，不把Gradle客户端单独-D开关当测试JVM已headless。没有新性能收益或实机PASS。


## 原生内存优化候选登记（2026-09-08，用户新调查）

目标是改善Cubism原生内存/CPU/GPU，不仅消除Turboism自身开销。P08/P09降为辅助排查。本批尚无新增运行时收益；用户提供的是方法级字节码机制，下面明确区分待复核、离线验证和实机收益。父已重新读取精确5302安装JAR计算SHA256：988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21。当前实际源目标为性能任务worktree（本机绝对路径保留在私有报告的身份记录中），不将旧路径或5.3.03材料当成本机身份。

### N01 — 已释放使用者的诊断强引用（优先1）
- 机制（用户字节码证据，待独立复核）：CImageResource.releaseResource先把retain记录追加到releasedRetainUserData_forDebug再从retainCounter删除；CImageResource$c持有真实ICImageResourceUser。CModelImage.reinit共享_filteredImage，已dispose使用者可经活资源反向保留模型关联。
- 拟议实现：只让已释放诊断历史不再强持有真实user，保留有界值摘要；有效retainCounter、释放/销毁条件和当前图像均不得变化。不预定具体变换策略，先核验全部诊断消费者、同步和失败路径。不能修改已加载类字段结构，不能修改官方工件。
- 验证：精确class/method/exception-table/所有读写点；离线共享资源A/B、释放A而B继续使用、重复/错误释放及诊断调用兼容；检查优化引入的外部状态不反向强持有资源/user。之后单项开关对照及GC-root/存活量/私有内存证据。
- 有效程度：候选机制尚待本轮独立复核；未实施、未运行、无节省字节/比例；不能称为每次操作泄漏整模型。失败/停止条件：诊断记录参与正常所有权或无法保持所有消费者兼容。重试需新的完整契约/调用证据。

### N02 — UtCache获取阶段异常漏还（优先2）
- 机制（用户证据，待复核）：空闲条目SoftReference但usingMap强键持有借出图像；HQ合成四次获取在try保护区之前。第2/3/4次获取失败可能遗留此前成功取得资源，延迟检查只警告。
- 拟议实现：以调用为单位记录成功获取资源，异常路径仅归还本次资源，原异常仍传播；正常finally不可双归还，嵌套/并发调用隔离。禁止usingMap.clear；不以全局OOME注入替代有界离线故障测试。
- 验证：核验描述符和异常表、获取/归还契约、同对象重复获取可能性；离线在第1/2/3/4次获取分别注入失败，检查净借出量、无双归还、正常结果、嵌套/并发和清理再抛异常。实机故障场景另需明确授权及队列准入。
- 有效程度：条件性安全/内存候选，未实施/未复现；正常路径的节省可能为零，不能据此解释日常高RSS。失败/停止条件：无法证明借用所有权或原生已有上层补偿；据反证降级而非强做Hook。

### N03 — HQ整页临时图工作集与并发字节预算（后续）
- 机制：8192² ARGB目标256MiB + 同尺寸彩色临时256MiB + 灰度临时64MiB = 576MiB仅理论像素工作集，不是RSS或实际同时存活值，未包含源图/旧atlas/GPU。
- 拟议实现：先按真实图像格式/尺寸统计活动任务字节，限制重任务并发和可回收空闲大图缓存；不清正在借出的对象。再评估局部区域合成，覆盖旋转/双三次边界/透明边缘/源图原地修改。
- 验证：真实租借时序/高水位，串行与预算调度的峰值、总时长、CPU/GPU；像素差分与保存重开。ROI必须完整语义证明，不能裁矩形即认定等价。
- 有效程度：理论工作集和候选，未实施、未测；预算可能降低峰值却增加耗时；ROI风险高。已有Atlas其他任务由其owner管理，本任务不抢写或启动其场景。

### N04 — PSD/Undo/历史对象保留归因（调查，不默认清理）
- 检查psdDoc/psdBytes/layer tree/icon和合法Undo/重导依赖；dispose方法不置空不足以证明泄漏。当前EditorHistoryMetadataRegistry弱键，不套用旧createUndo_forAllEdit结论。
- 拟议验证：对象持有链与可重建性、保存/重开/PSD重导/Undo/Redo。禁止删除历史、盲目置空PSD或仅凭isReplaced释放。
- 有效程度：尚未确定违规保留者，无实现收益；若属于合法业务保留则不修复。

### N05 — PNG归档复用/编码临时分配（已有方向，不重复计数）
- 对应本账本既有PNG reuse实验：受控重用可减少编码/分配，但此前自然工作负载未证明足够命中；不从零重复。archive已有解码缓存，不能宣称每次get都解码。
- 后续仅在新的实际归档工作负载和完整像素失效契约成立时重试；imageFileBuf非空不充分，HQ源图可能原地修改。优化toByteArray副本需确认编码API所有权。
- 有效程度：沿用既有受控有效/自然收益不足证据，默认关闭；不是新增省内存结果。

### N06 — 内存归因探针（测量基础，不是省内存优化）
- 拟观察JVM used/committed/GC后存活、诊断历史数与保留链、池借出/归还/空闲字节、图像工作集、进程RSS/PSS/私有内存、direct/GPU资源。指标重叠不能相加；JVM Non-Heap不是全部堆外，NMT不覆盖全部第三方分配。
- 探针约束：有界、值类型摘要/弱身份、不自行强持有模型，量化采样开销；已有进程CPU/DRM/RSS observer尽量复用，先完成dependency inventory准入，不开发第二套Runner。离屏渲染深度/区域仅待验证线索。
- 对照：原生Cubism、当前Turboism全优化关闭、单项开启；同fixture/操作/测量条件重复，诊断instrumentation单独校准。Windows与Proton分开，不以单次RSS或累计分配宣称占用下降。
- 有效程度：尚未新增该探针；有助归因但自身可能增加开销，不能列为优化收益。本批不包含新增宿主/故障注入授权。

### N01/N02 本轮独立核验与方案收窄（2026-09-08）

状态分层：下述是静态机制确认，不是已实施修复，更不是实测收益。内存占用、CPU占用、GPU占用均为 NOT_MEASURED；不填估算百分比冒充测量值。

- **N01 核心机制 VERIFIED_STATIC**：父复读 `build/native-image-resource-javap.txt` 的 releaseResource（1081–1180行）、finalize（640–689行）及 `getDebutInfoAboutRetainAndRelease`（1838–1937行），并复核当前安装JAR及class ZIP项SHA256。成功释放分支确实在BCI126/131读取/追加released历史，随后才删除active记录；不是DEBUG-only。`CImageResource$c.a`是final强user字段，构造器拒绝null，`hashCode()`直接调用user.hashCode()。因此“直接将user置null”不是可接受方案。
- **N01 诊断兼容性边界**：原生finalize与getDebutInfoAboutRetainAndRelease都读取released历史；保留active/released区分、标签及其顺序需要单独验收。原生记录还有equals/hashCode/toString，不能把released列表元素直接换成String。仅限制历史条数或去掉stack不能彻底切断其余记录对user的强引用，属于有界缓解而非完整修复。
- **N01 候选实现比较**：①跳过历史追加最窄，但丢失原生诊断，暂不采用；②只对已释放记录使用脱离宿主的摘要，优先研究，但必须保留原生诊断入口并确定容量/截断标识；③弱引用历史可允许回收，但仍须处理已回收后的诊断语义；④外部状态不是天然安全，键必须不强持有resource，值不得间接引用resource/user/model。②③④都尚未实现，不预设需要改变已加载类字段结构。有效retainCounter、相等比较、销毁条件和顺序不得改变。
- **N01 有界验证设计（NOT_RUN）**：资源R由A/B共享，释放A后B仍可用且active只含B；重复/错误release维持原生行为，最终释放B只产生原生应有的销毁；开/关调试条件下比较诊断标签、次序和缺省stack；单独验证探针/摘要无反向强引用。retain/release未见方法级同步，不能声称已有线程安全，需刻画并发或限定经过证实的线程边界。动态反射消费者和实际GC-root仍未验证。
- **N02 核心窗口 VERIFIED_STATIC（父独立复核）**：新dump `com.live2d.util.f.g.javap.txt` 1251–1300、1515–1547行，目标描述符 `(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics2D;Ljava/awt/image/BufferedImage;II)V`；四次获取22/39/56/71，存入locals7/8/9/10，catch-all覆盖[76,537)，不覆盖获取。清理四个资源后重新抛异常；不能说完全没有finally。
- **N02 新边界：获取函数内部也非事务式**。父完整读取 `jp.noids.util.UtCache.javap.txt`：新建路径先在BCI196 usingMap.put，随后BCI202 cache列表add，并可能创建延迟检查任务，到228才return。因此若插入后/返回前失败，调用方尚未取得引用；仅补调用方finally不能保证回收该次未返回对象。这是另一个条件性窗口，不宣称已复现或日常主因，也不将全局map差分清理作为修复。
- **N02 方案分层**：N02-a先保证先前“已成功返回”的局部资源在后续获取失败时归还；N02-b另行研究池内部取得/登记/返回的事务边界及锁内精确回滚，不假装N02-a涵盖它。原release先移除usingMap键再标空闲；正常finally和新增异常处理不得双归还。归还函数自身抛错时需继续尝试其余本次资源并保留原异常，具体策略需独立设计，尤其不得为构造suppressed异常在OOME路径无界分配。
- **N02 验证矩阵（NOT_RUN）**：第1/2/3/4次获取前注入失败，先前成功数分别0/1/2/3且最终净借出回到基线；全部成功/绘制失败、归还失败、嵌套/并发分别验证，无双归还、不触碰其他调用；另设池内部“登记后返回前失败”作为N02-b，未实现时必须明确失败/不覆盖。使用离线可控故障，不进行全局内存耗尽或未授权宿主异常注入。
- **可复核身份**：本轮父使用Python `ZipFile.read('<class>.class')` + SHA256（仅解析，不加载类）；官方JAR仍为 `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`。CImageResource=`0b7e56b4b3a1baa314daa6266b2f36f29fb7cab679f51afbe19490ce77a69c14`；$c=`048f08db85203901d8eb4958a18cfa63e3268c601e2f383c3db6b1d897e1628b`；HQ g=`ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6`；UtCache=`64193febe864958bf0ae6b701a75a38cfd09588f1e549d910eb968159e6c2db1`。
- **证据位置**：N01原报告 `/tmp/turboism-native-retain-20260908T0700/report.md`（43行8550B，SHA256 `d73f12447af344f052510a6b735bbbcbf9c8c984f5d69feacb846d9e74e62d86`）判定CORRECTION_REQUIRED：终态标记未带任务ID、摘要方案必需改字段结构表述过强，已要求新ID不可变修订，核心静态事实不因此升级为生产PASS。N02父复核dump位于 `/tmp/turboism-native-pool-20260908T0700/`，HQ dump SHA256 `21012485429e5dc620abb422a4c31d455f7d1f6e9fd5339312fa298f96faf4d8`，UtCache dump SHA256 `bf8c6bf6ed4b4a764da2b780c2d547819d23b788267c765486fb4b6eff066165`；子代理最终报告尚未回收，不冒充验收完成。
- **执行限制/失败记录**：对子代理a727d4a9的收口消息发送返回SEND_FAILED（active run cancellation未获确认），其状态仍running；未强行替换或停止进程，180秒等待超时不是分析失败结论。主工作区不写实现、未启动宿主/构建，未改变已运行worker。当前阶段只修改实验账本，main整合门禁与提交仍未完成。
- **N01 修订验收**：新报告 `/tmp/turboism-native-retain-20260908T0720-r1/report.md`（41行7272B，SHA256 `56b9451c8877b44b9d32edfab7abd36228accab7e23e1a66868960a2482a2141`）。父完整读取，核对Paseo身份、首尾同任务ID、稳定hash/字节数及RESULT一致；此前过强方案表述已修正。ACCEPTED仅限有界静态机制及风险报告；生产修复/诊断兼容性/三指标效果仍BLOCKED或NOT_MEASURED。
- **N02 代理异常后续**：其后wait返回 `stopReason=error`，inspect确认error且无报告；不把子代理任务记为完成。创建进度项#28，使用同一代理/模型派发新ID `turboism-native-pool-20260908T0730-r1`，只从已有dump及账本回收<=80行报告，禁止扩展检索/构建/实机操作。原始0700证据保持不变。
- **N02 回收与父裁决**：已完整读取r1报告 `/tmp/turboism-native-pool-20260908T0730-r1/report.md`（35行4578B，SHA256 `dc4f3d3677d06bef56305a901e06209134c1ce0e539792eb8dcb914c18caf0a5`），前后Paseo身份/首尾task_id/RESULT/hash一致。ACCEPTED仅限父已独立复核的获取窗口、强键及顺序归还事实；其他生产问题仍BLOCKED/UNKNOWN。报告N02-b标题泛指所有权/清理，本账本继续严格以N02-a=调用方已返回资源、N02-b=池内部登记事务窗口区分；“第1次失败无lease”只适用于调用方此前成功资源为0，不排除池内部已登记未返回资源。
- **N03 算术扩展（非新增实测）**：仅在source与target各8192²、彩色各4B/px、灰度各1B/px且像素存储独立时，四张scratch理论640MiB；加existing target为896MiB，再加existing source为1152MiB。后两个小计已分别包含目标/源像素，不能重复加；未包含对象/raster开销、旧atlas、其他池图、原生/显存，也不是并发峰值证据。较小source、池复用/超尺寸、别名都会改变实际账单。禁止以此推算“能省1GB”。
- **回收过程限制**：N02-r1报告称无scan，但父wait摘要显示做过/tmp文件名find/grep定位；故不接受其“只读指定文件、无scan”的绝对执行声明。未观察到新宿主/构建/JAR操作；已有dump事实由父复核，不依赖该自述。保留原报告与这一偏差，不覆盖痕迹或再开广泛探索。
- **共享门禁状态更新**：2026-09-08本轮末通过main本机 `python3 scripts/preview/host_validation.py status --json` 读取到workerOnline=true、host.state=idle、job_id=null、activeJobs=[]。原65687483已终态cancelled；本任务未调用cancel或清理。解除旧quiet-host等待，但这不是新增宿主场景授权；整合门禁尚未重跑。文档 `git diff --check` PASS，不等于实现或实机验收。

### N02-a 离线控制流原型（2026-09-08续推）

- **实现/边界**：源码保存于 `experiments/native-image-lease/NativeImageLeaseCleanupPrototype.java`，README列冻结范围和复现命令；这是JDK-only假池实验，不接触Cubism类、图像或原生安装。不计为新生产优化，不接入Gradle默认构建。控制组保留“4次获取在finally之前”的拓扑；候选以4个局部变量记录成功返回资源，异常路径逐一尝试归还。已存在primary时保持同一异常对象，否则尝试全部清理后抛首个cleanup异常。不创建lease集合或suppressed数组；生产中的次级错误诊断仍待设计。
- **如何验证**：共享队列确认idle且无activeJobs后，使用JDK17.0.20，`javac -d <task-classes> NativeImageLeaseCleanupPrototype.java`，`java -ea -Xmx64m -cp <task-classes> NativeImageLeaseCleanupPrototype`。源SHA256 `1a18c6222cf943f0a630a68b641986b67f1b3f098f685265f90d76622655cc64`；输出SHA256 `2aaff0c25ecfc2163e45038eafc07fd98c9ad3afa73d45a20127ea6495fc82ea`。本机原始source/result.log/toolchain.log在 `/tmp/turboism-native-lease-prototype-20260908/`；仓库源码与已执行源码逐字节相同。
- **结果**：PASS，500次断言检查（非500个独立用例）。第1/2/3/4次获取前失败，控制组遗留此前成功资源0/1/2/3个，候选为0；覆盖RuntimeException与人工Error、绘制体失败、每个清理位置失败、正常/外部借用/嵌套调用。确认不跳过后续清理、不误还其他调用、原异常identity保留。
- **明确未解决/失败方向**：登记后返回前失败，候选仍遗留1个，反例测试专门断言它不会被调用方修复；归还在移除登记之前失败，候选同样仍遗留1个，不能把“attempted”当“returned”。不做不知提交状态的盲重试。原控制组首个清理异常会替换body异常并跳过后3项清理，这也在原型中复现。并发、VM致命错误、原生字节码变换/验证器、诊断兼容、图像/Undo/保存重开均NOT_TESTED。后续需分别实现和验证N02-b事务边界，不用全局clear兜底。
- **有效程度**：只确认候选控制流在假池模型下处理已返回资源的能力；RAM/CPU/GPU均NOT_MEASURED，未证明实际高占用主因，正常路径的省内存可能为0。只有原生变换测试、获授权队列实机对照和对象保留/进程指标完成后，才讨论产品收益。

### I12 整合门禁续跑与暂停（2026-09-08）

- 本轮先重新读取全局协作规则/架构/新版调度README，确认主Agent可直接实现，不再把旧委派-only约束误当当前规则；Paseo MCP无工具，CLI实际daemon/provider检查可用，本轮未新派代理。CodeGraph仍无本worktree索引，未在他人测量期间进行全仓索引，定点文件检查不冒充图分析。
- main在951b6b97之后到83a49168的增量已只读审阅：4个文件，仅服务示例环境路径、说明和独立stub回归测试；未切换main worker、未安装服务。性能worktree仍是HEAD98b28395b+MERGE_HEAD951b6b97，尚未提交这次整合。
- 门禁命令：headless环境、`./gradlew --no-daemon --max-workers=1 devCheck checkCompletedCommit checkResourceValidationBundle`。首次本轮续跑约61秒，`checkRepositoryHygiene`因本账本330行机器绝对home路径失败；已改为可移植的“性能任务worktree”引用，原始精确身份仍保留私有报告，没有放宽hygiene规则或删除失败证据。结果位于 `build/main-integration/continuation-20260908/{gradle.log,result.json}`。
- 修正后重试在约10秒时观察到新任务c44666b4进入queued，任务级构建守卫停止了本次Gradle进程组（exit143，guard75）；当时host仍idle。它只监测队列并停止自己创建的构建，不启动/取消/清理任何Cubism任务，不是第二套宿主Runner。原始失败和重试日志使用不同目录，重试结果在 `build/main-integration/continuation-20260908-retry1/`，未覆写首轮失败。随后只读检查本worktree无Gradle进程残留。
- **当前结论**：整合门禁仍未完成；不能commit整合或宣称接入验收PASS。原型PASS、文档卫生修正和main集成是三种不同状态。需取得足够空闲窗口继续必要门禁，而不是在持续到来的实机测量间反复启动完整构建。
- **局部收口检查**：直接复用现有 `scan_repository_content` 对本账本及新增原型Java/README三个文件检查PASS；未修改扫描规则。仓库原型与已编译执行源逐字节一致，ReadSeek Java诊断0错误，`git diff --check` PASS。这只确认局部修正，不替代尚未完成的整合门禁。

### N06/I13 实机授权后的实际准入检查（2026-09-08）

- **授权**：用户明确“允许实机，继续推进”。可开展本任务隔离验证，但不授权绕过管理器inventory、清理外部会话、全局OOME或覆盖官方工件；本轮尚未submit/启动任何宿主。
- **最新门禁**：续跑retry2已通过仓库卫生并到达 `:devCheck` 完成；约75.6秒管理器报告external-busy（外部Cubism PID1024491），构建守卫只停止本次Gradle，exit143/guard75。`checkCompletedCommit`、`checkResourceValidationBundle`仍未完成，不宣称整合PASS；日志保留 `build/main-integration/continuation-20260908-retry2/`。守卫此前错误地把历史timed_out当active，本轮按管理器实际TERMINAL集合纠正；host非idle仍始终阻止构建，不以终态过滤放行external/quarantine。
- **工具整合身份**：性能worktree的host_validation.py、host_validation_queue.py、host_validation_containment.py、通用Runner和transport五文件与当前main逐字节一致；使用的是已接入的新队列工具，不是旧SSH/lease路径。main worker和主工作区工具均未修改。
- **实际prepare结果**：完整读取resource能力README/wrapper，按其fixture hash执行dry-run。首次缺失本机env配置失败，使用已有私有env后dry-run成功。默认目录无native-resource项，使用CLI支持的task-scoped `--manifest`明确指向现有resource wrapper，声明host-slot/performance-host；未改变共享catalog。随后真实执行 `host_validation.py --manifest <task-manifest> prepare native-resource:5302 --run-label nm-authorized-admission`，明确失败exit2：`custom hook requires reviewed dependency inventory`。未返回prepared ID，未submit。manifest/prepare.log/完整评审在 `/tmp/turboism-native-memory-admission-20260908/`；此为准入拒绝证据，不是实机运行失败或产品收益。
- **inventory 已逐文件读完**：start-task-memory-observer.sh=`00024a1cc6a72c39df10dff446e840289930443d642a595b5018f305a8759b29`；measure-task-memory.py=`da54d8b35d99c2b1d6eeea00759ef4f7d84f6ed80042d49e0a4aec659738a3ac`；host_resource_counters.py=`d0eaf3bcdbf7a01e8cf95716430566f70bf233d734c96f63b19699c9ede22c67`；host-task-processes.py=`e49b52003a763861f3bb3eaf3e19a3498299b8ee5ee63a220072f2b61e4bd603`。运行依赖bash、PATH解析python3及stdlib，后两项动态导入模块的staged路径/fallback需明确固定；不是仅把顶层shell加白名单。
- **发现的接入风险**：采样器只调用read_process/tagged/verify_task/properties，但导入的host-task-processes.py同时包含旧PID/process-tree清理CLI与signal函数。正常采样不调用它们，不能误报本轮已杀进程；同样不能未经评审把完整旧清理模块带进新hook闭包。当前观测身份是PID/startTicks/UID+prefix/environment筛选，不是队列绑定cgroup身份；不得当作清理授权。shell自行fork观察器、写pid文件，退出/超时/输出路径与snapshot篡改还需覆盖。
- **拟议最小接入方案（未实施）**：抽出不含任何signals/cleanup入口的只读身份依赖，固定解释器与完整helper闭包，绑定管理器认可的任务/进程身份，保留歧义/重用/不可读的fail-closed；在既有管理器中申请窄范围memory-observer inventory准入，验证后台生命周期和失败路径。不开发另一套Runner，不把脚本伪装成FPS hook，也不移除采样器冒称三指标协议不变。此涉及此前暂停的管理器相关工作范围，需要明确由本任务补齐还是由管理器维护者接入。
- **有效程度/后续**：新RAM/CPU/GPU实测均NONE，N01/N02尚未接入生产。180秒查找+900秒采样、约1Hz及DRM客户端数据不是硬字节上限；readDurationNs不含全部身份发现开销，不能当作探针总成本。只有inventory通过、构建产物门禁完成且外部会话正常结束后，才可提交获授权的基线；授权本身并未解除这些技术阻塞。

### N06/I14 只读身份提取实施与局部验证（2026-09-08）

- **范围裁决**：用户选择“本任务补齐准入”；限性能worktree提取纯只读依赖、既有队列窄准入和生命周期测试，未授权新Runner或main合并。Spec Kit 023的spec/plan/tasks已冻结，main上Atlas的feature指针保持不变。
- **门禁中断保留**：此前retry3只续跑`checkCompletedCommit checkResourceValidationBundle`，约26.6秒发现外部Cubism PID1113591，守卫只停止本次Gradle（143/75），日志`build/main-integration/continuation-20260908-retry3/`。两门禁仍未完成。此后只读status曾恢复idle，不代表预留宿主空闲窗口。
- **实现方案**：新增`scripts/test/host_memory_identity.py`，不含launch/signal/cleanup或环境匹配。只绑定观察器已继承的管理器scope，持有目录FD并检查device/inode、boot ID、观察器PID/start/UID；只枚举该scope的cgroup.procs，读前后检查Editor身份，多个Editor、移出scope、PID重用、不可读身份均拒绝。FD在失败和退出时释放，但绝不清理scope。
- **采样接入**：measure-task-memory.py改为仅加载同目录纯helper，不再fallback到旧清理模块；在每次采样前后检查绑定身份，样本附scope元数据。RSS/PSS/private、CPU与GPU字段/不可用语义及原300秒协议保持。readDurationNs现在含本次sample的身份检查，仍不等于完整探针成本（不含启动hash/发现/JSON写出）。
- **验证方式/结果**：先写`test_host_memory_identity.py`，未实现时真实失败FileNotFoundError；实现后12个隔离文件系统回归PASS（0.077秒），覆盖单Editor、外部同名不读取、歧义、scope移出/替换、PID/UID/读取中重用、观察器变化、缺失身份、退出及FD关闭。另8个sampler focused tests PASS（0.012秒），包括只读取测试自身进程的真实RSS/PSS、读中身份变化、scope失败写FAIL、不采样和异常关闭FD、单位/完整窗口/证据不覆盖。JDK30秒握手测试本小批尚未重跑；队列准入/完整门禁/实机仍待验证。
- **有效程度**：已验证纯只读身份机制的局部行为，不是Cubism优化；新增内存/CPU/GPU收益仍NONE。未知hook拒绝尚未改动，未prepare成功、未submit/启动宿主。下一步完成固定解释器/三文件闭包准入与负例，随后完整门禁、授权基线，再推进N01/N02原生优化。

### N06/I15 窄准入与受管exec实施（2026-09-08，实机仍待门禁）

- **实现**：现有PreparedStore仅为精确native-resource:5302的memory prelaunch添加闭包校验：限定background、标准上下文、单一解释器参数，恰好三个指定源/目标home-file；拒绝home-dir覆盖、重复/缺失/替代helper、额外hook/client、错误版本/任务/模式。解释器必须是prepare所用Python的解析后绝对路径，按既有hostDependencies保存hash并在command阶段重验；没有新增Runner/通用任意hook注册，也未修改main worker。FPS原准入保持。
- **生命周期**：shell不再自行fork或写独立PID清理文件，而是exec固定Python，`-I -S -B`隔离PYTHONPATH/site并禁止写pyc；由管理器既有background模式追踪退出。hook检查12参数、任务环境和home/evidence一致性。resource wrapper改为固定三文件和解释器。README去掉旧直接执行建议，明确队列prepare/submit与授权/验收区别。
- **验证**：两个新正例在实施前真实失败`custom hook requires reviewed dependency inventory`；实施后PreparedStore整组16 tests PASS（2.127秒），含既有真实Runner的无宿主prepare/replay隔离测试。三项新增synthetic hook测试PASS（0.257秒）：exec PID保持和exit7传播，环境注入的json/sitecustomize未加载，缺纯helper报错且无旧fallback，错误上下文在执行前拒绝。解释器变更负例只改临时伪解释器，未改系统Python。
- **当前阻塞/重试条件**：准备最终门禁时status显示job78d2e1e4 running、40d44ba9 queued；没有启动新的重构建或实机争抢。待队列/外部宿主安静后完成受影响Python/JDK/Gradle批次及真实prepare。局部PASS不等于准入exact-host验收，尚无本轮prepared成功/submit/宿主结果。
- **效果**：这是测量路径安全接入，不能称为Cubism RAM/CPU/GPU下降；三指标新增实测仍NONE。N01诊断强引用与N02异常漏还尚未生产启用，继续保持原记录中的静态/原型证据等级。

### I16 最终批次的整合回归与修复（2026-09-08）

- 等待已有队列任务终态后，status确认idle且无active/queued，执行受管最终批次；未启动Cubism。77个Python tests PASS：身份12、exec3、采样器含JDK30秒握手9、资源计数器7、完整队列46。`devCheck`及此前卫生/宿主参数相关门禁通过；总269.8秒后`checkCompletedCommit`在integration-tests失败（369tests/1fail/1skip），resource bundle和最后policy smoke尚未执行。完整首轮证据`build/main-integration/observer-admission-final-20260908/{gradle.log,result.json}`，不覆盖。
- 唯一失败`MappingPackDraftImportTest.boundingBoxDraftRemainsAnUnverifiedProjectionOfItsStaticEvidence:275`：草稿metadata.artifactSha256缺失，而静态证据为5303固定hash。先比较整合遗漏、生成材料漂移和旧格式测试三个假设；HEAD/main951/main83/当前worktree JSON逐项比较证实，pending main整合覆盖了分支既有014c5a3bf修正：丢失artifactSha256/confidenceBasis并把12项medium变为high，选择器没有变化。
- **修复**：只恢复该草稿原分支的metadata和medium置信度，保留全部测试不动，DRAFT/none/null和静态证据等级不变。解析JSON与整合前HEAD语义完全相等检查PASS。不是新增5303适配/宿主验证；Spec023 T008/T011注明这是保留已有分支修正。后续只续跑失败与未完成门禁，不重跑已通过的整组Python。

### N06/I17 新队列实机基线PASS与三指标结果（2026-09-08）

- **门禁收口**：修复后只续跑`checkCompletedCommit checkResourceValidationBundle checkCubismHostValidationArguments`和3项native resource policy smoke，239.6秒全部PASS；`build/main-integration/observer-admission-final-20260908-repair1/`保留完整日志。既有失败记录不删除，测试断言不变。
- **prepare/submit**：新wrapper dry-run成功，真实prepared ID=`e405a2c4e55d6cbf00de650292097c4f0bf8cb25604cbee4b29492ebbe0011e8`；显式核对三home-file、managed background、解释器dependency、四优化false和exact5302 fixture。由main CLI提交request=`native-memory-scope-baseline-20260908-b1`，job=`4acfbf9d-7dce-41f0-95c8-b8f897598115`，attempt=`79a34502-c568-416e-bcec-98eb55d019ac`，run=`queue-c55e1dfc7da34329a280f1ee1f0b5ddc`。快照记录dirtyFingerprint，不把未提交分支误报clean commit。
- **worker中断/恢复**：submit持久成功但workerOnline=false；通知中的旧Paseo terminal已不存在，main工作区terminal列表为空。用户随后明确选择恢复既有worker。只读核查无同UID exact serve argv后，从main83a49168的独立Paseo terminal `a75facf6-2c61-4ceb-b05a-1dad4e002d1b`启动原`host_validation.py serve`；未修改main代码、安装服务或新建Runner。同一排队job自行进入running，没有重复submit。结束后workerOnline=true、host idle。
- **结构化终态**：manager succeeded/PASS，identityVerified/fixtureUnchanged/normalExit=true；源码模型与任务副本hash=`029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`，官方JAR=`988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`，BAT=`2c767eb3e3640c007f0bead1e54ddfba629fc15493c7ccd8f7aab1a4fc91c334`前后相同，goldenUnchanged=true。wrapper.exit实际内容0；final details.wrapperExit为null的汇总字段不冒充原始退出码。cleanup=safe，kernel原scope绑定证明完整，cgroupKillWritten=false，cgroup自然destroyed，errors=[]，prefixRetained=false。旧observed-not-atomic PID辅助记录不是所有权证明，采用最终bound-cgroup证据。
- **采样和操作**：428个样本，所有PID/start/UID及scope path/device33/inode49080/boot与本次manager绑定一致；sampler complete PASS。60次原生zoom完成，原camera raw scale精确恢复，dirty/Undo未变，300秒JVM观测完整。任务主Java PID1428993；未做产品变换、强制GC、保存模型或真实OOME注入。

| 阶段 | RSS中位/采样峰值 MiB | PSS中位 MiB | CPU整机占比（12逻辑核） | i915 render时间加权占比 |
| --- | --- | --- | --- | --- |
| 加载181.33s | 1109.42 / 4294.68 | 1045.24 | 11.850% | 0.131%（146/160有效区间） |
| 原生idle30.01s | 3080.20 / 3080.31 | 3015.65 | 0.116% | 0.000% |
| zoom82.10s | 3203.66 / 3204.50 | 3139.13 | 9.028% | 2.045% |
| 恢复后idle30.89s | 3204.59 / 3208.19 | 3140.05 | 0.668% | 0.000% |
| 关文档idle120.01s | 3206.60 / 3206.63 | 3142.07 | 0.224% | 0.000% |
| 关文档后续35.76s | 3071.73 / 3202.08 | 3021.43 | 0.094% | 0.000% |

- **如何计算**：按resource-workload/result.properties的epoch阶段边界选择点样本；CPU仅纳入首尾均落在阶段内的相邻tick差/monotonic时间区间，除12给整机口径；GPU复用该次冻结的host_resource_counters.GpuIntervals高水位/客户端去重，只对有效区间按wallNs加权，不把加载缺失的14区间填0。表内idle GPU零来自有效engine计数不增，不是不可用替代值。RSS/PSS/private互有包含，不能相加；GPU为进程DRM engine占比，不是所有进程GPU总量或独立显存占用。
- **重要反例：RSS下降不等于释放**：关文档后末段近36秒，RSS从3202.08降到2807.08MiB，而VmSwap从106.51升至464.79MiB（约+358.29）；主要是换出，并非回收395MiB。全局MemAvailable最低815.36MiB，SwapFree最低0.125MiB，本轮存在明显内存压力。采样RSS峰值4294.68MiB，内核VmHWM4384.20MiB（采样可能漏短峰）。不能拿这一轮与旧机器状态下的结果算优化百分比。
- **堆与保留**：300秒内heap.used1934.83→1022.66MiB，heap.committed始终2688MiB，GC计数48→52；关文档约156秒后的documentWeak仍未clear。used下降/committed不降/RSS换出是不同事实，尚无retained-size或GC根路径，不据此断言N01已留住整个模型或N02日常漏还。探针sample调用耗时中位118.83ms/峰673.58ms、最大采样间隔1.674s；这是读延迟而非CPU耗时，探针扰动仍需留意。
- **复现产物**：`/tmp/turboism-native-memory-admission-20260908/`保留dry-run、prepare、submit、wait、events、offline分析脚本和summary；分析脚本SHA256=`cce1ada6968b9f861dba5ce0fc9ef1806f9cd0f39e7cda11632d0e473fcf120e`，summary=`34cca727c227e6de4349224e107642d6c3709e90a7c251ba26548d603236c1a6`。样本SHA256=`140e52ac17aacd0f246bb401146b442a0e1a69ea1d78bc9c173aebe91af047b1`，阶段结果=`4746ea6e04b05136092ec5c7d4071fd02bb5392441f6fa48182d8a0d862555ce`。原始证据按上述job/run定位，不猜最新目录。
- **有效程度/下一步**：完成新的只读观测准入实机验证，拿到Cubism自身三指标基线；没有新优化收益宣称。N01错误强引用/N02异常资源归还仍是待实现验证候选。先针对原生图像保留链做窄只读归因，再决定优化；后续A/B必须匹配内存压力/交换状态，不能把RSS换出或累计分配下降冒充省RAM。

### I18 分支提交与main增量接入（2026-09-08）

- 已在`feat/cubism-native-performance-20260905`提交`24c6dc21a5e81e6a6c7ffa878c837670d9fe0e2a`，父提交为原分支98b28395与main951b6b97。内容是已验证的main整合、只读观测切片、此前N02纯原型与完整实验记录；不是合并到main。最后15个变更/新文件直接复用现有卫生扫描PASS，git diff检查及提交hook卫生PASS。
- 随后只在性能worktree接入main83a49168余下4文件增量（env示例、service示例、调度README、service示例测试），自动合并保留memory observer窄准入说明，没有执行代码/实际worker配置变更。逐项审阅diff，并运行`python3 -B scripts/test/test_host_validation_service_example.py`：1 test PASS（0.623秒），只在临时目录执行stub，验证带空格/元字符路径及空/缺失/不存在路径失败，不安装服务、不启动队列或Cubism。没有重跑未受影响的整组实机/产品门禁；main工作区及正在运行的worker不变。

### N01/I19 原生保留链的被动观测入口核查（2026-09-08）

- **目的**：为N01归因选择不制造图像、不延长模型寿命的观察路径；尚未清除任何原生记录，也未新增宿主运行。复用既有精确5302 JAR，Linux `javap -p -c -s`只读取字节码，不加载Cubism类。
- **新发现/放弃方案**：`CModelImage.getFilteredImage()`不是纯getter：在字段为空时调用`ModelImageFilterSet.requestOutputValue`，再设置_filteredImage（BCI0–65）。因此放弃借用现有Atlas像素capture helper；它还会getImage/PNG编码，改变被测工作集。被动探针应读取原始_filteredImage，null作为未物化状态而不是触发计算。
- **可用路径**：CModelingDocument.getModelSource、CModelSource.getTextureManager、CTextureManager.getModelImageGroups、CModelImageGroup.getModelImages均经逐方法字节码确认是字段读取。getAllModelImages虽不生成像素，却会新建ArrayList并展平所有分组；弃用这种无界辅助分配，改为有上限的分组遍历候选。
- **释放事实补全**：完整复核CImageResource.dispose/dispose_exe；dispose_exe末尾BCI121–128将两个压缩byte[]字段置空，然后return；该方法没有清空active/released记录列表。dispose会移出soft cache并处理像素资源，但不能据此声称诊断引用消失。相关资源整体不可达时仍可回收，这不是整模型泄漏或实际retained bytes的证明。
- **证据**：私有`/tmp/turboism-native-retain-observer-20260908/`保存四个新javap文件。CModelSource.class SHA256=`55692c69382655a14142a6fc3dab1da78862d67180c6e7cbbe0c22e8253085d1`，CTextureManager.class=`e8cbde3360fc781b0e2fe3806b120dc73c74102fd7f173fde104f057b8548a5a`，CModelingDocument.class=`7ca4f69d9304e38d15adf0cce4750873b3d9325cac296a02e617d5c491d72188`。原CImageResource javap全文SHA256=`e8b2ad56aa5b4fd4a5b8742511658332759ad601cb56be10cfc37439613b03a1`。
- **后续实施约束**：仅测试aux、精确版本校验、EDT有界瞬时遍历、identity去重、标量报告；跨阶段只留弱引用。并发变化或截断必须显式标记，不能误报零。released记录中不在当前active集合的user只是候选，不自动等同无合法引用。不得调用用户toString/equals、解码图、清Undo、强制GC或以计数估算省下的GB。
- **有效程度**：静态入口核查成功，排除两个会污染观测的实现选择；内存/CPU/GPU优化收益仍未验证。下一切片先获取该工作负载中的真实链计数，再决定是否值得做生产释放诊断记录修复。
- **补充验证**：原生CArrayList为final ArrayList子类；get(int)直接调用ArrayList.get，size→getSize→ArrayList.size，无惰性像素行为。该class SHA256=`c1ce8ee957d4131db8ea6ee965b027acbf4e13d9b912602c58b955ef22ab3051`，新增只读CArrayList.javap.txt留在同一私有目录。现有aux构建自动包含全部Java源且排除产品JAR中的validation包，不需要新增模块或Runner。
- **下一切片冻结**：SpecKit024的spec/plan/tasks/research/data-model/quickstart和质量检查完成，8任务中T001–T002完成，未开始源实现/新实机。范围仅现有load/zoom/close，观察当前模型分组的filtered resources（不冒充所有图像/GPU资源）；每次上限4096组、4096图像/资源、16384 active+released记录，250ms协作式截止并报告实际耗时，非硬实时保证。两个在计时阶段之外的快照，关文档后只用refersTo(null)。最终批次含focused、devCheck/受影响bundle与参数门禁及一次队列exact5302；纯观测本身不算优化有效。

### N01/I20 有界只读归因探针实施（2026-09-09；沿用09-08私有任务目录）

- **实现**：新增测试专用NativeImageRetainObservation，不进入产品。精确字段类型/类来源与已验证宿主对齐；EDT读取原始model/group/image/resource字段，identity去重，active/released列表及record user读前后比较。有限列表副本与强identity表只在capture栈内存在；跨阶段仅Properties标量和WeakReference。当前资源统计先暂存并通过有界一致性检查，再整体提交，超时/缺失/观察到变化不伪装完整零。
- **场景接入**：沿用现有load/60次zoom/close；新增idle.end/restored.end，两个retain.begin/end明确排除在计时idle/zoom阶段之外。只保留beforeClose弱cohort，120秒后及sampler结束用refersTo(null)计数。已有身份/camera/dirty/Undo检查保留，观察前后再次检查作者状态。整体workload PASS与retain.attributionStatus分开，INCOMPLETE不能当归因完成。
- **验证与失败保留**：先写测试时真实失败（helper缺失，1failure/1error）；首次实现后synthetic图验证通过137断言，但文本禁止规则误把Long.toString(number)当宿主toString，导致1test FAIL。修正为禁止无参toString调用，保留会抛错的宿主equals/hashCode/toString/materialization对象，不改变行为验收。随后加入跨组重复、全局record预算、不同截止点不提交半资源、EDT/未知宿主负例；2测试入口PASS，565断言、71个可检测同大小替换时机（这些不是565个独立测试或全部并发窗口）。
- **限制/有效程度**：250ms是协作式截止，调度暂停和末尾标量/弱引用合并不受硬实时保证；一致性检查不排除ABA或所有后台线程写。当前model分组缺失的atlas-only资源未扫描，released user仍可能有其他合法所有者。没有GC根/retained size证明，也没有新内存/CPU/GPU收益。最终构建门禁与新实机尚待执行；后续只报告这次真实证据，不从静态机制外推GB。

### N01/I21 exact5302归因PASS：当前打开/缩放场景未发现released链（2026-09-09）

- **最终门禁**：`build/native-retain-attribution/final-20260908/{gates.log,result.json}`，74.18秒exit0、无宿主抢占；2 focused入口566断言/71检测替换时机PASS（新增已验证宿主来源不匹配负例），devCheck、checkResourceValidationBundle、checkCubismHostValidationArguments全部PASS，3项resource policy smoke PASS。新aux class存在于测试JAR，product包无validation类；README、diagnostics和后续diff检查单独收口。
- **固定与运行**：dry-run核对exact5302、heavy副本、四优化false/profilefalse、三固定helper和managed background；prepare=`0dd019193383928e70aa47042a0a462c5f820baf11c52ddcba43d5b514b81c6f`，source HEAD97866ae5c+dirtyDigest8f2742d3b0aec302896d6ab757c8a3db2e48a201493c9057a33f6a8314ddf47c，aux SHA256=`6e93803219a7314a56dce5d15a2135a8bce5e80e5d81a97a6651358206508f2d`。main现有worker未重启，提交request=`native-retain-attribution-20260909-b1`，job=`0e305cf3-3c79-4c95-bfd6-93652f0c7bc2`，attempt=`cd60f76d-cc59-48f8-806a-74a977283733`，run=`queue-67cde2143a5a428d87c04c2c845ae0e8`，label=`nr-chain-b1`。未在测量期间构建。
- **终态**：manager succeeded/PASS，retain.attributionStatus=COMPLETE；410样本均绑定Java PID1613339/start24093860与原cgroup（device33/inode55371、boot39a1d627-6b70-427b-828f-4eafaa615ae8）。normalExit/identityVerified/fixtureUnchanged/goldenUnchanged=true；wrapper.exit实值0，汇总wrapperExit仍null不替代原始证据。scope自然destroyed，originalCgroupBound=true、cgroupKillWritten=false、errors=[]、prefixRetained=false。官方JAR/BAT、原始heavy及副本、Agent/aux、解释器与Proton依赖前后hash一致；main host恢复idle、worker在线。
- **核心观测**：beforeZoom和beforeClose均COMPLETE/reason none：12组、849个modelImage、848个唯一resource、missingImage0、activeRecords3131、releasedRecords0、releasedRecordsAbsentActive0、releasedUsers0。两个snapshot自身耗时39.7839ms/16.5054ms；整个EDT/phase调用包络更大，不能把这两个数称完整探针CPU成本。关闭120秒后及约156秒时，848个资源弱引用仍全未clear，documentWeak亦未clear；released-user弱cohort为空是因为关闭前没有该类记录，不是“用户对象全被回收”。
- **负结果及重试条件**：当前打开/缩放/关闭前这两个时点，没有N01诊断记录可清，故不实施“清诊断列表”作为本场景省RAM手段。静态release机制仍成立；未扫描关闭动作之后的记录、atlas-only资源，也未执行导出/重导，所以不能全面排除N01或声称解释了关文档后全部保留。只有获得相关操作的独立授权/冻结范围及明确链证据后，才重试生产N01修复；不因零计数偷偷改场景。

| 阶段 | RSS中位/采样峰值 MiB | PSS中位 MiB | CPU整机%（12核） | 进程i915 render% |
| --- | --- | --- | --- | --- |
| 加载 | 668.87 / 4355.57 | 607.88 | 12.390 | 0.152（118/143有效区间） |
| native idle | 4350.97 / 4354.80 | 4287.26 | 0.353 | 0.000 |
| zoom | 4350.28 / 4355.55 | 4286.93 | 9.055 | 1.994 |
| restored idle | 4350.31 / 4350.33 | 4286.99 | 0.147 | 0.000 |
| closed idle | 4014.44 / 4014.68 | 3951.10 | 0.184 | 0.000 |
| closed tail | 4014.61 / 4014.64 | 3951.27 | 0.089 | 0.000 |

- **内存解释**：全程RSS/内核HWM峰4396.46MiB（约4.29GiB）；表内阶段之外有过渡峰值，不能只取表中最大。300秒heap.used约1567.29→1023.70MiB，heap.committed3840→3472MiB，GC计数39→42；GC计数不代表每次都全堆回收。关闭后RSS约减少336MiB，swap也从约53降至46MiB，与上一轮主要换出的模式不同，但这是原生基线行为，仍非新优化收益。全局MemAvailable最低1874MiB、SwapFree0；不同轮堆容量/内存压力明显不同，不能直接算探针或优化因果效应。sampler读延迟中位128.14ms、峰310.04ms，仍非CPU占用。
- **如何验证/复现**：离线分析采用新增idle.end/restored.end排除探针包络；CPU只纳入完整阶段相邻区间、除12，GPU复用冻结helper按有效wallNs加权、不补零。RSS/PSS/private不相加，GPU不是全机GPU或VRAM。私有`/tmp/turboism-native-retain-observer-20260908/`保留prepare/submit/wait/events、分析脚本及summary。分析SHA256=`1dd251342120b5a4bcdb085bd96e239582205bbcb691bacddf998c446a600cf3`，summary=`8020507334eac58cb985d7c9c692dd36bfa125492d9cded62b9e38edb059b965`；原始samples=`3d70753438b733b08ec5ef5565ed3f0e33487f2b130dd4b4b0d24ebc2f274a3e`，phase result=`448536de2fea732fa353e48489e39977220556053246545d927615a87602afc0`，jvm.csv=`7f88b6eba4e101f7797ce0bc40e508fd1b3d744668806b32efa0c505a64cc26d`。
- **有效程度**：只读归因实现及此场景exact-host验证成功，排除了一个未经实测支持的立即清理方向；新增内存/CPU/GPU优化收益NONE。下一步要分清真实强根保留、合法图像生命周期和JVM已提交容量未及时归还，不能仅凭848个weak未clear就清资源。

### N07/I22 关闭后的原生模型登记表与释放条件（2026-09-09，静态后续研究）

- **触发**：N01实机关闭前released=0，而关闭后resource weak848仍未clear。先回读本机旧文档`editor/cubism-document-lifecycle.md`作命令导航；旧HookBridge/PSD释放描述不当作当前实现契约。然后仅用精确5302 JAR的javap核查，不再启动宿主、不改源代码。
- **新机制**：CModelingDocument.closeFile在BCI123–205按file查询全局`com.live2d.cubism.doc.a.e`中的模型资源条目。无条目才直接dispose modelSource；有条目则先调用条目e()清owner字段，再调用a(true)。`com.live2d.cubism.doc.a.e.b`是static ArrayList强持有条目；模型条目`com.live2d.cubism.doc.a.b.b.a`强持有CModelSource，存在候选原生静态根路径：登记表→条目→modelSource→textureManager→images。
- **不能直接清理的原因**：模型条目a(boolean)先调用g()；owner非null或引用者集合非空时return false，不释放。允许释放时才dispose source、将字段置null并从登记表移除。传入true并不绕过g()。因此登记表存在不是泄漏证据；可能是合法共享/关联使用，也可能是不应存活的引用者未注销，必须测本任务条目是否仍在、owner/引用者实际身份，再谈修复，不能清空全局集合或强行dispose。
- **GC时机假设核查**：本轮jvm.csv在关闭附近GCcount39→40→41，关闭约87.5秒后又41→42（epoch1788920587347）。所以不能笼统称“关文档后完全没发生GC”；但聚合计数没有GC类型/代覆盖，也不足以称已验证全堆存活。关文档原生方法自身BCI241有System.gc，模型条目释放路径也有System.gc；未新增、抑制或迁移这些调用。
- **证据**：私有`/tmp/turboism-native-close-lifecycle-20260909/`三份javap。登记表class SHA256=`885ecffd1196d2f6b29d183f7f87534e7667141245422d2c3726a1f7679eece9`；模型条目=`0f20228aa254318207203b82142594cb0e454020ca1ee0b2eb7900e538c12d35`；条目基类=`2b742063795fb435a30cd356f2ea7e302748cd1b7bfbed8ef8523aaf0d7732c8`。g()检查BCI36–126；a(boolean)释放与移除BCI0–39。现有宿主console没有命中可据此确认本模型条目释放/拒绝的专门日志，不能从无日志反推分支。
- **有效程度/下一步**：仅确认更贴近关闭生命周期的待验证原生保留机制，RAM/CPU/GPU收益未验证；未冻结/实现新登记表探针，也未把N07混入已完成024。后续另冻窄只读归因切片，观察本任务条目及引用者，不扫描无关模型、不输出文件内容、不清Undo/PSD。另将JVM committed归还与对象强根作为不同问题，不能用改堆上限掩盖保留链。
- **交付/环境记录**：024实现与实机证据已提交性能分支`4062fb58995e38580a2da78ad0224461d6c9cf72`，没有合并到main。只读检查发现main由其他工作推进到836c0ad9e（Atlas当前页相关104文件）；本任务未修改main。对照原83a49168，队列CLI唯一变更是manifest允许5303；queue/containment/evidence/通用Runner未变。本次仍为已固定5302，不据此新增Atlas/5303运行授权；下一轮整合前需审阅新SDK/Atlas增量，不能覆盖本分支成果。


### N07/I23 复用历史JFR取得释放分支反证（2026-09-09）

- **目的/方法变化**：不立即新增登记表Hook或重复实机。在I22静态分支基础上，重新从既有rs-p1、rs-p2原始JFR导出 `jdk.SystemGC`（`jfr print --json --stack-depth 128 --events jdk.SystemGC <recording>`），逐一匹配方法描述符、BCI和关闭调用栈。这里只分析旧授权会话，不产生新的实机PASS或性能A/B；024实现/验收范围不变。
- **实测反证**：两份录制各7个SystemGC事件，均有一次 `com.live2d.cubism.doc.a.b.b.a(Z)Z` BCI39，上层 `CModelingDocument.closeFile(ZZ)Z` BCI195，且栈未截断。rs-p1发生于2026-09-07T13:03:06.920379611Z，rs-p2于13:20:21.357043391Z。精确JAR字节码在BCI0–8先判断g()，BCI26已经把该条目source置null，BCI36调用登记表remove，BCI39才System.gc。因此这两次调用**没有被owner/users检查拒绝**，已经走过source清空与注销调用，不能将这两轮关闭后document弱引用未清除归因于“该条目拒绝释放”。
- **结论边界**：JFR方法栈不包含receiver对象身份或完整GC-root，登记表remove返回值又被丢弃，故不宣称全局登记表已空、无重复条目或其他线程不可能重新登记；也不能把旧profile轮分支等同最新nr-chain-b1的plain轮。可排除的是这两次被记录调用在g()处提前退出，以及它们在BCI39时仍通过该条目source字段强持有模型的假设。I22保留为历史候选，当前优先级据反证下调，不抹去旧判断。
- **关闭路径补核**：`CModelingDocument.closeFile` BCI20–39在source.document等于当前doc时将反向关联置null；BCI42–111逐一closeView并清doc的viewContexts；BCI221调用project.remove。`CEViewContext.doc`仍是final强引用，因此仍被宿主其他根持有的view可能保留doc，但闭环本身不是GC根。CEAppCtrl有强viewContextHistory且有显式removeViewContextFromHistory；不能仅凭存在列表断言漏删。view-area基类dispose是空方法也不代表实际子类没有覆盖。下一只读归因应同时刻画关闭后的current view、view history/宿主UI拥有者和document/source身份，不据未置null盲清字段。
- **顺带观测（不是优化）**：两轮EDT上关闭调用的两次SystemGC事件持续时间分别235.14+181.19ms、637.43+382.07ms；它们是事件耗时而非整个操作CPU成本/可节省上限。没有抑制、合并或迁移原生GC，避免用更高驻留换出表面更快的关闭；若未来研究该方向需独立的内存与响应性契约。
- **证据/复现**：私有 `/tmp/turboism-native-close-jfr-20260909/` 保存两份小型SystemGC导出、close-path/view-close字节码、summarize.py及summary.json；脚本断言每轮恰有一个BCI39关闭事件、两个关闭SystemGC且栈不截断，通过。summary记录原JFR与导出SHA。分析脚本SHA256=`6a3984122cf11c83508e7f2860fba9c0b590f5eff8acbb90c2241e9e9260ed8a`，summary=`04a76196f4d27015e381079e87a82e670d3a19156cc012d386f30d25bb90cbb0`，close-path dump=`1791ab6210eaca416d5bc2e64efd68bbe0b11836fb75770515e2eb0c2dcccd37`。官方JAR重新计算仍988ef6a8…f8c84f21；模型条目class仍`0f20228aa254318207203b82142594cb0e454020ca1ee0b2eb7900e538c12d35`，CModelingDocument=`7ca4f69d9304e38d15adf0cce4750873b3d9325cac296a02e617d5c491d72188`。
- **有效程度/禁止原样重试**：新增收益NONE，未实施生产修复；本轮价值为用既有实机证据否定一个优先假设，避免额外Hook与无变化重跑。下一步不是强制dispose注册条目，而是定位关闭后的真实拥有者，并区分对象存活和committed/RSS。只有新的条目身份/持有者证据才重提登记表修复。
- **环境与实施边界**：性能worktree起点c72145efc且干净；main由其他工作推进至2cef29231，相比836c0ad9e只有工具栏图标及对应测试变化。main83→当前队列核心差异仍仅manifest接受5303，未改变worker、未整合新main或覆盖脏改动。Paseo MCP无工具，CLI确认daemon可达且pi provider available；本次顺序分析未委派。CodeGraph延用此前该worktree不可用的已知限制，实际依据为精确字节码和JFR，未伪称图查询。管理器只读status显示workerOnline、host idle；没有启动宿主、采堆快照、改官方工件或杀任何进程。


### I24 — 接手轮只读核查、证据归档与后续计划（2026-09-09）

- **接手/核实方式**：新接手代理只读核查性能分支状态、SpecKit024全量文档、本台账、4份子代理报告与review-receipt、`build/` 验收产物是否存在。未启动宿主、未跑门禁、未做性能测量、未改源码或官方工件；本轮所有数字均为既有证据引用，不是新测量。
- **分支状态**：`feat/cubism-native-performance-20260905` @ `a700980af`，性能worktree工作树干净；比集成基线 `83a49168` 多18个自有提交，落后当前main（`caba7a5f5`，0.43.10发布准备）12个提交；未合并、未推送main。SpecKit024的T001–T008全部完成，Status=Implemented。
- **证据丢失（重要）**：I19–I23引用的私有临时证据目录已**全部从 `/tmp` 消失**（close-jfr、close-lifecycle、retain-observer、pool 0700/0730-r1、retain 0700/0720-r1、lease-prototype、memory-admission、retain-attribution）。台账内SHA256仍保留，结论可在文字层追溯，但已无法再本地复算校验；`I23` 的JFR反证可由归档中的 `rs-p1.jfr`/`rs-p2.jfr` 重新导出。
- **归档动作**：已把性能worktree `build/` 下现存证据（`native-followup` 全量除两个超大派生JSON、`native-retain-attribution`、`main-integration`、`build/` 根目录 javap/log/txt/json）复制到操作者本地状态目录下的 `turboism/performance-evidence/20260909-handover/`，共169个文件/40.3MiB，并生成 `MANIFEST.sha256`（含未归档的4个超大派生文件SHA256）与说明README。未归档：`rs-p1-events.json`、`rs-p1-stacks.json`、`heavy-load-deep-jfr.json`、`heavy-load-jfr.json`（可由已归档的原始JFR再生）。本台账新增使用规则7。
- **遗留欠账（本轮未解决）**：①P08-a离线复现补丁已父核验 `git apply --check` 通过但**NOT_RUN**（曾受阻于队列占用）；②4份子代理报告中 image-pool、cpu-gpu 的父链变为null，父方判定身份溯源待澄清，不能算身份已核验交付，且cpu-gpu报告有1处BCI表述错误（已由review-receipt纠正）；③性能分支落后main 12个提交，最后一次main增量接入后未再完整复核门禁；④本台账头部日期此前过期（本轮修正）。
- **裁决与执行顺序**：用户批准三项——启动N07窄切片（关闭后原生视图/历史持有链只读归因，占一次实机窗口）、证据持久化并立规则、允许对当前main 12个提交做只读审阅与受影响门禁（不合并main）。顺序：证据归档（本轮完成）→ main增量只读审阅 → 冻结并实现N07只读切片与离线门禁 → 一次exact5302只读实机观测与独立复核。优先级2为P08-a离线复现；优先级3为N02-b池内部登记事务窗口静态复核。继续禁止：E/P系列原样重试、N03 ROI、N04清理、CPU repaint coalesce、清登记表/dispose/强制GC/采堆快照、合并或推送main。
- **有效程度**：本轮新增内存/CPU/GPU收益**NONE**；产出为状态核实、证据持久化与后续切片授权，不构成任何优化验收。


### I25 — main 增量只读审阅与整合面评估（2026-09-09）

- **范围/方式**：只读审阅 main 自合并基点 `83a49168` 后的 12 个提交（8 个普通提交 + 4 个合并提交，最新 `caba7a5f5` 为 0.43.10 发布准备）。未合并、未推送、未构建、未改 main 或 worker；仅用 `git diff`、`git log`、`git merge-tree --write-tree` 计算。
- **变更面**：132 个文件、+8764/−128；主体是 Atlas 当前页打包链（validation/texture-atlas-current-page 证据与脚本、sdk/runtime/plugins 的 textureatlas 契约、api-contracts 基线）、发布渠道脚本（scripts/release、distribution、.github/workflows）与兼容性清单（compatibility/cubism/verification 的 5.2.03/5.3.02/5.3.03 editor-model 记录）。
- **与本任务工具面的关系**：main 只动了两处相关文件——`host_validation.py` 一行（unsupported exact host version 集合由 {5203,5302} 扩为 {5203,5302,5303}）与 `host-validation-tasks.json`（+37 行，新增 Atlas 队列任务项）；另加 Atlas 专用脚本/README/测试（package-atlas-queue-probe、run-atlas-host-validation、test_atlas_host_validation、test_sdk_v8_linkage、test_texture_atlas_sdk_linkage）及 `test_host_validation_scheduler.py`（+24 行）。计时/内存 observer、containment、evidence、transport 与内存观测三文件在 main 无变化；本任务固定 5302，准入集合扩大不改变本切片身份约束。
- **整合面**：双方自基点起同时修改的文件仅 `gradle/verification.gradle.kts`，且落在不同区段（本分支在 checkCompletedCommit 依赖加入 checkPerformanceProbeReports；main 在 checkRelease 依赖加入四个 SDK/Atlas 检查）。`git merge-tree --write-tree HEAD main` 结果为**无冲突自动合并**（仅计算，未执行）。
- **本分支在同名工具文件上在先**：相对 main，本分支另有 `host_validation_queue.py`(+55/−6) 与 `host-validation-local-transport.sh`(+27) 的自有改动，属 I15/I17 已记录并验证过的准入/传输工作，不是 main 漂移。
- **受影响离线门禁（本轮实跑，无宿主）**：`scripts/check_remote_hygiene.py --worktree` = clean；`scripts/test/check_host_validation_scheduler.sh` PASS（11 scheduler + 46 queue + 12 evidence tests 及配套 shell 检查，exit0）。覆盖本轮台账改动与队列工具面。
- **判定/后续**：整合风险低，可在需要时自动合并；但**本轮仍未整合**，合并后必须重跑受影响门禁并重新准备身份固定的实机准入才能沿用旧证据；5303 准入扩大不得用于本任务的 5302 结论。

### I26 — N07 关闭期原生持有链只读归因：切片冻结、探针实现与离线验证（2026-09-10）

- **范围/方式**：为回答“关闭后登记表/视图链是否仍强持有文档与模型源”，在性能worktree冻结 SpecKit `028-native-close-ownership`（spec/plan/research/data-model/quickstart/tasks/checklists），并实现只读探针 `NativeCloseOwnershipObservation` 与宿主接入点。**未运行宿主**：本轮所有结论都来自字节码交叉复核、合成回归与离线门禁，不构成任何性能收益或持有链结论。
- **字节码复核（新发现，纠正了切片假设）**：从已装 5302 JAR（SHA `988ef6a8...`）重算类哈希与台账一致（registry `a.e`、entry base `a.b.a`、model entry `a.b.b`、`CModelingDocument`、`CEViewContext`、`CEAppCtrl`、`CModelSource`）。关键点：base 的 `b()` 返回的是加载器包装器 `doc.a.f`，`c()` 只等价于 `b() != null`；`doc.a.b.b` **不覆写** `b()`，其自有 `CModelSource` 只经 `f()` 暴露。因此只用 `b()`/`c()` 无法证明原生源仍被持有，探针改为在命中的具体类上解析 `f()`（返回类型名字必须严格等于 `com.live2d.cubism.doc.model.CModelSource`，不加载宿主类），并单独报告 `fixtureCarriedSourceIsRecordedSource`（仅用 `refersTo` 做身份比较）。此发现已写入 `research.md`，属对 N07 假设的必要修正。
- **探针不变量**：只读；不 remove/dispose/clear/强制GC；不比较宿主 equals/hashCode/toString；不调用 `getFilteredImage()`/图像获取接口；registry 列表与视图历史的实例、长度和元素身份在遍历前后一致才判 COMPLETE，否则 PARTIAL/`unstable`；越界报 PARTIAL/UNSUPPORTED 且**不发布任何计数**（计数分区暂存，仅 COMPLETE 提交），避免“未观测”与“0”混淆；快照对象只含 Properties 字段（回归用反射遍历断言）。
- **宿主接入**：四个采集点 `ownership.idle`（开启态对照组）、`ownership.beforeClose`、`ownership.closed120`、`ownership.closedFinal`，并输出聚合 `ownership.attributionStatus=COMPLETE|INCOMPLETE`。024 已有的身份/脏文档/Undo/相机/关闭断言全部保留；采集点自身的 begin/end 阶段不计入 idle/zoom 计时，属同口径修订而非历史基线。
- **离线验证（本轮实跑）**：`python3 -B scripts/test/test_native_close_ownership_observation.py` PASS（3 项：合成图 435 项断言、可变窗口 5 类、接入点顺序与禁用法调用点扫描）；`./gradlew --no-daemon --max-workers=1 devCheck checkResourceValidationBundle checkCubismHostValidationArguments` = 0；`scripts/test/test_native_resource_policy.py` PASS；024 回归 `scripts/test/test_native_image_retain_observation.py` PASS（566 项）。合成图覆盖：命中/未命中路径、null 与抛异常条目、重复条目、空/单/多历史视图、已清除与未清除弱引用、各类上限与截止时间扫描、错误访问器形状（`f()` 返回类型不符 → `absent`）、布局/来源不匹配 → `layout-or-origin`。
- **未完成与限制**：exact5302 实机观测尚未执行（T007 保持未完成）；私有字段 `viewContextHistory` 的 `trySetAccessible` 在实机下是否成功需由该次运行确认，失败会记为 `UNSUPPORTED`/`access`，不会记为 0。本切片不清理任何登记项、不做 GC/堆快照，因此即使观测到存活条目也**只授权后续另立修复切片**，不代表本轮有任何优化验收。
- **证据归档（使用规则 7）**：探针与测试源码修订、javap 转储、合成回归日志与 028 切片文档已归档到
  `~/.local/state/turboism/performance-evidence/20260910-native-close-ownership/`（21 文件 / 1.1MiB，含 `MANIFEST.sha256`）。
- **有效程度**：本轮新增内存/CPU/GPU 收益 **NONE**（未运行宿主，无新测量）。

### I27 — N07 exact5302 只读实机观测：登记表与视图历史方向被排除，整体因既有采样协议失败（2026-09-10）

- **运行身份**：prepared=`c63e7782c7beba3990600ccbd885835eff8f34abeaacd0ebfc1a1e8eadf74e93`，source HEAD=`caa3f1678`（dirtyDigest=空，工作树干净），aux jar SHA256=`b249da40ec962f728e8745892a6c81547b40d96a21719fe1ee7bc02ece8c2e62`（含探针类 `6270a536…`），fixture SHA256=`029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`，主机 JAR 仍 `988ef6a8…f8c84f21`。任务作用域 manifest 声明 host-slot+performance-host，四优化 flag 与 profile 均 false，三个固定 helper 与 pin 解释器 `/usr/bin/python3.14` 齐备。提交经 main CLI，request=`native-close-ownership-20260910-b1`，job=`eec9bcdb-80ff-4178-8768-ccca95b0ca0a`，attempt=`2f273820-7906-4c4e-942f-1ba01c3ea0cd`，run=`queue-4b2460f6c0d8426cb75071112a6fe167`，label=`nr-own-b1`。
- **整体结果：FAIL（非探针原因）**：`failure=java.lang.IllegalStateException: process memory sampling failed`，根因是既有采样器自校验 `measure-task-memory.py::validate_window` 抛 `memory sample timing gap outside 0..5 seconds`（381 个样本，最大相邻间隔 6.93s，位于 restored 窗口末 12:22:40→12:22:47；该时刻 `MemAvailable≈1.98GB`、`SwapFree≈2.2MB`，机器处于换出压力下）。因此 workload 在采样校验处中止，`ownership.closedFinal` 与聚合 `ownership.attributionStatus` 未写入。此失败模式属既有协议/环境问题，与本切片探针无关。
- **N07 实机观测（三组均 COMPLETE）**：
  - `ownership.idle`（开启态对照）：`registryEntries=1`、`fixtureEntryPresent=true`、`fixtureLoadedFlag=true`、`fixtureWrapperPresent=true`、`fixtureSourceAccessor=f`、`fixtureCarriedSourcePresent=true`、`fixtureCarriedSourceIsRecordedSource=true`、`historySize=1`、`historyViewsWithDocument=1`、`historyViewsReferringToDocument=1`、`documentWeakBefore/After=false`、duration≈14.7ms。
  - `ownership.beforeClose`：同上（entry present、source 身份匹配、hist視 referring=1）、duration≈0.22ms。
  - `ownership.closed120`（关闭后 120s）：`registryEntries=0`、`fixtureEntryPresent=false`、`historySize=0`、`historyViewsWithDocument=0`、`currentDocNull=true`、`currentViewNull=true`、`documentWeakBefore/After=false`、`sourceWeakBefore/After=false`、`referrersExamined=0`、duration≈0.11ms。
  - 同期 024 观测仍成立：`retain.closed.resources.notCleared=848`、用户未清除计数为 0、`documentWeakCleared=false`。
- **解释（阴性且自洽）**：关闭后静态登记表 `doc.a.e` 的实例在但**列表为空**，控制器视图历史为空、当前文档/视图为 null，而 848 个资源弱引用仍未清除。开启态对照组能读到恰好 1 条条目并把 `f()` 身份匹配到记录的 `CModelSource`，说明探针与接线有效。因此**关闭后文档/原生模型源并非由登记表条目或控制器视图历史强持有**——N07 假设的这两条链被排除，后续归因必须转向其它持有者（例如资源注册表/纹理管理侧），且需要另立切片。
- **限制与重试条件**：整体 status=FAIL，不是 SC-002 的完整 PASS 验收；`closedFinal` 与聚合状态缺失；探针未观察到 GC 后状态（未做强制 GC，符合约束）。重试需新 job/新 label，并先确认宿主内存压力已缓解（SwapFree 不再是 MB 级），否则采样协议可能在同一点再次失败。证据（job evidence、result.properties、outcome/containment、运行日志、samples）已归档到 `~/.local/state/turboism/performance-evidence/20260910-native-close-ownership/run/nr-own-b1/`。
- **有效程度**：新增内存/CPU/GPU 收益 **NONE**；产出为对 N07 登记表/视图历史方向的**否定性实机证据**，并暴露既有采样协议在内存压力下的失败点。

### I28 — N07 实机重复观测：登记表/视图历史方向被两次独立排除，整体仍因既有采样间隔协议失败（2026-09-10）

- **运行身份**：新 prepared=`7902af9d0b51e2766551bf0b7404d1197e2f87e40db0a4ad4712b0b18cc2c037`，source HEAD=`8738b7424`（dirtyDigest=空），aux jar 与 fixture 与 I27 同哈希，四优化 flag/profile 全 false，三个固定 helper 与 pin 解释器不变。提交经 main CLI，request=`native-close-ownership-20260910-b2`，job=`f16d992e-7351-4561-a70b-7bc89bc3b765`，attempt=`queue-713168bd68cd499882c4a912112e22b8` 对应 run，label=`nr-own-b2`。
- **整体结果：FAIL（同因、同一位置类型）**：`memory sample timing gap outside 0..5 seconds`，本次最大相邻样本间隔 **15.21s**（13:13:05→13:13:20，处于 zoom 窗口 13:12:42–13:14:00 内），样本数 365；该时刻 `MemAvailable≈2.87GB`、`SwapFree≈3.8MB`。两次运行（I27 6.93s / 本次 15.21s）**都在既有采样器的 0..5s 间隔自校验处失败**，且失败点都落在计时工作负载内、宿主换出压力（SwapFree 为 MB 级）之下；`ownership.closedFinal` 与聚合 `attributionStatus` 两次均缺失。
- **N07 重复观测（三组均 COMPLETE，与 I27 逐字段一致）**：`ownership.idle` 与 `ownership.beforeClose` 均为 `registryEntries=1`、`fixtureEntryPresent=true`、`fixtureLoadedFlag=true`、`fixtureWrapperPresent=true`、`fixtureSourceAccessor=f`、`fixtureCarriedSourcePresent=true`、`fixtureCarriedSourceIsRecordedSource=true`、`historySize=1`、`historyViewsWithDocument=1`、`historyViewsReferringToDocument=1`；`ownership.closed120` 为 `registryEntries=0`、`fixtureEntryPresent=false`、`historySize=0`、`historyViewsWithDocument=0`、`currentDocNull=true`、`currentViewNull=true`、`documentWeakBefore/After=false`、`sourceWeakBefore/After=false`。同期 024 观测再现：`retain.closed.resources.total=848`、`notCleared=848`、`cleared=0`、`documentWeakCleared=false`。官方 JAR/BAT 与 fixture 运行后哈希不变。
- **判定**：N07 的登记表条目与控制器视图历史两条强持有链被两次独立实机观测**排除**（开启态对照能读到恰好 1 条条目并身份匹配记录的 `CModelSource`，说明探针有效）；关闭后仍存活的是 848 个资源弱引用与文档弱引用。该结论不依赖运行整体 PASS，因为三组快照各自 `status=COMPLETE`、失败点在其后、且序列自洽。但**工作负载 PASS 与 `closedFinal`/聚合状态仍未取得**，T007 不因本条目完成。
- **阻塞与建议**：阻塞点是既有只读采样器的 0..5s 间隔自校验在换出压力下无法满足，属 023 准入协议与宿主容量条件，不属本切片；本轮**不**再第三次重试（两次同因失败已构成可复现）。建议先释放宿主换出压力（SwapFree 回升到 GB 级或重启）再跑，或就"压力下间隔规则"另立裁决；不得为通过验收而放宽该规则。未做强制 GC、未采堆快照、未清理登记项、未合并 main。
- **证据归档**：两次运行的 job evidence、`result.properties`、outcome/containment、runner/采样日志与 samples 均归档在 `~/.local/state/turboism/performance-evidence/20260910-native-close-ownership/run/nr-own-b{1,2}/`（112 项，`MANIFEST.sha256` 复核）。
- **有效程度**：新增内存/CPU/GPU 收益 **NONE**；产出为**两次可复现的 N07 否定性实机证据**与一个可复现的宿主换出压力下采样器失败点。

### I29 — 关闭后原生图 Java 静态根只读审计：方向切片、离线实现与 static 侧证据（2026-09-10）

- **动机（承接 I27/I28 的否定结果）**：028 已两次实机排除“静态登记表条目”与“控制器视图历史”两条链；关闭后 120s 仍存活的文档弱引用、原生模型源弱引用与 848 个资源弱引用必须另有根。可只读观测的下一层候选是 **Java 静态字段**（相对 live thread / JNI native 全局态）。
- **离线静态分析（仅解析 class 文件与 `javap`，不加载宿主类、不启动宿主）**：对精确 5.3.02 的 `Live2D_Cubism.jar`（SHA256 `988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21`，16 947 类 / 14 895 个静态字段）解析常量池与字段表并做反向可达性：
  - **0** 个静态字段的类型是 `CModelingDocument`，**0** 个是 `com.live2d.cubism.doc.IDocument`，**0** 个是 `CTextureManager`/`CModelImage`/`CCachedImage`/`ICImageResourceUser`；25 个静态 `CImageResource` 全是 final 图标常量而非缓存。
  - 恰好 **4** 个静态字段持有 `CModelSource`，全部为包私有/私有且**非 final**，位于参数/部件面板与 UI 辅助类：`appCtrlImpl.ui.a.a#d`、`view.palette.parameter.dialog.G#d`、`view.palette.parameter.dialog.af#i`、`view.palette.parts.a.a#d`。逐个 `javap -p -c` 复核：每处均只有**一次** `putstatic`（来自一个接收面板/`CEAppCtrl` 的 UI 更新方法），且**全程没有写回 null** → 候选陈旧静态缓存，属新的根嫌疑。
  - `CModelSource` 有**非 final 的 `document` 反向指针**，且 `CModelSource.textureManager` 可达模型图像组与 `CImageResource`：因此**只要源被根持有，文档与整图即随之存活**，与 I27/I28“无释放记录、弱引用不清”的观测一致。
  - 除 `CEAppCtrl` 单例扇出外，仍有 **44** 个静态字段可到达 `CModelSource` → 该 44 项与上述 4 项共同构成 029 的**审查边界**（非完整性证明）。
- **切片冻结**：新增 Spec Kit **029-static-root-audit**（canonical `/opt/dev/projects/turboism/specs/029-static-root-audit/`，冻结副本于性能 worktree `specs/029-static-root-audit/`，`diff -rq` 一致）；T001 记录上述静态分析。非目标：不做堆转储/GC 根遍历/保留大小测量、不做任何清理或产品修改；**正向命中只授权另立修复切片**。
- **探针实现（只读、有界、需 EDT）**：`NativeStaticRootAudit.java`，冻结 `DIRECT`（4 个源静态字段）与 `HOLDERS`（30 个已复核单例/状态静态 `owner#field`）。每项：不初始化地解析 owner 类 → 校验类加载器与 code source 来源 → 取声明静态字段 → `trySetAccessible` → 读一次；非空持有者再**一跳**扫描（自身类及至多 4 层父类）声明类型名**恰为**源或文档类型的实例字段（不可访问计 `unreadable`）。身份**只用** `WeakPair.sourceIs/documentIs`（即 `refersTo`），绝不 `get()`、绝不调宿主 `equals/hashCode/toString`；**绝不写**任何宿主静态字段；只输出标量与有界 `owner#field` 标签。上限 64 持有者 / 128 扫描字段 / 250ms 协作式截止（每次读取前检查）；计数分区暂存、仅 `COMPLETE` 提交 → `PARTIAL`/`UNSUPPORTED` **不发布任何计数**（避免“未观测”与“0”混淆）。读取静态字段可能触发其类初始化：类初始化器无法取得已打开文档的活源，故**身份命中必然来自应用写入**；因此审计排在同阶段 `ownership` 采集**之后**，初始化不可能影响该次采集。
- **宿主接入**：`NativeResourceHostAgent.java` 在四个采集点（`idle`、`beforeClose`、`closed120`、`closedFinal`）于对应 `ownership` 采集后调用 `auditStaticRoots(...)`，输出 `staticRoots.<phase>.*`；024/028 已有的身份/脏文档/Undo/相机/关闭断言与安全门控全部保留不变。
- **离线验证（本轮实跑）**：`python3 -B scripts/test/test_native_static_root_audit.py` **PASS**（3 项 / 112 断言：合成图直接命中源与文档、null/异源/异加载器负例、缺失与原始类型字段计 `unreadable`、空单例、继承字段一跳命中、holder/field/time 三类上限均 `PARTIAL` 且不发布计数、审计前后静态字段未变、空弱对、仅标量、EDT 要求与外来来源拒绝；另有只读/禁用法调用点扫描与接入点顺序断言）。`./gradlew --no-daemon --max-workers=1 devCheck checkResourceValidationBundle checkCubismHostValidationArguments` = **0**（含 `remote-hygiene: clean`、`PASS: Cubism host-validation argument and ownership hardening`）；`test_native_resource_policy.py` PASS；024 回归 `test_native_image_retain_observation.py` PASS（566 断言）；028 回归 `test_native_close_ownership_observation.py` PASS（435 断言）。
- **未完成与限制**：exact5302 实机观测**尚未执行**（T002–T005 完成，T006/T007 保持未完成）——当前阻塞与 I27/I28 相同：共享宿主的换出压力使既有采样器 0..5s 间隔自校验无法满足，且 `/home` 磁盘 96% 下两个保留 prefix 未释放、共享长驻 worker 未 drain。**离线静态分析不等于实机根证明**：它不证明这 4 个字段此刻确实持有活源，也不排除 live thread 与 JNI/native 全局态；44 项边界之外的容器型静态字段（如集合/缓存）仍可能到达该图。
- **证据归档（使用规则 7）**：静态分析脚本与转储（`roots.py`、`roots2.py`、`reach.py`、`static-fields.txt`、`interfaces-and-containers.txt`、`reachability.txt`、`direct-source-statics-bytecode.txt`、`CModelSource-fields.txt`、`host-jar.sha256`）归档到 `~/.local/state/turboism/performance-evidence/20260910-native-close-ownership/static-root-analysis/`。
- **有效程度**：本轮新增内存/CPU/GPU 收益 **NONE**（未运行宿主，无新测量）；产出为**可复现的 Java 静态根边界证据**（0 文档/纹理/图像静态字段；仅 4 个从不置 null 的源缓存；源→文档反向指针）与一个已通过离线门禁的只读审计探针。

### I30 — 029 静态根审计 exact5302 实机：工作负载 PASS，审查范围内的 Java 静态根被排除；静态容器成为新方向（2026-09-10）

- **运行身份**：prepared=`611f283ae45df7f1ff17faa0beebff0382b417c6283dd7cc1192eae68a7659a9`，source HEAD=`a17367f06c2644e9a85d7d451cf668a2ce19f727`（`dirtyDigest=e3b0c442…b855` = 空 → 工作树干净），aux jar SHA256=`390e8f32e4b1c1a7139ae5bb399d4584cc80f52240057b97b01d8955365dc9e4`（新增 `NativeStaticRootAudit`，与 I27/I28 的 `b249da40…` 不同，属本切片探针增量），fixture=`029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c`，主机 JAR 仍 `988ef6a8…f8c84f21`，四优化 flag 与 profile 全 false。任务作用域 manifest 同 I27/I28。提交经 main CLI，request=`native-static-root-20260910-b1`，job=`7326cecf-398c-4f66-8ceb-e71f34c87167`，attempt=`233e58eb-435f-4386-acee-bf8198732b7f`，run=`queue-d6f6b98e4b474ca5b311d372bbac0a8a`，label=`nr-static-b1`。
- **整体结果：PASS（本切片首个完整 PASS）**：`validationStatus=PASS`、`validationComplete=true`、`normalExit=true`、`runnerExitCode=0`、`identityVerified=true`、`goldenUnchanged=true`、`fixtureUnchanged=true`、`prefixRetained=false`、`cleanup=safe`、`taskOwnedCleanup=true`；内核证明 `cgroup-destroyed`（`processReturncode=0`），无残留宿主进程。**028 的 T007 缺口在本轮补齐**：`ownership.attributionStatus=COMPLETE`，四个 `ownership.*` 采集点（含此前两次缺失的 `closedFinal`）齐备。`status=PASS`，`documentWeakClearedAtSamplerEnd=false`。
- **采样协议阻塞已定位为环境原因**：本轮 381 个样本，**最大相邻间隔 1.11s**（中位 1.05s），远在既有 0..5s 规则内；运行期 `MemAvailable≈6.3–9.9 GiB`、`SwapFree≈12.8–13.4 GiB`。对照 I27（6.93s）与 I28（15.21s）均在 `SwapFree` 仅 MB 级时失败 → **既有采样器失败的直接原因是宿主换出压力，不是采样规则本身需要放宽**。
- **029 静态根审计（四组全部 COMPLETE，`reason=none`）**：
  | 采集点 | status | examined | read | unreadable | nonNull | scannedFields | holdersWithRecordedSource | holdersWithRecordedDocument | durationNs |
  |---|---|---|---|---|---|---|---|---|---|
  | `staticRoots.idle` | COMPLETE | 34 | 34 | 0 | 15 | 4 | **0** | **0** | 42 360 400 |
  | `staticRoots.beforeClose` | COMPLETE | 34 | 34 | 0 | 15 | 4 | **0** | **0** | 685 600 |
  | `staticRoots.closed120` | COMPLETE | 34 | 34 | 0 | 15 | 4 | **0** | **0** | 812 500 |
  | `staticRoots.closedFinal` | COMPLETE | 34 | 34 | 0 | 15 | 4 | **0** | **0** | 359 100 |
  四组的 `recordedSourceHolders`/`recordedDocumentHolders` 均为空串。同期 024/028 再现：`retain.closed.resources.total=848`、`notCleared=848`、`cleared=0`；`ownership.closed120`/`closedFinal` 均为 `registryEntries=0`、`historySize=0`、`currentDocNull=true`、`documentWeakAfter=false`、`sourceWeakAfter=false`。
- **判定（对审查范围内的 Java 静态根为否定）**：4 个直接 `CModelSource` 静态缓存与 30 个已复核单例/状态静态在**任何阶段**都不持有记录的源或文档——**包括文档合法打开且被强持有时的 `idle`/`beforeClose` 对照组**。该对照组使阴性结论有意义：探针确实能读到 15 个非空持有者与 4 个一跳源/文档类型字段，若根在其中则会在开启态命中。因此 **N07 之后的第三条方向（Java 静态根本身）在本工作负载内被排除**，与 I27/I28 的登记表/视图历史排除相独立。附带事实：本工作负载从未写入那 4 个源缓存（`nonNull=15/34`），故“陈旧静态缓存”假设在本工作负载内连素材都不存在。
- **未排除（必须显式记录的方向）**：`PARTIAL`/`UNSUPPORTED` 未出现，但审计的**只读边界**决定了以下仍开放：(a) **静态容器字段**——声明类型为 `java/util/*`、数组或自定义容器（如 `com.live2d.type.CArrayList`）的静态字段，其**元素**可持有模型对象，而按字段描述符的可达性分析与 DIRECT/HOLDERS 列表都看不见它们；(b) **live thread** 根（含静态 `java.util.Timer` 及其 `TimerThread`、EDT）；(c) **JNI/native 全局态**，Java 侧不可读。029 的审计只否定 (a) 之外的类字段型静态根，不能推广为“无静态根”。
- **新方向（离线已取证，待另立切片）**：对同一精确 JAR 重做**静态容器字段**扫描（`scripts`/`/tmp/nco-roots` 类文件解析，不加载宿主类）：16 947 类中静态容器/数组/引用类型字段 **1 594** 个、去重 **869** 个；其中泛型签名给出的**元素类型能到达目标图**的 **117** 个，元素类型距目标 **≤1 跳**的仅 **9** 个：`com.live2d.graphics.CImageResource#DEBUG_IMAGES`（`ArrayList<CImageResource>`，`d=0`，但 `<clinit>` 只建空表、唯一写入点是 `debugStart()` 且 `DEBUG=false` 时立即返回）、`com.live2d.graphics.CImageResource#cacheList`（`CArrayList<CImageResource$b>`，而 `CImageResource$b` 只持有 **`SoftReference<CImageResource>`**）、静态 `java.util.Timer timer`（`<clinit>` 构造并在 `CHECK_TIMER_SEC=300`/`ARCHIVE_IMAGE_TIME_SEC=360` 下 `schedule` 一个清理型 `TimerTask`）、`com.live2d.cubism.doc.modeling.util.S#o/#p/#q`、`com.live2d.cubism.doc.gameData.a.b#b/#c/#d`、`com.live2d.view.palette.parameter.dialog.af#b/#l`。**这些都不是强持有结论**：`cacheList` 是软引用缓存（软引用可跨显式 GC 存活，但在内存压力下会被清除），`DEBUG_IMAGES` 在生产配置下为空，`Timer` 只根住计时线程与任务队列。需在下一片用有界只读审计逐项读取这些静态容器的**元素身份**（仍只用 `refersTo`）才能判定。
- **限制**：029 的审计读静态字段可能初始化其声明类（类初始化器无法取得已打开文档的活源，故命中必为应用写入），且审计被安排在**同阶段 ownership 采集之后**，初始化不可能影响该采集。审计划分为 64 持有者 / 128 扫描字段 / 250ms，本轮 34 项远未触界，`unreadable=0` 说明 `trySetAccessible` 在实机全部成功。审计不做 GC 根遍历与保留大小测量。
- **证据归档（使用规则 7）**：`~/.local/state/turboism/performance-evidence/20260910-native-close-ownership/run/nr-static-b1/`（27 文件 / 1.1MiB：`job/` 为 job evidence/outcome/containment/heartbeat，`task/result/result.properties`、`task/evidence/`（含 381 样本 `memory-samples.jsonl`、`memory-sampler.log`、宿主与哈希证据）、`task/logs/`）；静态容器扫描产物归档在 `static-root-analysis/static-containers.txt`。归档 `MANIFEST.sha256` 已刷新。
- **有效程度**：本轮新增内存/CPU/GPU 收益 **NONE**（无优化改动）；产出为 **029 首个完整 PASS**、**审查范围内 Java 静态根的实机否定**（含开启态对照），以及将剩余方向收敛为“静态容器 / live thread / native 全局态”的可复现边界与静态容器清单。

### I31 — 关闭后存活图的机制候选：`CImageResource` 静态软引用缓存（bytecode 证据，待实机只读确认）（2026-09-10）

- **背景**：I27–I30 依序排除了登记表条目、控制器视图历史与审查范围内的 Java 静态根；I30 同时把剩余边界收敛为「静态容器 / live thread / native 全局态」。对静态容器做类文件扫描时发现了一条**能同时解释全部既有观测**的机制，不需要任何强根假设。
- **bytecode 证据链（精确 JAR `988ef6a8…f8c84f21`，仅 `javap -p -c`，未加载宿主类、未启动宿主）**：
  1. `com.live2d.graphics.CImageResource` 在 `<clinit>` 建 `private static CArrayList<CImageResource$b> cacheList` 与 `private static java.util.Timer timer`。
  2. **构造函数**执行 `cacheList.add(new CImageResource$b(this))`，而 `CImageResource$b` 只持有 `private final SoftReference<CImageResource> a` → **每个构造过的图像资源都通过静态缓存变成“软可达”**。
  3. 资源自身有 `private final ArrayList<CImageResource$c> retainCounter`，`CImageResource$c` 持有 `private final ICImageResourceUser a` → **资源强引用其使用者**（模型图里即持有它的 `CModelImage`）。
  4. `<clinit>` 以 `CHECK_TIMER_SEC = 300` 为周期 `timer.schedule(new com.live2d.graphics.b(), …)`；`b.run()` 调 `CImageResource$a.f()`。
  5. `CImageResource$a.f()` 遍历 `cacheList`：`SoftReference.get()` 为 null 的条目 `Iterator.remove()`；其余调 `archiveIfIdle()`。**循环在第一次成功 archive 后即 break**，且只要 archive 过就**自行调用 `System.gc()`**。
  6. `archiveIfIdle()` 仅在 `image != null` 且 `lastUse < now - ARCHIVE_IMAGE_TIME_SEC*1000`（=360s）时 archive；`archive()` 只是把图重编码进 `imageFileBuf`，**不**把条目移出 `cacheList`；只有 `dispose()` 才调 `removeFromCacheList()`。
- **为何这条机制解释全部观测**：软可达是**传递的**——`cacheList` 软引用资源 → 资源强引用 `ICImageResourceUser` → 使用者可达 `_modelSource` → `CModelSource` 的非 final `document` 反向指针。于是资源**与文档**都是**软可达**而非强可达；软可达对象**不会**被弱引用清除，显式 `System.gc()` 也只在内存压力下才清软引用。这与 848/848 资源弱引用未清、文档弱引用未清（+120s、+156s 与 sampler end）完全一致，同时登记表、视图历史与 34 项审查静态根全部为空；也解释了时间窗口：清理 tick 首次在 300s 后才触发、且只归档闲置超过 360s 的图像、每 tick 最多一个，而观测窗口（约 120–160s）落在驱逐窗口之内，且当轮 `MemAvailable` 为 6.3–9.9 GiB、软引用不会被清。
- **尚不能宣称的**：bytecode 只证明该机制**存在且足以**保留该图，**不证明**本轮那 848 个资源在关闭时确实在 `cacheList` 中；这需要下一片做**有界只读运行期观测**（`cacheList` 大小 + 用 `refersTo` 与已记录队列比对身份）。它也不排除 live thread 与 native 根。`releasedRetainUserData_forDebug` 是另一条独立列表（此前记为 N01），本轮无需它即可解释观测。
- **判定与后续**：把 N07/029 之后的第四方向定义为「**静态软引用缓存 + 资源→使用者强反向引用**」。若实机确认，则“848 个资源在关闭后仍存活”很可能是**设计内的软缓存保留**（驱逐取决于 JVM 软引用策略与 300s/360s 清理节拍），而非无界泄漏；相应的修复讨论应聚焦“关闭文档时是否应显式释放/驱逐”，而不是寻找新的 GC 根。修复须另立切片并单独授权，本切片不做任何清理。
- **证据归档（使用规则 7）**：`static-root-analysis/container-and-cache/`（`containers.py`、`sweep.py`、`sweep2.py`、`containers2.py`、`static-containers.txt`（869 个去重静态容器字段）、`sweep-output.txt`（117 个元素类型可达目标的容器）、`CImageResource.javap.txt`、`CImageResource_a.javap.txt`、`cleanup-timer-task.javap.txt`、`FINDING.md`）；归档 `MANIFEST.sha256` 已刷新（170 项）。
- **有效程度**：本轮新增内存/CPU/GPU 收益 **NONE**；产出为一条**由精确 bytecode 支撑、能同时解释既有全部观测、且可被只读实机观测证伪或确认**的机制候选。
