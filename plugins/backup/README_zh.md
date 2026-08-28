---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.backup
version: 0.1.0
kind: feature
status: preview
delivery: store-candidate
category: workflow
tags: backup, webdav, automation
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: swing
---

# WebDAV 自动备份同步插件

> **Turboism 官方插件** · **状态：预览版**

将 Cubism 备份构件上传至用户配置的 WebDAV 端点。

| 详情 | 值 |
|---|---|
| 版本 | `0.1.0` |
| 插件 ID | `dev.turboism.plugin.backup` |
| 类别 | `workflow` |
| 标签 | backup, webdav, automation |
| Turboism API | `[0.1.0,0.2.0)` |
| 需要 Cubism | 是 |
| 界面 | `swing` |
| 许可证 | 项目许可证 |

## 功能概述

- 添加用于配置 WebDAV 备份设置和测试端点的 Turboism 菜单项。
- 可在保存触发或主机自动备份时上传 `.cmo3` 构件，并采用有边界的重试。
- 使用 JDK `HttpClient`；凭据不会出现在渲染后的配置和诊断信息中。

## 要求与兼容性

- **Turboism API：**`[0.1.0,0.2.0)`。
- **Cubism：**需要 Cubism。Turboism 当前仅接受经审查的精确编辑器构件 `5.2.03`、`5.3.02` 和 `5.3.03`；本插件仅在其声明的服务和能力可用时公开相应的面向主机功能。
- **界面模式：**`swing`。
- **插件依赖项：**未声明。

## 安装与启用

此官方插件是一个**商店候选项**，尚未作为已发布的商店条目提供。在市场发布前，请通过 Turboism 的官方发布包安装它，然后在**插件管理**中启用它。当不需要该工作流程时，可在同一窗口中将其禁用或卸载。

## 使用方法

1. 打开 **Turboism → WebDAV 备份设置**。
2. 输入 HTTP(S) 端点、远程路径、可选用户名和密码、触发模式、超时、TLS 及重试设置；保存前测试端点。
3. 启用同步。配置的触发器触发时，会上传匹配的备份构件。

## 功能能力

插件清单中未声明任何能力。

## 权限

| 权限 | 范围 | 请求原因 |
|---|---|---|
| `turboism.config.plugin.read` | `application` | 读取 backup/webdav.cfg 端点配置。 |
| `turboism.event.subscribe` | `application` | 订阅 BackupCompletedEvent 以便上传新的备份构件。 |
| `turboism.cubism.backup.observe` | `application` | 观察隐私安全的运行时备份完成事实；精确构件仍保留在发起命令的结果中。 |
| `turboism.config.plugin.write` | `application` | 通过 backup/webdav.cfg 写入路径持久保存 WebDAV 端点设置，并进行回读确认。 |
| `turboism.action.register` | `application` | 注册 Turboism 菜单项背后的 backup.webdav.settings.open 操作。 |
| `turboism.ui.menu.contribute` | `application` | 通过 Turboism 菜单公开 WebDAV 备份设置对话框。 |
| `turboism.cubism.model.observe` | `application` | 观察模型和动画的保存生命周期，以触发由保存触发的备份。 |

## 隐私与数据

### 网络

连接到用户配置的 WebDAV HTTP(S) 端点，并使用 `MKCOL`、`PROPFIND` 和 `PUT`。可选的 Basic 身份验证会将配置的用户名和密码发送至该端点。配置的保存或自动备份触发器触发后，传输可以自动运行。禁用 TLS 验证会削弱传输安全性，仅适用于受信任的私有端点。

### 本地数据

将端点设置和凭据存储在 `backup/webdav.cfg` 的插件配置中。密码值在对话框中会被掩盖，并会从日志和对象渲染中删改。插件为上传目的读取符合条件的备份文件。

### 遥测

本插件不会发送遥测数据。

插件生命周期和故障记录可显示在 Turboism 的会话日志和 Cubism 的主机日志中，并附有插件 ID。

## 状态与限制

- **状态：**预览版。
- 需要可访问的 WebDAV 服务器以及 Cubism 保存/备份生命周期事件。
- 无效文件、端点失败、被中断的请求和耗尽重试次数均会以失败关闭的方式处理；在服务器接受上传前，不会假定远程备份成功。

## 故障排除

| 症状 | 检查项 |
|---|---|
| 端点测试失败 | 验证 URL、远程路径、凭据、TLS 设置以及服务器的 WebDAV 支持。 |
| 未上传备份 | 确认同步已启用、触发模式与事件相符，并检查插件范围内的日志记录。 |
| 重复出现服务器错误 | 检查 HTTP 状态和重试设置；插件仅重试有边界的瞬态失败。 |

## 支持与许可证

- **项目网站：**[https://turboism.dev](https://turboism.dev)
- **发布者：**Turboism Contributors
- **许可证：**项目许可证
- **插件 ID：**`dev.turboism.plugin.backup`
