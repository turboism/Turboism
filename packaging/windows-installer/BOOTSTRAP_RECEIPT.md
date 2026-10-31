# Turboism Bootstrap Installation Receipt Contract

This document describes the integration of `FrameworkUpdateHelper --initialize-receipt`
into the first conventional full installation and standard ZIP lifecycle (Issue #50).

## Overview

Self-update requires a signed framework installation receipt index (`installed-receipt.json`)
and immutable signed documents bound to the installation root. In a fresh installation or
standard ZIP extraction, no receipt index pre-exists because the destination root is unknown
until install/extract time.

The protected release publisher produces the complete signed framework manifest (`manifest.json`)
and detached signature envelope (`manifest.json.sig`) covering all static framework files.
The client-side installer and bootstrap runner only **verify** this signature against the
bundled trusted public keys; they never generate authority or private keys.

## Standalone Script: `bootstrap-receipt.ps1`

`packaging/windows-installer/bootstrap-receipt.ps1` is the canonical entrypoint installed
into the Turboism root directory (`<Turboism-home>/bootstrap-receipt.ps1`).

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `-HomePath` (alias `-Home`) | String | `$PSScriptRoot` / `$env:TURBOISM_HOME` | Root directory of the Turboism installation. |
| `-StagePath` | String | Probed defaults | Directory containing `manifest.json` and `manifest.json.sig`. |
| `-JavaPath` | String | Auto-detected | Path to runnable JDK 17+ or Cubism JRE (`app\jre\bin\java.exe`). |
| `-DiscoverySnapshot` | String | Empty | Path to Cubism discovery report JSON (from NSIS). |
| `-DiscoverySnapshotSha256` | String | Empty | Expected SHA-256 of the discovery report. |
| `-WorkPath` | String | `<root>/state/runtime/update/helper` | Directory for transaction lock and helper state. |
| `-Classpath` | String | `<root>/turboism-agent.jar` | Classpath containing `FrameworkUpdateHelper`. |
| `-HelperClass` | String | `dev.turboism.update.FrameworkUpdateHelper` | Class to invoke. |
| `-HelperPrefixArgs` | String[] | `@()` | Test/wrapper arguments prepended before `--initialize-receipt`. |
| `-Strict` | Switch | Off | Throw on missing documents/Java or receipt collision; default is clean skip (exit 0). |
| `-PassThru` | Switch | Off | Return structured `[pscustomobject]` rather than exiting. |

### Document Discovery

When `-StagePath` is omitted, `bootstrap-receipt.ps1` probes:
1. `<Turboism-home>\bootstrap`
2. `<Turboism-home>\state\runtime\update\bootstrap`
3. `<Turboism-home>\state\runtime\update\stage`
4. `<Turboism-home>`

If no valid manifest and signature pair is found:
- Without `-Strict`: prints an informational message, skips initialization, and exits `0`.
  Legacy installations continue to function normally without claiming self-update is active.
- With `-Strict`: throws an error and exits non-zero.

### Existing Receipt & Stale Document Handling

In default first-launch mode (without `-Strict`), if a valid receipt index already exists on disk
(`<root>\state\runtime\update\installed-receipt.json`):
- `bootstrap-receipt.ps1` detects the existing receipt, skips re-bootstrap, and exits `0`
  (status `ALREADY_INITIALIZED`). It does NOT falsely claim verification succeeded.
- This prevents upgraded installations (e.g. 1.1.0) from failing when historical 1.0.0 bootstrap
  documents remain present on disk.
- When `-Strict` is passed, different receipt collisions are strictly rejected (exit `74`).

### Java Discovery & State Integration

When `-JavaPath` is omitted, candidates are evaluated in priority order:
1. Explicit `-JavaPath` from trusted caller (e.g. launcher or configurator).
2. Selected candidate from real `cubism-installations.json` via `Read-CubismInstallationState`.
3. Verified installer discovery snapshot via `Read-CubismInstallerSnapshot` (validating `ExpectedSha256`).
4. Managed GraalVM runtime in `<root>\graal\runtime\bin\java.exe` or `<root>\graalvm\bin\java.exe`.
5. Environment variables: `$env:TURBOISM_GRAALVM_HOME`, `$env:GRAALVM_HOME`, `$env:JAVA_HOME`.
6. System `PATH` (`java.exe` / `java`).

Each candidate is tested via `-version` to require Java 17+.

### Private Receipt State Permissions

Before running `FrameworkUpdateHelper`, `bootstrap-receipt.ps1` ensures that `<root>\state`,
`<root>\state\runtime`, and `<root>\state\runtime\update` have owner-only access
(plus SYSTEM and Administrators on Windows), disabling inheritance, as required by
`FrameworkUpdateReceipt.requirePrivate`. Existing user files under `state` are preserved.

### Exit Codes

| Exit Code | Meaning |
|-----------|---------|
| `0` | Success: receipt initialized, cleanly skipped on legacy package, or receipt already present. |
| `10` | Helper `EXIT_NOTHING` (receipt initialized without modifying payload). |
| `70` | Helper `EXIT_REJECTED` (verification failed, invalid signature, or no active keys). |
| `74` | Helper `EXIT_FAILED` (tampered/modified payload, or different existing receipt). |
| `75` | Helper `EXIT_BUSY` (Cubism or another process is running). |

## Integration with Configurator: `configure_turboism.ps1`

- `-InitializeReceipt`: explicitly executes `bootstrap-receipt.ps1` with `-ReceiptStage`, `-ReceiptJava`,
  and discovery snapshot digest verification.
- `-InitializeSelection`: when run as part of installer finalization, checks for bootstrap documents,
  initializes receipt before first launch, and propagates real exit codes (non-zero exits abort).

## Packaging and Release Pipeline

1. **Gradle Staging & Cross-Layer Allowlist**:
   - `packaging/java-installer/installer.gradle.kts` declares `bootstrap-receipt.ps1` in `installerTemplateFiles` and stages into `stageInstallerPayload`.
   - `packaging/framework-update-layout.json` records `"bootstrap-receipt.ps1"` in `frameworkRootFiles`.
   - `UpdateManifestEntry.FRAMEWORK_ROOT_FILES` in runtime Java includes `"bootstrap-receipt.ps1"`.
   - `writeFrameworkPayloadInventory` includes `bootstrap-receipt.ps1` in the inventory signed by CI.
2. **Standard ZIP** (`assemble-release.sh`):
   - Validates `--bootstrap-receipt-dir` in the shared phase before packaging.
   - When provided, bundles `bootstrap/manifest.json` and `bootstrap/manifest.json.sig` into `turboism-<ver>.zip`.
   - Keeps public `$dist` strictly to the 8 approved release assets; no sidecars are published to `$dist`.
   - Candidate ZIP SHA-256 and size remain exact and match candidate/receipt.
3. **NSIS Installer** (`packaging/windows-installer/installer.nsi`):
   - Conditional compile flag: `-DBOOTSTRAP_RECEIPT_DIR=<dir>`.
   - Uses `SetOutPath "$INSTDIR\bootstrap"` followed by `File "/oname=manifest.json"` and `.sig`,
     restoring `SetOutPath "$INSTDIR"`.
   - During `.onInstSuccess`, `configure_turboism.ps1 -InitializeSelection` automatically executes `bootstrap-receipt.ps1`.
   - On uninstall, cleans up `$INSTDIR\bootstrap` and `$INSTDIR\bootstrap-receipt.ps1`.
   - When omitted (e.g. legacy build), NSIS builds cleanly without receipt bundling.

## Managed Launcher (`launch-cubism-turboism.ps1`)

The managed launcher automatically attempts initialization on the first standard-ZIP launch,
using the selected Java and structured `-PassThru` result. It runs only when the package's
`bootstrap/manifest.json` and `.sig` are present and neither a receipt nor a recovery journal
exists. `-ProbeOnly` never initializes state. Existing receipts are not re-bootstrapped.

Initialization failure emits a warning and leaves the full framework unchanged; normal Cubism
startup may continue, while the runtime receipt loader keeps self-update unavailable. Journal
recovery remains authoritative and can still block startup if the installation is unsafe.
The isolated launcher-block test covers this wiring and its skip guards; it is not a real
Cubism launch acceptance test.

## Verification Status and Limits

- **Verified via automated tests**:
  - Python `unittest` (`scripts/test/test_bootstrap_receipt.py`): cross-layer consistency (JSON layout, Java allowlist, Gradle staging, assemble script), real `FrameworkUpdateReceipt` integration with Ed25519 signing and Windows ACL validation, upgraded receipt collision protection, ZIP embedding, and tamper rejection.
  - PowerShell (`packaging/windows-installer/test-bootstrap-receipt.ps1`): 26 assertions covering syntax, mock and real execution, stale document bypass, strict collision rejection, and configurator failure propagation.
  - Full existing test suites: `test-config-merge.py`, `test-installer-packaging.py`, `test_release_tooling.py`, `test_windows_update_native.py`.
- **Explicitly Unverified / Downstream Scope**:
  - **Real NSIS compiler (`makensis`) execution**: `makensis` binary was not run in this environment; NSIS script validity is checked by AST and config-merge tests, real binary compilation belongs to CI.
  - **Protected Signing CI and Production Keys**: Client and installer remain strictly verification-only; production keys are not yet provisioned in `framework-update-trusted-keys.json`.
