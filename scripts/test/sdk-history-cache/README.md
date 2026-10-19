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

The following inventory was checked against **every v2–v21 anchor** in
`gradle/sdk-api.gradle.kts`, including root/settings/SDK/module builds and applied
Gradle scripts. All anchors use Gradle 8.10.2, Java 17, Maven Central for project
dependencies, and the default Gradle Plugin Portal for plugin requests. v2–v20 have no
buildSrc, version catalog, or external Kotlin plugin. v21 adds buildSrc with
Gradle 8.10.2's `kotlin-dsl` plugin (4.5.0, Kotlin 1.9.24) and a version catalog;
its convention plugins compile against Spotless 8.9.0. Preparation applies the
same Kotlin DSL plugin and resolves its compiler, compiler-plugin, compile and
runtime classpaths as well as the historical plugin graphs. The catalog retains
the same external library versions as v20.

| Needed by offline `:sdk:jar` | Anchors | Resolution |
| --- | --- | --- |
| `org.gradle.kotlin.kotlin-dsl` 4.5.0 / Kotlin 1.9.24 | v21 | Plugin classpath + buildSrc compiler and runtime dependencies |
| `org.izpack.gradle` 3.2.3 | v4–v21 | Plugin marker + implementation transitives |
| `com.gradleup.shadow` 8.3.11 | v11–v21 | Plugin marker + implementation transitives |
| `com.diffplug.spotless` 8.9.0 | v12–v21 | Plugin marker + implementation transitives |
| `com.google.errorprone:error_prone_core:2.42.0` | v11–v21 | SDK javac processor path + transitives |

Core plugins (`java`, `java-library`, `maven-publish`, `jacoco`, `application`)
come from Gradle. The SDK declares no external implementation dependencies.
The other external dependencies declared across these source trees are listed
below; they are not resolved by the historical `:sdk:jar` task graph:

- Jackson annotations/core/databind 2.17.2 (v2–v3), 2.18.9 (v4–v21).
- ASM 9.7.1; Resilience4j bulkhead/timelimiter/circuitbreaker 2.1.0 (v2–v21).
- JUnit BOM 5.10.3, Jupiter and Platform launcher (versions supplied by the BOM).
- IzPack api/ant/tools 5.2.6 (v4–v21), installer/gui/util 5.2.6 (v6–v21).
- GraalVM polyglot and platform-specific JS isolate community artifacts 25.2.4
  (v4–v21).
- Palantir Java Format 2.97.0 via Spotless formatting tasks (v12–v21).

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
