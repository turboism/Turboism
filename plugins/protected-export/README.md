---
turboismReadmeSchema: 1
pluginId: dev.turboism.plugin.protected-export
version: 0.1.0
kind: feature
status: development
delivery: development-only
category: workflow
tags: export, protected, candidate
turboismApi: "[0.1.0,0.2.0)"
requiresCubism: true
interface: none
---

# Protected Export

## What it does

- Runs a protected runtime-model export on a task-owned copy of the active model: the authoring document is never written.
- Flattens Warp and Rotation deformer state into the staged copy, then obfuscates ArtMesh identities so the published artifact does not leak authoring identifiers.
- Captures the source model's behavior as deterministic parameter samples and verifies the complete staged output against that capture before anything is published.
- Publishes the validated artifact atomically, reports veto and failure diagnostics with a stable reason, and restores the working state when any stage is cancelled or rejected.

## Requirements and compatibility

- **Turboism API:** `[0.1.0,0.2.0)`.
- **Cubism:** Requires an exact, reviewed Cubism Editor installation. Admissions are recorded per version; unsupported or unreviewed hosts fail closed during preflight.
- **Models:** Only eligible models are admitted. Models with unsupported families, non-stored keyforms, degenerate geometry, or missing pairs are vetoed with a blocker reason instead of partially exported.
- **Interface mode:** `none` — the plugin drives the export through framework actions and reports through notifications and logs.

## Install and enable

This is a **development-only** module, not a published store listing or release-delivery plugin. Build and load it only through this repository's development runtime, then enable it in **Plugin Management** when validating the protected-export pipeline. Disable it from the same window when protected exports are not needed.

## How to use

1. Enable the plugin in a development runtime connected to a reviewed exact Cubism host.
2. Open a model, trigger the protected export action, and wait for the staged pipeline to finish: copy binding, source behavior capture, flatten, obfuscation, and validation.
3. Confirm the published artifact, or read the reported blocker/veto reason when the pipeline fails closed.
4. Inspect the plugin log for the session-phase trace when diagnosing a rejected export.

## Capabilities

No capabilities are declared in the plugin manifest.

## Permissions

| Permission | Scope | Why it is requested |
|---|---|---|
| `turboism.cubism.model.read` | `application` | Reads the active Cubism model for protected-export preflight and the runtime orchestration's model census; the authoring document is never written. |

## Privacy and data

The plugin reads the active model in memory and writes export artifacts only to the task-owned output location you confirm. Identity obfuscation applies to the staged copy before publication. No model content, identity mapping, or diagnostic trace leaves the machine; logs stay in the local Turboism log directory.

## Status and limitations

- **Status:** development preview. The pipeline is admitted only on reviewed exact Cubism versions and eligible models, and it fails closed on every unadmitted input.
- Flattening covers Warp and Rotation deformers; other families pass through untouched or veto the export, depending on the admission record.
- Behavior verification is bounded by the deterministic sample matrix; unobserved parameter combinations are not covered.

## Troubleshooting

- **Preflight rejected:** the host or model is not admitted; check the reported blocker reason and the exact-version admission records.
- **Behavior mismatch:** the staged output diverged from the captured source behavior; the artifact is discarded, not published.
- **Restore failures and vetoes** are logged with their phase; review the plugin log lines around the failure for the failing stage.

## Support and license

| Item | Value |
|---|---|
| License | Project License |
| Website | https://turboism.dev |
| Issues | https://github.com/turboism/Turboism/issues |

This is an official first-party development module. See the project [EULA](https://github.com/turboism/Turboism/blob/main/EULA.md) and repository license for distribution terms.
