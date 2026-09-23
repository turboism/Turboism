# Public event contracts (third-party)

Turboism schema v5 lets independently built plugins share a public event
contract without modifying the framework SDK. A contract is a published,
class-only JAR carrying event record types and their closed payload API
types; every participating plugin embeds the exact same published bytes so
the runtime binds one shared `Class` identity across provider and consumer
loaders.

This is a restricted event-contract mechanism, not a general service/module
system: contract artifacts contain API types only — no service providers, no
module descriptors.

## 1. Build the contract artifact

The artifact is a plain JAR containing only the contract's public API
classes — typically `final record`s implementing
`dev.turboism.sdk.event.EventBus.TurboismEvent` plus the record/enum types
they reference.

A minimal reproducible build, given `src/com/acme/events/Greeting.java` and
`src/com/acme/events/GreetingPayload.java` and the published SDK on
`sdk.jar`:

```bash
# Compile the contract API against the SDK only.
javac -cp sdk.jar -d build/contract-classes \
    src/com/acme/events/Greeting.java \
    src/com/acme/events/GreetingPayload.java

# Package a class-only JAR (no manifest Class-Path, no services, no
# module-info, no resource entries).
jar --create --file acme-events-1.0.0.jar -C build/contract-classes .

# Copy the exact published bytes into each plugin JAR.
mkdir -p build/plugin/META-INF/turboism/contracts
cp acme-events-1.0.0.jar build/plugin/META-INF/turboism/contracts/
jar --create --file my-plugin.jar -C build/plugin .

# The descriptor pins the artifact bytes.
sha256sum acme-events-1.0.0.jar
```

`javac -cp acme-events-1.0.0.jar` is also how provider and consumer sources
import `com.acme.events.Greeting` directly; the artifact stays on the
*compile* classpath only — plugin bytecode keeps symbolic references the
runtime resolves through the bound contract loader. The artifact classes
must never also be copied loose into the plugin JAR: a loose `.class`
colliding with a contract member is an admission error.

Restrictions enforced at bind time:

- Class names must live in the author's namespace. Forbidden prefixes:
  `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `com.live2d.`,
  `dev.turboism.`.
- No `module-info.class`, no `META-INF/services/**`, no
  `META-INF/versions/**` (multi-release shadowing is non-deterministic), no
  manifest `Class-Path`, and no non-class entries other than the manifest.
- At most 8 MiB.

Record methods and initializers are ordinary class code — the mechanism
controls identity and visibility, not code confinement. Keep payload types
immutable, detached value objects: they must never reference host or
framework objects.

## 2. Compute the ABI digests

Every declared `eventType` pins a structural ABI digest. Compute it against
the artifact exactly as the runtime does:

```bash
java -cp <runtime-classes> dev.turboism.core.event.PublicEventAbiCli \
    --artifact acme-events-1.2.0.jar com.acme.events.Greeting
```

The CLI prints the artifact SHA-256 (for `eventContracts[].sha256`) and the
per-type `abiSha256` (for `eventExports`/`eventImports`). Re-run it whenever
the contract classes change; the runtime rejects stale pins with a mismatch
diagnostic.

## 3. Publish and declare

Publish the artifact once (e.g. a Maven coordinate or release download).
Every participating plugin then embeds **the exact published bytes** at
`META-INF/turboism/contracts/` and declares them:

```json
{
  "schemaVersion": 5,
  "eventContracts": [
    {
      "id": "acme.events",
      "version": "1.2.0",
      "artifact": "META-INF/turboism/contracts/acme-events-1.2.0.jar",
      "sha256": "<artifact sha256, lowercase hex>"
    }
  ]
}
```

- `artifact` must be a single-level path under
  `META-INF/turboism/contracts/`; `sha256` pins the exact bytes.
- Byte-identity binding is strict: two plugins sharing contract id
  `acme.events` must embed identical bytes. Rebuilding from the same source
  with different bytes is a different contract generation and is rejected
  while the earlier binding is live — ship one published artifact and embed
  it verbatim.

### Provider

```json
"eventExports": [
  {
    "id": "greeting",
    "contractVersion": "1.0.0",
    "eventType": "com.acme.events.Greeting",
    "abiSha256": "<from the ABI CLI>"
  }
]
```

`contractVersion` is the route-level semantic version (independent of the
artifact's `version`).

### Consumer

```json
"eventImports": [
  {
    "provider": "acme.provider",
    "eventId": "greeting",
    "contractVersion": "[1.0.0,2.0.0)",
    "eventType": "com.acme.events.Greeting",
    "abiSha256": "<same digest>",
    "required": true
  }
],
"dependencies": [
  {
    "id": "acme.provider",
    "type": "required",
    "version": "[1.0.0,2.0.0)",
    "ordering": "after"
  }
]
```

**Ordering rule:** a `required` import of a contract-bound event must be
backed by a `required` dependency with `ordering: "after"` on the provider —
route admission needs the provider generation admitted first. Optional
imports need no ordering constraint; the consumer's embedded artifact gives
it the contract types regardless of load order, and admission re-validates
the consumer when the provider arrives.

Subscribing or publishing a contract-bound event type without a declared
import/export is rejected — a contract type can never masquerade as a
plugin-private event.

## 4. Payload closure

Every type reachable from a contract event's public ABI surface — record
components, public/protected members, generic type graphs including owner
types, wildcards and type-variable bounds — must resolve to exactly:

- JDK platform-module classes, or
- `dev.turboism.sdk.*` classes, or
- classes inside the same contract artifact.

Runtime, core, internal, shaded-library and host classes are unreachable
from contract types even where they share a classloader with the SDK. A
member typed as a private DTO anywhere in the graph — including one hidden
behind a generic owner such as `Outer<Payload>.Inner` — fails admission at
preflight, before any plugin entrypoint runs.

A contract member name colliding with a loose `.class` inside the same
plugin JAR is rejected (shadow ambiguity), as is a name already bound to a
different artifact in the session.

## 5. Lifecycle and generations

- One contract classloader exists per artifact hash; plugin loaders delegate
  declared contract member names to it before their own parent and JAR, so a
  same-named host class can never preempt the shared identity.
- The binding lives as long as any plugin generation holds a lease —
  including retained generations still draining callbacks after runtime
  shutdown begins.
- When the last lease releases, the binding closes; a later load of the same
  contract id may then bind different artifact bytes, producing a **fresh**
  `Class` identity. Event instances created against an older generation are
  rejected as stale or foreign when published through the new owner.
- If contract-loader disposal fails, the `IOException` propagates through
  `ContractLease.close()` → `PluginContractClassLoader.close()` and is sticky:
  a retry rethrows the recorded first failure, so a no-op retry can never
  report a false successful disposal. Both failed-load cleanup and normal
  unload keep the generation strongly referenced while scope or classloader
  disposal remains unproven, even after in-flight work quiesces. Each disposal
  stage is attempted at most once: a permanent failure stays recorded and
  cannot turn into success on a later cleanup pass. Normal unload records
  `PLUGIN_CLASSLOADER_CLOSE_FAILED` in the unload summary and reports an
  `UNLOAD`/`FAILED` lifecycle verdict; a timed-out unload reports its terminal
  verdict only when the retained cleanup settles.
  Independently of either lifecycle path, the session catalog quarantines the
  failed binding: it leaves every lookup map (a later acquire binds a fresh
  generation) while staying strongly referenced with its recorded failure,
  and is never re-closed or handed out again.

## 6. Diagnostics (selected)

- `plugin descriptor declares event contract <id> at path <p> but the plugin
  JAR does not contain that artifact`
- `plugin JAR contains undeclared public event contract artifact <path>`
- `public event contract <id> is already bound to a different artifact`
- `public event contract <id> artifact sha256 mismatch`
- `public event contract <id> contains forbidden class <name>`
- `Required public event import <route> must declare a required dependency
  with ordering 'after'`
- `public event contract type <name> requires a declared event export or
  import`
