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
| `-WorkPath` | String | `<root>/state/runtime/update/helper` | Directory for transaction lock and helper state. |
| `-Classpath` | String | `<root>/turboism-agent.jar` | Classpath containing `FrameworkUpdateHelper`. |
| `-HelperClass` | String | `dev.turboism.update.FrameworkUpdateHelper` | Class to invoke. |
| `-Strict` | Switch | Off | Throw on missing documents/Java; default is clean skip (exit 0). |
| `-PassThru` | Switch | Off | Return structured `[pscustomobject]` rather than exiting. |

### Document Discovery

When `-StagePath` is omitted, `bootstrap-receipt.ps1` probes:
1. `<Turboism-home>\state\runtime\update\bootstrap`
2. `<Turboism-home>\state\runtime\update\stage`
3. `<Turboism-home>\bootstrap`
4. `<Turboism-home>`

If no valid manifest and signature pair is found:
- Without `-Strict`: prints an informational message, skips initialization, and exits `0`.
  Legacy installations continue to function normally without claiming self-update is active.
- With `-Strict`: throws an error and exits non-zero.

### Java Discovery

When `-JavaPath` is omitted, candidates are evaluated in priority order:
1. Discovered Cubism bundled JRE from `-DiscoverySnapshot` (`app\jre\bin\java.exe`).
2. Configured Cubism installation from `<root>\state\runtime\cubism-selection.json`.
3. Managed GraalVM runtime in `<root>\graal\runtime\bin\java.exe` or `<root>\graalvm\bin\java.exe`.
4. Environment variables: `$env:TURBOISM_GRAALVM_HOME`, `$env:GRAALVM_HOME`, `$env:JAVA_HOME`.
5. System `PATH` (`java.exe` / `java`).

Each candidate is tested via `-version` to require Java 17+.

### Exit Codes

| Exit Code | Meaning |
|-----------|---------|
| `0` | Success: receipt initialized, or cleanly skipped on legacy package without documents. |
| `10` | Helper `EXIT_NOTHING` (receipt initialized without modifying payload). |
| `70` | Helper `EXIT_REJECTED` (verification failed, invalid signature, or no active keys). |
| `74` | Helper `EXIT_FAILED` (tampered/modified payload, or different existing receipt). |
| `75` | Helper `EXIT_BUSY` (Cubism or another process is running). |

## Integration with Configurator: `configure_turboism.ps1`

- `-InitializeReceipt`: explicitly executes `bootstrap-receipt.ps1` with `-ReceiptStage` and `-ReceiptJava`.
- `-InitializeSelection`: when run as part of installer finalization, checks for bootstrap documents
  and initializes receipt before first launch.

## Packaging and Release Pipeline

1. **Gradle Staging** (`packaging/java-installer/installer.gradle.kts`):
   - Declares `bootstrap-receipt.ps1` in `installerTemplateFiles` and stages into `stageInstallerPayload`.
   - `packaging/framework-update-layout.json` records `"bootstrap-receipt.ps1"` in `frameworkRootFiles`.
   - `writeFrameworkPayloadInventory` includes `bootstrap-receipt.ps1` in the inventory signed by CI.
2. **Standard ZIP** (`assemble-release.sh`):
   - Bundles `bootstrap-receipt.ps1` in `turboism-<ver>.zip`.
   - Does NOT embed manifest inside agent JAR or candidate ZIP (prevents circular hash dependency).
   - Candidate ZIP SHA-256 and size remain exact and match candidate/receipt.
3. **NSIS Installer** (`packaging/windows-installer/installer.nsi`):
   - Conditional compile flag: `-DBOOTSTRAP_RECEIPT_DIR=<dir>`.
   - When present, unpacks `manifest.json` and `manifest.json.sig` to `$INSTDIR\state\runtime\update\bootstrap\`.
   - During `.onInstSuccess`, `configure_turboism.ps1 -InitializeSelection` automatically executes `bootstrap-receipt.ps1`.
   - On uninstall, deletes `$INSTDIR\bootstrap-receipt.ps1`.
   - When omitted (e.g. legacy build), NSIS builds cleanly without receipt bundling.

## Interface for Launcher Sub-Agent (`launch-cubism-turboism.ps1`)

If launcher cooperation is desired before launching Cubism, the launcher script can invoke
`bootstrap-receipt.ps1` with the discovered Cubism Java:

```powershell
# Optional bootstrap receipt check before starting Cubism:
$bootstrapScript = Join-Path $turboismHome 'bootstrap-receipt.ps1'
if (Test-Path -LiteralPath $bootstrapScript -PathType Leaf) {
    $updateJava = if ($javaOverride) { $javaOverride } else { $cubism.Java }
    & $bootstrapScript -Home $turboismHome -JavaPath $updateJava
}
```

If no documents are staged, or if the receipt is already initialized (idempotent), the
call completes with exit code 0 and does not delay normal launch.

## Production CI Note

This implementation wires the verifiable mechanism and fallback behavior. Real protected
signing CI and production key provisioning remain downstream deployment gates. No synthetic
or test keys are packaged into release artifacts.
