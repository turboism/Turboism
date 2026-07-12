# Turboism

Turboism is a runtime enhancement tool and experimental plugin framework for Live2D Cubism Editor. The platform uses Java 17 and coarse Gradle modules (`:runtime`, `:sdk`, `:plugins:*`, `:testframework`, `:tests`) with SDK-only plugin boundaries.

## Current status

- M1-M12 are completed at their documented evidence levels.
- M13 candidate behaviors are fake-ready within their bounded scope.
- M14 is **IN_PROGRESS**; real-host observation and manual validation remain pending.
- M16 production hardening is **NOT_STARTED**.
- Local offline Distribution Phase 1 is the current highest-priority usability track, independent of M1-M16, and may begin immediately. It remains **PLANNED / NOT_STARTED**; its accepted lifecycle design is not an implementation claim, and sandbox exercises grant neither phase completion nor product readiness.

See:

- [Migration roadmap](docs/migration/migration-roadmap-prd.md)
- [Recommended next migration slices](docs/migration/next-migration-slices.md)
- [M16 production hardening PRD](docs/migration/plans/m16-production-hardening-prd.md)
- [ADR 0022: local offline distribution lifecycle](docs/adr/0022-local-offline-distribution-lifecycle.md)
- [Local offline distribution roadmap](docs/release/local-offline-distribution-roadmap.md)
- [Packaging policy](docs/release/PACKAGING_POLICY.md)
- [Launcher policy](docs/release/LAUNCHER_POLICY.md)

Turboism does not distribute Cubism, replace its licensing, or authorize copying private Cubism source, resources, binaries, decompiled method bodies, or bypass logic.
