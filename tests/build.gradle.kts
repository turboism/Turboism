plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":runtime"))
    testImplementation(project(":sdk"))
    testImplementation(project(":plugins:demo"))
    testImplementation(project(":plugins:ui-theme"))
    testImplementation(project(":plugins:log-filter"))
    testImplementation(project(":plugins:main-toolbar"))
    testImplementation(project(":plugins:perf-opt"))
    testImplementation(project(":plugins:render-opt"))
    testImplementation(project(":plugins:clip-mask"))
    testImplementation(project(":plugins:parameter"))
    testImplementation(project(":plugins:mesh"))
    testImplementation(project(":plugins:bounding-box"))
    testImplementation(project(":plugins:context-menu"))
    testImplementation(project(":plugins:project-panel"))
    testImplementation(project(":plugins:psd-import"))
    testImplementation(project(":plugins:texture-atlas"))
    testImplementation(project(":testframework"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
}

tasks.test {
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("sdkBuildDir", project(":sdk").buildDir.absolutePath)
    systemProperty("demoBuildDir", project(":plugins:demo").buildDir.absolutePath)
    systemProperty("projectInspectorBuildDir", project(":plugins:project-inspector").buildDir.absolutePath)
    exclude("**/MigrationSuiteSafeIntegrationTest.class")
}

val officialPluginProjects = rootProject.subprojects
    .filter { it.path.startsWith(":plugins:") }
    .sortedBy { it.path }

val migrationSuiteSafeBundleDir = layout.buildDirectory.dir("migration-suite-safe")

val migrationSuiteSafeBundle by tasks.registering(Sync::class) {
    group = "verification"
    description = "Assembles a relocatable safe migration-suite test layout from all current official plugin modules."

    dependsOn(officialPluginProjects.map { it.tasks.named<org.gradle.jvm.tasks.Jar>("jar") })
    into(migrationSuiteSafeBundleDir)
    into("plugins") {
        from(officialPluginProjects.map { project ->
            project.tasks.named<org.gradle.jvm.tasks.Jar>("jar").flatMap { it.archiveFile }
        })
        eachFile {
            name = name.substringBeforeLast("-") + ".jar"
        }
    }
}

tasks.register<Test>("migrationSuiteSafeTest") {
    group = "verification"
    description = "Runs the deterministic child-JVM safe migration-suite lifecycle and preview-report gate."
    dependsOn(migrationSuiteSafeBundle)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("java.awt.headless", "true")
    systemProperty("migrationSuiteSafeBundleDir", migrationSuiteSafeBundleDir.get().asFile.absolutePath)
    filter {
        includeTestsMatching("dev.turboism.tests.migration.MigrationSuiteSafeIntegrationTest")
        isFailOnNoMatchingTests = true
    }
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
