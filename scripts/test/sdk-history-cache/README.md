# Historical SDK Gradle cache

`prepareSdkHistoryGradleCache` runs this independent build once before any
`prepareSdkV*ExactReference` task, using `turboismSdkHistoryGradleUserHome` (the
calling Gradle user home by default). It resolves dependencies online unless the
caller uses `--offline`. The reconstruction script still builds each immutable
Git archive with `--offline --no-build-cache`; no cached SDK output is trusted.
The preparation has no up-to-date stamp: an evicted or restored cache must be
checked on every invocation.

Preparation resolves the real Kotlin DSL plugin classpath (including Gradle's
embedded-Kotlin constraints) and each plugin's independent dependency graph.
Both matter: resolving only marker coordinates misses constrained Kotlin
transitives, while merging all plugins can evict artifacts used by older anchors.

The following inventory was checked against **every v2–v20 anchor** in
`gradle/sdk-api.gradle.kts`, including root/settings/SDK/module builds and applied
Gradle scripts. All anchors use Gradle 8.10.2, Java 17, Maven Central for project
dependencies, and the default Gradle Plugin Portal for plugin requests. None has
buildSrc, a version catalog, or an external Kotlin plugin.

| Needed by offline `:sdk:jar` | Anchors | Resolution |
| --- | --- | --- |
| `org.izpack.gradle` 3.2.3 | v4–v20 | Plugin marker + implementation transitives |
| `com.gradleup.shadow` 8.3.11 | v11–v20 | Plugin marker + implementation transitives |
| `com.diffplug.spotless` 8.9.0 | v12–v20 | Plugin marker + implementation transitives |
| `com.google.errorprone:error_prone_core:2.42.0` | v11–v20 | SDK javac processor path + transitives |

Core plugins (`java`, `java-library`, `maven-publish`, `jacoco`, `application`)
come from Gradle. The SDK declares no external implementation dependencies.
The other external dependencies declared across these source trees are listed
below; they are not resolved by the historical `:sdk:jar` task graph:

- Jackson annotations/core/databind 2.17.2 (v2–v3), 2.18.9 (v4–v20).
- ASM 9.7.1; Resilience4j bulkhead/timelimiter/circuitbreaker 2.1.0 (v2–v20).
- JUnit BOM 5.10.3, Jupiter and Platform launcher (versions supplied by the BOM).
- IzPack api/ant/tools 5.2.6 (v4–v20), installer/gui/util 5.2.6 (v6–v20).
- GraalVM polyglot and platform-specific JS isolate community artifacts 25.2.4
  (v4–v20).
- Palantir Java Format 2.97.0 via Spotless formatting tasks (v12–v20).

When adding an anchor, inspect its build inputs and extend this historical union
if necessary. Never infer historical dependencies from the current catalog.
CI's `actions/setup-java` Gradle cache can make missing preparation appear green
on a warm runner; a fresh Gradle user home is the useful regression check.

For a targeted check without a full release build:

```bash
history_cache=$(mktemp -d)
./gradlew -PturboismSdkHistoryGradleUserHome="$history_cache" prepareSdkV12ExactReference
./gradlew --offline -PturboismSdkHistoryGradleUserHome="$history_cache" prepareSdkV20ExactReference
```
