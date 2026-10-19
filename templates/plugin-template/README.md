# Turboism Plugin Template

[EN / English](README.md) · [ZH / 简体中文](README_zh.md)

A standalone Gradle project for building a Turboism plugin **without cloning or
building the Turboism monorepo**. Run one setup script to fetch and verify the
SDK and `@SubscribeEvent` annotation processor, customize your plugin identity,
and build a single plugin JAR ready for any Turboism installation.

The plugin SDK API range is `[0.67.0,1.0.0)`: its lower bound is the minimum framework base version, with beta/nightly/SNAPSHOT suffixes ignored. See [plugin API compatibility](../../compatibility/plugin-api.md) for the runtime version source and release synchronization command.


## Prerequisites

- **JDK 17** (or newer — the plugin is compiled for Java 17 bytecode)
- `curl` and `python3` (Linux/macOS) or PowerShell 5.1+ (Windows) for the setup script
- The Gradle wrapper (`./gradlew` / `gradlew.bat`, Gradle 8.10.2) is bundled in this repository

## Quick start

1. **Create your repository** — click **Use this template** on
   [`turboism/turboism-plugin-template`](https://github.com/turboism/turboism-plugin-template)
   or clone it directly:

   ```bash
   git clone https://github.com/turboism/turboism-plugin-template.git my-turboism-plugin
   cd my-turboism-plugin
   ```

2. **Run the setup script** — fetches `turboism-sdk-<version>.jar` and
   `turboism-event-processor-<version>.jar`, verifies their `.sha256` sidecars,
   and configures your plugin ID, display name, Java package, author, and
   Cubism Editor version declaration:

   ```bash
   # Interactive prompt (Linux / macOS / Git Bash)
   ./scripts/setup.sh

   # Non-interactive with explicit flags
   ./scripts/setup.sh --channel stable \
     --id com.example.myplugin \
     --name "My Plugin" \
     --package com.example.myplugin \
     --author "Ada Lovelace" \
     --yes
   ```

   On Windows PowerShell:

   ```powershell
   .\scripts\setup.ps1
   # or non-interactive:
   .\scripts\setup.ps1 -Channel stable -Id com.example.myplugin -Name "My Plugin" -Package com.example.myplugin -Author "Ada Lovelace" -Yes
   ```

3. **Build the plugin JAR**:

   ```bash
   ./gradlew build        # or .\gradlew.bat build on Windows
   ```

   The packaged plugin JAR is written to `build/libs/<slug>-turboism-plugin-0.1.0.jar`
   (by default `build/libs/hello-turboism-plugin-0.1.0.jar`).

4. **Install into Turboism** — copy the built JAR into the `plugins/` folder of
   your Turboism installation, enable it under **Plugin Management**, and
   restart. The sample plugin registers a `hello.hello` action and subscribes
   to `HelloEvent` via `EventBus#subscribe` (with a `@SubscribeEvent` example
   ready to uncomment whenever `turboism-event-processor` is present).

## Channel and version selection

`setup.sh` / `setup.ps1` resolves releases through
`https://api.turboism.dev/v1/releases/<channel>.json` and falls back to the
GitHub Releases API (`https://api.github.com/repos/turboism/Turboism/releases`):

- **Stable channel (default)**: `./scripts/setup.sh --channel stable`
- **Beta channel**: `./scripts/setup.sh --channel beta`
- **Pinned version**: `./scripts/setup.sh --version 0.67.0-beta.2`

The chosen version is recorded in `gradle.properties` (`turboismSdkVersion`),
and the verified JARs are placed in `libs/`. Re-running the setup script is
idempotent: it replaces stale JARs in `libs/` only after the new downloads pass
SHA-256 verification and updates the existing package/class and descriptor in
place.

## Offline and manual alternatives

### Option A — manual GitHub Release download

Download `turboism-sdk-<version>.jar` and
`turboism-event-processor-<version>.jar` (plus their `.sha256` sidecars) from
<https://github.com/turboism/Turboism/releases>, verify their SHA-256 digests,
and drop the JARs into `libs/`:

```bash
sha256sum -c turboism-sdk-<version>.jar.sha256 turboism-event-processor-<version>.jar.sha256
mv turboism-sdk-<version>.jar turboism-event-processor-<version>.jar libs/
./gradlew build
```

### Option B — local Maven publication (`mavenLocal()`)

Inside a Turboism monorepo checkout, publish the SDK and annotation processor to
`~/.m2/repository`:

```bash
./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal
```

When `libs/` contains no `turboism-sdk-*.jar`, `build.gradle.kts` resolves
`dev.turboism:sdk:<turboismSdkVersion>` and
`dev.turboism:event-processor:<turboismSdkVersion>` from `mavenLocal()`.

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

Declare your plugin's supported set in
`src/main/resources/META-INF/turboism/cubism-versions.json`:

```json
{
  "format": "turboism.plugin-cubism-versions/v1",
  "plugin": "dev.example.hello",
  "supportedVersions": [
    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06",
    "5.1.00", "5.1.01", "5.1.02", "5.1.03",
    "5.2.00", "5.2.01", "5.2.02", "5.2.03",
    "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
  ],
  "fullFeatureVersions": [
    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06",
    "5.1.00", "5.1.01", "5.1.02", "5.1.03",
    "5.2.00", "5.2.01", "5.2.02", "5.2.03",
    "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
  ]
}
```

By default, `setup.sh` / `setup.ps1` populates `cubism-versions.json` with all
reviewed Cubism Editor versions reported by the release API's `compatibility`
field (or Override with `--cubism-versions 5.2.03,5.3.02,5.3.03,5.3.04`). If your
plugin requires a live Cubism Editor host, pass `--requires-cubism true` (or set
`"requiresCubism": true` under `"environment"` in
`src/main/resources/META-INF/turboism/plugin.json`).

## Event subscriptions

Both subscription styles work out of the box:

- **Declarative (`@SubscribeEvent`)** — when the chosen release ships
  `turboism-event-processor-<version>.jar`, `build.gradle.kts` wires it onto
  `annotationProcessor`, which generates the `GeneratedSubscriberCatalog`
  service entry at compile time. If the selected release only ships
  `turboism-sdk-<version>.jar` (for example `0.67.0-beta.1`), `setup.sh` /
  `setup.ps1` prints a warning and configures the build to compile without the
  processor; use `EventBus#subscribe` or rerun setup after a release that ships
  `turboism-event-processor`.
- **Imperative (`context.eventBus().subscribe(...)`)** — works on every SDK
  release without annotation processing; register the returned `Registration`
  with `context.disposableScope()` so it is withdrawn on disable.

## Rules of the road

- Depend on `dev.turboism:sdk` with `compileOnly` only — never bundle the SDK
  inside your plugin JAR, and never depend on runtime internals or
  `com.live2d.*` classes.
- Declare every permission your plugin uses under `permissions` in
  `META-INF/turboism/plugin.json`; undeclared capabilities fail closed at load
  time.
- Keep `plugin.name` / `plugin.description` plus every key your code looks up
  in `META-INF/turboism/i18n/messages*.properties`.
- Any non-class file outside `META-INF/` (images, schemas, data) must live
  under a prefix listed in `resources` in `plugin.json`.

## For template maintainers

`templates/plugin-template/` in [`turboism/Turboism`](https://github.com/turboism/Turboism)
is the single source of truth for this template. The standalone repository
`turboism/turboism-plugin-template` syncs from public Turboism release tags via
`.github/workflows/sync-from-turboism.yml` (scheduled daily and runnable on
demand via `workflow_dispatch`):

1. Resolves the latest `stable` and `beta` release tags from the Turboism
   release API / GitHub Releases API.
2. Fetches `templates/plugin-template/` at that tag from the public
   `turboism/Turboism` repository (no cross-repo token or secret needed).
3. Replaces the template repository working tree (preserving `.git`), commits
   if changed, and tags `sdk-<version>`.
