[EN / English](README.md) · [ZH / 简体中文](README_zh.md) · [JP / 日本語](README_ja.md) · [KR / 한국어](README_ko.md)

# Turboism

## 版权声明

Copyright © 2026 Turboism Contributors。Turboism 采用 [MIT License](LICENSE) 开源许可。

Turboism 是**独立的第三方项目**，与 Live2D Inc. 不存在隶属关系，也未获得其背书或赞助。Live2D、Cubism 及相关名称、标志的权利归 Live2D Inc. 或相应权利人所有。Turboism 不分发 Cubism Editor，不提供、替代或绕过其许可；你需要另行安装并取得合法授权。

安装前请阅读[最终用户运行声明与免责声明](EULA.md)。该声明不缩减 MIT License 已授予的权利，且以简体中文正式文本为准。软件按**现状**提供；使用可能修改工程内容的插件或自动化功能前，请保留独立备份。

## 项目简介

Turboism 是面向 **Live2D Cubism Editor**、以 Windows 为主要平台的运行时增强工具与插件框架。它通过 Java Agent 和公开 SDK 提供建模工作流增强，例如参数与网格工具、PSD 辅助功能、面板增强及本地自动化。实际可用功能取决于已安装的插件、权限和 Editor 的精确版本。

## 支持的 Cubism Editor 版本

| Cubism Editor | 支持情况 |
| --- | --- |
| **5.2.03** | 提供精确版本适配器 |
| **5.3.02** | 提供精确版本适配器 |
| **5.3.03** | 提供精确版本适配器 |

支持的 Cubism 宿主平台为 **Windows x64**。未列出的 Editor 版本不承诺兼容；缺少所需适配器或功能时会拒绝启用，而不是猜测兼容性。支持某个版本不代表每个插件功能都能在该版本上使用。

Java 安装器也能在 macOS 和 Linux 上部署文件，但 macOS 打包仅处于预览阶段，尚未验证 Cubism 宿主支持；Linux 仅覆盖安装器和文件部署行为，不是受支持的 Cubism 宿主平台。

## 安装

从[最新 GitHub Release](https://github.com/turboism/Turboism/releases/latest)下载发行包及对应的 `.sha256` 文件。下列三种安装方式**任选一种**即可。安装或更新前，请关闭 Cubism 并备份工程。

当前安装器尚未签名，运行前请将下载文件的 SHA-256 与对应校验文件中的值进行比对。例如在 PowerShell 中执行（将 `<version>` 替换为下载的版本号）：

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### ZIP — Windows 手动配置

1. 选择 `turboism-<version>-full.zip` 可获得随包提供的第一方插件；`turboism-<version>-lite.zip` 仅包含运行时，不带插件 JAR。
2. 将**整个压缩包**解压到独立的 Turboism 文件夹，不要解压到 Cubism 安装目录。
3. 在解压后的文件夹中打开配置工具：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```

   执行策略选项仅对本次 PowerShell 进程生效。选择需要使用的 Cubism 安装和插件，然后保存。
4. 使用生成的 Turboism 快捷方式，或该文件夹中的 `launch-cubism-turboism.bat` 启动 Cubism。

### JAR — Java 安装器

安装 **Java 17 或更高版本**后运行：

```bash
java -jar "TurboismInstaller-<version>.jar"
```

阅读并完成许可和声明确认，选择安装目录与组件选项，然后完成安装。在 Windows 上，如有需要，可运行安装目录中的 `configure_turboism.ps1` 配置 Cubism，再通过 Turboism 启动。能在 macOS/Linux 上安装文件，不代表支持在这些平台上运行 Cubism 宿主。

### EXE — Windows 推荐方式

运行 `TurboismInstaller-<version>.exe`，按照安装向导选择安装目录、插件和启动选项。安装后使用生成的 Turboism 快捷方式启动。

与 Cubism 官方启动 BAT 的集成是**可选项**，必须明确勾选。该集成使用带哈希校验的备份；之后的手动修改可能导致无法自动恢复。请勿将这些安装器管理的备份当作工程备份。

所有发行包均不包含托管 fx 运行时文件，也不包含仅供开发使用的 Turboism with fx 插件。

## 开发

需要 **Git 和 JDK 17**。使用仓库自带的 Gradle Wrapper，无需另行安装 Gradle。

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

在 Windows 上，将 `./gradlew` 换为 `gradlew.bat`。请在独立的功能分支或 worktree 中进行修改。

开发插件时，可从[示例插件](plugins/demo/README.md)、其[构建配置](plugins/demo/build.gradle.kts)和[插件描述文件](plugins/demo/src/main/resources/META-INF/turboism/plugin.json)开始。插件通过 `compileOnly` 依赖 `:sdk`，不要直接依赖运行时内部实现或 `com.live2d.*` 类。

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

示例插件仅供开发使用，不随发行包提供。提交前请运行受影响的针对性测试和 `./gradlew devCheck`。API、生命周期、事务和验证规则请参阅[架构说明](ARCHITECTURE.md)。

## 文档

- [用户与开发者文档](https://docs.turboism.dev)
- [架构说明](ARCHITECTURE.md)与[路线图](ROADMAP.md)
- [SDK API 契约与兼容性](sdk/api-contracts/)及[SDK v7 迁移说明](sdk/api-contracts/sdk-api-v7-review.md)
- [示例插件](plugins/demo/README.md)
- [Java 安装器详细说明](packaging/java-installer/README-java-installer.md)
- [发布流程](RELEASING.md)与[更新日志](CHANGELOG.md)
