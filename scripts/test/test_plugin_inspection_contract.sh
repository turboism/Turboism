#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SCHEMA="docs/schema/local-offline-package-and-bundle-v1.md"
FIXTURES="runtime/src/test/resources/fixtures/schema/plugin-package-manifest-v1"
PRODUCTION=(
  runtime/src/main/java/dev/turboism/distribution/LocalPluginPackageInspector.java
  runtime/src/main/java/dev/turboism/distribution/PluginArtifactInspector.java
  runtime/src/main/java/dev/turboism/distribution/PluginDescriptorSnapshot.java
  runtime/src/main/java/dev/turboism/distribution/PluginInstallPlan.java
  runtime/src/main/java/dev/turboism/distribution/PluginJarInspector.java
  runtime/src/main/java/dev/turboism/distribution/PluginPathPolicy.java
  runtime/src/main/java/dev/turboism/distribution/StrictZipArchive.java
  runtime/src/main/java/dev/turboism/distribution/StrictZipParser.java
  runtime/src/main/java/dev/turboism/distribution/StrictZipSupport.java
)
MUTATION_TEST="tests/src/test/java/dev/turboism/tests/distribution/PluginStrictZipMutationIntegrationTest.java"
TEST_BUILD="tests/build.gradle.kts"
ROOT_BUILD="build.gradle.kts"

fail() { printf 'plugin inspection contract: %s\n' "$*" >&2; exit 1; }
require_text() { grep -Fq "$2" "$1" || fail "$1 missing: $2"; }

[[ -f "$SCHEMA" ]] || fail "missing authoritative schema"
require_text "$SCHEMA" 'raw outer input 1 GiB'
require_text "$SCHEMA" 'expanded total per ZIP scope 2 GiB'
require_text "$SCHEMA" '10,000 entries per ZIP scope'
require_text "$SCHEMA" '512 MiB per regular file'
require_text "$SCHEMA" 'compression ratio 100:1'
require_text "$SCHEMA" 'Scope-qualified contamination deny table'
require_text "$SCHEMA" 'Source revalidation is a best-effort, non-consuming `NOFOLLOW` observation'
require_text "$SCHEMA" 'revalidation never reopens the mutable pathname to recompute a digest'
require_text "$SCHEMA" 'Lifecycle preflight revalidation remains mandatory'
require_text "$SCHEMA" 'deep immutable'

[[ -f "$FIXTURES/valid/minimal.json" ]] || fail "missing persistent valid fixture"
invalid_count="$(find "$FIXTURES/invalid" -maxdepth 1 -type f -name '*.json' | wc -l)"
(( invalid_count >= 3 )) || fail "need at least three persistent invalid fixtures"

for source in "${PRODUCTION[@]}"; do
  [[ -f "$source" ]] || fail "missing production source $source"
  lines="$(wc -l < "$source")"
  (( lines <= 800 )) || fail "$source exceeds 800 lines"
done

! grep -R -E 'readAllBytes\(|readNBytes\(\(int\) PluginArchiveLimits\.RAW_MAX' \
  runtime/src/main/java/dev/turboism/distribution/{LocalPluginPackageInspector,PluginArtifactInspector,PluginJarInspector}.java \
  || fail "plugin archive inspection must remain streaming"
! grep -E 'private final .*sdk\.plugin\.PluginDescriptor|private final .*JsonNode|private final .*Path|private final byte\[\]' \
  runtime/src/main/java/dev/turboism/distribution/{PluginInstallPlan,PluginDescriptorSnapshot}.java \
  || fail "accepted evidence leaks mutable/parser/snapshot types"

grep -Fq 'LinkOption.NOFOLLOW_LINKS' runtime/src/main/java/dev/turboism/distribution/PackageAccess.java \
  || fail "source access lacks NOFOLLOW_LINKS"
grep -Fq 'PluginArchiveLimits.RAW_MAX' runtime/src/main/java/dev/turboism/distribution/LocalPluginPackageInspector.java \
  || fail "plugin raw limit is not wired"
grep -Fq 'PluginArchiveLimits.RATIO_MAX' runtime/src/main/java/dev/turboism/distribution/LocalPluginPackageInspector.java \
  || fail "plugin ratio limit is not wired"

[[ -f "$MUTATION_TEST" ]] || fail "missing production-backed strict ZIP mutation test"
require_text "$MUTATION_TEST" 'enforcesDescriptorLocalFieldClosureInEveryZipScope'
require_text "$TEST_BUILD" 'tasks.register<Test>("pluginInspectionMutationTest")'
require_text "$TEST_BUILD" 'PluginStrictZipMutationIntegrationTest'
require_text "$ROOT_BUILD" 'tasks.register("checkPluginInspectionRuntime")'
require_text "$ROOT_BUILD" 'dependsOn("checkPluginInspectionContract", ":tests:pluginInspectionMutationTest")'
require_text "$ROOT_BUILD" '"checkPluginInspectionRuntime"'

printf 'plugin inspection contract: PASS\n'
