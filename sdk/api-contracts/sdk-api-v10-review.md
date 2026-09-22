# SDK v10 canvas-hint contract review

This revision registers the native canvas-hint surface as the reviewed exact
contract for the 0.44.0 release. It adds API; it removes and changes nothing. This
is a contract revision, not a release or a host-validation PASS.

## Immutable reference

- Source: `a2031aaa1d6f1233d0cc830db8499f80eba60a36`, the last commit to change `sdk/` (it completes the native canvas-hint lifecycle contract on top of `ad1f9c9b0`).
- Reconstructed from an isolated Git archive using `reconstruct_sdk_gradle_jar.py`, Gradle 8.10.2 / Java 17.
- Reference JAR: 874951 bytes, SHA-256 `62b6b84ea12a831d9252dae2078131dc85bae566673ed51c62ade3602dc978ac`.
- Canonical dump: 6582 lines, SHA-256 `51d8cdd33b4a5621716645fac1090566451bd717d4b33891d376c9c4ddfad03f`.
- The anchor is pinned to the SDK-change commit and must stay reachable and unrebased. The release owner confirms it at release time.
- Baseline first captured outside the baseline directory for review, then added as a **new** v10 baseline. All v2–v9 baseline files and their anchors remain unchanged.
- v9 becomes a historical exact audit. v10 is the live exact gate; both remain required by `checkRelease`. No verification task generates or replaces a baseline.

## v9 → v10 delta and compatibility limits

The exact comparison reported **0 removed or changed records and 42 added records**
(6538 → 6580). Every addition is new API; no existing record, descriptor or
signature moved.

| Surface | Reviewed effect |
| --- | --- |
| `CanvasHintNotification` | New record: `id`, `message`, `durationSeconds`, `Optional<Runnable> onClick`, `Optional<CanvasHintPosition> position`, four constructors, `withOnClick`, `withPosition`, and the `DEFAULT_DURATION_SECONDS` / `UNTIL_DISMISSED` constants. |
| `CanvasHintHandle` | New interface: `renew()` and a default `dismiss()`. The returned handle dismisses only the keyed hint that is still current. |
| `CanvasHintPosition` | New record holding the optional `x`/`y` override; the host owns the native lower-right placement and theme when it is absent. |
| `ConditionalCanvasHint` | New utility: `whileTrue(...)` in a four- and a five-argument overload, plus `DEFAULT_CADENCE`. Renews a keyed hint while a condition holds and clears it when the condition reports false. |
| `UiHostCapabilityService` | Three added default methods: `notifyCanvasHint`, `notifyDismissibleCanvasHint`, `showCanvasHintWhile`. Their defaults throw `UnsupportedOperationException` (or delegate), so a host or stub that does not implement canvas hints keeps compiling and fails closed at call time rather than silently doing nothing. |
| `PermissionIds.TURBOISM_UI_CANVAS_HINT` | New permission constant gating the capability. |
| Plugin migration | **None required.** The revision is purely additive; every v9 and earlier entry point is retained with identical metadata. Plugins that do not use canvas hints are unaffected. |

## Compatibility notes

The capability is version-routed: the runtime resolves `CEAppCtrl.getCurrentViewContext`
→ `CEViewContext.showHint` and the clickable `showHintWithFunc` route through
verified SAM proxies against the reviewed 5.2.03, 5.3.02 and 5.3.03 artifacts,
with `ui.canvas.hint` selectors, records and mapping packs per version. A host
that cannot resolve the route reports the capability as unavailable instead of
approximating it.

Because a notification carries a caller-supplied `Runnable`, a plugin must treat
it as a UI-thread callback; the runtime invokes it on the host UI thread and the
documented convenience wrappers already marshal through the plugin's scheduler.

## Gates

```sh
./gradlew checkSdkV9ExactApiCompatibility checkSdkV10ExactApiCompatibility \
  checkSdkV8Linkage checkTextureAtlasSdkV7Linkage checkSdkApiBaselineTool :sdk:test
```

`checkSdkV8Linkage` compiles history/settings/current-page client bytecode against
the frozen v8 JAR and runs it with only the current SDK. It checks retained entry
points and representative values, not all history semantics. The existing v7 Atlas
linkage gate remains intact.

A v9 JAR tested against the v10 exact baseline must be rejected as an addition.
Mutation selftests must still reject identity tampering and unauthorized API drift.
Exact API passing does not replace behavioral tests, full release checks or
real-host evidence.
