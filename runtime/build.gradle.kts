import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("turboism.java-conventions")
}

val generatedCoreCatalogRoot = layout.buildDirectory.dir(
    "generated/sources/cubism-core-catalog/java/main"
)
val generatedCoreCatalogFile = generatedCoreCatalogRoot.map {
    it.file(
        "dev/turboism/adapter/cubism/core/GeneratedCorePublicApiCatalog.java"
    )
}
val generatedCoreSelectorContractFile = generatedCoreCatalogRoot.map {
    it.file(
        "dev/turboism/mapping/verification/selector/CorePublicApiSelectorContract.java"
    )
}

val generateCorePublicApiCatalog by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates the complete internal Cubism Core public-member catalog."
    inputs.files(
        rootProject.file("scripts/cubism_core_api.py"),
        rootProject.file("scripts/cubism_core_policy.py"),
        rootProject.file(
            "compatibility/cubism/core-api/policy/cubism-core-member-policy.json"
        ),
        fileTree(
            rootProject.file("compatibility/cubism/core-api/observed")
        ) { include("*.json") }
    )
    outputs.file(generatedCoreCatalogFile)
    outputs.cacheIf { true }
    doFirst {
        val output = generatedCoreCatalogFile.get().asFile
        output.parentFile.mkdirs()
        val inventories = rootProject.file("compatibility/cubism/core-api/observed")
            .listFiles { f -> f.name.endsWith(".json") }
            .sorted()
            .flatMap { inventory -> listOf("--inventory", inventory.absolutePath) }
        commandLine(
            listOf(
                "python3",
                rootProject.file("scripts/cubism_core_policy.py").absolutePath,
                "render-java",
                "--policy",
                rootProject.file(
                    "compatibility/cubism/core-api/policy/cubism-core-member-policy.json"
                ).absolutePath,
                "--output",
                output.absolutePath
            ) + inventories
        )
    }
}

val generatedVerificationRoot = layout.buildDirectory.dir(
    "generated/sources/verification/java/main"
)

val generateVerificationSources by tasks.registering(Exec::class) {
    dependsOn(rootProject.tasks.named("materializeDraftContracts"))
    dependsOn(rootProject.tasks.named("materializeVerificationRecords"))
    group = "build"
    description = "Renders verification manifests and selector contracts from byte-hashed records plus family authoring files."
    inputs.files(
        rootProject.file("scripts/generate_verification_sources.py"),
        rootProject.file("compatibility/cubism/reviewed-releases.json"),
        rootProject.file("scripts/preview/reviewed_host_artifacts.py"),
        rootProject.file("scripts/turboism_dev/json_io.py"),
        rootProject.file("runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/model-update-skip.json"),
        rootProject.file("runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/incremental-update.json"),
        fileTree(rootProject.file("scripts/verification-sources")),
        fileTree(rootProject.file("build/generated/verification")) {
            include("*.json")
        },
        fileTree(rootProject.file("build/generated/draft/mapping-packs")) {
            include("*.json")
        },
        fileTree(
            rootProject.file("runtime/src/main/java/dev/turboism/adapter/cubism")
        ) { include("**/*.java") },
        fileTree(rootProject.file("runtime/src/main/java/dev/turboism/ui")) {
            include("**/*.java")
        }
    )
    outputs.dir(generatedVerificationRoot)
    outputs.cacheIf { true }
    doFirst {
        commandLine(
            "python3",
            rootProject.file("scripts/generate_verification_sources.py"),
            rootProject.rootDir.absolutePath,
            "render",
            "--out",
            generatedVerificationRoot.get().asFile
        )
    }
}

val generateCorePublicApiSelectorContract by tasks.registering(Exec::class) {
    dependsOn(rootProject.tasks.named("materializeVerificationRecords"))
    group = "build"
    description = "Generates the exact Cubism Core selector/profile contract."
    inputs.files(
        rootProject.file("scripts/cubism_core_api.py"),
        rootProject.file("scripts/cubism_core_selector_policy.py"),
        rootProject.file(
            "compatibility/cubism/core-api/policy/cubism-core-selector-policy.json"
        ),
        fileTree(
            rootProject.file("build/generated/verification")
        ) { include("cubism-*-core-model-read.json") }
    )
    outputs.file(generatedCoreSelectorContractFile)
    outputs.cacheIf { true }
    doFirst {
        val output = generatedCoreSelectorContractFile.get().asFile
        output.parentFile.mkdirs()
        // The task declares one output file, so Gradle will not remove a copy this generator
        // emitted under a previous package. An exact-host run found exactly that: a stale
        // CorePublicApiSelectorContract from the pre-`selector` package was still compiled into
        // the agent jar alongside the current one, and the classloader reached the stale
        // constants first. Sweep any same-named generated source outside the current package.
        val root = generatedCoreCatalogRoot.get().asFile
        root.walkTopDown()
            .filter { it.isFile && it.name == output.name && it != output }
            .forEach {
                logger.lifecycle("removing stale generated ${it.relativeTo(root)}")
                it.delete()
            }
        commandLine(
            listOf(
                "python3",
                rootProject.file("scripts/cubism_core_selector_policy.py").absolutePath,
                "render-java",
                "--policy",
                rootProject.file(
                    "compatibility/cubism/core-api/policy/cubism-core-selector-policy.json"
                ).absolutePath,
                "--record-dir",
                rootProject.file("build/generated/verification").absolutePath,
                "--output",
                output.absolutePath
            )
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generatedCoreCatalogRoot)
    java.srcDir(generatedVerificationRoot)
}

val frameworkVersionResource = layout.buildDirectory.file(
    "generated/resources/turboism-framework-version/framework-version.properties"
)

val generateFrameworkVersionResource by tasks.registering {
    group = "build"
    description = "Generates the framework version resource packaged into the runtime."
    @Suppress("UNCHECKED_CAST")
    val metadata = rootProject.extra["turboismBuildMetadata"] as Map<String, String>
    inputs.properties(metadata)
    outputs.file(frameworkVersionResource)
    doLast {
        val file = frameworkVersionResource.get().asFile
        file.parentFile.mkdirs()
        // Stable order and no generated timestamp: rebuilding identical inputs is reproducible.
        file.writeText(metadata.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateFrameworkVersionResource)
    from(frameworkVersionResource) {
        into("META-INF/turboism")
    }
    // Single-source manifests shared with the packaging/installer lanes; bundled
    // so the runtime reads byte-identical pins and retired ids at runtime.
    from(rootProject.file("packaging/retired-plugins.txt")) {
        into("META-INF/turboism")
    }
    from(rootProject.file("packaging/release-plugins.txt")) {
        into("META-INF/turboism")
    }
    from(rootProject.file("packaging/managed-graal.json")) {
        into("META-INF/turboism")
    }
}

tasks.named("compileJava") {
    dependsOn(
        generateCorePublicApiCatalog,
        generateCorePublicApiSelectorContract,
        generateVerificationSources
    )
}

val protocolRecordValidationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the focused Distribution Slice 1A protocol-record validation suite."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.distribution.record.*")
        isFailOnNoMatchingTests = true
    }
}

val asyncHostReadFoundationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the runtime async host-read lane, source, and lifecycle contract tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    filter {
        includeTestsMatching("dev.turboism.hostread.*")
        isFailOnNoMatchingTests = true
    }
}

val warpAltMirrorArtifactTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks warp Alt-mirror hook and toolbar contracts against every reviewed Cubism JAR."
    val refRoot = providers.environmentVariable("TURBOISM_CUBISM_REF_ROOT").orElse("")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("turboism.cubismRefRoot", refRoot.get())
    inputs.property("cubismRefRoot", refRoot)
    if (refRoot.get().isNotBlank()) {
        inputs.files(fileTree(refRoot.get()) { include("*/Live2D_Cubism.jar") })
    }
    // Real host constructors create a relative data/.tmp tree. Keep it in this worktree's build output.
    val isolatedHostDir = layout.buildDirectory.dir("warp-alt-mirror-artifact-host")
    doFirst {
        isolatedHostDir.get().asFile.mkdirs()
        workingDir(isolatedHostDir.get().asFile)
    }
    doFirst {
        require(refRoot.get().isNotBlank()) {
            "Set TURBOISM_CUBISM_REF_ROOT to a directory containing <version>/Live2D_Cubism.jar"
        }
    }
    filter {
        includeTestsMatching("dev.turboism.adapter.cubism.warpalt.WarpAltMirrorReviewedArtifactsTest")
        isFailOnNoMatchingTests = true
    }
}

val parameterLockArtifactTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks parameter-lock interception, row UI transformations and lifecycle targets against every reviewed Cubism JAR."
    val refRoot = providers.environmentVariable("TURBOISM_CUBISM_REF_ROOT").orElse("")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("turboism.cubismRefRoot", refRoot.get())
    inputs.property("cubismRefRoot", refRoot)
    dependsOn(rootProject.tasks.named("materializeVerificationRecords"))
    inputs.files(rootProject.fileTree("build/generated/verification") {
        include("cubism-*-editor-model.json", "cubism-*-ui-control-appearance.json")
    })
    if (refRoot.get().isNotBlank()) {
        inputs.files(fileTree(refRoot.get()) { include("*/Live2D_Cubism.jar") })
    }
    doFirst {
        require(refRoot.get().isNotBlank()) {
            "Set TURBOISM_CUBISM_REF_ROOT to a directory containing <version>/Live2D_Cubism.jar"
        }
    }
    filter {
        includeTestsMatching("dev.turboism.mapping.verification.ParameterLockReviewedArtifactsTest")
        isFailOnNoMatchingTests = true
    }
}

val corePublicApiProviderTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs synthetic Cubism Core admission, lease, call-site, and structural projection tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.adapter.cubism.core.*")
        includeTestsMatching(
            "dev.turboism.mapping.verification.VerifiedMethodCallSiteTest"
        )
        isFailOnNoMatchingTests = true
    }
}

val admissionPreflightTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Offline admission preflight: replays the production manifest-record gate for every reviewed record x generated manifest without a host artifact."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.mapping.verification.*AdmissionPreflight*")
        isFailOnNoMatchingTests = true
    }
}

// Test classes or individual methods requiring separately staged licensed Cubism evidence; they
// live in legacyCubismEvidenceTest and never run in the offline suites.
val legacyEvidenceTests = listOf(
    "dev.turboism.adapter.cubism.VerifiedProjectWorkspaceImageDocumentTest",
    "dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfileTest",
    "dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndexExactHostShapeTest",
    "dev.turboism.mapping.verification.EditorDocumentReadSelectorContractTest",
    "dev.turboism.mapping.verification.EditorEditSelectorContractTest",
    "dev.turboism.mapping.verification.EditorInspectorDrawableSelectorContractTest",
    "dev.turboism.mapping.verification.EditorInspectorSelectorContractTest",
    "dev.turboism.mapping.verification.EditorObjectReadSelectorContractTest",
    "dev.turboism.mapping.verification.EditorParameterBindingSelectorContractTest",
    "dev.turboism.mapping.verification.EditorPartNameSelectorContractTest",
    "dev.turboism.mapping.verification.EditorPartOpacitySelectorContractTest",
    "dev.turboism.mapping.verification.EditorRawImagePsdReplaceSelectorContractTest",
    "dev.turboism.mapping.verification.EditorRawImagePsdSelectorContractTest",
    "dev.turboism.mapping.verification.EditorStructureSelectorContractTest",
    "dev.turboism.mapping.verification.EditorTextureRelationsSelectorContractTest",
    "dev.turboism.mapping.verification.EditorTextureSelectorContractTest",
    "dev.turboism.mapping.verification.CorePublicApiAdmissionTest",
    "dev.turboism.mapping.verification.VerifiedEditorStartupResolverFactoriesTest",
    "dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchArtifactTest",
    "dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionArtifactTest",
    "dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseArtifactTest",
    "dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationArtifactTest",
    "dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipArtifactTest",
    "dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateArtifactTest",
    "dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseArtifactTest",
    "dev.turboism.adapter.cubism.optimization.stateelision.RedundantStateElisionArtifactTest",
    "dev.turboism.adapter.cubism.optimization.uniform.UniformLocationCallSiteArtifactTest",
    "dev.turboism.adapter.cubism.performance.PerformanceProbeImageArtifactTest",
    "dev.turboism.adapter.cubism.shader.ShaderUniformCompositionArtifactTest",
    "dev.turboism.mapping.verification.CubismHostCompatibilityRealSampleTest",
    // Mixed suites retain their synthetic behavior tests in the offline gates.
    "dev.turboism.adapter.cubism.interpolation.MeshInterpolationTransformerTest.realJarBytesMatchPinnedDigestsAndTransformOnEveryReviewedVersion",
    "dev.turboism.adapter.cubism.mesh.PopupMeshUndoContractTest.popupSnapshotRestoresRealNativeMeshAndInvalidatesPositionsOnUndoRedo",
    "dev.turboism.adapter.ui.OffCanvasBackgroundHostAccessTest.refreshesExistingAndFutureBackgroundMaterialsAcrossReviewedHosts",
    "dev.turboism.mapping.verification.ReviewedHostArtifactsTest.localExactArtifactsMatchTheReviewedIdentitiesWhenPresent",
    "dev.turboism.ui.table.SceneTableCompatibilityTest.realMembersBindAfterRepackingAndDeclarationChangeButRejectOtherSources"
)

val legacyCubismEvidenceTest by tasks.registering(Test::class) {
    group = "host verification"
    description = "Runs tests that require separately supplied licensed Cubism binary evidence."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    // Evidence supplied to Gradle must also reach the opt-in evidence JVM.
    listOf(
        "turboism.test.cubismEditorJar",
        "turboism.test.cubismCoreJar",
        "turboism.test.cubism5302Jar",
        "turboism.test.verificationDir"
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    filter {
        legacyEvidenceTests.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}

tasks.named<Test>("test") {
    @Suppress("UNCHECKED_CAST")
    val buildMetadata = rootProject.extra["turboismBuildMetadata"] as Map<String, String>
    systemProperty("turboism.expectedFrameworkVersion", buildMetadata.getValue("version"))
    // Optional test-JVM parallelism for the big runtime suite. Off (1) by default;
    // CI enables it via -PruntimeTestForks=2 after the isolation audit. Lanes that
    // must stay serial pin maxParallelForks on their own tasks and are unaffected.
    maxParallelForks = (findProperty("runtimeTestForks") as String?)?.toInt()?.coerceAtLeast(1) ?: 1
    // Optional real-host evidence paths for gated sample tests; absent means skip.
    listOf(
        "turboism.test.cubismEditorJar",
        "turboism.test.cubismCoreJar",
        "turboism.test.cubism5302Jar",
        "turboism.test.verificationDir"
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    filter {
        legacyEvidenceTests.forEach { excludeTestsMatching(it) }
        // Need TURBOISM_CUBISM_REF_ROOT; run only in warpAltMirrorArtifactTest / parameterLockArtifactTest.
        excludeTestsMatching("dev.turboism.adapter.cubism.warpalt.WarpAltMirrorReviewedArtifactsTest")
        excludeTestsMatching("dev.turboism.mapping.verification.ParameterLockReviewedArtifactsTest")
    }
}

/*
 * Offline admission guard for devCheck: the mapping/verification, Cubism
 * adapter, and plugin-context suites that pin reviewed records, alias
 * matrices, and SDK availability boundaries. devCheck never ran plain unit
 * tests, so admission expectation drift only surfaced in
 * checkCompletedCommit; this suite keeps the daily gate honest without paying
 * for the full test task.
 */
val admissionUnitTests by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the offline mapping, adapter, and plugin-context admission unit tests."
    @Suppress("UNCHECKED_CAST")
    val buildMetadata = rootProject.extra["turboismBuildMetadata"] as Map<String, String>
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("turboism.expectedFrameworkVersion", buildMetadata.getValue("version"))
    listOf(
        "turboism.test.cubismEditorJar",
        "turboism.test.cubismCoreJar",
        "turboism.test.cubism5302Jar",
        "turboism.test.verificationDir"
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    filter {
        includeTestsMatching("dev.turboism.mapping.*")
        includeTestsMatching("dev.turboism.adapter.*")
        includeTestsMatching("dev.turboism.core.plugin.context.*")
        legacyEvidenceTests.forEach { excludeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}

tasks.named<ProcessResources>("processTestResources") {
    from(project(":testing:test-support").file(
        "src/main/resources/fixtures/schema/preview-report-v1"
    )) {
        into("fixtures/schema/preview-report-v1")
    }
}

// Build a real production-runtime artifact with the framework UI physically absent.
// The headless probe must load and close an external plugin without resolving shell classes.
val headlessRuntimeJar by tasks.registering(Jar::class) {
    archiveFileName.set("runtime-without-shell.jar")
    destinationDirectory.set(layout.buildDirectory.dir("headless-probe"))
    from(sourceSets.main.get().output) {
        exclude("dev/turboism/shell/**", "META-INF/turboism/shell/**")
    }
}

sourceSets {
    create("headlessProbe") {
        java.srcDir("src/headlessProbe/java")
    }
}
sourceSets.named("headlessProbe") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

val headlessProbeHome = layout.buildDirectory.dir("headless-probe/home")

val headlessClasspathStamp = layout.buildDirectory.file("verification-stamps/checkHeadlessRuntimeClasspath.stamp")

val checkHeadlessRuntimeClasspath by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Loads and closes a real external plugin with framework shell classes " +
        "physically absent from the production runtime artifact."
    dependsOn(headlessRuntimeJar, "compileHeadlessProbeJava")
    classpath = sourceSets["headlessProbe"].output + files(headlessRuntimeJar) +
        configurations.runtimeClasspath.get()
    mainClass.set("dev.turboism.preview.HeadlessRuntimeProbeMain")
    jvmArgs("-Djava.awt.headless=true")
    // The probe verdict is a pure function of the classpath above; the stamp gives
    // Gradle a persistent output so a passing run is not repeated unchanged.
    outputs.file(headlessClasspathStamp)
    doFirst {
        val home = headlessProbeHome.get().asFile
        home.deleteRecursively()
        home.mkdirs()
        args(home.absolutePath)
    }
    doLast {
        headlessClasspathStamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

dependencies {
    testImplementation(project(":plugins:selection-brush"))
    testImplementation(project(":testing:test-support"))
    implementation(project(":sdk"))
    // Internal management contracts shared with the framework shell.
    // The runtime never depends on :plugins:* modules.
    implementation(project(":core-contract"))

    // JSON parsing implementation stays in runtime, not in SDK
    implementation(libs.jackson.databind)
    implementation(libs.jackson.core)
    implementation(libs.jackson.annotations)

    // Runtime-only bytecode metadata scanning for the local draft mapping review pipeline.
    implementation("org.ow2.asm:asm:9.7.1")

    // Resilience4j uses SLF4J 1.7. Keep private dependency diagnostics silent and
    // avoid LoggerFactory's missing-binding warning in Cubism's native log.
    runtimeOnly("org.slf4j:slf4j-nop:1.7.30")

    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.timelimiter)
    implementation(libs.resilience4j.circuitbreaker)

}

// Keep standalone focused gates useful, while the full suite owns their coverage
// whenever it is present (including devCheck + checkCompletedCommit in CI).
listOf("admissionUnitTests", "admissionPreflightTest", "corePublicApiProviderTest", "asyncHostReadFoundationTest").forEach { name ->
    tasks.named<Test>(name) {
        description += " Skipped when :runtime:test is scheduled, which includes this coverage."
        onlyIf("the full runtime suite is not scheduled") { !gradle.taskGraph.hasTask(":runtime:test") }
    }
}
listOf("admissionPreflightTest", "corePublicApiProviderTest").forEach { name ->
    tasks.named<Test>(name) {
        description += " Also covered by admissionUnitTests when that task is scheduled."
        onlyIf("the admission unit suite is not scheduled") { !gradle.taskGraph.hasTask(":runtime:admissionUnitTests") }
    }
}

// Live-tree mapping/profile fixtures require the complete generated DRAFT view.
tasks.withType<Test>().configureEach {
    dependsOn(rootProject.tasks.named("materializeDraftContracts"))
    inputs.files(rootProject.tasks.named("materializeDraftContracts"))
}
