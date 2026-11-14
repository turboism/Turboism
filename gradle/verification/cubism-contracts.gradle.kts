import org.gradle.api.tasks.Exec
import dev.turboism.gradle.internal.VerificationStamps

/*
 * Cubism compatibility contracts: verification records, DRAFT packs, selector policies, the
 * compatibility matrix and the SDK annotation baseline. Every gate here reads materialized
 * records, so the two consumer sets at the bottom attach the materializer outputs as inputs.
 *
 * Leaves only; gradle/verification.gradle.kts owns every aggregate membership.
 */

val checkCubismCoreApiInventory by tasks.registering(Exec::class) {
    group = "integration verification"
    description = "Validates exact-artifact Cubism Core public API inventories without generated documentation gates."
    workingDir(rootDir)
    inputs.files(
        "scripts/cubism_core_api.py",
        "scripts/test/test_cubism_core_api_inventory.py",
        "compatibility/cubism/index.md",
        fileTree("compatibility/cubism/core-api/observed") { include("*.json") },
        "build/generated/draft/mapping-packs/cubism-5.1.03-core-model-read.json",
        "build/generated/draft/mapping-packs/cubism-5.2.03-core-model-read.json",
        "build/generated/draft/mapping-packs/cubism-5.3.04-core-model-read.json",
        "build/generated/draft/mapping-packs/cubism-5.3.02-core-model-read.json",
        "build/generated/draft/profiles/cubism-5.2.03.json",
        "build/generated/draft/profiles/cubism-5.3.02.json"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_cubism_core_api_inventory.py")
}

val checkCubismCoreMemberPolicy by tasks.registering(Exec::class) {
    group = "integration verification"
    description = "Classifies every observed Cubism Core public member and checks machine policy drift."
    workingDir(rootDir)
    inputs.files(
        "scripts/cubism_core_policy.py",
        "scripts/test/test_cubism_core_member_policy.py",
        "compatibility/cubism/core-api/policy/cubism-core-member-policy.json",
        fileTree("compatibility/cubism/core-api/observed") { include("*.json") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_cubism_core_member_policy.py")
}

val checkCubismCoreSelectorPolicy by tasks.registering(Exec::class) {
    group = "integration verification"
    description = "Validates generated Cubism Core selector constants and profile coverage."
    workingDir(rootDir)
    inputs.files(
        "scripts/cubism_core_selector_policy.py",
        "scripts/test/test_cubism_core_selector_policy.py",
        "compatibility/cubism/core-api/policy/cubism-core-selector-policy.json",
        fileTree("build/generated/draft/mapping-packs") {
            include("cubism-*-core-model-read.json")
        }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_cubism_core_selector_policy.py")
}

// The Javadoc rule parses production sources through the JDK compiler tree API; the JDK 17
// toolchain path is wired to the checker so it never depends on an incidental system JRE.
val checkEditorModelAliases by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Rejects host selector aliases the implementation invokes without a reviewed record " +
            "or a named no-record pin, and holds the unused-alias count non-increasing."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/check_editor_model_aliases.py",
        "scripts/test/test_editor_model_aliases.py",
        "scripts/turboism_dev/alias_vocabulary.py",
        "scripts/verification-sources/no-record-aliases.json"
    )
    inputs.files(
        fileTree("scripts/verification-sources/families") { include("*.json") },
        fileTree("build/generated/verification") { include("*.json") },
        fileTree("runtime/src/main/java") { include("**/*.java") },
        "build/generated/verification/cubism-5.2.03-editor-model.json",
        "build/generated/verification/cubism-5.3.02-editor-model.json",
        "build/generated/verification/cubism-5.3.03-editor-model.json"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/check_editor_model_aliases.py", rootDir.absolutePath)
}

/*
 * Draft pack metadata restates its referenced verification record (selector count,
 * capability roster, record SHA-256). Those pins drifted once already; the check
 * task recomputes them from the pinned record bytes, and the self test proves the
 * tamper matrix fails closed.
 */
tasks.register<Exec>("syncDraftPackMetadata") {
    group = "build"
    description = "Rewrites DRAFT pack metadata projections from the referenced verification record bytes."
    workingDir(rootDir)
    inputs.files(
        "scripts/sync_draft_pack_metadata.py",
        fileTree("build/generated/draft/mapping-packs") { include("*.json") },
        fileTree("build/generated/verification") { include("*.json") }
    )
    commandLine("python3", "scripts/sync_draft_pack_metadata.py", rootDir.absolutePath)
}

val checkDraftPackMetadata by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when DRAFT pack metadata does not match its referenced verification record."
    workingDir(rootDir)
    inputs.files(
        "scripts/sync_draft_pack_metadata.py",
        fileTree("build/generated/draft/mapping-packs") { include("*.json") },
        fileTree("build/generated/verification") { include("*.json") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/sync_draft_pack_metadata.py", rootDir.absolutePath, "--check")
}

// One release-table gate owns R1–R4. Materialization is a shared prerequisite for
// Exec gates below; no host process is needed for these checks.
val checkReviewedReleases by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks reviewed release integrity, patch closure, literal bans and task coverage."
    dependsOn("checkCompatibilityMatrix")
    workingDir(rootDir)
    inputs.file("build/generated/compatibility/cubism/compatibility-matrix.json")
    inputs.files(fileTree(rootDir) {
        include("scripts/**/*.py", "scripts/**/*.json", "scripts/**/*.java", "scripts/**/*.template",
                "scripts/preview/run-*.sh", "compatibility/cubism/**/*.json", "compatibility/cubism/index.md",
                "sdk/src/main/**/*.java", "runtime/src/main/**/*.java", "bootstrap/src/main/**/*.java",
                "core/contract/src/main/**/*.java", "core/persistence/src/main/**/*.java",
                "core/compatibility-policy/src/main/**/*.java",
                "core/event-processor/src/main/**/*.java",
                "graal-host/src/main/**/*.java", "buildSrc/src/main/**/*.java",
                "packaging/java-installer/listener-src/**/*.java",
                "plugins/*/src/main/**/*.java", "plugins/mcp/src/main/resources/META-INF/turboism/mcp-sdk-coverage.json",
                "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/*.json",
                "testing/integration-tests/src/test/java/dev/turboism/tests/plugin/Windows*.java",
                ".env.example")
        exclude("**/build/**", "**/.gradle/**", "**/__pycache__/**")
    })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_reviewed_releases.py", rootDir.absolutePath)
}



/*
 * The compatibility matrix JSON, the localized compatibility Markdown pages, and
 * the generated blocks inside plugin READMEs are derived artifacts: every
 * version set comes from the verification records, selector contracts, SDK
 * annotations, and campaign results. The check task regenerates them in
 * memory and fails on tracked-output drift before materializing the matrix
 * under build/generated. One render serves the check and its downstream readers.
 */
/*
 * generate_compatibility_matrix.py reads Java sources only through token-gated
 * regexes (scripts/compat_matrix/extract.py): a file that lacks every listed
 * token provably cannot change the output. Selecting by content keeps edits to
 * unrelated Java files from re-running the check, while a file that gains or
 * loses a token enters or leaves the set and so still invalidates the task.
 */
fun javaSourcesContaining(dir: String, vararg tokens: String): FileCollection =
    files(provider {
        fileTree(dir) { include("**/*.java") }.files.filter { source ->
            val text = source.readText()
            tokens.any { text.contains(it) }
        }
    })

val checkCompatibilityMatrix by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when generated compatibility artifacts drift from the reviewed data."
    workingDir(rootDir)
    inputs.files(
        "scripts/preview/reviewed_host_artifacts.py",
        "scripts/generate_compatibility_matrix.py",
        fileTree("scripts/compat_matrix") { include("*.py") },
        fileTree("scripts/turboism_dev") { include("*.py") },
        "scripts/version-monotonicity-exceptions.json",
        "compatibility/cubism/reviewed-releases.json",
        "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java",
        "runtime/src/main/java/dev/turboism/kernel/context/CubismEditorApiAvailabilityInterceptor.java",
        "validation/cubism-version-matrix/campaign-jobs.json",
        "compatibility/cubism/version-boundaries.json",
        "compatibility/cubism/official-feature-timeline.json",
        "packaging/release-plugins.txt",
        "packaging/market-plugins.txt",
        "packaging/market-plugins.json",
        fileTree("build/generated/verification") { include("*.json") },
        fileTree("scripts/verification-sources/families") { include("*.json") },
        // SUPPORTED_*_VERSIONS sets (contract and feature-level gates).
        javaSourcesContaining("scripts/verification-sources/templates", "SUPPORTED"),
        javaSourcesContaining("runtime/src/main/java", "SUPPORTED", "ClassPinTable.load("),
        // @CubismEditor annotations; the three command enums are parsed by path.
        javaSourcesContaining("sdk/src/main/java", "CubismEditor"),
        "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java",
        "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorParameterizedCommand.java",
        "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorFileCommand.java",
        fileTree("runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins") {
            include("*.json")
        },
        fileTree("compatibility/cubism") { include("*.md") },
        fileTree("plugins") {
            include("*/src/main/resources/META-INF/turboism/plugin.json")
            include("*/src/main/resources/META-INF/turboism/*-coverage.json")
            include("*/src/main/resources/META-INF/turboism/cubism-versions.json")
            // Plugin sources stay whole: SDK accessor use is detected by method-name regex.
            include("*/src/main/java/**/*.java")
            include("*/README*.md")
        }
    )
    outputs.file("build/generated/compatibility/cubism/compatibility-matrix.json")
    VerificationStamps.apply(this)
    commandLine(
        "python3", "scripts/generate_compatibility_matrix.py",
        "--check", "--generated-only", "--root", rootDir.absolutePath
    )
}







/*
 * Every public type under dev.turboism.sdk.cubism declares host-facing API and
 * must carry @CubismEditor — on itself, an enclosing type, or an interface
 * parent. sdk/api-contracts/cubism-annotation-baseline.json freezes the legacy
 * backlog; the gate fails on any new unannotated type and on stale baseline
 * entries, so the backlog can only shrink.
 */
val checkSdkCubismEditorAnnotations by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when a public dev.turboism.sdk.cubism type lacks @CubismEditor coverage."
    workingDir(rootDir)
    inputs.files(
        "scripts/check_sdk_cubism_annotations.py",
        "sdk/api-contracts/cubism-annotation-baseline.json",
        fileTree("sdk/src/main/java") { include("**/*.java") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_sdk_cubism_annotations.py", rootDir.absolutePath)
}

val checkMaterializedRecords by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks materialized verification records byte-for-byte against canonical release and family sources."
    workingDir(rootDir)
    inputs.files(
        "scripts/materialize_verification_records.py",
        "scripts/preview/reviewed_host_artifacts.py",
        "compatibility/cubism/reviewed-releases.json",
        fileTree("compatibility/cubism/verification-src") { include("*.json") },
        fileTree("build/generated/verification") { include("*.json") }
    )
    VerificationStamps.apply(this)
    commandLine(
        "python3", "scripts/materialize_verification_records.py",
        "--check-against", "build/generated/verification/"
    )
}

// Packaging consumes the materializer task output; validate its exact bytes,
// filename set and manifest pins before copying the resource directory.


/*
 * The VerificationManifest classes and selector contracts are generated by
 * :runtime:generateVerificationSources from byte-hashed verification records
 * plus per-family authoring files under scripts/verification-sources/. The
 * check fails if a generated target reappears under src/main/java, and
 * render-time validation keeps alias/capability literals bound to the record
 * universe; byte-level equivalence against the retired checked-in sources was
 * proven by the migration gate before the sources were removed.
 */
val checkVerificationSources by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves generated verification sources are byte-identical to checked-in sources and record-derived."
    workingDir(rootDir)
    inputs.files(
        "scripts/generate_verification_sources.py",
        "compatibility/cubism/reviewed-releases.json",
        "scripts/preview/reviewed_host_artifacts.py",
        "scripts/turboism_dev/alias_vocabulary.py",
        "scripts/turboism_dev/json_io.py",
        "scripts/verification-sources/model-update-targets.json",
        "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/model-update-skip.json",
        "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/incremental-update.json",
        fileTree("scripts/verification-sources") { include("**/*") },
        fileTree("build/generated/verification") { include("*.json") },
        fileTree("build/generated/draft/mapping-packs") { include("*.json") },
        fileTree("runtime/src/main/java/dev/turboism/mapping/verification") { include("**/*.java") },
        fileTree("runtime/src/main/java/dev/turboism/adapter/cubism") { include("**/*.java") },
        fileTree("runtime/src/main/java/dev/turboism/ui") { include("**/*.java") }
    )
    VerificationStamps.apply(this)
    commandLine(
        "python3", "scripts/generate_verification_sources.py",
        rootDir.absolutePath, "check", "--expect", "generated"
    )
}

val draftConsumers = setOf(
    "checkCubismCoreApiInventory",
    "checkCubismCoreSelectorPolicy",
    "syncDraftPackMetadata",
    "checkDraftPackMetadata",
    "checkVerificationSources"
)

val recordsConsumers = setOf(
    "checkEditorModelAliases",
    "checkReviewedReleases",
    "checkCompatibilityMatrix",
    "checkMaterializedRecords",
    "checkHostToolingSelfTest"
)

draftConsumers.forEach { taskName ->
    tasks.named(taskName).configure {
        inputs.files(tasks.named("materializeDraftContracts"))
    }
}

recordsConsumers.forEach { taskName ->
    tasks.named(taskName).configure {
        inputs.files(tasks.named("materializeVerificationRecords"))
    }
}

// Ordinary CI checks sources/pins/ABI without requiring MinGW or pretending native tests passed.
// Bootstrap receipt integration covers signed first-install initialization, cross-layer layout
// consistency and collision safety. The gate exports the runtime test classpath once and hands its
// file to the Python runner through a structured env var, so the test never starts a nested,
// competing Gradle build inside this Exec; standalone runs still export the classpath themselves.
