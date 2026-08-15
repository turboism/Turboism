plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":runtime"))
    testImplementation(project(":sdk"))
    testImplementation(project(":plugins:demo"))
    testImplementation(project(":plugins:ui-theme"))
    testImplementation(project(":plugins:log-filter"))
    testImplementation(project(":plugins:core"))
    testImplementation(project(":plugins:perf-opt"))
    testImplementation(project(":plugins:render-opt"))
    testImplementation(project(":plugins:clip-mask"))
    testImplementation(project(":plugins:parameter"))
    testImplementation(project(":plugins:mesh"))
    testImplementation(project(":plugins:bounding-box"))
    testImplementation(project(":plugins:context-menu"))
    testImplementation(project(":plugins:project-panel"))
    testImplementation(project(":plugins:psd-import"))
    testImplementation(project(":plugins:psd-clip-mask-import"))
    testImplementation(project(":plugins:atlas-maxrects-bssf"))
    testImplementation(project(":testframework"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
}

tasks.test {
    dependsOn(":plugins:project-inspector:jar")
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("sdkBuildDir", project(":sdk").buildDir.absolutePath)
    systemProperty("demoBuildDir", project(":plugins:demo").buildDir.absolutePath)
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
}

tasks.register<Test>("previewPluginRuntimeTest") {
    group = "verification"
    description = "Runs real local plugin loading and preview failure-report integration tests."
    dependsOn(":plugins:project-inspector:jar")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("java.awt.headless", "true")
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
    filter {
        includeTestsMatching("dev.turboism.tests.preview.LocalPluginRuntimeIntegrationTest")
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
        rootProject.file("scripts/preview/host-locale-validation-contract.sh")
    )
    commandLine("bash", "scripts/preview/host-locale-validation-contract.sh")
}
