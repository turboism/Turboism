# 按部件文件夹分治排版

独立插件：atlas-folder-layout（纹理集一键排版），仅依赖 SDK。算法由独立插件注册。

入口：Turboism → 按部件文件夹一键排版。需要活动模型已有纹理集。

模态设置提供最大正方形边长 1024 / 2048 / 4096 / 8192（默认 4096）、
排序算法、边距、图片间距，以及按内容自动选择每页独立尺寸（默认开启）。
关闭自动尺寸后使用所选上限的正方形。图片保持原始大小，不旋转、不缩放。
每次打开窗口时从注册表自动发现所有算法，使用插件发布的名称。
优先 MaxRects-BSSF、Dalsoo，其余算法按注册顺序排列，最后显示 Cubism 原生。
只显示已注册的插件算法；开始排版时重新解析，已卸载的算法报告不可用。
Dalsoo 使用矩形规划入口；原生及其他没有规划器的委托算法禁用并标明原因。

先按顶层部件文件夹分组，溢出时递归按子文件夹分治。
叶子中的图片再按面积降序二分；页面按树顺序编号，重复文件夹名称不会造成页名重复。
跨文件夹共用图片归到使用者的最近共同上级，无归属图片独立保留。
规划成功后通过既有完整图集事务整体应用，保留原生撤销路径。

## 离线验证

```sh
./gradlew :plugins:atlas-folder-layout:test :plugins:atlas-maxrects-bssf:test :plugins:atlas-dalsoo-polygon:test :sdk:test :runtime:test --tests 'dev.turboism.adapter.cubism.textureatlas.*' --offline
```

结果：BUILD SUCCESSFUL。MaxRects 插件 50 项、Dalsoo 插件 36 项、SDK 457 项（1 跳过）、
纹理运行时 186 项，共 728 通过、1 跳过。
覆盖嵌套归属、共用图片、未归属图片、树顺序、多层溢出、叶子拆分、预算、尺寸、
边距、固定页大小、选定规划器的 partial/overflow，以及 Dalsoo 的不旋转和单位缩放约束。

Swing 模态交互在隔离 Xvfb 中验证：默认 4096、四个尺寸选项、禁用未启用 Dalsoo / 原生、
确认传回 8192 和修改后的自动尺寸选项、取消返回空值。
[窗口预览](settings-preview.png) 由已显示的 Swing 窗口自身绘制产生，不是 Cubism 宿主截图。
上述截图和实机记录对应首次实现；随后算法发现改为动态注册表列表，
新增测试覆盖第三方算法名称、优先级、注册/移除后的列表刷新和无规划器条目禁用。

全量 runtime 测试曾在无关的 ScreenshotCaptureHostOperationsTest 中超时，已停止；上述定向测试通过。

## 实机验证

经用户授权，通过统一队列使用官方 Cubism 5.3.03、隔离 Proton 前缀和 Geometry100
样本副本验证。探针操作实际生产菜单及模态窗口，不直接调用规划器。
验证代码在 [src](src/dev/turboism/validation/atlasfolders/FolderAtlasHostProbe.java)，
启动入口为 [队列包装脚本](../../scripts/preview/run-atlas-folders-host-validation.sh)。

MaxRects-BSSF 和 Dalsoo 均将 100 张原尺寸图片按三级部件树分成 3 页（1024×1024），
页顺序为 HostLeft、HostRight、HostBody。检查包括取消不修改、默认算法和四档上限、
文件夹递归拆分、图片完整性、无旋转缩放、3 px 边距、6 px 间距、固定方形设置、
精确撤销/重做及原生保存。独立重开会话再次比较完整计划 SHA-256。

实机发现并修复了两处既有运行时问题：读取原生缩放/旋转图片时使用变换后的边界；
将撤销/重做回调使用的 reinit-input 纳入正式访问清单。对应回归已通过。
前三轮失败保留为失败。原始结果和宿主截图保留在本地忽略目录 host-evidence，
遵循仓库规则，不提交含本机路径的原始证据。四轮成功任务均核对宿主身份、正常退出、
样本未变、原始绑定 cgroup 安全清理且内核错误为空：

| 检查 | 队列任务 ID | 结果 |
| --- | --- | --- |
| MaxRects 排版与保存 | e6ddff0d-9e16-435e-be72-63de63f98a71 | PASS |
| Dalsoo 排版与保存 | 1238b0a7-0b90-4fd7-b2e9-7fea8aab6763 | PASS |
| MaxRects 独立重开 | 6ba8218a-83b3-4c89-84d6-911207fffde1 | PASS |
| Dalsoo 独立重开 | 2f786233-a08a-4172-98c6-3fe5c5591fe7 | PASS |

保存模型保留在队列各任务的 state/atlas-folders/saved.cmo3 中，不纳入源码。
本轮只验证 5.3.03；不将静态准入版本等同于逐版本实机通过。

## 独立插件拆分

界面、菜单、部件归属及分治逻辑已迁移至 plugins/atlas-folder-layout。MaxRects 不再注册该菜单。
分治仅使用 SDK 的 TextureAtlasLayoutPlanner 和共享打包失败协议，无算法插件编译或运行依赖。
新增离线检查覆盖无算法时菜单注册与卸载清理、仅第三方算法可用的发现顺序、当前页算法禁用。
前述实机记录对应拆分前版本；拆分后验证结果另行记录，不能沿用为新二进制的宿主验收。
