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
`FrameworkUpdateReceipt.requirePrivate`. Directory ownership is checked and preserved; only the DACL is changed,
without requesting `WRITE_OWNER`. Existing user file bytes under `state` are preserved.

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
   - Keeps public `$dist` strictly to the 8 approved release assets, including their checksum sidecars. Incremental documents and objects stay outside `$dist`.
   - Compute candidate ZIP SHA-256 and size from the final archive after embedding these documents.
3. **NSIS Installer** (`packaging/windows-installer/installer.nsi`):
   - Conditional compile flag: `-DBOOTSTRAP_RECEIPT_DIR=<dir>`.
   - Uses `SetOutPath "$INSTDIR\bootstrap"` followed by `File "/oname=manifest.json"` and `.sig`,
     restoring `SetOutPath "$INSTDIR"`.
   - During `.onInstSuccess`, `configure_turboism.ps1 -InitializeSelection` automatically executes `bootstrap-receipt.ps1`.
   - On uninstall, cleans up `$INSTDIR\bootstrap` and `$INSTDIR\bootstrap-receipt.ps1`.
   - When omitted (e.g. legacy build), the conditional receipt payload is absent.

### Candidate signing and protected publication

Stable and Beta can opt into the signed-package path. With no configuration, the existing full-package release
path remains in use. Nightly continues to publish full packages: its scheduled workflow identity is not the
configured framework publisher identity.

1. `release.yml` generates the public client resources before the first Gradle build and produces the complete
   first package, allocated build receipt and `writeFrameworkPayloadInventory` output.
2. `framework-release-signing.yml` runs in `framework-release-signing`. It binds the receipt to the same
   source, version, channel, run and attempt; checks the activation policy and keyring inside the actual agent;
   then calls `prepare-framework-release.py sign`. Only that step receives the private signing key.
3. `assemble-release.sh --skip-gradle --bootstrap-receipt-dir ...` packages the signed documents into both
   ZIP and EXE. It reuses the candidate's developer artifacts. The manifest covers framework files; the two
   bootstrap documents are package metadata, so there is no self-referential hash.
4. `prepare-framework-release.py verify` checks the final ZIP payload against the signature and inventory,
   allows exactly the declared config/plugin exclusions, and compares the embedded documents byte for byte.
   Final candidate evidence is refreshed before the canonical candidate artifact is uploaded.
5. `release-github-only.yml` verifies that candidate as usual. With `framework_publish=admit` (or `initialize`
   for an empty channel), it uploads the signed resources and performs the existing public byte/Range preflight
   after plugin publication checks and before the final GitHub Release promotion. It never rebuilds the candidate.
   `framework_reactivate=true` is required to restore a deliberately withdrawn candidate; ordinary retries do not.

The official signing key, its public keyring and the main-only signing environment were provisioned on
2026-11-01; `FRAMEWORK_UPDATE_SIGNING_ENABLED` remains `false`. The updater baseline and publisher credentials
remain pending the first release. See [signing-provisioning-validation.md](../../validation/issue-50-updater/signing-provisioning-validation.md).

| Location | Name | Purpose |
| --- | --- | --- |
| Product repository variable | `FRAMEWORK_UPDATE_SIGNING_ENABLED` | Set to `true` to produce signed Stable/Beta candidates; unset is disabled. |
| Product repository variable | `FRAMEWORK_UPDATE_TRUSTED_KEYS` | Public client schema: `schemaVersion`, `keys` and `revoked`; generated resources stay under `build/`. |
| Signing variable | `FRAMEWORK_UPDATE_SIGNING_KEY_ID` | An active key ID already bundled in the candidate agent. |
| Signing variable | `FRAMEWORK_UPDATE_MINIMUM_UPDATER` | Required compatible updater baseline. B and C must retain a baseline supported by A when the protocol remains compatible; it must not automatically follow the target release version. |
| `framework-release-signing` environment secret | `FRAMEWORK_UPDATE_SIGNING_KEY_B64` | Base64 PEM Ed25519 private key, read only by the signing step and removed from its temporary file afterwards. |
| `production-release` environment secrets | `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` | Upload to the existing isolated R2 bucket. |
| Worker configuration | `FRAMEWORK_PUBLISHER_WORKFLOW`, `FRAMEWORK_PUBLISHER_ENVIRONMENT` | The publisher is `release-github-only.yml` in `production-release`. |
| Worker configuration | `FRAMEWORK_TRUSTED_KEYS` | Bare active key-ID-to-SPKI map matching the client public keys. |

Changing these production values or deploying the Worker is separate from preparing this implementation.
The existing candidate, signing and publisher workflows require product `main`. This implementation remains on
the requested test branch; a production release requires a separate source-release decision. Deploying the
Worker from the pinned test-branch commit does not require merging the product branch.

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
  - PowerShell (`packaging/windows-installer/test-bootstrap-receipt.ps1`): 33 assertions covering script plumbing with a mock helper, stale-document bypass, collision/error propagation. Real receipt/signature execution is covered separately by the Python integration tests.
  - Full existing test suites: `test-config-merge.py`, `test-installer-packaging.py`, `test_release_tooling.py`, `test_windows_update_native.py`.
- **Explicitly Unverified / Downstream Scope**:
  - **Real installation acceptance**: NSIS 3.09 compiled an isolated signed fixture into an EXE with the eight-asset roster on 2026-11-01; its ZIP passed signature/payload checks. This is compilation evidence, not execution of a real CI-produced installer. See [release-pipeline-validation.md](../../validation/issue-50-updater/release-pipeline-validation.md).
  - **Protected Signing CI and Activation**: The official key and public keyring are provisioned, but signing/activation remains disabled. No production candidate has yet bundled these resources or completed the protected signing workflow.
