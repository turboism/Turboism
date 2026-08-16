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
./gradlew izPackCreateInstaller -PinstallerVersion=<version>
```

The installer.xml is generated at build time from each plugin JAR's
`META-INF/turboism/plugin.json` (missing, malformed, duplicate, or
runtime-owned `turboism.core` metadata fails the build; order is stable by
plugin id). The shared staged payload (`build/windows-installer/staging`)
is assembled by `stageInstallerPayload` and is the single source for the
Java installer, the NSIS installer and the ZIPs.

Pinned toolchain (no dynamic versions): Gradle plugin `org.izpack.gradle`
3.2.3 and `org.codehaus.izpack:izpack-ant` 5.2.6. Their SHA-256 entries live
in `gradle/verification-metadata.xml`.

## Install

```bash
java -jar TurboismInstaller-<version>.jar
```

Flow: Welcome -> License -> Full/Lite -> optional plugins (Full only) ->
target directory -> summary -> install -> finish. Full defaults every
first-party plugin to selected; Lite installs the agent and common files
with no plugin JAR copied. Default home: `%LOCALAPPDATA%\Turboism` (Windows),
`~/Library/Application Support/Turboism` (macOS),
`${XDG_DATA_HOME:-~/.local/share}/Turboism` (Linux); another directory may
be chosen.

`config.json` is never overwritten blindly: an existing valid config is
parsed with a bounded JSON parser, unrelated fields are preserved, and only
`worktreeId` (`turboism-runtime`), `pluginDirs` (`["plugins"]`) and
`disabledPlugins` are installer-owned. Reselection semantics: start from
the existing disabled ids, remove every current bundled id (so reselecting
a previously disabled bundled plugin enables it), then add the sorted
bundled-but-unselected ids; unrelated disabled ids remain. Lite treats every
bundled plugin id as unselected so stale official JARs from a prior Full
install stay inactive (retired ids are additionally pruned here; any leftover
retired JAR is denied by the runtime PluginJarContract boundary, since the
installer deletes only identity-proven retired JARs and never deletes
unverifiable entries). Invalid, oversized (> 64 KiB), symlinked, escaping,
or non-regular config targets fail closed without truncating the original;
reads are bounded and never follow symlinks, writes are atomic-only. Unknown
or third-party plugin files are never deleted.

Rerun the installer to change the selected plugin set.

On Windows, the staged payload also includes `configure_turboism.ps1` and `cubism-launch-common.ps1`. The configurator defaults to independent Turboism-owned shortcuts; the explicit takeover mode replaces only shortcuts whose COM target exactly matches a selected official Cubism BAT, records a same-directory byte backup, and uses a deterministic managed launcher with explicit `-Home`, `-CubismRoot`, and `-Variant`. Unmatched variants get independent fallback shortcuts. Cleanup restores only hash-matching managed entries; user edits or malformed state are conflicts and preserve state/backups for retry. It discovers supported Cubism 5.2 and 5.3 family candidates; it does not establish exact patch identity or host readiness, preserves separate installation state, and never edits a Cubism installation.

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
./gradlew checkJavaInstaller -PinstallerVersion=<version>
```

Deterministic, non-GUI (console mode), runnable on Linux/macOS/Windows with
Java 17. It builds the installer and verifies: JAR layout (en/zh/ja
langpacks, uninstaller, one required common pack, one optional pack per
non-core plugin), Lite install into a path containing spaces, Full install
with deselects, config merge and fail-closed cases (strict numbers,
canonical v1 identity, malformed UTF-8, size boundary), and both uninstall
branches with a synthetic third-party plugin file preserved. Every captured
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
