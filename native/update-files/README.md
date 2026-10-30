# W2 read-only Windows receipt validation

This original AGPL-3.0 core is independent of JNI. It is **not enabled by any
production entry point**. The only Java selection is the package-local
`WindowsInstallationFiles.validationFactory`. Cache writes, installation,
Helper, DLL extraction, and production key configuration are absent.

The core opens a fixed local NTFS drive and each child relative to a retained
parent handle with `NtCreateFile`. Directory handles share READ/WRITE but never
DELETE; leaf handles share READ only. A receipt load retains all root ancestors;
each leaf read retains every intermediate directory until EOF/check/close. It
checks handle-based volume/file ID, reparse tag, file-vs-directory/link count,
owner/DACL and streams, and reads from that same handle. Hashing is streamed by
Java in 32 KiB chunks. The JNI bridge uses generation/type-checked registry
slots under one SRW lock; stale/double/wrong-kind close cannot dereference a Java
integer as an address. Root close is rejected while a file borrows it.

SYSTEM/Admin/current process user may control installation bytes; TrustedInstaller
is additionally accepted on system ancestors only. Unknown ACE types fail closed.
Ancestor policy allows unrelated file/directory creation but rejects untrusted
DELETE_CHILD/DELETE/WRITE_DAC/WRITE_OWNER. Controlled subtree policy also rejects
untrusted write-data/append/EA/attributes. Directory link count need not be one.
This is a conservative policy, not a complete Windows AccessCheck emulator.

All ADS (including Zone.Identifier/MOTW) are rejected. No streams are removed.
Normal browser ZIP extraction compatibility needs review. UNC, SUBST, non-NTFS,
reparse points, short-name aliases, device names, trailing-dot/space, control
characters and UTF-16 surrogates are rejected. Paths are bounded to 30,000 UTF-16
units, components to 240, depth to 128. These are deliberate fixture restrictions,
not a claim to support all normal Windows installations.

The code performs synchronous bounded local reads; interruption is checked
between chunks, not an immediate kernel cancellation guarantee. Trusted owners,
admin/kernel/volume configuration changes and already-mapped writable sections
are not sandboxed. Evidence is an observation, not a perpetual seal: existing
Java admission still re-loads before staging/commit and a future Helper must
reverify. No read-only code claims rename/flush/power-loss durability.

## Sources and build

`sources.json` records exact unmodified OpenJDK 17.0.16+8 Windows `jni_md.h`,
SHA256 and GPLv2 + Classpath exception; license files accompany it. Host JDK17
`jni.h` is used and its hash/version is recorded in the build report. Linux's
`jni_md.h` is never used for Windows. MinGW/Windows SDK headers and import
libraries are supplied by the operator's toolchain, not copied into this repo;
compiler version and full source hashes are reported. Official Microsoft
references are pinned as review provenance, not runtime dependencies. The W4
production toolchain/SBOM/trusted immutable DLL installation remain outstanding.

Linux opt-in build (requires existing MinGW x64, objdump and JDK17; installs and
fetches nothing):

```sh
python3 scripts/dev/build_windows_update_files.py --jdk /usr/lib/jvm/java-17-openjdk
./gradlew :runtime:windowsReceiptValidationBundle --console=plain
python3 scripts/dev/build_windows_update_files.py --seal-bundle
```

The script builds twice and requires identical DLL bytes, x64 PE, zero timestamp,
exact JNI exports and only ADVAPI32/KERNEL32/ntdll imports. A fixed preferred image
base avoids linker path-derived output; ASLR/relocations remain enabled. No CRT,
private DLL or binary is committed. The independent fixture EXE exercises sharing,
controlled concurrent parent rename and handle cleanup. Outputs are only in
`build/native/windows-receipt-validation`. Same-toolchain reproducibility is not
cross-toolchain reproducibility. ABI source/PE checks do not execute Windows JNI.

Ordinary CI: `./gradlew checkWindowsUpdateNativeSources`; no MinGW required and no
native test is counted as a skipped pass. Existing update tests still run normally.

## Native Windows fixture

Use Windows x64 with a local JDK17. No Cubism, editor closing, admin session,
user installation, remote service, or global ACL/policy change is needed.
PowerShell creates a **new** private fixture only; existing directories are refused
and fixtures are retained for audit. Use a different fresh suffix for another run.

Local preparation requires both `-BuildNative -PrepareBundle`. It only supports
the default repository build directory; omit `-Bundle`. Unsupported combinations
are rejected before any compiler/Gradle/JDK invocation. With installed MinGW-w64
x64 GCC/objdump, Python3.11+, and the normal project
Gradle build prerequisites (including its script environment):

```powershell
.\scripts\dev\test-windows-receipt.ps1 -JdkHome 'C:\path\to\jdk-17' -BuildNative -Compiler gcc -Objdump objdump -Python python -PrepareBundle -FixtureRoot "$env:LOCALAPPDATA\Turboism-W2-Fixture-01"
```

Without a compiler/build environment, the main agent must explicitly deliver the
**whole** sealed cross-built directory above and the independently recorded
`bundleManifestSha256` returned by `--seal-bundle`. This digest authenticates the
exact bytes of `bundle-manifest.json`, which binds every file below. A DLL-only
digest is no longer accepted.
Nothing is uploaded or assumed downloadable by these scripts. After delivery:

```powershell
.\scripts\dev\test-windows-receipt.ps1 -JdkHome 'C:\path\to\jdk-17' -Bundle 'C:\path\to\windows-receipt-validation' -ExpectedBundleSha256 '<reviewed-bundle-manifest-64-hex-sha256>' -FixtureRoot "$env:LOCALAPPDATA\Turboism-W2-Fixture-02"
```

The manifest format is `turboism-w2-validation-bundle-v1`: schemaVersion 1 and
seven flat file records (`path`, positive integral `bytes`, SHA256). These are
`native-build.json`, DLL, probe EXE, fixture JAR, and exactly one of each
Jackson annotations/core/databind JAR. No directories, reparse entries, path
escapes, duplicate/case aliases, or additional files/classpath JARs are accepted.
The manifest is excluded from its own inventory; its independent digest is the
operator handoff authority. `native-build.json` is diagnostic, never authorization
for an EXE or JAR. Individual files are limited to 64 MiB and total payload to
128 MiB. The local build/seal flow generates the same format and reports the
new complete digest. It fails rather than silently retaining extra old outputs.

Before executing fixture Java or the probe, the runner verifies the independent
manifest digest, exact inventory, every byte length/hash, and native source hashes.
It retains read-only file handles through completion and uses an explicit JAR
classpath instead of a wildcard. Static ancestor/link checks and these locks are
for an operator-owned test package, not a W4 product supply-chain or same-user
path-race sandbox. A trusted bundle can execute code; its digest must come from
the main agent independently of the directory being delivered.

The runner runs the standalone Java fixture using
the actual Loader with ephemeral Ed25519 keys, then the separate C probe. Reports
are `build/native/windows-receipt-report/<unique-run-id>/{report,java}.json`: OS/JDK/compiler,
checkout SHA/dirty flag, actual artifact hashes, and individual PASS/FAIL/NOT_RUN.
Insufficient symlink/foreign-owner privilege or unavailable 8.3 names are explicit
NOT_RUN; exit 2 means incomplete/failed, never acceptance. A fresh random run directory prevents stale `java.json` from an earlier process
being accepted after a failed launch. A failed launch without its own report
remains failed. Protocol source paths always use `/` on Windows and POSIX;
toolchain/fixture absolute paths are diagnostics only. The script itself has
not been executed on Linux. A pre-existing DELETE handle conflicts even before
verification; releasing pins permits normal rename. No production path is enabled
by a successful fixture. W2 remains pending until native review/acceptance.
