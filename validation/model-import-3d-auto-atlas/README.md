# Append-Only Texture Atlas Real-Host Validation Probe

## Overview

The native probe passed on Cubism 5.3.04 on 2026-10-15. See [verification.md](verification.md) for evidence and scope.
This directory contains the standalone, procedural real-host probe for verifying the native append-only texture atlas capability (`ModelTextures.appendTextureAtlasPages`).

It uses programmatically synthesized procedural PNG textures and quad geometries without relying on proprietary 3D fixtures or external assets.

## Checks Performed by the Probe (Target: Cubism 5.3.04)
1. **Capability Pre-Check**: `model.textures().supportsAppendTextureAtlasPages()` returns `true` before transaction.
2. **Zero-Atlas Standalone Bootstrap**: Appending from an unatlased model creates page 1 (`512x512`) and binds `mesh1` to `TEXTURE_ATLAS_REGION`.
3. **Single-Root Authoring Transaction Append**:
   - `mesh2`, model image registration, and atlas page 2 (`1024x1024`) append occur in a single `AuthoringTransaction`.
   - Produces exactly 1 native history entry (`hAfter.position() == hBefore.position() + 1`).
4. **Preservation of Page 1 GUID & Mesh 1 UVs**:
   - Page 1 GUID is strictly preserved after appending page 2.
   - Mesh 1 source UVs and atlas binding remain 100% invariant.
5. **Single-Step Undo / Redo**:
   - `history.undo(1)` cleanly removes page 2 and `mesh2`, keeping page 1 and `mesh1` intact.
   - `history.redo(1)` cleanly restores page 2 and `mesh2`.
6. **Native In-Memory Bake Sampling (`exportMoc`)**:
   - Calls `model.exportMoc(settings)` without saving MOC3 to disk.
   - Samples primary-color texels away from accent grid lines on the differently sized pages and verifies primary procedural colors (proving native Cubism actually bakes textures rather than allocating empty pages).
7. **Authoring Transaction Rollback**:
   - If an error occurs inside a transaction containing an append:
     - Transaction outcome is `ROLLED_BACK`.
     - `history.position()` is unchanged (`hAfter.position() == hBefore.position()`).
     - Appended page 3 is discarded.
     - Uncommitted mesh 3, raw image 3, and model image 3 are discarded.
     - Pre-existing pages 1 & 2 and meshes 1 & 2 remain untouched.

## Explicit Verification Scope Boundaries
- **Verified on 5.3.04**: Native protocol, object/GUID invariance, single-root Undo/Redo, transaction rollback, and in-memory exportMoc ARGB texel sampling.
- **Excluded by Minimal Contract**: Host `.cmo3` file save/reopen persistence cycles across separate JVM launches, and full GPU viewport shader readback (which belongs to full fixture suites).

## Build and Run Instructions for Primary Agent / Isolated Prefix

### 1. Build the Probe JAR
Ensure SDK is compiled:
```bash
./gradlew :sdk:jar --max-workers=2
./validation/model-import-3d-auto-atlas/build.sh \
  --sdk build/worktree/auto-atlas-integration/sdk/libs/sdk-0.67.0-SNAPSHOT.jar \
  --runtime build/worktree/auto-atlas-integration/bootstrap/libs/turboism-agent.jar
```
This produces `build/validation/auto-atlas/auto-atlas-host-probe-latest.jar` (and a timestamped build directory).

### 2. Run on Clean Isolated 5.3.04 Prefix
Use `--jvm-option -Dturboism.autoatlas.exitAfterProbe=true` with the shared queue runner when a complete launcher lifecycle result is required. This exits only the matching queue-owned JVM after PASS; it does not exercise native close/save prompts.

Install the plugin JAR into the test prefix's `plugins/` directory:
```bash
cp build/validation/auto-atlas/auto-atlas-host-probe-latest.jar <prefix>/plugins/
```
Launch Cubism Editor 5.3.04 in a queue-owned isolated prefix with a disposable seed document. After the trigger, the probe creates a fresh model through `EditorCommand.NEW_MODEL` and leaves the seed unchanged.
When ready, create the trigger file:
```bash
touch <state-dir>/probe.trigger
```
(Or set `-Dturboism.autoatlas.immediate=true` in JVM options).
The probe detects the trigger file, creates its model, then logs:
`[AUTO_ATLAS_PROBE] Ready marker observed ... dispatching to EDT...`,
executes all phases on the EDT, and writes:
- `<state-dir>/result.properties` (`status=PASS` / `status=FAIL`)
- `<state-dir>/auto-atlas-probe-report.json`

### 3. Verify Report Artifacts
```bash
python3 validation/model-import-3d-auto-atlas/verify-auto-atlas.py <state-dir>/auto-atlas-probe-report.json
```
