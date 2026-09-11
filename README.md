[EN / English](README.md) · [ZH / 简体中文](README_zh.md) · [JP / 日本語](README_ja.md) · [KR / 한국어](README_ko.md)

# Turboism

## Copyright and notices

Copyright © 2026 Turboism Contributors. Turboism is open source under the [MIT License](LICENSE).

Turboism is an **independent third-party project**, not affiliated with, endorsed by, or sponsored by Live2D Inc. Live2D, Cubism and related names and marks belong to Live2D Inc. or their respective owners. Turboism does not distribute Cubism Editor or grant, replace or bypass its license; a separately installed, lawfully licensed copy is required.

Read the [End User Runtime Statement and Disclaimer](EULA.md) before installation. It does not reduce the rights granted by the MIT License; its Simplified Chinese text is authoritative. The software is provided **as is**. Keep independent backups before using plugins or automation that change project content.

## About

Turboism is a Windows-first runtime enhancement tool and plugin framework for **Live2D Cubism Editor**. It uses a Java agent and a public SDK to provide modeling workflow improvements, including parameter and mesh tools, PSD utilities, palette enhancements and local automation. Available features depend on the installed plugins, permissions and exact Editor version.

## Supported Cubism Editor versions

| Cubism Editor | Support |
| --- | --- |
| **5.2.03** | Exact-version adapters |
| **5.3.02** | Exact-version adapters |
| **5.3.03** | Exact-version adapters |

**Windows x64** is the supported Cubism host platform. Unlisted Editor versions are not claimed compatible; unavailable adapters or features fail closed. Version support does not mean every plugin feature is available on every version.

The Java installer can also package/install files on macOS and Linux. macOS packaging is preview-only with no verified Cubism host support; Linux covers installer/payload behavior only and is not a supported Cubism host.

## Installation

Download a package and its matching `.sha256` file from the [latest GitHub Release](https://github.com/turboism/Turboism/releases/latest). Choose **one** installation format below. Close Cubism and back up your projects before installing or updating.

Every Turboism installer ships in **English, Simplified Chinese, Japanese and Korean**. Choosing a language only changes the installer's own interface; the language Turboism uses at runtime still comes from `config.json` (`locale`).

Installers are currently unsigned. Verify the downloaded file against its checksum before running it. For example, in PowerShell (replace `<version>` with the downloaded version):

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### ZIP — manual setup on Windows

1. Choose `turboism-<version>-full.zip` for the bundled first-party plugins, or `turboism-<version>-lite.zip` for the runtime without plugin JARs.
2. Extract the **whole archive** into a separate Turboism folder, not into the Cubism installation directory.
3. From the extracted folder, open the configurator:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```

   The execution-policy option applies only to that PowerShell process. Select the Cubism installations and plugins you want, then save.
4. Start Cubism using the generated Turboism shortcut or `launch-cubism-turboism.bat` in that folder.

### JAR — Java installer

Install **Java 17 or newer**, then run:

```bash
java -jar "TurboismInstaller-<version>.jar"
```

Complete the license/declaration prompts, choose the installation directory and package options, and finish setup. The wizard starts with a language-selection dialog for the four supported languages; pass `-language eng|chn|jpn|kor` to preselect one and skip that dialog. On Windows, use the installed `configure_turboism.ps1` to configure Cubism if needed, then launch through Turboism. Installing files on macOS/Linux does not imply Cubism host compatibility there.

### EXE — recommended on Windows

Run `TurboismInstaller-<version>.exe` and follow the setup wizard to choose the installation directory, plugins and launch options. The wizard offers a language picker (English, Simplified Chinese, Japanese, Korean) before the welcome page; it applies to the installer only. Use the generated Turboism shortcut afterward.

Integration with the official Cubism startup BAT is **optional** and must be explicitly selected. It uses hash-guarded backups; later user edits can prevent automatic restoration. Keep your project backups separate from these installer-managed backups.

No release package includes managed fx runtime bytes or the development-only Turboism with fx plugin.

## Development

You need **Git and JDK 17**. Use the repository's Gradle wrapper; no separate Gradle installation is required.

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Make changes on a separate feature branch or worktree.

For plugin development, start with the [demo plugin](plugins/demo/README.md), its [build configuration](plugins/demo/build.gradle.kts) and [plugin descriptor](plugins/demo/src/main/resources/META-INF/turboism/plugin.json). Plugins depend on `:sdk` with `compileOnly` scope; do not directly depend on runtime internals or `com.live2d.*` classes.

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

The demo is development-only, not part of the release bundle. Run focused tests for your changes and `./gradlew devCheck` before submitting them. See the [architecture](ARCHITECTURE.md) for API, lifecycle, transaction and verification rules.

## Documentation

- [User and developer documentation](https://docs.turboism.dev)
- [Architecture](ARCHITECTURE.md) and [roadmap](ROADMAP.md)
- [SDK API contracts and compatibility](sdk/api-contracts/) and [SDK v7 migration notes](sdk/api-contracts/sdk-api-v7-review.md)
- [Demo plugin](plugins/demo/README.md)
- [Java installer details](packaging/java-installer/README-java-installer.md)
- [Release process](RELEASING.md) and [changelog](CHANGELOG.md)
