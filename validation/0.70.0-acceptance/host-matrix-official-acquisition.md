# 0.70.0 official matrix input acquisition

Status: all eight exact endpoints have verified official inputs. Native behavior remains pending.

The previous installed inventory lacked four endpoints. It also included managed or incomplete launchers.
This acquisition removes that input blocker. It does not replace host acceptance.

## Acquisition and trust

The official [download script](https://cubism.live2d.com/editor/js/download.js) lists all eight versions.
The script defines the installer filename pattern used for these downloads.
Downloads used HTTPS with certificate verification. No installer process ran.
Windows Authenticode returned `Valid` for every installer.
Every signer identifies the publisher as `Live2D Inc.`.
Timestamp signatures preserve the validation of older installers.

7-Zip 23.01 extracted each NSIS payload into a separate task-owned directory on `D:`.
Automatic renaming preserves duplicate localized BAT files. Extraction did not overwrite an existing host root.
The original installations and models remain unchanged.
Each Editor JAR matches the exact size and SHA-256 in `reviewed-releases.json`.
Each Core JAR matches its exact observed artifact record.
Every extracted runtime reports Java `17.0.3.1` in its release metadata.

## Endpoint status

| Endpoint | Installer signature | Editor identity | Core identity | Native behavior |
| --- | --- | --- | --- | --- |
| 5.0.00 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.0.06 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.1.00 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.1.03 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.2.00 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.2.03 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.3.00 | Valid, Live2D Inc. | Exact match | Exact match | Pending |
| 5.3.04 | Valid, Live2D Inc. | Exact match | Exact match | Pending |

[The retained identity manifest](host-matrix-official-inputs.json) records URLs, installer hashes, certificate thumbprints, and exact JAR identities.

Private evidence resides under `D:/TurboismValidation/070-worthy-emu/matrix-inputs/`:

- `official-installers/` retains the original signed installers.
- `official-hosts/<version>/` retains separate extracted host roots.
- `<version>-official-input.json` records trusted file hashes and signature facts.
- `official-inputs-all-eight.json` combines the eight input reports.
- `<version>-extraction.log` records each extraction result.

## Acceptance boundary

Input verification does not prove startup, provider admission, feature behavior, locale behavior, performance, or cleanup.
The [representative matrix](representative-host-matrix.md) still defines the required host evidence.
Use separate profiles, disposable models, official launchers, and task-owned Windows Job containment for those runs.
Do not mark an endpoint passed from these static input records.
