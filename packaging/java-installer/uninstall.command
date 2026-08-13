#!/bin/sh
# Turboism uninstaller launcher (macOS).
#
# Runs the IzPack-generated uninstaller for the Turboism home directory that
# contains this script, in the normal interactive mode: the uninstaller asks
# whether to delete config.json (default: delete; closing the dialog keeps it)
# and removes the installed agent, installer-owned plugin JARs,
# installer-owned files, the generated uninstaller, and the runtime
# logs/state/cache directories. Unknown files and third-party plugin JARs are
# preserved.
#
# Equivalent command from a terminal:
#   java -jar "$(dirname "$0")/Uninstaller/uninstaller.jar"
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UNINSTALLER="$SCRIPT_DIR/Uninstaller/uninstaller.jar"
if [ ! -f "$UNINSTALLER" ]; then
  echo "Turboism uninstaller not found: $UNINSTALLER" >&2
  echo "Press Enter to close this window." >&2
  read -r _ || true
  exit 1
fi
exec java -jar "$UNINSTALLER"
