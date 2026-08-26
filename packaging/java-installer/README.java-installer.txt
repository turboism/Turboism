Turboism Java Installer (macOS / Linux)
=========================================

This directory is the Turboism home. It contains the Turboism agent
(turboism-agent.jar), this README, LICENSE.txt, and (Full installs) the
first-party plugin JARs under plugins/. On Windows x64, a managed GraalVM
runtime may additionally be installed under graal/runtime; the packaged
isolated host libraries remain under graal/lib and its download cache is
cache/runtime/graal.

Install and update
------------------
Run the installer with a Java 17+ runtime:

    java -jar TurboismInstaller-<version>.jar

Full mode installs the agent and every first-party plugin and lets you
deselect individual plugins. Lite mode installs only the agent and common
files. The installer never overwrites an existing config.json blindly: it
preserves unrelated settings and only merges the plugin selection
(disabledPlugins). Rerun the installer at any time to change the selected
plugin set. Applicable legal files for a managed GraalVM runtime are preserved.

On Windows, run the packaged configure_turboism.ps1 later whenever you need to
change Cubism installations or managed launch entries. It keeps installation
state separate from config.json and calls selected roots only through their
official Cubism BAT. Canceling setup leaves a valid framework-only install;
Cubism files are never changed.

Managed GraalVM runtime (Windows x64)
--------------------------------------
When GraalVM is selected but absent, open Turboism Settings > Performance >
Cubism JVM and explicitly start the automatic install. It installs the pinned
GraalVM Community 25.2.4 / JDK 25.0.4 runtime to graal/runtime. Before
activation, it verifies official HTTPS hosts, release metadata, exact size and
SHA-256, safe extraction, and an isolated host READY result. It never changes
JAVA_HOME, PATH, the registry, Cubism, or Cubism's bundled Java. Failure or
cancellation retains the prior selection. Startup never downloads; an
unresolvable saved/default GraalVM preference falls back to Cubism's bundled
Java. The service can verify or remove the managed runtime, but the current UI
offers installation only. For manual removal, close Cubism and Turboism, delete
graal/runtime, and preserve graal/lib.

Default install location (change it in the installer):
  macOS:  ~/Library/Application Support/Turboism
  Linux:  ${XDG_DATA_HOME:-~/.local/share}/Turboism

Uninstall
---------
macOS: double-click `uninstall.command` in this directory (or run it from a
terminal). It launches the generated uninstaller, which asks whether to also
delete config.json (default: delete).

Linux: run the generated uninstaller from a terminal:

    java -jar "$HOME/.local/share/Turboism/Uninstaller/uninstaller.jar"

On Windows, the uninstaller also removes the entire graal directory (including
any managed runtime and packaged graal/lib), together with the installed agent,
installer-owned plugin JARs, installer-owned files, the generated uninstaller,
and the runtime logs/state/cache directories. On macOS and Linux, it removes the
installed agent, installer-owned plugin JARs, installer-owned files, the
generated uninstaller, and the runtime logs/state/cache directories. Unknown
files and third-party plugin JARs are preserved; config.json is removed only
when you select deletion; the home directory is removed only when empty.

Platform status
---------------
macOS packaging: Preview
macOS Cubism host: not verified
Linux Cubism host: not supported; Linux covers installer/payload semantics
only.

Launching Cubism with Turboism on macOS is not part of this package: no
Cubism launch command is installed, and no macOS Cubism host readiness is
claimed.
