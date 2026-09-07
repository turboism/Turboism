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
| P01 | 变形器坐标只读投影 | PLANNED，用户已批准实施验证 | 尚未实现/验证，不得当成成功缓存 |
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

## P01 — 已批准、尚未验证的下一个候选

- 用户已批准实施并验证：只替换精确 `CExtendedInterpolationExtension.updateInterpolatedForms_common` 第一段只读 `getAllPointRef().map(getPos)`，直接创建同样的新native GVector2列表。
- 不缓存WarpPointRef、不保留form/positions、不改第二段保留引用的写路径、插值数学、dirty、Undo或GL。默认关闭、精确版本/完整方法形状、异常回退和恢复必须保留。
- 依据是已有JFR中的实际临时点引用成本及只读字节码调查；约846MB是采样权重，不是RAM节省预测。
- 实现后须逐调用native坐标bit对照、fresh对象/错误语义/未改写路径验证，再做相同三指标实验。**目前PLANNED，不得填入成功数据。**
- 设计细节在当前任务本地冻结文档 `docs/agents/native-warp-position-projection-proposal-20260907.md`；该本地文档不构成构建依赖。实验完成立即追加本台账结果和重试条件。

## P02 — 尚不能宣称验证过的方向

Dirty rectangles、局部图集合成、属性级VBO更新、原生更新合并、全局WarpPointRef缓存、XML校验绕过都未通过本任务的完整实施/实机验收。缺少完整像素写范围、边缘失效、消费前flush或可变引用生命周期证明时不启用。不能将“考虑过”写成“算法已失败”，也不能每次从同一无证据假设重新开始。

旧 `run-cubism-camera-scenario.sh` 实际驱动窗口resize，不是真正camera/拖参场景；源码历史注释不能当成本轮GPU证据。新的交互GPU负载必须说明真实动作、任务窗口身份、恢复和采样窗口后再验证。
