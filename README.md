[English](docs/en/README.md) / [简体中文](docs/zh/README.md) / [日本語](docs/ja/README.md) / [한국어](docs/ko/README.md)

# Turboism

## Disclaimer

Turboism is an independent third-party project—it is not affiliated with, endorsed by, or sponsored by Live2D Inc., and is not an official Cubism product (Live2D, Cubism, and related marks belong to Live2D Inc. or their respective owners).

Turboism does not distribute Cubism Editor and will never help users bypass license checks, unlock restricted features, or exceed the permissions of their Cubism license. A separately installed, legitimately licensed copy of Cubism Editor is required.

The software is provided **as is** under [AGPL-3.0-only](LICENSE). Read the [EULA](EULA.md) before installing and keep independent backups of your projects.

## About

Turboism is a Windows-first runtime enhancement tool and plugin framework for **Live2D Cubism Editor**. Built on a Java agent and a public SDK, it extends modeling workflows with parameter and mesh tools, PSD utilities, palette enhancements, and local automation—depending on the plugins you enable and your Editor version.

## Current Features

### Framework

- **Performance & JVM Tuning**: Opt-in rendering, model-update, clip-mask, texture-upload, and mesh/triangulation optimizations, plus Cubism JVM profile selection (such as ZGC / GraalVM) and optional startup dialog suppression.
- **Plugin Platform & Marketplace**: Built-in Plugin Management and an Ed25519-signed Plugin Marketplace (install, update, and toggle plugins), enforced permission and Cubism-version admission checks, and framework update checks.
- **Host Shell & Developer Tooling**: Unified Turboism menu, toolbar home entry, shared Settings & Keybindings UI, save-directory history, diagnostic logs, a public Java SDK (`dev.turboism:sdk`), and an out-of-process GraalJS scripting host.

### First-Party Plugins

Standard release packages bundle 35 first-party plugins (`parameter-lock` is distributed via the Plugin Marketplace). See the [plugin compatibility matrix](compatibility/cubism/plugins.md) for per-version support:

- **Modeling, Mesh & Deformers**: `turbo-mesh` (custom curvature-aware point placement & triangulation), `geometric-mesh-layout` (circular/polygon mesh tools), `selection-brush`, `artmesh-mirror`, `mesh-edit-mirror-axis-enhance`, `boundingbox-warp-mirror`, `warp-deformer-alt-symmetry`, `deformer-batch-rename`.
- **Parameters, Physics & Animation**: `parameter` (CSV import/export, binding inversion & transfer), `parameter-batch-transfer`, `parameter-sync`, `parameter-lock` *(Marketplace)*, `physics-editor` (select-all & state retention), `frame-animation-import` (sprite-sheet/image-sequence import).
- **PSD, 3D & Texture Atlas**: `external-psd-edit` (live raw PSD editing), `psd-clip-mask-import`, `model-import-3d` (GLB/glTF/OBJ import), `atlas-maxrects-bssf`, `atlas-dalsoo-polygon`, `atlas-bitmask-nesting`, `atlas-folder-layout`, `texture-atlas-stats`.
- **Workspace, UI & Inspection**: `ui-theme`, `command-palette` (`Ctrl+K`), `cubism-tab-filter`, `palette-label-style`, `scene-palette-enhancer`, `recent-project-board`, `recent-preview`, `history-panel` (semantic Undo timeline), `clipmask-viewer`, `perf-stats`, `project-work-time`.
- **Backup, Export & AI / Automation**: `webdav-backup`, `protected-export` (Cubism 5.1+ flattened/obfuscated export), `mcp` (local MCP server), `acp` (ACP v1 agent chat window).

## Platform Support

- **Windows (x64)** is currently the primary supported and maintained platform.
- **macOS**: Thanks to Java's cross-platform nature, Turboism may also be able to run on macOS (`turboism-<version>.zip` is provided as an unverified preview package). We do not currently test or maintain macOS builds—if you have a Mac and are willing to help test or contribute, please reach out via [GitHub Issues](https://github.com/turboism/Turboism/issues).

## Cubism Support

| Cubism Editor | Support |
| --- | --- |
| **5.0.00 – 5.0.06** | Partial — missing auto backup, auto sway read, geometry read, integration bridges, custom modeling tools, protected export, top-menu integration, MOC export read, and part-inspector writes; animation/timeline is partial |
| **5.1.00 – 5.1.03** | Partial — missing geometry read, custom modeling tools, MOC export read, and part-inspector writes; animation/timeline is partial (integration bridges partial before 5.1.02) |
| **5.2.00 – 5.2.03** | Near-complete — missing only 5.3-only surfaces (alpha composition, part-inspector writes, MOC export read, Core parameter repeat) |
| **5.3.00 – 5.3.04** | Full support — all verified capabilities available |

Unlisted Editor versions and unsupported features are disabled automatically; see the [capability matrix](compatibility/cubism/capabilities.md) for per-API details.

## Installation

Close Cubism Editor and back up your projects first, then download a package from the [latest GitHub Release](https://github.com/turboism/Turboism/releases/latest). Standard packages include all bundled first-party plugins and offline GraalJS dependencies (a compatible GraalVM runtime can be installed later via the configurator).

Installers are currently unsigned; verify your download against its `.sha256` file before running:

```powershell
Get-FileHash ".\TurboismInstaller-<version>.exe" -Algorithm SHA256
```

### EXE (Recommended on Windows)

Run `TurboismInstaller-<version>.exe` and follow the wizard (available in English, 简体中文, 日本語, and 한국어; runtime language is configured in `config.json`). Launch-script integration with Cubism's startup `.bat` is enabled by default with automatic backup/restore on uninstall, and upgrades preserve your `disabledPlugins` settings. Launch Cubism via the generated Turboism shortcut afterward.

### ZIP (Portable)

1. Extract `turboism-<version>.zip` to its own folder (not inside the Cubism installation directory).
2. Run the configurator to select your Cubism installations and plugins:
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ".\configure_turboism.ps1"
   ```
3. Launch Cubism from the generated shortcut or `launch-cubism-turboism.bat`.

## Development

### Plugins

You do not need to clone or build Turboism to write a plugin—only **JDK 17+** is required:

1. Create a project from the [standalone plugin template](https://github.com/turboism/turboism-plugin-template) and run `./scripts/setup.sh` (or `.\scripts\setup.ps1` on Windows) to fetch and verify `turboism-sdk-<version>.jar` and `turboism-event-processor-<version>.jar`, or place them in `libs/` manually.
2. Build with `./gradlew build` (`gradlew.bat build` on Windows).

If you are working inside this repository, refer to the [demo plugin](plugins/demo/README.md) ([build.gradle.kts](plugins/demo/build.gradle.kts), [plugin.json](plugins/demo/src/main/resources/META-INF/turboism/plugin.json)) and build it with:

```bash
./gradlew :plugins:demo:test :plugins:demo:jar
```

> Note: `plugins:demo` is development-only and excluded from release bundles. Plugins must depend on `:sdk` (`dev.turboism:sdk`) with `compileOnly` scope only—never bundle the SDK or depend on runtime internals or `com.live2d.*`. See the [Plugin Author Guide](sdk/plugin-author-guide.md) for lifecycle, permissions, and API rules.

### Framework

Requires **Git** and **JDK 17** (use the bundled `./gradlew`, or `gradlew.bat` on Windows):

```bash
git clone https://github.com/turboism/Turboism.git
cd Turboism
./gradlew devCheck
```

- **Environments**: **Windows x64** runs native Cubism host validation; **Linux** runs headless Gradle/unit checks under Xvfb and Cubism host validation via Proton (Wine).
- **Local SDK testing**: Publish the SDK and `@SubscribeEvent` annotation processor to `~/.m2` so standalone plugins can resolve `mavenLocal()`:
  ```bash
  ./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal
  ```
- See [Architecture](docs/architecture/ARCHITECTURE.md) for module boundaries, transactions, and verification gates.

## Documentation

- [User and developer docs](https://docs.turboism.dev)
- [Architecture](docs/architecture/ARCHITECTURE.md) · [Roadmap](docs/ROADMAP.md)
- [SDK API contracts](sdk/api-contracts/) · [Current public SDK surface](sdk/api-contracts/current.txt)
- [Plugin Author Guide](sdk/plugin-author-guide.md) · [Plugin template](https://github.com/turboism/turboism-plugin-template) · [Demo plugin](plugins/demo/README.md)
- [Release process](docs/RELEASING.md) · [Changelog](docs/en/CHANGELOG.md)

## License

Copyright © 2026 Turboism Contributors. Licensed under [AGPL-3.0-only](LICENSE); third-party components retain their respective licenses.
