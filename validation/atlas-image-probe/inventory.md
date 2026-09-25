# 020 T036 依赖清单：atlas-image-observe:5303

## 授权与边界

- 本目录是 `validation/atlas-image-probe/`，只用于 T036 离线实现与自检。
- 本次允许的宿主行为尚未执行：没有 prepare、submit、启动 Cubism、外部 collector、信号或强制进程清理。
- `scripts/preview/host-validation-020.json` 保持 `runnable=false`；场景、依赖和真实宿主证据须由主代理及管理器审核后另行放行。
- 本轮只观察类定义与固定菜单路径，不做 CPU、内存、JFR、FPS、图像处理时间或并行性能测量。

## 固定输入与官方依赖

| 项目 | 固定值/来源 | 摘要 |
| --- | --- | --- |
| 场景 | `atlas-image-observe:5303` | 仅 Circle100、单次 UI 观察 |
| Circle100 源文件 | 忽略的 `TURBOISM_HOST_VALIDATION_FIXTURE_5303`（`.env`/环境） | wrapper/build 要求绝对路径，规范化后 basename 必须为 `atlas_mapping_100.cmo3` |
| Circle100 SHA-256 | `2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e` | 不信任环境 hash；build、wrapper、manifest 三处固定校验 |
| 官方 JAR | JVM 真实只读 `java.class.path` 条目中唯一 basename `Live2D_Cubism.jar` | 不扫描磁盘、不猜进程、不 `Class.forName` |
| 5303 官方 JAR SHA-256 | `bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166` | observer 在注册前校验；target class bytes 单独读取 |
| 官方源码/类 | 不进入普通测试 classpath | 只在显式 real-host smoke 中读 JAR；本次未使用 |
| 生产 agent | 显式 publish 参数或 `$root/build/preview/<worktree-id>/turboism-agent.jar` | realpath 后仍须位于当前 worktree preview root；manifest 固定记录路径与 hash，不使用 `latest` |

## 代码、JAR 与配置

### 生产验证代码

- `src/.../AtlasImageObserveContract.java`：observer/driver 共享常量，并严格要求 `{FIXTURE}` basename 等于 Runner 实际展开的 `{TASK_ID}-atlas_mapping_100.cmo3`。
- `src/.../AtlasImageLoadProbeAgent.java`：兼容 `premain(properties-path)`；无参数时读取命名 JVM 属性；同步注册只读 transformer，始终返回 `null`。
- `src/.../ObservationSession.java`、`src/.../ReportCompletion.java`：已有 finish/shutdown 报告协议，结果写入独立 run 目录。
- `src/.../SceneDriverState.java`：固定场景状态机；只允许 `Cancel -> finish.request -> observer persisted -> native exit action complete -> result`。
- `src/.../AtlasImageSceneDriverAgent.java`：独立无参数 auxiliary agent；JDK Swing/EDT 只匹配唯一完整 fixture 窗口、`建模 -> 纹理 -> 编辑纹理集...`、精确编辑器 Window class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`、精确 `Cancel`、精确原生 `退出`。editor action 在 Cancel 后以 latch/finally 有界等待并传播异常；native exit action 同样有界完成后才允许一次结果发布；不做自动排版/排序/保存，不启动命令，不调用 `System.exit`，不把 shutdown hook 当成功。
  每个 `onEdt` 查询/动作改为 `invokeLater` + 5 秒有界等待；5 秒是 dispatch/wait 预算，固定 `QUEUED/STARTED/COMPLETED/TIMED_OUT` gate 让超时或中断时仍在队列的 callback 失效并在迟到时永久跳过，已开始动作不可撤销、不伪称回滚、不重试、不推进。唯一例外是纯读 `MAIN_LOOKUP` 的 `TIMED_OUT + startedAtTimeout=false`：在明确 120 秒 startup safety budget 和原整体 deadline 内只按未就绪继续查询，不重试已开始、异常、菜单、Cancel 或 Exit；预算是失败留证余量，不是性能阈值。

### 离线自检代码

- 原有 `AtlasImageLoadProbeSelfCheck.java`、`AtlasImageLifecycleSelfCheck.java`、`SyntheticLoadTarget` 保留。
- 新增 `AtlasImageProbeConfigurationSelfCheck.java`：properties-path/no-arg、Runner task-prefixed fixture basename、缺失/重复 JAR、错误路径/hash、重复 run、路径边界、错误编辑器 class、Cancel action 完成屏障异常拒绝和退出后结果顺序。
  同一 selfcheck 用真实 JDK EDT 阻塞复现实际编辑器菜单 dispatch 的排队超时、队列释放后菜单选择/点击均为零、中断失效、已开始动作迟返、正常完成和异常传递；并复现 `MAIN_LOOKUP` 排队超时后的唯一只读重查、首 callback 失效、预算到期失败和无 UI 副作用；验证迟返不覆盖已记录 FAIL。
- `selfcheck/premain_smoke_selfcheck.py` 与 `premain-smoke.py` 保留；普通测试只使用项目自有 synthetic bytes。

### 交付物

- `atlas-image-load-probe.jar`：仅 observer + shared contract。
- `atlas-image-scene-driver.jar`：仅 fixed driver + state + shared contract。
- `build/atlas-image-probe/compile.<unique>/`：每次构建独立 evidence、日志、两个 JAR、`artifact.sha256`、`verified.properties`；没有共享 JAR 或 `latest` 指针。
- `--publish` 会按自身 worktree ID 写入 `build/preview/<id>/atlas-image-observe/bundle.<observer-hash>.<driver-hash>/`，并用无覆盖 hard-link 原子发布 `bundle.manifest`；已有 manifest 时失败，不覆盖并发产物。wrapper 在 agent realpath 后再次检查 preview root。
- publish 必须提供/找到明确生产 agent，并把生产 agent、observer、driver、fixture、官方 5303 hash 写入 manifest。

## 配置与启动协议

Runner 不修改。wrapper 通过 `--jvm-option` 传入以下属性，并显式传递 `{HOME}/{TASK_ID}/{FIXTURE}/{FIXTURE_NAME}`：

```text
-Dturboism.validation.atlasImageObserve.home={HOME}
-Dturboism.validation.atlasImageObserve.taskId={TASK_ID}
-Dturboism.validation.atlasImageObserve.fixture={FIXTURE}
-Dturboism.validation.atlasImageObserve.fixtureName={FIXTURE_NAME}
-Dturboism.validation.atlasImageObserve.version=5303
-Dturboism.validation.atlasImageObserve.outputRelative=state/atlas-image-observe
-Dturboism.validation.atlasImageObserve.timeoutSeconds=900
```

同时复用 Runner 自带的 `turboism.home`、`turboism.validation.runId`、`hostVersion`。aux agent 均无参数：observer 依据 `java.class.path`，driver 依据命名属性；旧 properties-path 入口继续接受 `version/editorJar/outputRoot/runId`，真实 host 的新入口不依赖 home-file 模板展开。机器路径只来自忽略的 `TURBOISM_HOST_VALIDATION_FIXTURE_5303`。

## 构建命令与网络/秘密

离线构建与全部 synthetic 检查：

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh
```

只在主代理完成依赖审核后、且已有明确生产 agent 时，才可由管理器另行决定是否运行（本次不运行）：

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh \
  --publish --production-agent /absolute/reviewed/turboism-agent.jar
```

本实现不需要网络、下载、云服务、外部 collector、SSH、秘密、token 或凭据。构建只需要 JDK 17、`javac`、`jar`、Python 3 和本地 shell 工具。

## 结果、超时与 cleanup 责任

1. observer 原子写入 `state/atlas-image-observe/<runId>/identity.properties` 与 `result.properties`；run 目录通过 `createDirectory` 唯一 claim，重复 run 拒绝。
   driver 同时在 run 目录原子替换有界 `driver-stage.properties`，记录固定 stage、时间/耗时、startupBudgetSeconds、mainLookupRetryCount、计数、布尔和 onEdt 状态，覆盖 Cancel dispatch/EDT return、关闭轮询、动作完成等待与 finish.request；不记录窗口标题或模型内容。
2. driver 等待 observer run；严格匹配 Runner 已复制的 task-prefixed fixture 主窗口，异步触发固定菜单；确认精确 `f$b` editor Window 后只点 `Cancel`。
3. Cancel 成功且 editor/secondary dialog 安全关闭后，driver 用 `CREATE_NEW` 写 `finish.request`；只接受同 run、`completionReason=EXPLICIT_FINISH` 的 observer 结果。
4. observer 缺失、超时、非 `PASS`、冲突、目标未观察或字段不全，driver 只能原子写 `status=FAIL`，不得伪造 PASS。
5. Cancel action 完成并读取符合 run/completionReason 协议的 observer 结果后，driver 发起唯一精确原生 `退出`；observer 非 PASS 时也尝试该退出，最终只能写 FAIL。该 action 的 `doClick` 必须有界返回且无异常，随后才单次写 canonical `result.txt`。不依赖 PASS 后覆盖、failure marker 或 shutdown hook；若同步退出在 `doClick` 返回前终止则不写 PASS。action 返回不代表宿主退出完成，仍须最终 normal-exit/containment 证据；不 `dispose`、不 `kill`、不 `System.exit`。
6. wrapper 不传 readiness/failure marker；现有 Runner 只有 marker 列表非空才读取 `$HOME/logs/runtime/*.log`，所以 stdout/stderr 仅作诊断。result 900 秒、graceful exit 120 秒，driver 自身默认 900 秒固定场景等待。主代理/管理器负责 queue admission、超时证据、宿主正常退出、bounded cleanup 和未完成状态处理。

## 本轮修正与离线证据

- Runner 实际把显式 `--fixture-name atlas_mapping_100.cmo3` 展开为 `$TASK_ID-atlas_mapping_100.cmo3`；dry-run 回归同时核对 `fixturePath`、`fixtureName`、`validationFixtureNameJvmOption` 与四个 `{HOME}/{TASK_ID}/{FIXTURE}/{FIXTURE_NAME}` 展开结果。
- observer 与 driver 都严格核对复制后 fixture basename；driver 只接受 `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`，对 `atlasEditor.f` 和 `atlasEditor.a.f` 有负控。editor `doClick` 在一次 EDT dispatch 中先释放 driver，Cancel 后由完成 latch/finally 有界等待并检查异常；native exit action 同样先异步派发、完成后才发布结果。
- wrapper manifest 现在拒绝 unknown key；production agent `realpath` 后再次检查当前 worktree preview root；不传 readiness/failure marker，Runner 默认 marker 列表为空时不读取 readiness 日志。
- `PreparedStore.capture` -> 删除临时 synthetic source 与输入 -> `load`/`command` 回归通过；没有运行 prepare、submit、worker、host 或外部 hook。测试只使用临时 synthetic bytes，不移动真实项目或 Circle100 fixture。
- 配置自检注入 Cancel action 异常，确认完成屏障拒绝并不进入结果发布；结果发布顺序由 state/selfcheck 与静态 contract 固定为 native exit action 完成后单次写入，不再保留 PASS 后覆盖方案。
- 离线证据：`WRAPPER_ARGUMENT_CONTRACT`、`DRIVER_MODAL_DISPATCH_CONTRACT`、`ATLAS_IMAGE_PROBE_SELFCHECK checks=29`、`ATLAS_IMAGE_LIFECYCLE_SELFCHECK checks=39`、`ATLAS_IMAGE_CONFIGURATION_SELFCHECK checks=133`、`ATLAS_IMAGE_EDITOR_DISPATCH_SELFCHECK`、`ATLAS_IMAGE_MAIN_LOOKUP_SELFCHECK`、`ATLAS_IMAGE_STAGE_EVIDENCE_SELFCHECK`、`PREPARED_STORE_OFFLINE` 与 `ATLAS_IMAGE_HOST_VALIDATION_OFFLINE_TEST` 均通过；`host-validation-020.json` 仍为 `runnable=false`。阶段 properties 是固定大小的原子诊断文件，但同步文件 I/O 不宣称严格有界。
- 本轮新增离线证据 `ATLAS_IMAGE_EDT_GATE_SELFCHECK PASS`、`ATLAS_IMAGE_MAIN_LOOKUP_SELFCHECK PASS queued-retry-only=true startup-budget=120`；阶段证据文件为单文件原子替换，不会随轮询无限增长；未启动宿主或第三次真实作业。第二轮 console/stage 只证明约 7.765 秒时 queued MAIN_LOOKUP 超时，未见 document load/main scale 完成，不能据此断言正常启动、最终能否找到窗口或根因，也不声称解决首轮 Cancel 卡点。
- 最后一次离线 build evidence（相对 worktree，`PYTHONOPTIMIZE=1` shell 回归）：`build/atlas-image-probe/compile.ap3Tan/`；observer JAR SHA-256 `f5313f97cf682fb7a346ed05d5ac09fdd2739276784ee9dff8d113d65d6631bb`，driver JAR SHA-256 `c69e727402464e53e26a97ede1172956471acdf0a110aca4020ebf4998991ad5`。

### 本次交付 source SHA-256

| 文件 | SHA-256 |
| --- | --- |
| `src/.../AtlasImageObserveContract.java` | `3b46f7fd1932fff80580cf434d6fdf2c1c779ad040f3a2577122d64f52f1b572` |
| `src/.../AtlasImageLoadProbeAgent.java` | `6312c1ad87876cb09fbc6bf450bac5312f5fc0c7b24cf4b21d5e5ae2a193749a` |
| `src/.../AtlasImageSceneDriverAgent.java` | `6b3e594294f93d708c67f0b19e2ec309fb501bdcdc63bbb20b531c270452c054` |
| `src/.../ObservationSession.java` | `5ca39a5ef103677bb0b23de17812ad43c9afd5edec54c8d8ce6ce14c11291a3f` |
| `src/.../ReportCompletion.java` | `80d43067fc64cd9ed2221580fcbe5299bcdbcb26b8923853d4124a471728218b` |
| `src/.../SceneDriverState.java` | `47578bb26e2fd6933afb59b81e4e6dbb837489a688b6e1c7f99f7dac6c5cec2c` |
| `selfcheck/.../AtlasImageLifecycleSelfCheck.java` | `13dc90d89a8b282247f6b27ab3c32bde9d2d1ca5592639e82dfa4497d0eece42` |
| `selfcheck/.../AtlasImageLoadProbeSelfCheck.java` | `dbb24d257c2e42cdc29594d40e218d8bad128e83673eff808ec2dad742dd1bc8` |
| `selfcheck/.../AtlasImageProbeConfigurationSelfCheck.java` | `274bc3644ba35233a11367614781ef67eb01701bafe7de5b3fb50824b58a4768` |
| `build-and-selfcheck.sh` | `a7e01b4400349216b385116902bb270fd5e6fb7f529aa44c32a0e4b5adb01a3c` |
| `scripts/preview/run-atlas-image-host-validation.sh` | `9247f65e14843ad07a3855b652c495457853bd9ffbbf165a5f8734c2c8670941` |
| `scripts/preview/host-validation-020.json` | `27095486b128fbb00ec5f76d1cd537f17e1657d3f5ac91ee063d2811a960b422` |
| `scripts/test/test_atlas_image_host_validation.sh` | `c05e02fa7eb51364464c7be3150a97e7804e5375474e3650f6ef074590d7c0e7` |
| `README.md` | `a64f64e66e02acb9fcf9c9bc9e0d35019005f2ba8c8e075962644453a644c90a` |
## 剩余门禁

- 主代理审阅源码、bundle manifest、实际生产 agent/hash 和宿主 classpath 事实。
- 管理器审阅中文菜单路径、唯一窗口标题、exact editor Window class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`（负控 `atlasEditor.f`/`atlasEditor.a.f`）、英文 `Cancel`、原生 `退出` 以及 save-dialog fail-closed 行为。
- 只有上述审阅通过后，管理器才可把 manifest 从 blocked 状态推进到可排队状态，并自行 prepare/submit/启动真实宿主；本次未解除该门禁。
- 性能与 CPU 采样另立任务，不由 T036 最小正确性观察承担。
