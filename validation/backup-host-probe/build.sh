#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local auto-backup host validation exerciser plugin JAR against
# the already-built SDK jar. The probe is validation tooling only; it is never
# part of the production preview bundle or product build.
#
# The probe reuses the production WebDAV client sources from plugins/webdav-backup
# the real-host run exercises the exact plugin WebDAV target code (JDK-only).
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../shared/probe-build.sh"

webdav_src="plugins/webdav-backup/src/main/java/dev/turboism/plugin/webdavbackup/webdav"
probe_build_plugin_jar validation/backup-host-probe \
  build/backup-host-validation-exerciser.jar \
  validation/backup-host-probe/src/dev/turboism/validation/backup/BackupHostValidationPlugin.java \
  "$webdav_src/WebDavConfig.java" \
  "$webdav_src/WebDavSyncTarget.java" \
  "$webdav_src/BoundedInputStreamBodyPublisher.java"
