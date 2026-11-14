[English](../en/README.md) / [简体中文](../zh/README.md) / [日本語](../ja/README.md) / [한국어](../ko/README.md)

# Turboism

## 免责声明

Turboism 是独立的第三方项目，与 Live2D Inc. 无任何隶属、背书或赞助关系，也非 Cubism 官方产品（Live2D、Cubism 及相关商标归 Live2D Inc. 或对应权利人所有）。

Turboism 不分发 Cubism Editor，也绝不会协助用户绕过授权校验、解锁受限功能或越权使用超出许可范围的 Cubism 能力——使用前需自行安装并取得合法授权的 Cubism Editor。

本软件基于 [AGPL-3.0-only](../../LICENSE) 协议按**现状**提供。安装前请阅读[最终用户声明与免责声明](EULA.md)，并务必自行备份工程文件。

## 项目简介

Turboism 是面向 **Live2D Cubism Editor** 的运行时增强工具与插件框架（以 Windows 为主）。它通过 Java Agent 和公开 SDK 扩展建模工作流，提供参数与网格工具、PSD 辅助、面板增强和本地自动化等功能；具体可用功能取决于启用的插件与 Editor 版本。

## 当前功能

### 框架部分

- **性能优化与 JVM 调优**：提供按需开启的渲染、模型更新、剪贴蒙版、纹理上传与网格三角剖分优化，支持 Cubism JVM 配置切换（如 ZGC / GraalVM）以及可选的启动弹窗抑制。
- **插件平台与市场**：内置插件管理与基于 Ed25519 签名的插件市场（支持安装、更新、启停插件），提供权限与 Cubism 版本准入校验，以及框架更新检查。
- **宿主集成与开发底座**：提供统一的 Turboism 菜单、主工具栏入口、设置与快捷键面板、保存路径历史、诊断日志、公开 Java SDK（`dev.turboism:sdk`）以及独立进程的 GraalJS 脚本宿主。

### 第一方插件

标准发行包内置 35 个第一方插件（`parameter-lock` 通过插件市场提供），各插件对应的 Editor 版本支持情况见[插件兼容矩阵](../../compatibility/cubism/plugins_zh.md)：

- **建模、网格与变形器**：`turbo-mesh`（曲率感知自定义布点与三角剖分）、`geometric-mesh-layout`（圆形/多边形网格生成）、`selection-brush`（选择笔刷）、`artmesh-mirror`、`mesh-edit-mirror-axis-enhance`、`boundingbox-warp-mirror`、`warp-deformer-alt-symmetry`、`deformer-batch-rename`。
- **参数、物理与动画**：`parameter`（参数 CSV 导入导出、绑定反转与迁移）、`parameter-batch-transfer`、`parameter-sync`、`parameter-lock` *（插件市场）*、`physics-editor`（物理列表全选与状态保留）、`frame-animation-import`（序列帧与精灵图导入）。
- **PSD、3D 与纹理图集**：`external-psd-edit`（外部实时编辑原图 PSD）、`psd-clip-mask-import`、`model-import-3d`（GLB/glTF/OBJ 导入生成原生网格）、`atlas-maxrects-bssf`、`atlas-dalsoo-polygon`、`atlas-bitmask-nesting`、`atlas-folder-layout`、`texture-atlas-stats`。
- **工作区、界面与检查**：`ui-theme`、`command-palette`（`Ctrl+K` 命令面板）、`cubism-tab-filter`、`palette-label-style`、`scene-palette-enhancer`、`recent-project-board`、`recent-preview`、`history-panel`（语义化撤销时间线）、`clipmask-viewer`、`perf-stats`、`project-work-time`。
- **备份、导出与 AI / 自动化**：`webdav-backup`、`protected-export`（Cubism 5.1+ 扁平化与混淆导出）、`mcp`（本地 MCP 服务）、`acp`（ACP v1 Agent 对话窗口）。

## 平台支持

- **Windows（x64）** 是当前主要支持与维护的平台。
- **macOS**：得益于 Java 的跨平台特性，Turboism 也有可能在 macOS 上运行（目前仅提供未验证的 `turboism-<version>.zip` 预览包）。我们暂未在 macOS 上进行适配与维护——如果你持有 macOS 设备并愿意协助测试或提供帮助，欢迎通过 [GitHub Issues](https://github.com/turboism/Turboism/issues) 联系我们。

## Cubism 兼容支持

| Cubism Editor | 支持情况 |
| --- | --- |
| **5.0.00 – 5.0.06** | 部分支持——缺少自动备份、自动摆动读取、几何读取、集成桥接、建模自定义工具、受保护导出、顶部菜单集成、MOC 导出读取、部件检查器写入；动画/时间线为部分支持 |
| **5.1.00 – 5.1.03** | 部分支持——缺少几何读取、建模自定义工具、MOC 导出读取、部件检查器写入；动画/时间线为部分支持（5.1.02 之前集成桥接为部分支持） |
| **5.2.00 – 5.2.03** | 基本完整——仅缺 5.3 专有接口（Alpha 混合、部件检查器写入、MOC 导出读取、Core 参数重复） |
| **5.3.00 – 5.3.04** | 完全支持——所有已验证能力组均可用 |

未列出的版本和不支持的功能会自动禁用；逐项接口明细见[能力矩阵](../../compatibility/cubism/capabilities_zh.md)。

## 安装

安装或更新前请先关闭 Cubism Editor 并备份工程，然后从[最新 GitHub Release](https://github.com/turboism/Turboism/releases/latest) 下载对应文件。标准包已内置全部第一方插件与 GraalJS 离线依赖（如需完整 GraalVM 运行时，可在配置器中按需安装）。

当前安装包尚未代码签名，运行前建议先核对 `.sha256` 校验值：

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### EXE 安装器（Windows 推荐）

运行 `TurboismInstaller-<version>.exe` 并按向导安装（安装器支持英语、简体中文、日语、韩语；Turboism 运行时语言在 `config.json` 中设置）。默认会集成 Cubism 的启动 `.bat` 并自动备份原文件以便卸载恢复，升级安装会保留现有的 `disabledPlugins` 配置。安装完成后通过生成的 Turboism 快捷方式启动。

### ZIP 便携包

1. 将 `turboism-<version>.zip` 解压到单独的文件夹（不要直接解压到 Cubism 安装目录）。
2. 运行配置器选择 Cubism 安装目录与需要启用的插件：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```
3. 通过生成的快捷方式或文件夹内的 `launch-cubism-turboism.bat` 启动 Cubism。

## 开发

### 插件开发

开发插件无需克隆或构建 Turboism 本体，仅需 **JDK 17+**：

1. 复制 [templates/plugin-template](../../templates/plugin-template/) 作为独立工程，运行 `./scripts/setup.sh`（Windows 下运行 `.\scripts\setup.ps1`）自动下载并校验 `turboism-sdk-<version>.jar` 与 `turboism-event-processor-<version>.jar`，或手动放入 `libs/` 目录。
2. 执行 `./gradlew build`（Windows 下为 `gradlew.bat build`）构建插件 JAR。

如果在当前仓库内开发，可参考[示例插件](../../plugins/demo/README.md)（[build.gradle.kts](../../plugins/demo/build.gradle.kts)、[plugin.json](../../plugins/demo/src/main/resources/META-INF/turboism/plugin.json)），构建命令如下：

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

> 说明：`plugins:demo` 仅用于开发验证，不会打入发行包。插件只能以 `compileOnly` 依赖 `:sdk`（`dev.turboism:sdk`），切勿打包 SDK 或直接依赖运行时内部实现及 `com.live2d.*` 类。生命周期、权限与兼容规范见[插件开发指南](../../sdk/plugin-author-guide.md)。

### 框架开发

需要 **Git** 与 **JDK 17**（直接使用仓库自带的 `./gradlew`，Windows 下为 `gradlew.bat`）：

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

- **开发与验证环境**：**Windows x64** 可直接运行完整的原生 Cubism 宿主验证；**Linux** 可在 Xvfb 下运行无头 Gradle 与单元测试，并通过 Proton（Wine）运行 Cubism 宿主验证。
- **本地联调 SDK**：可将 SDK 与 `@SubscribeEvent` 注解处理器发布到本地 Maven 仓库（`~/.m2`），供外部插件工程通过 `mavenLocal()` 引用：
  ```bash
  ./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal
  ```
- 模块边界、事务模型与验证门禁见[架构说明](../architecture/ARCHITECTURE.md)。

## 文档

- [用户与开发者文档](https://docs.turboism.dev)
- [架构说明](../architecture/ARCHITECTURE.md) · [路线图](../ROADMAP.md)
- [SDK API 契约](../../sdk/api-contracts/) · [当前公开 SDK 表面](../../sdk/api-contracts/current.txt)
- [插件开发指南](../../sdk/plugin-author-guide.md) · [独立插件模板](../../templates/plugin-template/) · [示例插件](../../plugins/demo/README.md)
- [发布流程](../RELEASING.md) · [更新日志](CHANGELOG.md)

## 开源协议

Copyright © 2026 Turboism Contributors。本项目基于 [AGPL-3.0-only](../../LICENSE) 开源，第三方组件继续适用各自许可。
