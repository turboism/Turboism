# Dependency inventory baseline (`#70`, step 1)

This is the pre-extraction inventory: what the module graph and the `:runtime`
package graph actually look like today, recorded so that a change is reviewed
instead of discovered late. It is **not** a proposal to split `:runtime`, and it
does not replace the authoritative boundary gates.

## Authority

| Question | Owner |
| --- | --- |
| Is this dependency direction allowed? | `checkModuleBoundaries`, `checkResolvedBytecodeDependencyGraph`, SDK ABI gates |
| What do the directions look like today? | this inventory |
| Is this Java AST pattern acceptable? | `checkArchitectureRules` (ast-grep) |

The inventory reads package declarations and imports. Fully qualified references
without imports, reflected names, service wiring and classloader sharing are
**not** visible here; those stay with the bytecode and module-boundary gates.
The module half is authoritative because Gradle reports it, including edges that
convention plugins add and that no `build.gradle.kts` scan can see.

## Regenerating

```bash
./gradlew dependencyInventoryRaw                      # authoritative module graph
python3 scripts/dev/dependency_inventory.py --report \
    --project-graph build/architecture/dependency-inventory-project-graph.json
python3 scripts/dev/dependency_inventory.py --write \
    --project-graph build/architecture/dependency-inventory-project-graph.json   # reviewed change
```

`checkDependencyInventory` runs the `--check` mode inside `devCheck`: a new
module edge, a new `:runtime` package cycle, or a removed cycle fails the build
until `architecture/dependency-inventory.json` is regenerated in a reviewed
commit. `--report` prints the fan-in/fan-out analysis without writing.

## Recorded baseline

- 43 Gradle modules, including convention-supplied edges (plugins declare
  `compileOnly :sdk`, `annotationProcessor :event-processor`, and
  `testImplementation :sdk`, `:testing:test-support`).
- `:runtime`: 34 top-level `dev.turboism.*` packages, 123 top-level package
  edges, 1 top-level cycle, 6 leaf-package cycles.
- Production code has no dependency on `:plugins:*`; runtime test code does
  (`testImplementation` only), which the inventory keeps as a separate
  configuration instead of folding into the production graph.

## Cycles to break before any extraction

Recorded at leaf-package granularity; `--report` prints the current list when it
changes. Candidate direction is a note, not a decision.

| Size | Cycle | Candidate direction |
| --- | --- | --- |
| 8 | `adapter.cubism.{mesh,modeling,optimization}`, `mapping.verification`, `ui.{filter,mesh,palette,toolbar}` | the adapter should expose an admission contract; `ui` must not be reachable from `mapping.verification` |
| 5 | `core.{event,runtime,runtime.sidecar,runtime.work}`, `runtime.log` | logging is infrastructure: `core.runtime` should not own the direction of `runtime.log` |
| 4 | `adapter.cubism.{edit,editor,editor.history,editor.transaction}` | one of edit/transaction is the lower layer; split the history adapter out first |
| 3 | `adapter.cubism`, `adapter.cubism.service.read`, `hostread` | `hostread` is a port: the adapter should depend on it, never the reverse |
| 2 | `adapter`, `adapter.host` | `adapter` is a namespace package; the edge is a misplaced class |
| 2 | `preview`, `preview.report` | one-way report rendering; merge or invert |

At top-level granularity the six merge into a single 11-package cycle
(`adapter`, `config`, `core`, `hostread`, `mapping`, `recentfile`,
`recentpreview`, `runtime`, `screenshot`, `task`, `ui`), so a "break one edge at
the top level" reading is misleading — the leaf list above is the actionable one.

## Extraction order this inventory supports

1. Pure persistence codec/migration kernel (already covered by the golden
   snapshots) — it has no cycle today, only composition-layer reach-through.
2. Compatibility policy, after the `hostread`/`adapter` direction is inverted.
3. Nothing else until the six cycles above are gone; moving files across a cycle
   turns a local cycle into a cross-module cycle and makes the gate unusable.

Every extraction adds one reviewable module contract (owner, allowed compile and
runtime dependencies, public API, forbidden imports, threading/IO semantics,
tests) and must keep this inventory green in the same commit.
