# Native Harness Installation and Detection

Check date: 2026-10-20. The detection reads the local installation only and runs the version, help, or ACP handshake. It downloads and installs nothing.

| Harness | Official installation channel | Windows detection entry |
| --- | --- | --- |
| Claude Code | Native PowerShell/CMD script, WinGet; npm is the older installation route; macOS also has Homebrew and Linux has a system package | PATH, `~/.local/bin/claude.exe`, WinGet Links, `claude.exe` under `Microsoft/WinGet/Packages/Anthropic.ClaudeCode_*`, and the npm `.cmd` |
| Codex | Native Windows PowerShell and macOS or Linux shell script, the npm `@openai/codex`, Homebrew | PATH, the common user bin, the npm `.cmd`. The official installation page does not state the default directory of the standalone script, so no guessed private version directory is used |
| Pi | Windows PowerShell or Unix shell script, npm (the current documentation names `@earendil-works/pi-coding-agent`) | PATH, the npm `.cmd`, `~/.pi/agent/bin/pi.cmd`, `PI_CODING_AGENT_DIR/bin` |

Official sources:

- [Claude Code setup](https://code.claude.com/docs/en/setup)
- [OpenAI Docs: Codex CLI](https://developers.openai.com/codex/cli)
- [Pi README](https://github.com/earendil-works/pi/tree/main/packages/coding-agent)
- [Official Pi Windows install script](https://pi.dev/install.ps1), reviewed only and never executed

The public launcher of an installer is preferred, and no older version is picked at random from a private releases or versions directory. On Windows the detection skips the extension-less Unix shim and the `.ps1` in the npm directory and uses the `.exe`, `.cmd`, or `.bat`. It never starts a Linux installation through WSL.

The search inherits PATH and adds the common npm, native bin, WinGet, Scoop, Volta, Bun, and pnpm locations, plus `NPM_CONFIG_PREFIX`, `PI_NPM_INSTALL_PREFIX`, `NVM_SYMLINK`, and `PNPM_HOME`. Those generic directories are a compatibility fallback and do not mean that all three projects officially recommend the matching package manager. A custom installation still needs a PATH entry or a custom command.

To handle an old environment where a CLI was installed after Cubism started, the startup detection and the Agent child process extend only the child PATH, which includes the common Node directories and the Pi-managed `LOCALAPPDATA/pi-node/current`, and they never modify the user or system environment.

The detection reads `--version` first and then checks the native mode: stream-json and input-format for Claude, app-server for Codex, rpc and extension for Pi, and an initialize handshake for an ACP adapter. The version number serves display and diagnosis, and the real capability decides availability. The detection reads stdout and stderr together with a timeout, an output limit, and process cleanup. It uses ordinary chunked reads because the Cubism Windows Java 17 `FileInputStream.readNBytes()` calls `position0` on a process pipe and throws IOException; a read failure is no longer converted into empty output. When the current candidate is unsupported, the search continues with the other installations, and a manual "Detect" clears the short-lived cache.

The Agent management page shows the path and version it found and separates a startup or protocol failure from an unsupported native mode. The harness menu of the input box still lists only the available items. The `Harness detection:` log records the path, the version, and the result of each candidate: `executable-not-found`, `probe-failed-*`, `native-mode-not-advertised`, `initialize-failed-*`, or `available`. A missing installation and a failed startup or protocol check are never conflated.

The regression tests cover the WinGet Claude and npm Pi/Codex layouts a user provided, a script launcher, a configuration prefix, the Node child PATH, stderr output, the version, the retry of a later candidate, the refreshed cache, and a non-zero exit. A controlled `.cmd` script also verifies a path with a space, the stderr version, and the help output under the Cubism Windows JRE; that is not the same as starting a real CLI on a user Windows machine.
