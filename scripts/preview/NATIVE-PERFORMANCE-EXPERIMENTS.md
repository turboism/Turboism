# 原生性能实验台账

最后更新：2026-09-07。当前主指标由用户确定为 **内存占用、CPU 占用、GPU 占用**。累计分配、调用次数和缓存命中率仅用于解释，不替代主指标。

本台账是本任务的统一检索入口，不是构建/运行时依赖，也不替代结构化 exact-host 证据。历史数据、失败和后续相反结果必须同时保留。所有实现位于独立分支；未授权合并或推送 main。

## 使用规则：防止无依据地重复试验

1. 实验前查编号、调用点、算法和参数范围；没有实质变化时，不重跑已经否定的方案来寻找有利样本。
2. 每次试验结束立即记录：**实现、验证方法、结果、原因/假设、证据、重试条件**。未成功完成的试验也要记录。
3. 明确区分 `PRIMARY_BENEFIT`（当前主指标获益）、`ALLOCATION_ONLY`、`NO_BENEFIT`、`INCONCLUSIVE`、`VALIDATION_PASS`、`VALIDATION_FAIL`、`PLANNED`。验证通过不等于优化成功。
4. 重试必须引用旧编号，并写明改变了什么：实现、输入、精确宿主、观测协议或可证伪假设。新观测器可能扰动 GC/时序，不跨批次拼接百分比。
5. 原因分为**已证实**和**可能/未定**；不能把一次成功重跑当成前一次失败的根因证明。
6. 终态判定包含 runner、身份、原文件 hash、正常退出和清理；辅助结果 `status=PASS` 不足以覆盖整轮 FAIL。只提交摘要/代码，不提交模型、官方 JAR 或含 licensing 信息的原始日志。

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
