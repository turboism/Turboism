# T040 atlas-image-shadow 依赖与证据清单

## 范围、授权与状态

- 场景：`atlas-image-shadow:5303`，固定 Circle100，独立于 T036；T036 保持 BLOCKED。
- 本次只新增 `validation/atlas-image-shadow-scene/`、`scripts/preview/run-atlas-image-shadow-host-validation.sh`、`scripts/preview/host-validation-020-shadow.json`、`scripts/test/test_atlas_image_shadow_host_validation.sh`。
- 未修改 Runner、kernel-probe、SDK、core、settings、T036；未 prepare、submit、启动宿主、发送信号或运行外部 collector。
- `runnable=false` 是固定门禁；本清单不是实机验收。

## 固定输入与只读依赖

| 项目 | 入口/摘要 | SHA-256 |
|---|---|---|
| Circle100 源 fixture | `${TURBOISM_HOST_VALIDATION_FIXTURE_5303}`，basename 必须为 `atlas_mapping_100.cmo3` | `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e` |
| 官方 Cubism JAR（构建期参考） | `${TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE}`，显式 `Live2D_Cubism.jar`；仅供离线 build/publish 校验，不写入运行期 `trustedSourcePaths` | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` |
| T039 agent JAR | `${TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT}`，交付文件名 `t039-shadow-agent.jar`；本轮实际纯 JDK bridge 输入 | `88a30f4aa156e10af009532d45bb066c01840aaaae9aa986f8985038d7d85a0a` |
| T039 source | 任务指定的只读 `T039ShadowAgent.java` 参考；本实现不运行时读取、不提交个人路径 | `de2b24bfb2de1eb7ec54f80f917244baf840e794e24942e894c58048837da9d1` |
| T039 REPORT | 只读 schema/eligibility/runtime-binding 依据；本实现不运行时读取、不提交个人路径 | `17048d7dc371539fa196f1d8c1e90cc67b912706164350d002beac1f1928cc36` |
| T039 helper class | 由 T039 artifact/inventory 显式交付 | `9aa1db0ff67c27b026d4c29fd56485bfb0641c92d1e5b31963764462c4a8caf0` |
| T038 helper class | T039 依赖摘要 | `47500ced125b0d69bd42d1af3f3d55137a2e3fb785ffaca87f69678bf3635fd6` |
| T039 official class resource | 固定 5303 profile | `ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6` |
| T039 5303 shape | 固定目标 shape 摘要 | `a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f` |
| T039 runtime binding | `sourceBinding=target-pd`；唯一 `trustedSourcePaths` 候选为隔离 task-prefix 内部的 `C:\Program Files\Live2D Cubism 5.3.03\app\lib\Live2D_Cubism.jar`，由实际 JVM `ProtectionDomain`/`toRealPath()` 与 loader/hash 复核 | `T040RuntimeSourceBinding.java` `2b0e3c1b92496c4547f7ad2ae1a80771398c63f302769a041d861a07d849b12c` |
| T040 runtime binding harness | 只读验证同 hash shifted path、错 hash、多候选、非 file CodeSource、PD/path mismatch、wrong loader；不定义/执行官方类 | `T040RuntimeSourceBindingHarness.java` `72844c8329ba79f5dbcc38e949a491bc7bbedb7e6b57166b4650e4ad818367f0` |
| Runner 5303 path evidence | Runner `cubism_win`/`cubism_rel` 固定为 5303，prefix 按 task 克隆；官方 BAT `cd /d "%~dp0"` 后使用相对 `app\lib\Live2D_Cubism.jar` classpath | Runner `e17ec59c1cc1400f4b0f033f5a1ef72042e0099f1514973e0d606cd8fd6cdc35`; BAT `85552a49c6633a997d4e8df41dcad6b92b4e6bcf06dc1a0c333b5e13e5ea10bf` |

普通 T040 编译/selfcheck classpath 不含官方 JAR、T039 source 或官方类。shell 回归只把本轮显式 T039 agent JAR（SHA `88a30f4aa156e10af009532d45bb066c01840aaaae9aa986f8985038d7d85a0a`）放入临时纯 JDK probe classpath，确认 `public static Map<String,String> freezeCapture(String)` 并确认未布防调用被拒绝；不定义或执行官方 Cubism 类。

## T039 freeze schema 与准入

桥接依据只读 T039 `createFrozenCapture` / `requireFreezeEligible`，严格要求完整 43-key flat string map；缺失、未知、非字符串、换行/NUL、错误 run/profile 或错误固定状态均拒绝：

```text
schemaVersion, runId, profile, shadowMode, state, removalStatus,
transformerRegistered, candidateReturnedCount, frozen, fullBoundsPreserved,
sampleOutcome, reason, helperPrewarmed, helperHash, t038HelperPrewarmed,
t038HelperHash,
stats.recorded, stats.dropped, stats.admitted, stats.rejected,
stats.aliased, stats.potentialTrim, stats.eventLimit,
stats.lastKx, stats.lastKy, stats.lastSourceWidth, stats.lastSourceHeight,
stats.lastStride, stats.lastFullWidth, stats.lastFullHeight, stats.lastPadding,
stats.lastSourceLength, stats.lastDestinationLength, stats.lastAliased,
stats.lastAdmitted, stats.lastPotentialTrim, stats.lastOptimizationRequested,
stats.targetEvents, stats.lateCallbacks, stats.candidateCount,
stats.candidateReturnedCount, stats.rejectionCount, stats.methodExecuted
```

固定 5303 场景还要求 `state=SHADOW_READY`、`removalStatus=REMOVED`、`transformerRegistered=false`、`candidateReturnedCount=1`、`frozen=true`、`fullBoundsPreserved=true`。`sampleOutcome` 仅接受 `NO_CALLS|NO_ELIGIBLE_CALLS|POTENTIAL_TRIM|TRUNCATED`；这些是采集分类，不是覆盖、优化或正确性证据。冻结后不再写 payload 数据；晚到调用由 T039 自身 full-bounds 语义处理，不得改变冻结快照。

## 实现摘要与路径边界

- `T040ShadowSceneDriverAgent` 是固定场景 driver：严格 task/fixture 身份，唯一主窗口，精确 `建模 -> 纹理 -> 编辑纹理集...`，精确编辑器类 `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`，唯一 OK；编辑器内再点唯一 `自动排版...`，在宿主排版对话框（`com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f`，`APPLICATION_MODAL`）里点 `用户指定`、把倍率控件（`com.live2d.ui.control.a.a.j`）的文本字段写成固定 `40`%，并以该对话框唯一 `OK` 应用。宿主整数降采样 kernel 只在有效缩放 ≤0.45 时被调用，这一处固定倍率就是让真实 OK 重建走到 kernel 的唯一动作；不改其他参数，不保存 CMO3，不导出。
- 未知 secondary window、保存提示、重复 OK、异常或动作未完成均 fail-closed；不默认点击 Save/Cancel/OK 以外未知控件。就绪门后先采样一次 host window baseline（identity 集合，只含当时已 showing 的非主窗口）：真实 5303 的宿主浮动窗口是普通 `javax.swing.JDialog`（`com.live2d.ui.window.m`），驱动自己的菜单弹窗是 heavy-weight popup。baseline 之后新出现的窗口才算候选，且只有模态窗口（`Dialog.isModal()`；真实 5303 的 `jp.noids.framework.e.a.f` 进度窗为 `setModal(true)`）才阻断；非模态新窗口（popup、宿主 palette）只记录不阻断。菜单动作派发后 `MenuSelectionManager.clearSelectedPath()` 清掉本驱动自己的 popup。close poll 只观察不操作，持续采样到 240 s 预算：只有 editor 关闭且无模态窗口才继续，预算耗尽才 fail-closed；写入 `editor.closePollVisible`、`editor.closePollUnexpected`、`editor.unexpectedWindowCount`/`editor.unexpectedWindowClasses`（模态）、`editor.nonModalWindowCount`/`editor.nonModalWindowClasses`（非模态）、`editor.closePollMillis`、`editor.closePollTimedOut` 以及 `baseline.windowCount`/`baseline.windowClasses`，每类最多 3 个白名单过滤且截断的类名，使阻止原因可诊断且不泄露标题/模型内容。
- 顺序为：OK 后确认 editor 关闭与 action completion → T039 freeze → payload 原子创建、关闭重读与 hash 校验 → canonical 单次 `COMPLETE`。canonical 发布前循环处理短写、force 后完整字节/Properties 一致性校验；截断、写失败、零进展或重复目标不发布 COMPLETE。前置失败只尝试一次 `FAILED`；失败写入保持无 COMPLETE。native Exit 返回不作为采集成功条件：COMPLETE 后以 fire-and-forget 投递退出点击（宿主退出动作可能直接终止 JVM），不因未返回改写已完成的采集；宿主关闭未保存文档时会用 `UUOption`+`JOptionPane` 模态提问（按钮文本来自 Look-and-Feel 的 `OptionPane.yesButtonText`/`noButtonText`/`cancelButtonText`），scene 只允许在唯一 option pane 内点击唯一的 no-button（期望标签同样从 Look-and-Feel 读取，不硬编码/不记录本地化文本），无法判定则不点击并记录原因；20 s 有界窗口内记录 `nativeExit.clickMillis`、`exit.windowSamples`/`windowCount`/`windowClasses`、`exit.promptSeen`/`promptAnswer`/`promptButtonCount`/`promptClasses`/`promptMillis`、`exit.nonDaemonThreadCount`/`nonDaemonThreadNames` 作为诊断证据；生命周期/containment 由队列门禁负责。
- `FixedEdt` 只服务本场景；纯读与动作使用 `invokeLater`，排队的启动预算与已开始动作的查询预算各 5 秒。状态为 `QUEUED -> STARTED -> COMPLETED` 或 queued 竞争失败后的 `TIMED_OUT`；超时/中断的 queued callback 迟到不执行，已 STARTED 不伪称可撤销，但其长耗时不再被误判为失败。编辑器菜单 modal `doClick` 的 started barrier、OK 的同步模态 apply 与 action completion latch 分离。
- driver 在 premain 启动，驱动 UI 前先过就绪门：连续 3 次 EDT 往返均须 ≤1000 ms，慢轮次重置计数并计入 `readiness.slowRounds`。`OK` 只等待按下，随后在整体 run deadline 内轮询 editor 关闭（queued 超时只记录并继续）并等待模态动作结束；真实 5303 的同步纹理集重建不再触发假 `EDT_TIMEOUT`。
- 启动主窗口最多使用 240 秒 startup budget；queued 的纯读 lookup 超时可在整体 deadline 内继续等待，已 STARTED 的 lookup 不重试。默认 Runner result timeout 900 秒未改变。
- `driver-stage.properties` 为有界可替换快照，记录 stage、epoch/elapsed、EDT 计数/状态、动作完成、freeze、payload、canonical、native-exit 布尔；写入真实换行，不写标题、模型内容或无界事件历史。
- task home/output 只从显式 `turboism.home` 与 runId/taskId 定位；driver 在干净 home 安全创建固定 `state/atlas-image-shadow` parent，并用 `CREATE_NEW` 原子 claim `state/atlas-image-shadow/<taskId>`，拒绝重复/并发落败、symlink 和越界路径。重复 payload/result 拒绝，bundle 按 worktree id 与 artifact hash 唯一定位，不使用 `latest`。T039 runtime 候选不使用个人/构建机路径，而是 Runner 5303 每个隔离 task-prefix 内部的固定 Windows path；wrapper 只传 `sourceBinding=target-pd` 与一个候选，实际 JVM 负责 canonical/PD/loader/hash 复核。

## 依赖、构建与验证命令

依赖仅为 JDK 17 AWT/Swing/标准库；零网络、零秘密、零外部服务要求。所有路径由 ignored `.env` 或显式环境变量提供，不把个人 worktree 路径写入生产脚本、wrapper、manifest 或 inventory。

```bash
TURBOISM_HOST_VALIDATION_FIXTURE_5303="${TURBOISM_HOST_VALIDATION_FIXTURE_5303}" \
  bash validation/atlas-image-shadow-scene/build-and-selfcheck.sh

TURBOISM_HOST_VALIDATION_FIXTURE_5303="${TURBOISM_HOST_VALIDATION_FIXTURE_5303}" \
TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_AGENT}" \
TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE="${TURBOISM_ATLAS_IMAGE_SHADOW_T039_CODE_SOURCE}" \
  bash scripts/test/test_atlas_image_shadow_host_validation.sh
```

builder 使用 `javac --release 17 -proc:none -implicit:none -Xlint:all -Werror`；不执行 `--publish`，不创建 production bundle。只有管理器审核后，才可用显式 production agent、T039 agent、构建期官方 code source、loader class 与 helper hashes 调用 `build-and-selfcheck.sh --publish`；发布 manifest 只记录运行期 binding/path 字段，不把构建机 code-source 路径转发给 task JVM，且仍 blocked。

## 当前离线证据

- `bash -n`：builder、wrapper、test 通过；`python3 -m json.tool scripts/preview/host-validation-020-shadow.json` 通过。
- 最新 offline build：`T040_SHADOW_SCENE_SELFCHECK PASS checks=78 hostExecuted=false`；driver artifact SHA `cd0df055e92003798f9fca2c4aeebd50840e9f21eb6d43752cac8dada915dc47`，相对输出位置为 `build/atlas-image-shadow-scene/compile.xPoRtn/atlas-image-shadow-scene-driver.jar`。
- shell 回归通过（`PYTHONOPTIMIZE=1`）：static contract、实际 T039 agent JAR SHA `88a30f4aa156e10af009532d45bb066c01840aaaae9aa986f8985038d7d85a0a` 的纯 JDK public-static bridge（unarmed rejected）、Runner dry-run 的 task-local prefix/fixture 展开且未传 build-host golden path、unknown-key 与 PreparedStore invalid-input 拒绝、完整 capture→删除仅合成 source/bundle/agent/fixture→snapshot replay/hash；输出 `T040_PREPARED_STORE_REPLAY ... hostLaunched=false` 与 `ATLAS_IMAGE_SHADOW_HOST_VALIDATION_OFFLINE_TEST PASS hostLaunched=false`。
- selfcheck 预期的两个 `ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED` 行来自保存 prompt/重复 OK 负控；claim 的干净 home/并发/重复/symlink/越界负控及 canonical 短写/截断/写失败/零进展负控均在同一 `checks=78` 离线 selfcheck 内通过，不是宿主执行。manifest `runnable=false`，本轮未 prepare、submit 或启动宿主。

## 本次交付文件 SHA-256

```text
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/FixedEdt.java                         eaa61f1b9d73298cf9ef1587d8fd4a67f01c9d4bde16db9da97cfa68fca8a7ec
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/ShadowPayloadStore.java               df5ba84928bb9303d18e1ed7785f6f31f7ce6b1d27db27f534c68e2e1e6efb1b
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/ShadowSceneContract.java              c5ca6cb8c6d4caaae3c272001f9ec356369d0a9b21b361349098378b32469cf6
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/StageEvidence.java                    5b0b29482d884bae02ba3ba4089ca309839c19e717dd363d7011bd6db2b60507
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/T039FreezeBridge.java                  f701b8e56ce3fc3569d8e36225243f0bc095904dbb39f9593e519a397330bf42
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/T040ShadowSceneDriverAgent.java        1f294e1e68c8874d61732a250634e196647ff4f9e50a79452df6cc06001e3939
validation/atlas-image-shadow-scene/selfcheck/com/live2d/cubism/doc/modeling/ui/atlasEditor/f$b.java                    3d9f051958d3d0ed8cc793464492367bc657fdc76ab492b7027508f20212ad6b
validation/atlas-image-shadow-scene/selfcheck/dev/turboism/validation/atlasimage/shadow/T040SelfCheck.java              e6b8b74266fb913faf5b73a3b2c92a20b7a80016221625a5cf19412873603e94
validation/atlas-image-shadow-scene/build-and-selfcheck.sh                                                             8a11898dec00d3ab9d205983b65597e0cbb706bddc8e3dcd0ece938fe65ee578
validation/atlas-image-shadow-scene/README.md                                                                          e799d96be2a98f7706a7bae8f7a21eec2c7f21c8ee92c66240e5a6676beacc0f
scripts/preview/run-atlas-image-shadow-host-validation.sh                                                              efaafd63b5edbc5bd30711cfa468bd026e15ffddee5351103a1fa7645a023853
scripts/preview/host-validation-020-shadow.json                                                                        37406dcbb9ee31edae731c4b3064c822ee40c1504638dd57b9fd6785204da986
scripts/test/test_atlas_image_shadow_host_validation.sh                                                                 1e13d0b7df83a42da3adea3419a519799b649d403176b46c2a3703d63745c3c5
```

## 责任与剩余门禁

- driver 负责固定 UI 动作、T039 freeze eligibility、payload 内容与 hash、secure output/run claim、自身 canonical 单次发布和失败 fail-closed。
- Runner/队列管理器负责 prepare/submit、宿主生命周期、原生正常退出、containment、result timeout、cleanup 与最终任务判定；当前 wrapper 不提供 hook/client/collector/readiness 通道。只有队列看到宿主自身的 `-- successfully exited pid:N --` 标记（`com.live2d.cubism.appCtrlImpl.bn.c()`）才判定正常退出；driver 侧的 exit 证据不能替代它。
- 主代理仍须复核真实 production agent、T039 参数/依赖逐项 hash、Runner 展开后的 `{TASK_ID}-atlas_mapping_100.cmo3`、真实 UI OK 文案/归属、payload 后验、fixture 源/副本不变性，并经管理器解除 `runnable=false` 后才能实机准入。本次没有实机验收。

## Primary 整合产物（T040 首轮候选，取代上述历史 artifact 摘要）

- production agent SHA-256：`224d6ee29597dcb520aa294ea5e569dcc8cb4b95831e179e00dd33b7522e3261`。
- T039 自包含 agent SHA-256：`29323bf536208a76b2a1c7ff532e5b34ac98742b4cd69c8d51cd03687c953a3a`；私有 ASM 9.7.1 共 38 类，无外部 Class-Path。
- scene driver SHA-256：`367b0076c912017bd7552862080c20917e87d5629a6f34020571b9cf332f6996`。
- T039/T038 helper class SHA 与上表相同；fixture/官方 JAR/class/shape 身份不变。
- kernel 独立构建证据：`/tmp/atlas-image-kernel-probe.GSaHv6`；scene 构建：`build/atlas-image-shadow-scene/compile.JrOqlU`。
- 交付清单：`build/preview/optimize-atlas-processing-cache-parallel/atlas-image-shadow/bundle.manifest`。
- 预期 loader 为 `jdk.internal.loader.ClassLoaders$AppClassLoader`；这是本轮拒绝不匹配的准入条件，不是此前观察已证明的 loader 类名。
- 此记录仅固定本轮输入，不代表 prepared 或宿主验收通过。

## T040 首轮实机失败与 launch-property 契约修复（取代上述 driver/契约相关摘要）

- 首轮实机作业 `f37232b8-3aa5-4cf2-8c5c-c40d71fd7b83`（seq 69，prepared `2e267cc65d79c8997f405736cffe0e844fe776a66cff0e447bcdf23966629cd1`）已 **failed**：`cubism-console.txt` 首行计划内无 `t039.codeSource`，driver premain 抛 `IllegalArgumentException`，证据目录出现 `ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED IllegalArgumentException`；driver 未启动，`state/atlas-image-shadow/result.txt` 不存在，exit 1。宿主 JAR 身份核对 PASS，fixture 与 golden/clone 哈希未变。
- 根因：T039 源绑定改造后 wrapper 只传 `sourceBinding=target-pd` + 单一 `trustedSourcePaths`（静态契约禁止 `codeSource` 出现），但 `T040ShadowSceneDriverAgent` 仍无条件要求 `t039.codeSource`。离线 selfcheck 只用 `DriverConfig.forSelfCheck(...)`，从不运行 `fromSystemProperties()`，因此 `checks=78` 全绿仍漏检。
- 修复：driver 启动门改为校验 `sourceBinding=target-pd` 与单一绝对 `trustedSourcePaths` 候选（以 `Live2D_Cubism.jar` 结尾）；selfcheck 增加正向/负向形状用例（`checks=88`）；shell 回归新增端到端启动门：解析 Runner `--dry-run` 的全部 `jvmOption.*`，用真实 `fromSystemProperties()` 断言接受，并断言去掉 `t039.sourceBinding`/`t039.trustedSourcePaths` 即拒绝。
- 回归证据：同一份计划下，修复前 driver `367b0076…` 报 `missing turboism.validation.t039.codeSource`，修复后 driver（本次构建 `compile.vDgVdR`，SHA `96b4f835bd80d7074e08505d04e3333ff7ded0ea00dab3b5f85530e5f4b88470`）PASS；driver JAR 逐次构建不保证字节相同（每次构建 SHA 不同），bundle manifest 按发布时实际文件记录 hash。
- 修复后文件 SHA-256（取代上表对应行）：`ShadowSceneContract.java f72aa1419263b4ecf020a162e32ed3ca96cfbf1185f269afb8fb9dea04637566`；`T040ShadowSceneDriverAgent.java 4ea62718c2ce2cbccdcf88b9516c300c868dbce234906201966e67d0e7df4a2c`；`T040SelfCheck.java 6ae7c2919495e2a541738e62c3d10f42dcb2e31cca7b80bb31da1beaeba7a291`；`scripts/test/test_atlas_image_shadow_host_validation.sh 9aa1ec6efb8f782f5b8bf404df52d1872a70fe7b2b83dab811f1f2a5f737f10c`；`README.md` 随本次说明更新。
- 已发布的 bundle manifest（driver `367b0076…`）已过期，必须重新 publish 后才能 prepare/submit；本轮只重建与离线回归，未 publish、未 prepare、未 submit、未启动宿主。

## T040-S4 倍率白名单、T039 窗口加宽与反向阈值对照（本轮）

- 变化：排版倍率原本硬编码为 `"40"`（`ShadowSceneContract.LAYOUT_SCALE_PERCENT`）。现在倍率是本场景唯一按作业参数：wrapper 从 `{runLabel}` 后缀严格选择（无后缀或 `-scale40` = 40，`-scale60` = 60，其它 `-scale<数字>` fail-closed），经 `-Dturboism.validation.atlasImageShadow.layoutScalePercent` 传入；driver `DriverConfig.fromSystemProperties()` 在启动门就按二值白名单（`40`/`60`）复核，越界即拒绝，`LayoutApplyDispatch` 只接收 config 里的倍率，并在写文本框后读回校验。因为同一份 driver/T039/生产 agent 字节可以跑两侧，反向对照没有引入任何代码分叉。
- 变化：wrapper 的 `-Dturboism.validation.t039.maxEvents` 从 `16` 放宽到 `64`（driver 侧上限 64，T039 helper 自身上限 128），使 40% 运行的前缀证据从 16 扩大到 64。
- 修复：`scripts/preview/host-validation-020-shadow.json` 被上一轮用紧凑分隔符重新序列化（`"runnable":false` 无空格），使离线测试的 `grep -q '"runnable": false'` 在 `set -e` 下静默失败（只有 rc=1，无错误输出，看上去像构建失败）。已按同目录 `host-validation-020.json` 的格式（2 空格缩进、`": "`）恢复并把 description 更新为倍率可选；JSON 语义未变，prepare 时仍临时翻为 true、完成后立即恢复 false。
- 离线证据：`T040_SHADOW_SCENE_SELFCHECK PASS checks=134 hostExecuted=false`（+10 项：白名单正/负/属性路径，以及 60% 完整 driver 流程）；`ATLAS_IMAGE_SHADOW_HOST_VALIDATION_OFFLINE_TEST PASS hostLaunched=false`（新增默认 40 / `-scale60` / `-scale55` fail-closed 三条 wrapper dry-run 断言，以及启动属性契约的 `layoutScalePercent=55` 拒绝）。
- 实机 run A（40%，窗口 64）：job seq 114 / `queue-6b482897236f4d939bc392fa84e51c76`，prepared `37d297b28078b8ebff52e49ae1857416d9c0de4263b79dddbeaa56c9fe8fae8f`，`state=succeeded`/`validationStatus=PASS`/`normalExit=true`/`identityVerified=true`/`fixtureUnchanged=true`；`freeze.sampleOutcome=TRUNCATED`、`eventLimit=64`、`recorded=64`、`admitted=64`、`potentialTrim=64`、`dropped=36`（共 100 次）；`layout.scaleText=40`、`layout.dialogSeen=true`、`layout.closed=true`、`runElapsedMillis=109059`。
- 实机 run B（60%，反向对照）：job seq 116 / `queue-53c2672283984acf91e34dd1c5c90269`，prepared `5d424a54a0a62e5257ce36d411810c70cb53ec068ffa5164c6973a0d0acd78be`，队列全项 PASS；`freeze.sampleOutcome=NO_CALLS`、`recorded=0`、`admitted=0`、kernel **0 次调用**；`layout.scaleText=60`、`layout.dialogSeen=true`、`layout.closed=true`、`runElapsedMillis=93940`。
- 结论与限制：40% → 100 次真实调用（64 条前缀全部 `admitted+potentialTrim`，参数 2×2、stride=104、destLen=10816），60% → 0 次调用，与 job 8 的 1:1 一起把阈值从两侧坐实。`TRUNCATED` 依旧是前缀分类而非全量命中率；SC-01/02/04a 仍未完成，`runElapsedMillis` 差异不能当收益。
- 本轮发布 bundle：`build/preview/optimize-atlas-processing-cache-parallel/atlas-image-shadow/bundle.manifest`（driver `2b5c4f161fd7cff6b6100151027ef1322ebdfab726c8f6237dd24f629058175a`，T039 agent `29323bf5…`、production agent `224d6ee2…` 复用；`runnable=false`）。
- 本轮变更文件 SHA-256（取代上表及历次补丁行，README 另含本段说明）：

```text
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/ShadowSceneContract.java      03e8d2f6ad48d92718e689686c676ff0f6a765c09a92d9efd6037cf8768049fa
validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow/T040ShadowSceneDriverAgent.java 6d5a66cbe38b5e11a7aa43b458c1fa8b376b66c5cebea139dfa8d798b43e9f90
validation/atlas-image-shadow-scene/selfcheck/dev/turboism/validation/atlasimage/shadow/T040SelfCheck.java          e27c763dfb9d68623ca5629dd073e3c8a430d63db1c249458ef739414d50f8bc
validation/atlas-image-shadow-scene/README.md                                                                     79cfc60cd023ce21a4f1ab6b37b6ed0d93b714b867a4298fd69779e7c6831d67
scripts/preview/run-atlas-image-shadow-host-validation.sh                                                         8841eca0b4f7104d9c30db9437f7757f345683da9813838b631a7eba8bcd8be3
scripts/preview/host-validation-020-shadow.json                                                                    c844decceeb706ef483c120c886e2d23f51a8dd7cf8fdf18cd0e4754b09064bd
scripts/test/test_atlas_image_shadow_host_validation.sh                                                             b8a9929ef93512a2dfa01207d4e7817810875bf5304db0ae4c089cbe6e1957c4
```
- 未变：fixture/官方 JAR/class/shape、T039/T038 helper hash、loader class；仍不启动 collector、不改 Runner/生产 runtime，`fixtureUnchanged` 在四次运行中都为 true。
