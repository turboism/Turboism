# 原生 Harness 安装与检测

核对日期：2026-10-20。检测只读取本机安装并运行版本、帮助或 ACP 握手，不下载或安装软件。

| Harness | 官方安装渠道 | Windows 检测入口 |
| --- | --- | --- |
| Claude Code | 原生 PowerShell/CMD 脚本、WinGet；npm 为旧安装方式；macOS 另有 Homebrew，Linux 有系统包 | PATH、`~/.local/bin/claude.exe`、WinGet Links 和 `Microsoft/WinGet/Packages/Anthropic.ClaudeCode_*` 下的 `claude.exe`、npm `.cmd` |
| Codex | 原生 Windows PowerShell / macOS 和 Linux shell 脚本、npm `@openai/codex`、Homebrew | PATH、通用用户 bin、npm `.cmd`；官方安装页未明确独立脚本的默认目录，不依赖猜测的私有版本目录 |
| Pi | Windows PowerShell / Unix shell 脚本、npm（当前文档为 `@earendil-works/pi-coding-agent`） | PATH、npm `.cmd`、`~/.pi/agent/bin/pi.cmd`、`PI_CODING_AGENT_DIR/bin` |

官方来源：

- [Claude Code setup](https://code.claude.com/docs/en/setup)
- [OpenAI Docs：Codex CLI](https://developers.openai.com/codex/cli)
- [Pi README](https://github.com/earendil-works/pi/tree/main/packages/coding-agent)
- [Pi 官方 Windows 安装脚本](https://pi.dev/install.ps1)（仅审阅，未执行）

优先使用安装器公开的 launcher；不从私有 releases/versions 目录任选一个旧版本。Windows 跳过 npm 同目录的无扩展名 Unix shim 和 `.ps1`，使用 `.exe`、`.cmd` 或 `.bat`。不通过 WSL 启动 Linux 安装。

搜索继承 PATH，并补充常见 npm、原生 bin、WinGet、Scoop、Volta、Bun、pnpm 等位置，以及 `NPM_CONFIG_PREFIX`、`PI_NPM_INSTALL_PREFIX`、`NVM_SYMLINK`、`PNPM_HOME`。这些通用目录是兼容性回退，不代表三个项目都官方推荐对应的包管理器。任意自定义安装仍需加入 PATH 或填写自定义命令。

为处理 Cubism 启动后安装 CLI 导致的旧环境，启动检测和 Agent 子进程时仅补充子进程 PATH（含常见 Node 目录和 Pi 管理的 `LOCALAPPDATA/pi-node/current`），不修改用户或系统环境。

检测先读取 `--version`，再检查原生模式：Claude 的 stream-json / input-format、Codex 的 app-server、Pi 的 rpc / extension；ACP adapter 使用 initialize 握手。版本号用于展示与诊断，实际能力决定可用性。stdout、stderr 同时读取并有超时、输出上限与进程清理。使用普通分块读取，避开 Cubism Windows Java 17 的 `FileInputStream.readNBytes()` 对进程管道调用 `position0` 而抛出 IOException 的行为；读取失败也不再被转换成空输出。当前候选不支持时继续查找其余安装；手动“检测”会清除短时缓存。

Agent 管理页显示已找到的路径和版本，并区分启动/协议检查失败与原生模式不支持；输入框的 harness 菜单仍只显示可用项。日志 `Harness detection:` 记录每个候选的路径、版本及结果：`executable-not-found`、`probe-failed-*`、`native-mode-not-advertised`、`initialize-failed-*` 或 `available`。未找到和启动/协议检查失败不能混为安装缺失。

回归测试覆盖用户提供的 WinGet Claude 与 npm Pi/Codex 布局、脚本 launcher、配置前缀、Node 子进程 PATH、stderr 输出、版本、后续候选重试、刷新缓存和非零退出。另有受控 `.cmd` 脚本在 Cubism 的 Windows JRE 下验证带空格路径、stderr 版本和帮助输出；这不等于已在用户 Windows 上启动真实 CLI。
