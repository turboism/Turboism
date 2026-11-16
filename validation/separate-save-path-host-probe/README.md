# separate-save-path-host-probe

The probe verifies the read-only file chooser history contract of an ordinary plugin.

Build the SDK first with `./gradlew :sdk:jar`, then build the probe separately with `bash validation/separate-save-path-host-probe/build.sh`. The offline regression command is `bash validation/separate-save-path-host-probe/test.sh`. Both scripts depend only on the SDK JAR and the JDK, never on the runtime or another plugin.

Before launch, the host Runner places `fixtures/save-directory-history.properties` into the shell configuration directory of an isolated home through `--home-file`. The two relative paths are independent sentinel values. The probe opens neither path and performs no save.

The probe must read both slots and verify the on-disk persistence. Both setters and the provider registration must each throw `UnsupportedOperationException`. After the rejection, the read result and the persisted content must stay unchanged. An unavailable service, an empty slot, a wrong slot, or a silently accepted write all fail. The probe also keeps the global configuration migration and export separation toggle assertions.

Results use schemaVersion=2 and mark `contract=plugin-read-only-seeded-history`. Older write round-trip evidence cannot prove this contract. This probe verifies the persistence read and the read-only boundary; the history that a real save dialog produces still needs separate host evidence.
