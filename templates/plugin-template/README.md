# Turboism Plugin Template

A standalone Gradle project for building a Turboism plugin **without cloning or
building the Turboism monorepo**. The result is a single plugin JAR you can drop
into a Turboism installation.

## Prerequisites

- **JDK 17** (or newer — the plugin is compiled for Java 17 bytecode)
- **Gradle 8.10+** on your `PATH`, or copy the Gradle wrapper (`gradlew`,
  `gradlew.bat`, `gradle/wrapper/`) from the Turboism repository root into this
  directory — or generate one with `gradle wrapper --gradle-version 8.10.2`

## Step 1 — provide the SDK

The SDK is a compile-time dependency only (`compileOnly`); the Turboism runtime
supplies the real implementation when your plugin loads. Pick **one** source:

### Option A — GitHub Release (recommended)

Download `turboism-sdk-<version>.jar` (and its `.sha256` for verification) from
<https://github.com/turboism/Turboism/releases> and put the JAR in `libs/`.
The build picks it up automatically.

### Option B — local Maven publication

Inside a Turboism repository checkout (for example a framework contributor's
clone), publish the SDK and annotation processor once:

```bash
./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal
```

Then keep `libs/` empty: the build resolves `dev.turboism:sdk` from
`mavenLocal()` using `turboismSdkVersion` in `gradle.properties`
(development builds end with `-SNAPSHOT`).

## Step 2 — build

```bash
gradle jar          # or ./gradlew jar if you installed the wrapper
```

This produces `build/libs/hello-turboism-plugin-0.1.0.jar`.

## Step 3 — install

Copy the JAR into the `plugins/` directory inside your Turboism installation,
then enable it under Plugin Management and restart. The plugin appears as
"Hello Plugin" and registers a `hello.hello` action; invoking the action
publishes a `HelloEvent` that the plugin's own subscriber logs.

## Make it yours

1. Rename the package `dev.example.hello` and the class `HelloPlugin`.
2. Update `id`, `name`, `version`, `entrypoints`, `authors`, `website`,
   `description`, and `license` in
   `src/main/resources/META-INF/turboism/plugin.json`. The `id` is a
   reverse-domain identifier and must stay unique across all installed plugins.
3. Set `turboismApi` to the API range your plugin supports, e.g.
   `"[0.1.0,0.2.0)"`. The host rejects plugins whose range does not cover the
   running Turboism API version.
4. Declare every permission your plugin uses under `permissions`; undeclared
   capabilities fail closed at load time.
5. Keep `plugin.name` / `plugin.description` plus every key your code looks up
   in the i18n catalogs. The base catalog `messages.properties` and every
   declared locale catalog (e.g. `messages_en.properties` for locale `en`) must
   ship inside the JAR.
6. Any non-class file outside `META-INF/` (images, schemas, data) must live
   under a prefix listed in `resources`; undeclared resources are rejected.

## Cubism Editor version support

Turboism hosts run different Cubism Editor versions, and every Cubism-facing
SDK API declares the versions it supports with `@CubismEditor` — an exact
`value` list admits only the named reviewed versions, `from`/`to` ranges can
also satisfy unreviewed compatibility-admitted hosts, and `exclude` always
removes a version. The full per-API matrix is generated into
`compatibility/cubism/compatibility-matrix.json` (developer view:
`compatibility/cubism/capabilities.md`) in the Turboism repository.

Query availability instead of assuming it:

- Facade accessors hand out fail-closed `unavailable()` sentinels for
  surfaces the current host cannot serve; probe `isAvailable()` before use.
- Calling an API gated to a different Editor version throws
  `CubismEditorApiUnavailableException`; its `supportedVersions()` tells you
  which versions admit the call. Catch it at feature boundaries and degrade
  gracefully — hide the menu entry, disable the action, or fall back to a
  supported API — rather than letting the exception reach the user.

```java
context.services().find(EditorCommandService.class).ifPresent(commands -> {
    if (commands.isAvailable()) {
        commands.execute(EditorCommand.ADD_SCENE);
    }
});
```

Declare your plugin's supported set by shipping
`META-INF/turboism/cubism-versions.json` in the JAR:

```json
{
  "format": "turboism.plugin-cubism-versions/v1",
  "plugin": "dev.example.hello",
  "supportedVersions": ["5.2.03", "5.3.02", "5.3.03", "5.3.04"],
  "fullFeatureVersions": ["5.3.02", "5.3.03", "5.3.04"]
}
```

The declaration is informational and never blocks loading: a `requiresCubism`
plugin without the file is treated as *undeclared* and the loader logs a
single note (a malformed file logs a warning the same way). First-party
plugins in the Turboism repository do not write this file by hand — the
compatibility-matrix generator derives it from declared capabilities and SDK
usage and writes it for them. Derive yours the same way: cover every Editor
version your code paths actually verify, and prefer `isAvailable()` probes
over narrowing the declared set.

## Event subscriptions

The template subscribes imperatively via `context.eventBus().subscribe(...)`,
which works with the plain SDK JAR. If you prefer the `@SubscribeEvent`
annotation style used by `plugins/demo`, add the annotation processor
(Option B above — it is published locally, not attached to GitHub Releases):

```kotlin
annotationProcessor("dev.turboism:event-processor:$turboismSdkVersion")
```

The processor generates the `GeneratedSubscriberCatalog` service entry the
runtime scans; without it, `@SubscribeEvent` methods are never invoked.

## Rules of the road

- Depend on `dev.turboism:sdk` with `compileOnly` only — never bundle the SDK
  inside your plugin JAR, and never depend on runtime internals or
  `com.live2d.*` classes.
- Match the SDK version to the Turboism version you target; the public API
  surface and its guarantees are described under `sdk/api-contracts/` in the
  Turboism repository and at <https://docs.turboism.dev>.
