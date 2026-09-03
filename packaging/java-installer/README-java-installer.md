# Turboism cross-platform Java installer

IzPack-based installer JAR that installs the same Turboism agent and
first-party plugin payload on Windows, macOS, and Linux. The NSIS EXE and
Lite/Full ZIP artifacts remain the Windows-native delivery; this JAR is the
cross-platform option and requires a Java 17+ runtime.

## Artifacts

A release build retains (all under `build/windows-installer/dist/`):

- `turboism-<version>-lite.zip`, `turboism-<version>-full.zip` (+ `.sha256`);
- `TurboismInstaller-<version>.exe` (+ `.sha256`) — NSIS, preferred on Windows;
- `TurboismInstaller-<version>.jar` (+ `.sha256`) — this installer.

## Build

```bash
./gradlew izPackCreateInstaller \
  -PinstallerVersion=<version> \
  -PturboismRelease=true
```

The installer.xml is generated at build time from each plugin JAR's
`META-INF/turboism/plugin.json` (missing, malformed, duplicate, or
runtime-owned `turboism.core` metadata fails the build; order is stable by
plugin id). `stageInstallerPayload` assembles the runtime-free shared Windows
source at `build/windows-installer/staging` for the NSIS installer and ZIPs.
`stageJavaInstallerPayload` copies that source to
`build/java-installer/staging` and adds only the reviewed Linux/macOS managed
fx payloads for the Java installer. No Windows ZIP or NSIS payload contains
`runtimes/fx/**`.

Pinned toolchain (no dynamic versions): Gradle plugin `org.izpack.gradle`
3.2.3 and `org.codehaus.izpack:izpack-ant` 5.2.6. Their SHA-256 entries live
in `gradle/verification-metadata.xml`.

## Install

```bash
java -jar TurboismInstaller-<version>.jar
```

Flow: Welcome -> MIT License -> four required Turboism runtime-declaration acknowledgements -> full localized runtime declaration -> Full/Thin/Lite -> optional plugins (Full and Thin) -> target directory -> summary -> install -> finish. Full defaults
every first-party plugin to selected and installs the matching reviewed managed
fx runtime. Linux and macOS use their reviewed OS/CPU payloads. Windows x64 Full
installs the reviewed fx product payload; other Windows architectures fail
closed before config or payload mutation. Thin installs the complete plugin
roster without native fx bytes and also accepts an explicit custom executable
path. The first-party loopback MCP server is available on supported Windows
hosts with bearer authentication and per-user, owner/file-type, reparse-point,
secure-temporary-file, and post-move publication checks. Verified online fx
repair remains limited to the reviewed Linux/macOS platforms. Lite installs the
agent and common files with no plugin JAR or fx runtime. Default home:
`%LOCALAPPDATA%\Turboism` (Windows),
`~/Library/Application Support/Turboism` (macOS),
`${XDG_DATA_HOME:-~/.local/share}/Turboism` (Linux); another directory may
be chosen.

The declaration gate is the same contract in GUI, console, and automated modes: all four acknowledgements must be explicit before the full localized declaration is accepted. The acknowledgements cover Turboism's independent third-party identity, lawful Cubism authorization, independent backups before authorized content-changing automation, and open-source as-is operation without compatibility or recovery guarantees.

`config.json` has explicit update ownership. A fresh installation creates the
current schema from the bundled template and initial plugin selection. On an
existing valid v1 document, the installer changes only `disabledPlugins`; all
other user settings remain intact, and a selection that is already current
leaves the original bytes untouched. A recognized legacy v0 document is
migrated to v1, validated against the complete runtime schema, and then receives
the selected plugin state in the same atomic publication. Malformed, unknown,
future-schema, or runtime-invalid documents fail closed before payload mutation
without modifying the original. Retired JAR cleanup remains identity-verified
and independent of config migration; any leftover retired JAR is denied by the
runtime `PluginJarContract` boundary because the installer deletes only
identity-proven retired JARs and never deletes unverifiable entries. Invalid,
oversized (> 64 KiB), symlinked, escaping, or non-regular config targets fail
closed without truncating the original; reads are bounded and never follow
symlinks, and writes use atomic replacement only. Unknown or third-party plugin
files are never deleted.

Rerun the installer to apply a different plugin selection.

On Windows, the staged payload also includes `configure_turboism.ps1` and `cubism-launch-common.ps1`. After installation the configurator opens automatically, lists only exact supported Cubism Editor 5.2.03, 5.3.02, and 5.3.03 installations, and presents independent controls for Turboism-owned shortcuts and official Cubism BAT integration. BAT integration runs only for exact installations selected by the user, records a hash-guarded backup, and cleanup restores only hash-matching managed files; user edits or malformed state are conflicts and preserve state/backups for retry. The configurator writes actionable diagnostics under `logs/installer/`.

## Uninstall

The installer generates `Uninstaller/uninstaller.jar` under the home
directory. The uninstaller removes the installed agent, installer-owned
plugin JARs, installer-owned launch/configuration files, the generated
uninstaller, and the runtime `logs`, `state` and `cache` directories.
`config.json` is removed only when selected (en/zh/ja confirmation,
default delete; closing the dialog without choosing preserves it). Windows
takeover records are restored from exact backups before those records or
the home can be removed. A user-edited shortcut, invalid state, or failed
atomic restoration preserves the shortcut, state, backups, and home for a
later retry. Unknown files and third-party plugin JARs are preserved; the
home directory is removed only when empty. Custom cleanup runs only when the
uninstaller path proves the exact `<home>/Uninstaller/uninstaller.jar`
layout; a missing or malformed identity performs no custom deletion and
never falls back to the working directory.

- Windows: `java -jar <home>\Uninstaller\uninstaller.jar`
- macOS: double-click `uninstall.command` in the home directory (launches
  the interactive uninstaller)
- Linux: `java -jar <home>/Uninstaller/uninstaller.jar`

Non-interactive (console) runs pass the property and `-console` explicitly,
e.g. `java -Dturboism.uninstall.deleteConfig=false -jar <home>/Uninstaller/
uninstaller.jar -console`.

## Verification

```bash
./gradlew checkJavaInstaller \
  -PinstallerVersion=<version> \
  -PturboismRelease=true
```

Deterministic, non-GUI (console mode), runnable on Linux/macOS/Windows with
Java 17. It builds the installer and verifies: JAR layout (en/zh/ja
langpacks, uninstaller, one required common pack, one optional pack per
non-core plugin), Lite and Thin installs, Full install with deselects on a
reviewed platform, compiled listener policy behavior for Windows x64 Full and
unsupported Windows architectures, config selection/migration and fail-closed
cases (strict numbers, ASCII-only Unicode escapes, canonical v1 identity,
invalid v1 values, malformed UTF-8, size boundary), and both uninstall branches
with a synthetic
third-party plugin file preserved. Every captured
JVM runs with a task-owned `java.io.tmpdir` and `-Dfile.encoding=UTF-8`;
all subprocess text is decoded with explicit UTF-8, so the live en/zh/ja
locale probes are byte-deterministic on any host. The global IzPack lock
path (`<tmp>/iz-Turboism.tmp`) is never created, written or deleted by the
verifier — a read-only snapshot asserts it is untouched (absent stays
absent; a pre-existing file/symlink/special path stays byte/target/mode-
identical).

macOS CI: the manual workflow
`.github/workflows/macos-packaging-verification.yml` (Apple Silicon and
Intel, Java 17, pinned Actions) runs only `checkJavaInstaller`.

## Platform status

- macOS packaging: Preview
- macOS Cubism host: not verified
- Linux Cubism host: not supported; Linux covers installer/payload
  semantics only

No macOS Cubism launch command is installed, and no host-readiness claim is
made for macOS or Linux.
