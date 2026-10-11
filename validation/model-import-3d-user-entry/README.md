# 正式导入入口实机驱动

该验证插件需与正式 `plugins:model-import-3d` 插件一起放进隔离任务包。
它通过实际菜单、配色弹窗、运行时文件选择器和确认弹窗触发导入，
不会链接或直接调用 `SurfaceImport`，也不注入固定文件授权。
控件操作使用公开 Swing API，并非鼠标机器人测试。

驱动从只读种子新建任务文档，设置 1024 画布，选择“保留原材质”和由授权 cube.glb 添加嵌入贴图的冻结
artifacts/cube-textured/cube-textured.glb；通过 SDK 记录六个网格、45×45 绑定、资源及一次 Undo/Redo 的状态。
报告明确不证明渲染、保存重开或更广泛输入支持。任务结束退出专属 JVM，
共享队列监督器另行判定生命周期。不能在用户工作会话中安装这个验证插件。

`build.py --sdk <SDK.jar> --agent <turboism-agent.jar> --out <全新工作树 build 子目录>` 只编译、检查打包并冻结
来源；不运行或提交宿主。必须另行完成正式插件和最新 runtime 的包身份检查、
队列 prepare、独立准入审阅及提交，才能称为实机测试。

构建使用冻结 agent 内的实际 `PluginJarContract` 检查语言资源、入口及资源声明，
并运行缺失语言资源、缺失入口和未声明语言资源三个拒绝对照。

`verify-report.py --texture artifacts/cube-textured/cube-textured.glb` 校验新输入的固定哈希、
原始几何/UV 字节不变、完整 UV 与图片坐标、单张共享图片绑定及 Undo/Redo。
省略该选项仅用于历史 FACE_COLORS 报告。两者均不证明原生像素采样正确。

`verify-texture-pixels.py` 提供独立源贴图像素检查器，预先固定九个关键姿态
（两个轴各 -30、0、30）、1024 画布、200 像素/源单位。由源三维深度决定可见面，
由源 UV 计算 top-row-first、CLAMP_TO_EDGE/LINEAR 采样；四像素网格仅排除
投影三角形边缘一像素，不排除贴图文字或箭头。RGB 容差 2、alpha 精确匹配。
它需要宿主另外捕获九张 PNG，并由外层验证状态、姿态、历史和生命周期；
目前仅离线自测通过，未与原生截图完成比较，不构成实机验收。

新增 `SourceTextureCapture` 在完成菜单导入和 Undo/Redo 后，通过 SDK 为上述
九个姿态读取原生渲染像素。每个姿态要求新的参数消费记录；渲染前后保存求值、
历史和保护状态摘要，最后撤销采集的参数修改。保护状态保留网格、资源、
控制层级和参数定义，只排除当前参数值；完整原始 JSON 随报告供独立绑定。

新驱动需重新冻结后才能提交，已排队的旧测试不会自动增加截图。
采集结果使用 `verify-texture-pixels.py --report <report.json> --cube <原始cube.glb>
--texture <cube-textured.glb> --artifacts <已收集产物目录> --run <run-id> --out <结果>`
检查内容；作业身份、包身份、生命周期仍由外层准入验证。当前离线结构测试中
像素阶段明确使用占位函数，只验证状态门禁，不将其计作真实渲染通过。

## Surface menu persistence profiles

`--surface-fixture plane|seam --surface-save` builds the explicit `plane-menu-save` or `seam-menu-save` SDK-only driver. It uses the real production menu/chooser, verifies single Undo/Redo and nine fresh source rasters, then calls typed reject-existing SAVE_AS. The isolated runtime grants only `mi3d-surface-menu-save` to the UI validation plugin, at `persistence/mi3d-surface-model.cmo3`, with both exact mode and save opt-in. The artifact budget is 64 MiB; it does not inherit the apple budget.

`--surface-reopen` builds observe-only `plane-menu-reopen` or `seam-menu-reopen`; the bundle pins `seed.cmo3` to the accepted SAVE_AS hash/bytes/job/prepared identity. Reopen profiles have no file grant and do not create or import a model. `verify-surface-reopen-job.py` rechecks the full saved job before admitting a distinct job/run/attempt, then checks all stored geometry/forms/resources/parameters and nine PNG identities. `test-surface-save.py` and `test-surface-reopen.py --save-artifacts <accepted-save-artifacts>` are synthetic refusal checks; they cannot prove native persistence. Full intermediate poses and official export are separate gates.
