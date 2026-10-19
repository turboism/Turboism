# SDK v21 plugin lifecycle and JSON sanitization review

This revision freezes the SDK after introducing the optional plugin lifecycle
base class and lenient JSON preparation helper:
- `AbstractTurboismPlugin` implements `TurboismPlugin`, stores the permission-scoped
  context, validates lifecycle state, and owns a disposal scope for registrations.
  Disable closes the scope in reverse registration order and prepares a fresh one;
  shutdown closes remaining resources and invalidates the context. State is guarded
  while user cleanup runs outside the lock, as documented in its Javadoc.
- `Json.sanitize(Object)` replaces unpaired string surrogates with U+FFFD and
  non-finite floating-point values with null, including map keys and nested values.
  It never mutates the input and returns the original instance when no rewrite is needed.

The intent follows commits `616e8fed0`, `8ddca8cb8`, `13c66e43f` (optional base
class, removal of the redundant alias, simplified init/enable signatures),
`fcf69483a` (lenient JSON preparation), and their final Javadoc contracts.
v20 becomes a historical exact audit while v21 is the live gate.

## Immutable reference

- Source: `99b2dc03bacc4bb84c55638326419ac034eac681`; `sdk/` main sources are
  frozen at this commit. This is the HEAD before adding the v21 baseline; the last
  SDK main-source commit is `b5c0282ee`, and `git diff b5c0282ee 99b2dc03b -- sdk/src/main`
  is empty. As with v20's source anchor preceding its baseline-registration commit
  `e3b54ffd0`, this archive does not depend on the baseline being introduced here.
- Reconstructed from an isolated Git archive with `scripts/test/reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 and Java 17, without live workspace sources.
- Canonical dump: 8364 lines, SHA-256 `c123f96240000f23242c1e6af8c4d51960976dfc52c3d727e38cd0f644e27840`.
- The baseline was captured with `scripts/test/sdk_api_baseline_cli.py capture`
  to a review path outside the baselines directory, then added as a new v21
  baseline. All v2–v20 baseline files and their anchors remain unchanged.

## v20 → v21 delta

The frozen v20 baseline has 8355 canonical lines. v21 has 8364 canonical lines (+9 lines).
There are exactly 9 added records and 0 removed or changed records:

- `AbstractTurboismPlugin` class (public abstract, implements `TurboismPlugin`).
- Its protected no-argument constructor.
- `context()` (public final, returns `PluginContext`).
- `disable()` (public, void, throws `Exception`).
- `disposalScope()` (public final, returns `DisposableScope`).
- `enable()` (public, void).
- `init(PluginContext)` (public, void).
- `shutdown()` (public, void, throws `Exception`).
- `Json.sanitize(Object)` (public static, generic signature `<T> T sanitize(T)`).

### Removed surfaces

None.
