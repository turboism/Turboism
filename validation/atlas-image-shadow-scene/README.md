# T040 atlas-image-shadow 独立场景

本目录只实现 T040 的离线准备物：固定 5303 / Circle100 的最小 UI 采集驱动、T039 固定 freeze API 桥接、payload 持久化和 blocked bundle wrapper。它不修改 T036 probe、Runner、kernel probe、SDK、core 或 settings。

## 固定边界

- 输入源只由忽略的 `.env`/环境入口 `TURBOISM_HOST_VALIDATION_FIXTURE_5303` 提供，源 basename 必须是 `atlas_mapping_100.cmo3`，SHA-256 固定为 `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e`。
- Runner 负责把源文件复制为 `{TASK_ID}-atlas_mapping_100.cmo3`；driver 严格比较展开后的 `fixtureName` 与实际复制文件 basename，不把源 suffix 当作复制文件名。
- UI 只精确匹配唯一 fixture 主窗口、`建模 -> 纹理 -> 编辑纹理集...` 菜单、唯一 `OK`，以及编辑器内的 `自动排版...` 按钮和它打开的宿主排版对话框。宿主的整数降采样 kernel 只在有效缩放 ≤0.45 时才被调用，所以场景在排版对话框里选 `用户指定` 倍率；倍率是本场景唯一的按作业参数，由 wrapper 从 `{runLabel}` 后缀严格选择（`-scale40` = 0.45 以下，应命中 kernel；`-scale60` = 0.45 以上，只应走 Graphics2D 快路径；其它 `-scale<数字>` fail-closed，无后缀默认 40），以 `turboism.validation.atlasImageShadow.layoutScalePercent` 传入，并由 driver 的二值白名单复核（对话框 `com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f`，倍率控件 `com.live2d.ui.control.a.a.j`），再由对话框自己的 `OK` 应用；除这一处倍率外不改任何参数，不保存 CMO3，不导出。
- OK 后的 close poll 只在“安静窗口集”后继续，不默认点击 Save 或其他按钮。真实 5303 的宿主浮动窗口是普通 `javax.swing.JDialog`（`com.live2d.ui.window.m`），驱动自己的菜单弹窗是 heavy-weight popup；就绪门后立即采样一次 host window baseline（按窗口 identity），baseline 里已有的窗口不再参与判断，只有 baseline 之后新出现的窗口才进入判断，并且只有**模态**窗口（`Dialog.isModal()`，即能吞输入、能假装保存提示的那类；真实 5303 的 `jp.noids.framework.e.a.f` 进度窗就是 `setModal(true)`）才阻断。非模态新窗口（popup、宿主 palette）只记录不阻断。菜单动作派发后 `clearSelectedPath()` 清掉本驱动自己的 popup。
- T039 只通过固定的 `dev.turboism.validation.atlasimage.t039.T039ShadowAgent.freezeCapture(String)` 反射入口调用；没有复制 T039 实现或通用宿主反射。桥按交付源码/REPORT 的精确 43-key schema 校验 flat `Map<String,String>`，拒绝缺失/未知字段；离线 selfcheck 的 map 仅是严格同 schema stub，真实 artifact 另做纯 JDK bridge 反射回归。
- freeze 必须绑定当前 runId，且必须为 `state=SHADOW_READY`、`removalStatus=REMOVED`、`candidateReturnedCount=1`、`transformerRegistered=false`、`frozen=true`、`fullBoundsPreserved=true`。`NO_CALLS`、`NO_ELIGIBLE_CALLS`、`POTENTIAL_TRIM`、`TRUNCATED` 是分类，不等于覆盖或优化有效。
- 顺序固定为：唯一 OK 完成并关闭 editor → T039 freeze → payload 写入、关闭重读与 hash 校验 → 单次 `collectionStatus=COMPLETE`；canonical 发布前循环处理短写、force 后完整重读并校验四个固定字段，截断/写失败/零进展都不发布 COMPLETE → 唯一原生退出菜单。失败只尝试单次 `collectionStatus=FAILED`，写失败则保持无 canonical result。编辑器确认前先走一次排版：点唯一 `自动排版...`（宿主的 `APPLICATION_MODAL` 对话框会在 EDT 上阻塞到它自己的 OK 关闭它，所以 driver 先 post 点击、再等待窗口出现，然后从第二个 EDT 动作里配置并点它的 OK），`layout.dialogSeen`/`layout.dialogMillis`/`layout.scaleText`/`layout.applyElapsedMillis`/`layout.closed` 是有界证据；倍率字段被宿主拒绝时 fail-closed，不继续采集。
- native Exit 是否返回不作为采集成功条件；正常退出和 containment 由队列生命周期验收。COMPLETE 已发布后，exit 是 best-effort：点击以 fire-and-forget 方式投递（宿主的退出动作可能直接终止 JVM，永远不会回报完成），driver 不因 exit 未返回而把已完成的采集改写成失败。宿主在关闭有未保存改动的文档时用自己的选项面板提问（`com.live2d.util.UUOption` 从 Look-and-Feel 的 `OptionPane.yesButtonText`/`noButtonText`/`cancelButtonText` 构造三个按钮并以 `JOptionPane` 模态弹出）；fixture 必须字节不变，所以 scene 只允许回答**不保存**，且仅在唯一一个 option pane 里存在唯一的 no-button 时点击，期望标签从宿主同一个 Look-and-Feel 资源读取，不硬编码也不记录本地化文本，无法判定时保持不点击并记录原因。在有界窗口内（20 s，`EXIT_PROBE_SECONDS`）观察并记录 `nativeExit.clickMillis`、`exit.windowSamples`、`exit.windowCount`、最多 3 个去重后的结构类名 `exit.windowClasses`、`exit.promptSeen`/`exit.promptAnswer`/`exit.promptButtonCount`/`exit.promptClasses`/`exit.promptMillis`、`exit.nonDaemonThreadCount` 与 `exit.nonDaemonThreadNames`（同一字符白名单与截断），使“退出被什么挡住”可由结构证据诊断。driver 不调用 `System.exit`、kill、shutdown hook、外部 hook、client 或 collector。
## T039 runtime source binding 与 Runner 5303 事实

已只读核对统一 Runner、5303 官方 BAT 与最新 T039 runtime binding：Runner 的 `--jvm-option` 只展开 `{TASK_ID}`、`{HOME}`、`{FIXTURE}`、`{FIXTURE_NAME}`；没有 `{CUBISM_JAR}` 或构建机路径占位符。5303 Runner 将官方 Proton prefix 克隆到每个 task 的 `prefix`，然后在该隔离 prefix 内调用固定的 `C:\Program Files\Live2D Cubism 5.3.03\CubismEditor5.bat`。官方 BAT 先 `cd /d "%~dp0"`，再以相对 `app\lib\Live2D_Cubism.jar` classpath 启动，因此 JVM 内唯一候选是该 task-prefix 内部 Windows 路径 `C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar`；路径字符串固定是因为每个 task 使用独立 prefix，不是构建机 golden 路径，也不是 `{HOME}`/`{FIXTURE}` 的 Z: 映射。

wrapper/build manifest 只传 `sourceBinding=target-pd` 与上述单一 `trustedSourcePaths` 候选；`--t039-code-source`/`TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE` 仅在离线构建/发布时读取官方 JAR 并校验固定 SHA，不进入运行期属性。T039 在实际 JVM 内从首次目标定义的 ProtectionDomain 取得 `file:` CodeSource，`toRealPath()` 后要求唯一候选、精确路径、loader 与 JAR SHA，并在返回候选前复核；未满足即拒绝。若真实 launcher/classpath 不再满足这组事实，本场景保持 blocked，不猜测 Unix/Windows 映射或改用宿主路径。

同一组事实也是 driver 的启动门：`DriverConfig.fromSystemProperties()` 不读 `t039.codeSource`，而是要求 `sourceBinding=target-pd` 与一个绝对、以 `Live2D_Cubism.jar` 结尾的 `trustedSourcePaths` 候选（纯字符串形状检查，保证离线可复现）；真正的 canonical/PD/loader/hash 复核仍在 T039 内进行。离线回归把 Runner `--dry-run` 规划出的完整 JVM 属性集灌入真实 `fromSystemProperties()`，并断言删掉 `t039.sourceBinding`/`t039.trustedSourcePaths` 时被拒绝。

## EDT 与证据

`FixedEdt` 仅服务这个固定场景，把两种预算分开：**排入队列的启动预算**和**已开始动作的查询预算**，各 5 秒有限等待。queued callback 在超时或中断时原子转为 `TIMED_OUT`，迟到 callback 只记录 skipped，不执行动作。已经 `STARTED` 的动作不可撤销、不可伪称回滚；固定查询预算只负责报告它，不再把“宿主还没跑完”当成“动作没发生”。菜单 `doClick` 的开始屏障、OK 的同步模态动作与 modal action completion latch 分离，以容纳同步模态菜单动作。

driver 在 premain 启动，早于宿主完成自身 bootstrap，所以驱动 UI 之前先过一道就绪门：连续 3 次 EDT 往返都必须在 1000 ms 内完成；任何慢/排队的往返都重置计数并累加 `readiness.slowRounds`，避免在宿主仍占用 EDT 时抢跑。真实 5303 的 `OK` 会在 EDT 上同步执行纹理集重建，可能远超 5 秒：driver 只把 `OK` 按下作为开始点，随后在整体 run deadline 内轮询 editor 关闭（纯读轮询被 queued 超时打断时只记录并继续），再等待菜单模态动作结束；超时仍在整体 deadline 内重试，不提前宣告失败。

每个 run 只原子替换一个有界 `driver-stage.properties`，写入真实换行，并记录 stage、时间/耗时、EDT queued/started/completed/timedOut/lateSkipped 计数、就绪门轮次/慢轮次、OK 派发与等待耗时、固定布尔状态及最后一次操作；不写窗口标题、模型内容或无界事件历史。driver 在干净 task home 中安全创建固定 output parent，并以 `CREATE_NEW` 原子 claim `state/atlas-image-shadow/<taskId>`；重复、并发落败、symlink 或越界路径 fail-closed。启动窗口查找最多使用 240 秒 startup budget；这不是性能阈值，也不会重试已开始动作。

close poll 会一直采样到 `CLOSE_POLL_TIMEOUT_SECONDS`（240 s）：只有 editor 已关闭且没有模态窗口时才继续 freeze，否则在预算耗尽时 fail-closed，而不是在第一次看到窗口时就误判。它把 `editor.closePollVisible`、`editor.closePollUnexpected`、`editor.unexpectedWindowCount`/`Classes`（模态，阻断集）、`editor.nonModalWindowCount`/`Classes`（非模态，容忍集）、`editor.closePollMillis`、`editor.closePollTimedOut` 与 `baseline.windowCount`/`baseline.windowClasses` 写入有界证据（每类最多 3 个经过白名单与长度截断的类名，不记录标题、模型内容或无界历史）。

## 实机采集结果（5303 / Circle100）

T039 整数降采样 kernel `com.live2d.util.f.g.a(II[III[IIIII)V` 在全 JAR 里只有一条调用链：`CTextureAtlas.setupCacheImage$cubism` → `D.a(CWritableImage, ui.g, CWritableImage, I, I, Z)`（mask=32 取默认 double `0.45`）→ `g.a(BufferedImage, Graphics, BufferedImage, I, I, D, Z)`；该 7 参方法在偏移 166 比较 `min(|sx|,|sy|)` 与 `0.45`，**只有有效缩放 ≤0.45** 才落入调用 kernel 的分支，否则走 UtCache 临时图 + `Graphics2D.drawImage` 快路径。四次实机采集把这一条件变成了可核对的事实：

| 运行 | 排版倍率 | `freeze.sampleOutcome` | `stats.eventLimit` | `recorded`/`dropped` | `admitted`/`potentialTrim` | 最后一次真实参数 |
|---|---|---|---|---|---|---|
| job 8 `queue-de28907537e447128af8f25bd2af14cb` | 未排版（当前页 1:1） | `NO_CALLS` | 16 | 0 / 0 | 0 / 0 | — |
| job 9 `queue-bbe77b2627ba4c61bad9009516692145` | 用户指定 40% | `TRUNCATED` | 16 | 16 / 84 | 16 / 16 | kx=2 ky=2 padding=2 source=100×100 stride=104 destLen=10816 full=100×100 |
| job 10 `queue-6b482897236f4d939bc392fa84e51c76` | 用户指定 40%（窗口放宽到 64） | `TRUNCATED` | 64 | 64 / 36 | 64 / 64 | 同上（2×2 降采样） |
| job 11 `queue-53c2672283984acf91e34dd1c5c90269` | 用户指定 60%（反向对照） | `NO_CALLS` | 64 | 0 / 0 | 0 / 0 | — |

四次都是 5303 队列 PASS（`state=succeeded`、`validationStatus=PASS`、`normalExit=true`、`identityVerified=true`、`fixtureUnchanged=true`），fixture 全程字节不变（场景从不保存，退出提问只回答不保存）。driver 证据：job 9 `layout.dialogMillis=585`、`layout.scaleText=40`、`editor.closePollMillis=11902`、`runElapsedMillis=87388`；job 10 `layout.dialogMillis=408`、`layout.scaleText=40`、`editor.closePollMillis=9797`、`runElapsedMillis=109059`；job 11 `layout.dialogMillis=244`、`layout.scaleText=60`、`runElapsedMillis=93940`；三次排版都 `layout.dialogSeen=true`、`layout.closed=true`。

结论与限制：kernel 的触发条件是宿主排版倍率，不是驱动缺陷。job 9/job 10 都发生 100 次 kernel 调用（job 10 为 64 进窗口 + 36 丢弃），被记录的前 64 次全部 `admitted=true` 且 `potentialTrim=true`，参数为 2×2 降采样；job 11 在 60% 倍率下 **0 次调用**，与 job 8 的 1:1 一起从另一侧坐实“有效缩放 ≤0.45 才走 kernel”。`freeze.sampleOutcome=TRUNCATED` 只说明 T039 的有界事件窗口（`stats.eventLimit=64`，已是 T039 上限）被填满，`recorded/admitted/potentialTrim` 计数只在窗口未满时推进，所以这仍是 **64/100 的前缀分类**，不是 100 次调用的全量命中率；要得到全量分类需要改 T039 的计数语义（属于 T038/T039 交付，不在本场景范围内）。`stats.methodExecuted=false` 是 shadow-ready 的预期值（原方法体不执行，候选原样返回全边界）。

按作业倍率的选择方式本身就是这次反向对照的对照条件：job 10 与 job 11 使用完全相同的 driver/T039/生产 agent 字节，只差 `{runLabel}` 后缀（`-scale60`）经 `turboism.validation.atlasImageShadow.layoutScalePercent` 传入的一处参数。

参数分布与配对性能（SC-02/SC-04a）仍待完成：当前证据只能证明“真实入口会走到该 kernel、记录了真实参数、阈值可由倍率两侧控制”，不能替代优化开/关的配对耗时、CPU 与内存测量；job 9/10/11 的 `runElapsedMillis`（87–109 s）差异不能当作收益或回归。

## 构建与离线验证

```bash
TURBOISM_HOST_VALIDATION_FIXTURE_5303=/path/from/ignored/.env/atlas_mapping_100.cmo3 \
  bash validation/atlas-image-shadow-scene/build-and-selfcheck.sh
TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT=/path/to/explicit/t039-shadow-agent.jar \
  TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE=/path/from/ignored/Live2D_Cubism.jar \
  bash scripts/test/test_atlas_image_shadow_host_validation.sh
```
`TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE` 是构建期只读参考；它不被写入 `trustedSourcePaths`，也不作为 task clone 的运行期 CodeSource 猜测。
运行期可选的运行标签后缀（都 fail-closed，默认行为一字未改）：
- `-scale40` / 无后缀 = 40%（低于宿主 0.45 阈值，命中 kernel）；`-scale60` = 60% 反向对照；其它 `-scale<数字>` 拒绝，重复指定或畸形后缀也拒绝。
- `-jfr` = 额外注入 `-XX:StartFlightRecording=filename={HOME}\atlas-profiling.jfr,settings=profile,maxsize=256m,dumponexit=true`，用于 T029 瓶颈测量；不改变场景动作、参数或结果文件。
- `-heavy` = 切换到生产尺度 fixture `heavy.cmo3`（SHA-256 `029e9a4e…`，来自 `TURBOISM_ATLAS_IMAGE_SHADOW_FIXTURE_HEAVY_5303`），并把墙钟预算提高到启动/关闭轮询 1800 s、排版对话框 900 s、驱动 3600 s、结果 3600 s、退出 300 s。该档案需要自己的 published manifest（`build-and-selfcheck.sh --publish --fixture-profile heavy`），因为 manifest 会固定一对 fixture 身份。

运行期唯一按作业参数是排版倍率：wrapper 从 `{runLabel}` 后缀选择（无后缀/`-scale40` = 40%，`-scale60` = 60%，其它 `-scale<数字>` fail-closed），并通过 `-Dturboism.validation.atlasImageShadow.layoutScalePercent` 传给 driver。
构建使用 JDK 17、`-Xlint:all -Werror`，普通编译 classpath 不含官方 Cubism JAR、T039 源码或宿主类。selfcheck 覆盖：

- 实际 Swing EDT 菜单→模态 editor→唯一 `自动排版...`→模态排版对话框（`用户指定` + 倍率文本字段）→唯一 OK→关闭→原生退出状态机；离线 stand-in 按宿主语义只在值 label 的 `mouseClicked` 后才把文本字段加入组件树，并断言驱动写入的倍率被对话框读到（默认 40% 与显式 60% 各跑一遍完整 driver 流程，且 `40/60` 之外的 45/55/40.0/null 被白名单拒绝）；重复 OK 和未知保存 prompt 均拒绝且不点 Save；OK 同步阻塞 EDT 超过查询预算时仍到达 COMPLETE，宿主占用 EDT 的就绪门慢轮次被记录；
- queued 超时失效、迟到 callback 不动作、started 动作迟返回仍不覆盖失败、正常动作、异常和中断等待；
- T039 精确 43-key schema、wrong-run、缺字段、非 flat scalar、未知字段及四种 sample 分类；shell 回归用显式 T039 agent JAR 做纯 JDK public-static bridge 检查，未定义/执行官方类；
- payload 原子写、关闭重读/hash、重复 payload；canonical 短写循环、force 后完整 Properties/字节一致性校验、重复/截断/写失败/零进展均不产生 COMPLETE；失败结果仍为单独 FAILED；
- driver-stage.properties 的 `Properties.load` 解析与真实换行；
- 干净 home 的 output/run 创建、重复/并发 claim、symlink/越界路径拒绝；
- `hostExecuted=false`。自有 fake editor 只进入 selfcheck JAR，不进入 production driver JAR。
- 包装层离线回归另外断言：默认 run label 产出 `layoutScalePercent=40` 与 `-Dturboism.validation.t039.maxEvents=64`；`-scale60` label 产出 `layoutScalePercent=60`；`-scale55` label fail-closed 且不产出任何 `layoutScalePercent`；启动属性契约接受规划出的整套属性、拒绝 `layoutScalePercent=55`，并拒绝删掉 `t039.sourceBinding`/`t039.trustedSourcePaths` 的计划。

## Bundle / admission

生产 bundle 必须使用 `build-and-selfcheck.sh --publish` 并显式提供 production agent、T039 agent、真实官方 JAR code source、loader class 和两个 helper hash；脚本会校验真实文件和 SHA-256，按当前 worktree id 生成唯一 bundle，不使用 `latest`，也不覆盖已有 manifest。没有真实 T039 artifact/API 时不得用 stub 生成 production bundle。

`scripts/preview/host-validation-020-shadow.json` 和生成的 bundle manifest 都保持 `runnable=false`。本轮不 prepare、submit、启动宿主或运行真实 collector；主代理和管理器需另行审核 T039 artifact、固定 OK 文案/归属、payload 后验、正常退出与 fixture 不变性后才能解除门禁。
