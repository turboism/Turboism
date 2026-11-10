---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.turbo-mesh
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: modeling
tags: mesh, artmesh, editing
turboismApi: "[0.70.0,1.0.0)"
requiresCubism: true
interface: embedded
---

# Turbo Mesh

> **Turboism 官方插件** · **状态：Preview**

自定义 ArtMesh 布点：曲率自适应边界重采样、区域自治细分、约束 Delaunay 三角化，以单次可撤销编辑写回。

| 条目 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.turbo-mesh` |
| 分类 | `modeling` |
| 标签 | mesh, artmesh, editing |
| Turboism API | `[0.70.0,1.0.0)` |
| 依赖 Cubism | 是 |
| 界面模式 | `embedded` |
| 许可证 | Project License |

## 功能概述

- 在 ArtMesh 右键菜单中增加 **Turbo Mesh：重新布点** 入口。
- 提取网格边界环并按曲率自适应重采样：尖端生成锚点并加密，平滑曲线段由弦高误差控制采样间距。
- 将形状分割为自治区域（4 连通域，狭长/喉口处递归细分），按区域面积分配内部点预算。
- 内部点在带种子的抖动网格上采样，并保持到区域边界的保护距离。
- 全部点做约束 Delaunay 三角化，区域分割线成为硬约束边，随后经编辑器 Undo 信封原子写回 —— Undo/Redo 正常工作。

## 要求与兼容性

- **Turboism API：**`[0.70.0,1.0.0)`。
- **Cubism：** 需要 Cubism。Turboism 当前仅承认经过审查的精确 Editor 工件 `5.2.03`、`5.3.02` 与 `5.3.03`；本插件只在其声明的服务与能力可用时才暴露对应宿主功能。
- **界面模式：**`embedded`。
- **插件依赖：** 未声明。

<!-- compatibility:begin -->
**Cubism Editor 版本支持（自动生成）：** 5.2.00–5.2.03, 5.3.00–5.3.04（更早版本 Cubism 本身无此功能）.
完整功能（审核版本）：5.2.00–5.2.03, 5.3.00–5.3.04。
- 网格几何读取 仅 5.2.00–5.3.04（更早版本 Cubism 本身无此功能）
完整矩阵: [compatibility matrix](../../compatibility/cubism/plugins_zh.md)
<!-- compatibility:end -->

## 安装与启用

本官方插件是 **store candidate**，尚未上架商店。在商店发布前，请通过 Turboism 官方发行包安装，然后在 **插件管理** 中启用。不需要该工作流时可在同一窗口禁用或卸载。

## 使用方法

1. 在变形器、部件或工作区对象面板中选中一个 ArtMesh。
2. 右键选择 **Turbo Mesh：重新布点**。
3. 在模式对话框中选择布点模式（自动混合 / 柔性条带 / 柔性片状 / 刚性几何）。
4. 在确认对话框中对比原网格与新网格统计，确认后写回；取消则不改变任何数据。
5. 网格作为一次可撤销编辑被重新布点写回，Undo/Redo 正常工作。

绑定了参数关键形态的 ArtMesh 会在确认对话框中收到警告；宿主无法显示对话框时，
含关键形态的 ArtMesh 直接被阻止重新布点。

当前通过 JVM 系统属性调节（设置对话框在计划中）：

| 属性 | 默认值 | 含义 |
|---|---|---|
| `turboMesh.targetPoints` | 320 | 目标总点数（软预算） |
| `turboMesh.features` | `false` | 启用 0.2.0 特征感知布点路径 |
| `turboMesh.features.preset` | `AUTO_BLEND` | 预设：`AUTO_BLEND` / `SOFT_STRIP` / `SOFT_SHEET` / `RIGID_GEOMETRY` |
| `turboMesh.features.weightTexture` | 按预设 | 纹理边缘密度权重（软信号） |
| `turboMesh.features.directionInfluence` | 按预设 | 方向场对采样的影响（0=关闭） |
| `turboMesh.confirm` | `dialog` | `never` 跳过确认对话框（自动化/验证用） |
| `turboMesh.alphaThreshold` | `8` | 纹理 alpha 域阈值 |

### 自动网格生成算法

本插件向编辑器的“自动生成网格”入口注册两个算法，与编辑器自带生成器并列显示；选择自带项时
宿主生成器保持原样。

| 算法 | 作用 | 预设 |
|---|---|---|
| `turbo-mesh.region` | 按纹理把图形分成区域，区域分界线作为硬边，逐区域三角化。 | 稀疏 / 精细 / 硬表面 |
| `turbo-mesh.adaptive` | 按曲率重采样边界，并用区域感知的点填充内部。 | 均衡 / 柔性条带 / 柔性片状 / 刚性几何 |

区域算法读取 ArtMesh 的纹理像素；宿主不提供像素时，以 `no-texture` 理由拒绝，而不是猜测网格。
每个自动项都配有开关：开关开启时，规划器按轮廓尺寸推算该数值，并忽略旁边的数字。

运行时未提供自动网格注册表时，插件只记录一行日志，ArtMesh 右键菜单行为完全不变。

## 功能能力

| 已声明能力 | 用户可见效果 |
|---|---|
| `cubism.mesh.read` | 读取所选 ArtMesh 几何用于布点规划。 |
| `cubism.mesh.write` | 将生成的网格几何写回 ArtMesh。 |
| `ui.context-menu.contribute` | 在 ArtMesh 右键菜单中注册布点入口。 |
| `ui.status.notify` | 通过宿主状态通知报告布点结果与失败。 |

## 权限

| 权限 | 作用域 | 申请原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取所选 ArtMesh 几何用于布点规划。 |
| `turboism.cubism.model.write` | `application` | 将生成的网格几何写回 ArtMesh。 |
| `turboism.action.register` | `application` | 注册自定义布点动作。 |
| `turboism.ui.context-menu.contribute` | `application` | 在 ArtMesh 右键菜单中注册布点入口。 |
| `turboism.ui.status.notify` | `application` | 报告布点结果与失败。 |

## 隐私与数据

### 网络

不发起任何网络连接。

### 本地数据

不持久化模型数据或插件设置。布点结果仅作为普通编辑写入当前打开的模型文档。

### 遥测

本插件不发送任何遥测数据。

插件生命周期与失败记录可能携带插件 ID 出现在 Turboism 会话日志和 Cubism 宿主日志中。

## 状态与限制

- **状态：** Preview。
- 几何写入只作用于**当前 keyform**。对已在其他 keyform 上形变的网格重新布点会改变顶点数，那些 keyform 不会自动重映射——请在形变前布点，或先在副本上验证。
- 布点域取自网格现有 UV 轮廓（支持镂空、保持拓扑）。纹理 alpha 感知布点作为后续扩展，待受审的像素读取面落地后提供。

## 故障排除

| 现象 | 检查项 |
|---|---|
| 菜单入口缺失 | 确认插件已启用且选中对象为 ArtMesh。 |
| 布点报告失败 | 查看状态通知与会话日志中的规划器诊断信息。 |
| 网格无变化 | 确认网格具备有效三角拓扑且未被锁定。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布方：** Turboism Contributors
- **许可证：** Project License
- **插件 ID：** `dev.turboism.plugin.turbo-mesh`
