# Append-Only Texture Atlas Real-Host Validation Probe

## Overview

Real-host execution has not yet completed. The checks below describe the probe, not verified host support.
This directory contains the standalone, procedural real-host probe for verifying the native append-only texture atlas capability (`ModelTextures.appendTextureAtlasPages`).

It uses programmatically synthesized procedural PNG textures and quad geometries without relying on proprietary 3D fixtures or external assets.

## Checks Performed by the Probe (Target: Cubism 5.3.04)
1. **Capability Pre-Check**: `model.textures().supportsAppendTextureAtlasPages()` returns `true` before transaction.
2. **Zero-Atlas Standalone Bootstrap**: Appending from an unatlased model creates page 1 (`1024x1024`) and binds `mesh1` to `TEXTURE_ATLAS_REGION`.
3. **Single-Root Authoring Transaction Append**:
   - `mesh2`, model image registration, and atlas page 2 append occur in a single `AuthoringTransaction`.
   - Produces exactly 1 native history entry (`hAfter.position() == hBefore.position() + 1`).
4. **Preservation of Page 1 GUID & Mesh 1 UVs**:
   - Page 1 GUID is strictly preserved after appending page 2.
   - Mesh 1 source UVs and atlas binding remain 100% invariant.
5. **Single-Step Undo / Redo**:
   - `history.undo(1)` cleanly removes page 2 and `mesh2`, keeping page 1 and `mesh1` intact.
   - `history.redo(1)` cleanly restores page 2 and `mesh2`.
6. **Native In-Memory Bake Sampling (`exportMoc`)**:
   - Calls `model.exportMoc(settings)` without saving MOC3 to disk.
   - Samples center texels of both 1024x1024 pages and verifies primary procedural colors (proving native Cubism actually bakes textures rather than allocating empty pages).
7. **Authoring Transaction Rollback**:
   - If an error occurs inside a transaction containing an append:
     - Transaction outcome is `ROLLED_BACK`.
     - `history.position()` is unchanged (`hAfter.position() == hBefore.position()`).
     - Appended page 3 is discarded.
     - Uncommitted mesh 3, raw image 3, and model image 3 are discarded.
     - Pre-existing pages 1 & 2 and meshes 1 & 2 remain untouched.

## Explicit Verification Scope Boundaries
- **Probe coverage (host execution pending)**: Native protocol, object/GUID invariance, single-root Undo/Redo, transaction rollback, and in-memory exportMoc ARGB texel sampling.
- **Excluded by Minimal Contract**: Host `.cmo3` file save/reopen persistence cycles across separate JVM launches, and full GPU viewport shader readback (which belongs to full fixture suites).

## Build and Run Instructions for Primary Agent / Isolated Prefix

### 1. Build the Probe JAR
Ensure SDK is compiled:
```bash
./gradlew :sdk:jar --max-workers=2
./validation/model-import-3d-auto-atlas/build.sh
```
This produces `build/validation/auto-atlas/auto-atlas-host-probe-latest.jar` (and a timestamped build directory).

### 2. Run on Clean Isolated 5.3.04 Prefix
Install the plugin JAR into the test prefix's `plugins/` directory:
```bash
cp build/validation/auto-atlas/auto-atlas-host-probe-latest.jar <prefix>/plugins/
```
Launch Cubism Editor 5.3.04 with an active blank document (1024x1024).
When ready, create the trigger file:
```bash
touch <state-dir>/probe.trigger
```
(Or set `-Dturboism.autoatlas.immediate=true` in JVM options).
The probe detects the trigger file and active model, logs:
`[AUTO_ATLAS_PROBE] Ready marker observed ... dispatching to EDT...`,
executes all phases on the EDT, and writes:
- `<state-dir>/result.properties` (`status=PASS` / `status=FAIL`)
- `<state-dir>/auto-atlas-probe-report.json`

### 3. Verify Report Artifacts
```bash
python3 validation/model-import-3d-auto-atlas/verify-auto-atlas.py <state-dir>/auto-atlas-probe-report.json
```
