# Atlas image observe（020 T036，离线实现）

这是 feature 020 的最小“可排队观察”场景，不是生产插件，也不声称完成并行化、图像正确性或性能验收。

当前场景：`atlas-image-observe:5303`，固定 Circle100 fixture。实现只允许以下宿主动作：

1. 唯一完整 fixture 名称的主窗口出现后，在 EDT 上匹配 `建模 -> 纹理 -> 编辑纹理集...`。
2. 只确认 exact editor window class `com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b`，并要求唯一可见、启用的英文 `Cancel`；控制器 `atlasEditor.f` 与设置 Dialog `atlasEditor.a.f` 均拒绝。
3. 点击 `Cancel`，不自动排版、不改排序、不保存模型、不接受保存对话框。
4. 仅在 Cancel 完成、observer 已持久化且精确原生 `退出` 菜单的 `doClick` 有界返回无异常后，单次写入 task-home-relative `result.txt`；不把 shutdown hook 当作动作成功。

driver 与 observer 是两个独立 auxiliary agent；driver 不启动进程、不访问外部客户端、不使用 `Class.forName`、`System.exit` 或 kill。

## 离线构建与自检

需要支持 `--release 17` 的 JDK、Python 3 和本地 shell 工具：

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh
bash scripts/test/test_atlas_image_host_validation.sh
```

自检不启动 Cubism，不把官方源码或类加入普通测试 classpath。保留原有 29-check observer、39-check lifecycle 与 Python smoke validator，并额外覆盖：

- 旧 `premain(properties-path)` 与无参数命名 JVM 属性入口；
- 缺失/重复 `Live2D_Cubism.jar`、错误路径、错误 hash、非法路径和重复 run ID；
- fake driver 的 `Cancel -> finish -> observer result -> native exit action complete -> result.txt` 状态顺序；Cancel action 异常不得发布 PASS；
- 缺失 observer 结果、超时、非 `PASS` 不产生虚假 PASS；
- wrapper dry-run 的精确参数、Runner 实际 task-prefixed fixture basename、两个 aux agent、七个命名 JVM 属性以及无 hook/client；真实 prepare 仍由管理器门禁控制。
- `host-validation-020.json` 保持 `runnable=false`。
- 固定 driver 的每次 EDT 查询/动作使用 `invokeLater` + 5 秒有界等待；原子状态区分 `QUEUED/STARTED/COMPLETED/TIMED_OUT`。5 秒是 dispatch/wait 预算：排队且未开始的 callback 在超时或中断时原子失效，迟到只记为 skipped；已 `STARTED` 的动作不可撤销、不可伪称回滚，不重试、不推进，迟返不得改写已记录失败；唯一例外是纯读 `MAIN_LOOKUP` 的 `TIMED_OUT + startedAtTimeout=false`，在明确 120 秒 startup safety budget 和原整体 deadline 内按未就绪继续只读查询；不重试已开始、异常、菜单、Cancel 或 Exit。这个 120 秒是失败留证余量的安全预算，不是性能阈值；JDK selfcheck 覆盖实际阻塞、迟到 callback 失效、下一次只读识别、预算到期失败和无 UI 副作用。

每次 build 使用独立 `build/atlas-image-probe/compile.<unique>/`，只接受带 `verified.properties` 和 `artifact.sha256` 的结果；没有共享 JAR 或 `latest` 指针。
每个已 claim run 另原子替换一个有界 `driver-stage.properties`，只记录固定 stage、时间/耗时、计数、布尔和 EDT 状态，不写窗口标题、模型内容或无限事件日志；它覆盖 Cancel dispatch/return、关闭轮询、动作完成等待和 finish.request。selfcheck 对实际生成文件用 `Properties.load` 检查每个字段、固定值和真实换行；文件写入仍是诊断记录，不承诺同步文件 I/O 本身严格有界。

## 入口契约

历史入口继续可用：

```properties
version=5303
editorJar=/absolute/reviewed/Live2D_Cubism.jar
outputRoot=/absolute/task-owned-output
runId=properties-run-001
```

新真实 host 入口由 Runner 传入无参数 auxiliary agent，并使用显式命名属性：

```text
-Dturboism.validation.atlasImageObserve.home={HOME}
-Dturboism.validation.atlasImageObserve.taskId={TASK_ID}
-Dturboism.validation.atlasImageObserve.fixture={FIXTURE}
-Dturboism.validation.atlasImageObserve.fixtureName={FIXTURE_NAME}
-Dturboism.validation.atlasImageObserve.version=5303
-Dturboism.validation.atlasImageObserve.outputRelative=state/atlas-image-observe
-Dturboism.validation.atlasImageObserve.timeoutSeconds=900
```

observer 同时要求 Runner 的 `turboism.home`、`turboism.validation.runId` 与 `hostVersion` 身份一致。无参数入口只在真实 JVM 的只读 `java.class.path` 中寻找唯一 exact basename `Live2D_Cubism.jar`，规范化后验证固定 5303 hash，再读取 target class entries；不搜索磁盘/进程，也不加载官方类。

任务输出只能从显式 `turboism.home` 和 bounded run ID 派生：

```text
$HOME/state/atlas-image-observe/<runId>/identity.properties
$HOME/state/atlas-image-observe/<runId>/finish.request
$HOME/state/atlas-image-observe/<runId>/result.properties
$HOME/state/atlas-image-observe/result.txt
$HOME/state/atlas-image-observe/<runId>/driver-stage.properties
```

run 目录用 `Files.createDirectory` 唯一 claim，重复 run 拒绝。observer transformer 同步注册、始终返回 `null`，不改官方字节。

## 固定输入

- fixture：由忽略的 `TURBOISM_HOST_VALIDATION_FIXTURE_5303`（`.env`/环境）提供；运行时 basename 必须为 `atlas_mapping_100.cmo3`。
- fixture SHA-256：`2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e`
- 5303 `Live2D_Cubism.jar` SHA-256：`bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166`

wrapper 为薄委托层：

```bash
scripts/preview/run-atlas-image-host-validation.sh 5303 r1 \
  --bundle-manifest /absolute/published/bundle.manifest \
  --dry-run
```

它只校验明确 manifest、artifact/hash、固定 fixture/hash 和自身 worktree delivery root，然后委托现有 `run-cubism-host-validation.sh` 的 `--prepare-dir`/`--dry-run`/实际生命周期。当前离线回归只用 `--dry-run`，不自行 launch，不传 home-file、plugin、client 或 remote hook 参数。

发布 bundle（仅实现，不代表已获宿主授权）：

```bash
bash validation/atlas-image-probe/build-and-selfcheck.sh \
  --publish --production-agent /absolute/reviewed/turboism-agent.jar
```

发布按 worktree ID 使用固定 delivery 根和 hash 命名 bundle；manifest 已存在或目标产物冲突时失败，不覆盖并发产物。生产 agent、observer、driver、fixture 与官方 hash 都必须在显式 manifest 中。

## 结果协议与门禁

observer `result.properties` 必须是同 run、`completionReason=EXPLICIT_FINISH`；driver 只在 `status=PASS`、目标确实观察到、无 conflict/overflow、`guardInstalled=false` 且 `optimizationReadiness=NOT_EVALUATED` 时，在原生退出 action 有界完成且无异常后单次写 `status=PASS`。其他情况原子写 `status=FAIL`；不再依赖 PASS 后覆盖或 failure marker 收口。若原生菜单在 `doClick` 返回前同步终止，driver 不写 PASS，由最终门禁按缺少 canonical PASS 拒绝；动作返回后宿主仍未正常退出，则由最终 normal-exit/containment 证据拒绝。

Runner 未传 readiness/failure marker：现有 Runner 仅在 marker 列表非空时读取 `$HOME/logs/runtime/*.log`，因此本场景不把 stdout/stderr 当 readiness 证据；result 900 秒、graceful exit 120 秒，driver 默认 fixed-scene timeout 900 秒。超时、无 observer result、非 PASS、重复结果和不安全/歧义 UI 都 fail closed。主代理/管理器负责 prepare、submit、宿主启动、正常退出证据、超时与 bounded cleanup。
EDT 超时或中断会先写阶段失败证据、使未开始 callback 失效并停止后续动作；只有未开始的纯读 `MAIN_LOOKUP` 超时可在 startup budget 内继续，不改变 secondary-window fail-closed 条件。正常结果仍只在 Cancel 动作完成、observer 结果持久化及原生退出动作无异常后单次发布，晚到 callback 不会补点控件或恢复流程。

第二轮真实证据只显示宿主启动日志至约 7.765 秒时 `MAIN_LOOKUP` 排队超时；未见 document load/main scale 完成信息，因此本切片不证明正常启动耗时、最终能否找到窗口或任何根因，也不声称解决首轮 Cancel 卡点。本次只完成离线实现和验证，manifest 仍 blocked。真实菜单动态匹配、精确 `f$b`/`Cancel`/原生 `退出`、生产 agent 依赖、class-path 唯一性、宿主正常退出和管理器 admission 仍是主代理审阅后的剩余门禁；性能测量另立任务。
