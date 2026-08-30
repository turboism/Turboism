Turboism Java Installer (macOS / Linux)
=========================================

This directory is the Turboism home. It contains the Turboism agent
(turboism-agent.jar), this README, LICENSE.txt, and (Full installs) the
first-party plugin JARs under plugins/.

Install and update
------------------
Run the installer with a Java 17+ runtime:

    java -jar TurboismInstaller-<version>.jar

Full mode installs the agent, every first-party plugin, and the matching
offline managed fx runtime on reviewed macOS/Linux OS and CPU pairs. It rejects
Windows before changing config or payload files. Thin mode installs the same
plugins without native fx bytes; on reviewed macOS/Linux pairs, Turboism with fx
can explicitly download and verify the exact reviewed platform runtime later.
On Windows, Thin installation and an explicit custom executable path are
structurally accepted. The first-party loopback MCP server is available with
bearer authentication and per-user, owner/file-type, reparse-point,
secure-temporary-file, and post-move publication checks, but no managed native
fx runtime is shipped for Windows. Both plugin modes let you deselect
individual plugins. Lite mode installs only the agent and common
files. The installer never overwrites an existing config.json blindly: it
preserves unrelated settings and only merges the plugin selection
(disabledPlugins). Rerun the installer at any time to change the selected
plugin set.

On Windows, configure_turboism.ps1 opens after installation and can be run
again later. It lists only exact supported Cubism Editor 5.2.03, 5.3.02, and
5.3.03 installations and exposes separate controls for Turboism-owned
shortcuts and official Cubism BAT integration. BAT integration runs only for
selected installations, preserves a hash-guarded backup, and writes actionable
diagnostics under logs/installer/. Canceling setup leaves a valid
framework-only install.

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

The uninstaller removes the installed agent, installer-owned plugin JARs,
installer-owned files, the generated uninstaller, and the runtime
logs/state/cache directories. Unknown files and third-party plugin JARs are
preserved; config.json is removed only when you select deletion; the home
directory is removed only when empty.

Platform status
---------------
macOS packaging: Preview
macOS Cubism host: not verified
Linux Cubism host: not supported; Linux covers installer/payload semantics
only.

Launching Cubism with Turboism on macOS is not part of this package: no
Cubism launch command is installed, and no macOS Cubism host readiness is
claimed.
