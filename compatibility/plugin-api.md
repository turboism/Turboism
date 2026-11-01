# Plugin SDK API compatibility

See the [plugin author guide](../sdk/plugin-author-guide.md) for lifecycle,
permissions, settings and packaging.

A plugin's own `version` is independent of this API version; only `turboismApi` ties it to the framework.

The runtime's Turboism API version is the framework base version: `0.67.0`,
`0.67.0-beta.2`, `0.67.0-0.nightly.32` and local `-SNAPSHOT` builds all expose
API `0.67.0`. The version comes from `gradle/common-java.gradle.kts` through the
runtime's `META-INF/turboism/framework-version.properties`; no server lookup is used.
The properties record `version`, `Implementation-Version` and `apiVersion`.
The runtime JAR manifest also records `Implementation-Version` and `Turboism-API-Version`.
The SDK JAR remains the byte-exact reviewed library and receives no build metadata.

Plugins declare `turboismApi: "[0.67.0,1.0.0)"`: they need at least `0.67.0`
and accept subsequent framework APIs below `1.0.0`. Prerelease suffixes do not
lower the runtime API version. Old `[0.1.0,0.2.0)` plugins fail discovery with
`TURBOISM_API_INCOMPATIBLE`; the diagnostic includes the actual runtime API.
Market installation and automatic update selection use the same runtime API.

The runtime API follows the framework version; a plugin's lower bound records the
minimum API it actually needs. A framework patch release alone does not raise it.
Most official plugins retain `[0.67.0,1.0.0)`. `external-psd-edit` and the
`atlas-maxrects-bssf` planner require `0.67.2` because they use SDK members added
in that release. `artmesh-mirror`, `deformer-batch-rename` and `mcp` require `0.68.0` for the same reason.
`atlas-folder-layout`, `atlas-bitmask-nesting`,
`atlas-dalsoo-polygon` and `psd-clip-mask-import` require `0.67.4` because they
use SDK members added in that release. When a plugin starts using a newer SDK API,
update that plugin's `plugin.json` range deliberately, then synchronize its README:

```sh
python3 scripts/sync_plugin_api.py --write
./gradlew checkPluginApiRanges checkOfficialPluginReadmes
```

`checkPluginApiRanges` validates that every official/template range includes the
current framework API and that README declarations match the plugin descriptor.
`--write` updates READMEs only; it never changes a plugin's declared range or
its dependency versions. It is included in `devCheck` and release checks.

`checkPluginApiFloors` enforces that lower bound mechanically. `compatibility/sdk-api-since.json`
records the first framework API that shipped each SDK class and member (anything older than
`0.67.0` is the baseline). The task reads every official plugin's compiled class files and fails
when `turboismApi` starts below the newest SDK member the plugin links. It also fails when the SDK
surface changed since the file was written; regenerate it from the release tags and the working
tree (run `git fetch --tags` first):

```sh
python3 scripts/sdk_api_since.py --write
```

The check covers linked classes, methods, constructors and fields, not behavior changes.
