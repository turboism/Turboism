# Turboism Product Roadmap

Work is ordered by product and framework capability. Track labels describe parallel areas of the system—not sequential milestones or phase gates—and must not evolve back into numbered stage governance.

## Current objective

Maintain a usable, version-routed Cubism and Editor SDK that plugins consume naturally, and keep first-party modeling workflows aligned with it. The priority order is **framework > first-party plugins > third-party plugins**: third-party consumption shapes the SDK ergonomics, but it never outranks framework stability or official workflows.

Two architectural axes govern all Cubism-facing work:

```text
Core / Editor unification
  one SDK object graph
  Editor authoring state as the write truth
  Core evaluated state as the rendering/evaluation truth
  verified identity and generation joins

Semantic event lifecycle
  one canonical operation
  before -> native operation -> changed-only on -> after
  override-based hooks discovered from plugin entrypoints
  no duplicate Core, Editor, and plugin event sources
```

## Track status

### Track A — Unified Cubism object API

**Current state:** Implemented for the core model object graph (Canvas, Parameters, Parts, Drawables, Deformers, Glues, and offscreen/render surfaces) across reviewed **5.0.00 – 5.3.04** adapters (20 exact versions), with `@CubismEditor` version gating, immutable snapshots, generation-bound stale rejection, and `CoreEvaluatedJoin` for Core evaluated reads.

**Remaining focus:** Expand Core/Editor object coverage only where driven by real plugin consumers, and keep version-coverage parity across Tier-1 targets (`5.2.03` and `5.3.02+`).

### Track B — Editor-owned authoring writes

**Current state:** Parameter, Part, Drawable, Deformer, and Morph Target writes route through Editor-owned transactions (`AuthoringTransaction*`) with host-thread dispatch, native `GroupUndo`/`SimpleUndo` integration, dirty-state tracking, and stale-target rejection.

**Remaining focus:** Extend authoring write families strictly from verified plugin needs and ensure automated Undo/Redo, save, and reopen evidence covers Tier-1 Cubism hosts.

### Track C — Invocation and event standard

**Current state:** The `before -> invoke -> on state change -> after` lifecycle, `ParameterHooks`, and the canonical `dev.turboism.sdk.cubism.event` families are implemented, with bytecode-to-SDK correlation preventing duplicate events across plugin, UI, and native callers.

**Remaining focus:** Add new wrapped invocation families only where real consumers require argument interception or native-edit observation.

### Track D — Editor semantic API

**Current state:** Project/document context, selection and hierarchy queries, mediated user file access, and host UI contributions (menus, toolbars, context menus, shared settings, panels, dialogs, keybindings, and canvas hints) are exposed through `PluginContext.services()`.

**Remaining focus:** Keep host-UI isolation strict (no leaked Swing/host widgets outside documented exceptions) and improve async host-read ergonomics for heavy editor queries.

### Track E — Official workflow restoration

**Current state:** 35 first-party plugins ship in standard release packages (plus `parameter-lock` on the Ed25519-signed Plugin Marketplace), and all legacy `b1/` migration packages have graduated into stable plugin-owned packages.

**Active workflow focus:**

- **3D character workflow (`plugins/model-import-3d`)**: Standalone 3D viewing and editable native glTF/GLB/OBJ import (including Blend Shape Morph Targets, structural Parts, nested Warp parents, Undo/Redo, and save/reopen) pass on Cubism 5.3.04. Remaining work focuses on angle-conditioned depth/pose corrections for non-neutral yaw/pitch views and optional anatomical/rigging presets.
- **Cross-version parity**: Keep bundled plugins verified on Tier-1 Cubism hosts (`5.2.03` and `5.3.02+`) and expand automated host-probe coverage where gaps remain in [the plugin matrix](../compatibility/cubism/plugins.md).

### Track F — Hook and render ingress

**Current state:** Install-time hooks run through the `VerifiedInstall` protocol (`META-INF/turboism/hooks` with both `HookContributor` classes and `@`-prefixed `DeclarativeHooks`), and opt-in native rendering/update optimizations carry persisted kill switches.

**Remaining focus:** Keep hook surface minimal—every new hook requires consumer necessity evidence, exact-artifact proofs, bounded execution, and clean uninstall/disable restoration.

### Track G — Third-party and release readiness

**Current state:**

- Pre-freeze SDK cleanups are complete: `dev.turboism.sdk.event.cubism` is retired and rejected by boundary checks, `PluginContext` optional services use uniform `unavailable()` / `isAvailable()` contracts, and `sdk/api-contracts/current.txt` tracks the pre-1.0 public surface.
- Standalone plugin development works via [`turboism/turboism-plugin-template`](https://github.com/turboism/turboism-plugin-template) with `setup.sh` / `setup.ps1` and released SDK / event-processor JARs.
- Windows EXE/ZIP packaging, localized installers, and the Ed25519-signed plugin marketplace are operational.

**Remaining focus:**

- **1.0 SDK freeze**: Stabilize remaining `@Incubating` surfaces and lock the initial 1.0 compatibility baseline (after which SDK changes are strictly additive within the 1.x line).
- **Release & supply-chain hardening**: Add code signing for Windows installers when available, extend recovery-point and golden-snapshot coverage to legacy `config.json` and install/update journals, and gather community macOS validation feedback to move macOS packaging beyond preview status.

## Near-term priorities

1. Stabilize `@Incubating` SDK APIs and lock the 1.0 SDK compatibility baseline.
2. Expand automated exact-host validation coverage across Tier-1 Cubism versions (`5.2.03` and `5.3.02+`) and under-probed bundled plugins.
3. Improve `model-import-3d` angle-conditioned Warp/depth corrections and optional rigging mappings.
4. Close remaining recovery-point / golden-snapshot coverage for legacy `config.json`, install journals, and signed update receipts.
5. Pursue code signing for Windows release installers and macOS host testing with community contributors.

## Not used as progress measures

The following do not determine work order or completion:

- numbered migration or implementation phases (M1–M16, etc.);
- migration-board row counts or capability catalog size;
- one permission per method;
- fake-ready labels or self-referential closure reports;
- mandatory PRD/schema/report creation for ordinary additive SDK changes.

Progress is measured by usable SDK surfaces, real providers, safety invariants, automated exact-host evidence, and working user workflows.
