# incremental-update-host-probe

增量更新路径宿主行为探针。

运行：`./build.sh` 编译。需要真实 Cubism Editor 宿主。

## 撤销重建与版本兼容（2026-10-28）

Warp 绿色手柄拖动后撤销可在不改变文档时间戳、不调用插值入口的情况下，通过
`reinitInstance` 原地恢复表单。仅监听插值无法证明子 ArtMesh 的计算坐标仍可复用。
增量更新现在监听公共 `ACParameterControllableSource.reinitInstance` 入口，清除已完成和
待提交的基线、父子缓存及差分探针状态，同时恢复原生插值。下一次更新完整计算，随后
稳定帧恢复增量复用。帧内重建不会提交旧基线；关闭优化时移除新增回调并恢复重写类。

覆盖的 20 个版本：5.0.00–5.0.06、5.1.00–5.1.03、5.2.00–5.2.03、5.3.00–5.3.04。
逐版本固定公共源类、Warp/Rotation/ArtMesh 源类及继承链的 SHA-256；未知方法或字节码
不匹配时拒绝安装。安装器要求五个类、七个方法全部注入成功，卸载覆盖新增源类。

自动回归：

```sh
./gradlew :runtime:test --tests '*IncrementalUpdate*' \
  :bootstrap:test --tests '*IncrementalUpdate*'
TURBOISM_CUBISM_REF=/path/to/refs ./gradlew :runtime:legacyCubismEvidenceTest \
  --tests '*IncrementalUpdateArtifactTest*'
```

参考目录为 `<refs>/<version>/Live2D_Cubism.jar`，同目录及其子目录提供该版本的依赖 JAR。
允许版本目录使用符号链接。无授权宿主材料的版本单独跳过，不计为已验证。

本次 20 个真实 JAR 检查全部通过，覆盖类指纹、源重建继承调用链、依赖与方法签名、
五个类的真实字节码转换。离线测试覆盖无时间戳/插值回调的重建失效、帧内及异线程通知、
随后恢复复用，以及通知缺失、类型错误和异常时原生重建继续执行。这些结果不代表
20 个版本均已通过编辑器交互回归。

宿主交互验收：关闭两个镜像，开启增量更新，拖动 Warp 绿色手柄后分别撤销、重做，
检查 Warp、源坐标、子 ArtMesh 和多层子变形器一致恢复；稳定帧应再次出现复用。
再关闭增量更新做相同操作，比较结果。编辑器内验收结果见 [2026-10-28 实机报告](acceptance-20261028.md)，仅对报告列出的版本和场景作结论。

离线门禁结果：无图形模式 `devCheck` 通过；runtime 准入共 3981 项、零失败、8 项跳过。
真实版本回归 20 项全部通过、无跳过。runtime/bootstrap 的 `spotlessJavaCheck` 通过。
默认图形模式的首轮 `devCheck` 因既有截图测试阻塞于系统屏幕捕获而中止，未计为通过。

## 异线程原生回退（2026-10-28）

替换标脏和 ArtMesh 变形的回调只能通过抛异常触发注入器的原生回退；正常返回或返回
`null` 都会被视为已处理。异线程调用现在先清除几何基线及探针状态、恢复原生插值，
再通过现有异常分支执行原生序列。被打断的更新不得提交旧基线，完整更新后恢复复用。

回归测试将真实 Bridge 回调与 20 个版本描述符的转换后调用点组合，断言原生标脏、
变换创建及 ArtMesh 变形各执行一次，并验证帧内失效和后续恢复。该测试在修复前失败。
这属于调用点行为验证，不代表新增了编辑器 GUI 交互验收。

## 编辑器内 Warp 拖动验收

任务专用 Java agent 通过当前建模视图的官方鼠标事件包装器拖动可见 Warp 控制点，
调用编辑器的 Undo/Redo 命令，并逐位比较所有 Warp/ArtMesh 源表单及计算坐标。
阶段间只请求 `repaintCanvas`，不调用模型重建或 `updateParamInstance`。
两个镜像钩子禁用且不加载镜像插件。先跑关闭增量更新的原生对照，再开启增量更新，
比较四个阶段的坐标与画布，并要求实际出现网格复用、Bridge 失败计数不增加。

```sh
./gradlew previewBundle
bash validation/incremental-update-host-probe/build-undo-agent.sh /path/to/editor/app/lib
bash validation/incremental-update-host-probe/run-undo-host-validation.sh 5304 \
  --golden-prefix /path/to/proton-prefix
```

构建载体需要显式提供合法安装的宿主依赖；它只用于验收，不进入产品分发包。

本机安装与项目路径由现有 `.env` / `TURBOISM_ENV_FILE` 提供。运行器经全局队列启动
官方 BAT、复制测试项目和 Proton 前缀；探针只接受匹配队列 run ID 的任务目录，结果
落盘后退出该任务的编辑器。仅使用编辑器原生 FREE 启动或已存在的许可状态。
启动器、原始项目及黄金前缀由运行器校验，测试不会保存修改到原项目。

画布从建模视图的组件树精确定位 `com.jogamp.opengl.awt.GLJPanel`，避免把旧版内部
组件或纹理预览当作建模画布。坐标键使用稳定 UUID，避免 Undo 重建后的调试编号
变化造成误报。画面证据使用原生绘制入口；空白或单色捕获不能计为通过。

2026-10-28 最终实机结果：5.0.00、5.1.03、5.2.03、5.3.03、5.3.04 的目标场景通过。
详细任务证据及 5.0.00 单次像素差异的复测边界见 [实机报告](acceptance-20261028.md)。

组合验收的 Undo 工具等待框架 runtime 完成启动，再要求建模画布布局连续稳定三秒。
每组开始和每次画布捕获都必须保持同一边界；布局变化直接拒绝对照，不放宽坐标位级
或像素相等断言。这防止框架工具栏尚在安装时，过早启动的 OFF/ON 使用不同画布。

Undo、Redo 和最终 Undo 后各自记录稳定帧的 `meshSkipped` 与失败增量，并分别要求
ON 复用为正、OFF 复用为零、Bridge 新增失败为零。整组复用总量不能替代这些阶段断言。
