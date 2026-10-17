plugins {
    `java-library`
}

// The audited native startup responder is validation tooling shared as source;
// host probes packaged from the test classes need it on their classpath.
sourceSets.test {
    java.srcDir(rootProject.file("validation/shared/src"))
}

// RelocateJar is probe-build tooling that needs org.ow2.asm:asm-commons, which is
// not on the test classpath; the probes compile it themselves from source.
tasks.named<JavaCompile>("compileTestJava") {
    exclude("dev/turboism/validation/shared/tools/RelocateJar.java")
}

dependencies {
    testImplementation(project(":runtime"))
    testImplementation(project(":sdk"))
    testImplementation(project(":core-contract"))
    testImplementation(project(":plugins:demo"))
    testImplementation(project(":plugins:ui-theme"))
    testImplementation(project(":plugins:parameter"))
    testImplementation(project(":plugins:mesh-edit-mirror-axis-enhance"))
    testImplementation(project(":plugins:psd-clip-mask-import"))
    testImplementation(project(":plugins:atlas-maxrects-bssf"))
    testImplementation(project(":testing:test-support"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.9")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.18.9")
}

tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.file("compatibility/cubism/verification")) {
        include("cubism-*-editor-model.json")
        into("META-INF/turboism/verification")
    }
}

tasks.test {
    dependsOn(":plugins:project-inspector:jar")
    dependsOn(":plugins:mcp:jar")
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("sdkBuildDir", project(":sdk").buildDir.absolutePath)
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
    systemProperty("mcpBuildDir", project(":plugins:mcp").buildDir.absolutePath)
}

/*
 * devCheck admission guard: the static verification record and DRAFT mapping
 * pack repository suites. These are the tests that pin review-data drift
 * (record digests, selector matrices, pack provenance) and previously only ran
 * under checkCompletedCommit.
 */
val admissionUnitTests by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the offline static verification record and draft mapping pack repository tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("sdkBuildDir", project(":sdk").buildDir.absolutePath)
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
    systemProperty("mcpBuildDir", project(":plugins:mcp").buildDir.absolutePath)
    filter {
        includeTestsMatching("dev.turboism.tests.mapping.*")
        isFailOnNoMatchingTests = true
    }
}

tasks.register<Test>("previewPluginRuntimeTest") {
    group = "verification"
    description = "Runs real local plugin loading and preview failure-report integration tests."
    dependsOn(":plugins:project-inspector:jar")
    dependsOn(":plugins:mcp:jar")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("java.awt.headless", "true")
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
    systemProperty("mcpBuildDir", project(":plugins:mcp").buildDir.absolutePath)
    filter {
        includeTestsMatching("dev.turboism.tests.preview.LocalPluginRuntimeIntegrationTest")
        includeTestsMatching("dev.turboism.tests.preview.McpPluginRuntimeIntegrationTest")
        includeTestsMatching("dev.turboism.preview.LocalPluginRuntimeFailureReportIntegrationTest")
        isFailOnNoMatchingTests = true
    }
}

val asyncHostReadPreviewIntegrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Verifies Project Inspector production wiring and lifecycle through the local plugin runtime."
    dependsOn(":plugins:project-inspector:jar")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("java.awt.headless", "true")
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
    filter {
        includeTestsMatching("dev.turboism.tests.preview.LocalPluginRuntimeIntegrationTest")
        isFailOnNoMatchingTests = true
    }
}

tasks.register<Test>("pluginInspectionMutationTest") {
    group = "verification"
    description = "Runs the production-backed strict ZIP mutation matrix for plugin inspection."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.tests.distribution.PluginStrictZipMutationIntegrationTest")
        isFailOnNoMatchingTests = true
    }
}

tasks.register<Test>("officialPluginI18nCompletenessTest") {
    group = "verification"
    description = "Verifies required official-plugin catalogs against their baseline keys."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    filter {
        includeTestsMatching("dev.turboism.tests.i18n.OfficialPluginCatalogCompletenessTest")
        includeTestsMatching("dev.turboism.tests.i18n.FrameworkCatalogCompletenessTest")
        isFailOnNoMatchingTests = true
    }
}

tasks.register<Test>("pluginPackageContractTest") {
    group = "verification"
    description = "Verifies installed-plugin package inspection and security contracts."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.tests.distribution.PluginPackage*IntegrationTest")
        isFailOnNoMatchingTests = true
    }
}

tasks.register("previewBundleContractTest") {
    group = "verification"
    description = "Builds and verifies the preview bundle contract."
    dependsOn(":checkPreviewBundleLayout")
}

tasks.register<org.gradle.api.tasks.Exec>("hostValidationScriptContractTest") {
    group = "verification"
    description = "Checks the locale host-validation wrapper without launching Cubism."
    workingDir(rootProject.projectDir)
    inputs.files(
        rootProject.file("scripts/preview/run-host-locale-host-validation.sh"),
        rootProject.file("scripts/preview/launch-cubism-host-locale-validation.sh"),
        rootProject.file("scripts/preview/launch-cubism-host-locale-validation-52.sh"),
        rootProject.file("scripts/preview/launch-cubism-host-locale-validation-53.sh"),
        rootProject.file("scripts/preview/host-locale-environment-language-hook.sh"),
        rootProject.file("scripts/preview/host-locale-validation-contract.sh")
    )
    commandLine("bash", "scripts/preview/host-locale-validation-contract.sh")
}
