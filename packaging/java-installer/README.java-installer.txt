Turboism Java Installer (macOS / Linux)
=========================================

This directory is the Turboism home. It contains the Turboism agent
(turboism-agent.jar), this README, LICENSE.txt, and (Full installs) the
first-party plugin JARs under plugins/.

Install and update
------------------
Run the installer with a Java 17+ runtime:

    java -jar TurboismInstaller-<version>.jar

Full mode installs the agent and every first-party plugin and lets you
deselect individual plugins. Lite mode installs only the agent and common
files. The installer never overwrites an existing config.json blindly: it
preserves unrelated settings and only merges the plugin selection
(disabledPlugins). Rerun the installer at any time to change the selected
plugin set.

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
