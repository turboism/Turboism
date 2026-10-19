# Plugin SDK API compatibility

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

After changing the declared framework version, refresh official plugin and template
ranges and their localized README declarations with:

```sh
python3 scripts/sync_plugin_api.py --write
./gradlew checkPluginApiRanges checkOfficialPluginReadmes
```

`checkPluginApiRanges` is included in `devCheck` and the release checks. It detects
outdated checked-in API floors without changing plugin metadata during packaging;
README files therefore remain byte-identical to their packaged copies.
External plugin authors should declare the minimum framework API they actually use.
