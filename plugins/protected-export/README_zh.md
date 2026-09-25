---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.protected-export
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: export, protected, candidate
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Protected Export

> **Official Turboism plugin** · **Status: Preview**

## 功能概述

- 在任务专有的模型副本上运行受保护的运行时模型导出：永不写回创作文档。
- 将 Warp 与 Rotation 变形器的状态展平到 staged 副本，并对 ArtMesh 身份做混淆，使发布产物不泄露创作标识。
- 以确定性的参数采样捕获源模型行为，并在发布前对完整 staged 产物做校验。
- 校验通过后原子化发布产物；任一阶段取消或被拒绝时，报告带稳定原因的诊断并恢复工作状态。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。按精确评审版本（当前为 `5.2.03`、`5.3.03`）记录准入；不支持或未评审的宿主在预检时失败关闭。
- **模型：**仅允许符合条件的模型。包含不支持家族、非存储关键帧、退化几何或缺失配对的模型会收到阻塞原因，而不是部分导出。
- **界面模式：**`none` —— 插件通过框架动作驱动导出，并经通知与日志报告结果。

## 安装与启用

本官方插件为**商店候选**，尚未发布到商店。在市场上架前，请通过 Turboism 官方发行包安装，然后在**插件管理**中启用；不需要该工作流时，可在同一窗口禁用或卸载。

## 使用方法

1. 在已评审的精确 Cubism 宿主上启用插件。
2. 打开模型，触发受保护导出动作，等待流水线完成：副本绑定、源行为捕获、展平、混淆与校验。
3. 确认发布产物；流水线失败关闭时，阅读报告的阻塞/否决原因。
4. 排查被拒绝的导出时，查看插件日志中的会话阶段轨迹。

## 功能能力

插件清单未声明功能能力。

## 权限

| 权限 | 作用域 | 申请原因 |
|---|---|---|
| `turboism.cubism.model.read` | `application` | 读取活动 Cubism 模型，用于受保护导出的预检与运行时编排的模型普查；绝不写回创作文档。 |

## 隐私与数据

插件在内存中读取活动模型，导出产物只写入你确认的任务专有输出位置。身份混淆在发布前作用于 staged 副本。任何模型内容、身份映射或诊断轨迹都不会离开本机；日志保存在本地 Turboism 日志目录。

## 状态与限制

- **状态：**预览。流水线仅在评审过的精确 Cubism 版本与符合条件的模型上准入，并对所有未准入输入失败关闭。
- 展平覆盖 Warp 与 Rotation 变形器；其他家族按准入记录原样透传或否决导出。
- 行为校验受确定性采样矩阵约束；未被观测的参数组合不在覆盖范围内。

## 故障排除

- **预检被拒：**宿主或模型未准入；查看报告的阻塞原因与精确版本准入记录。
- **行为不一致：**staged 产物与捕获的源行为出现分歧；产物会被丢弃，不会发布。
- **恢复失败与否决**会连同所处阶段一起记录；在失败点附近查看插件日志即可定位阶段。

## 支持与许可证

| 项目 | 值 |
|---|---|
| 许可证 | Project License |
| 网站 | https://turboism.dev |
| 问题反馈 | https://github.com/turboism/Turboism/issues |

这是官方第一方插件。分发条款见项目 [EULA](https://github.com/turboism/Turboism/blob/main/EULA.md) 与仓库许可证。
