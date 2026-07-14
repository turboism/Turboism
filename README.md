# Turboism

Turboism is a runtime enhancement tool and experimental plugin framework for Live2D Cubism Editor. The platform uses Java 17 and coarse Gradle modules (`:bootstrap`, `:runtime`, `:sdk`, `:plugins:*`, `:testframework`, `:tests`) with SDK-only plugin boundaries. `:bootstrap` is a thin Java Agent artifact/entrypoint module governed by ADR 0024, not a separate platform subsystem.

## Current status

- M1-M12 are completed at their documented evidence levels.
- M13 candidate behaviors are fake-ready within their bounded scope.
- M14 is **IN_PROGRESS**; real-host observation and manual validation remain pending.
- **Turboism 0.1 Developer Preview is now the highest-priority track.** Its goal is one real vertical slice: isolated Java-agent launch, local plugin loading, verified Cubism 5.3.02 project/workspace connection, a visible Project Inspector, and a relocatable preview bundle.
- Local offline Distribution Phase 1 is **FROZEN / RETAINED FOR LATER** after its current protocol and package-inspection baseline. It is not complete, but additional transaction-contract work is deferred until the 0.1 vertical slice works.
- M16 production hardening is **NOT_STARTED**.

See:

- [Turboism 0.1 Developer Preview plan](docs/release/turboism-0.1-developer-preview-plan.md)
- [Migration roadmap](docs/migration/migration-roadmap-prd.md)
- [Recommended next migration slices](docs/migration/next-migration-slices.md)
- [M16 production hardening PRD](docs/migration/plans/m16-production-hardening-prd.md)
- [ADR 0022: local offline distribution lifecycle](docs/adr/0022-local-offline-distribution-lifecycle.md)
- [Local offline distribution roadmap](docs/release/local-offline-distribution-roadmap.md)
- [Packaging policy](docs/release/PACKAGING_POLICY.md)
- [Launcher policy](docs/release/LAUNCHER_POLICY.md)

Turboism does not distribute Cubism, replace its licensing, or authorize copying private Cubism source, resources, binaries, decompiled method bodies, or bypass logic.
