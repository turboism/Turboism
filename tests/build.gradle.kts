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
    exclude("**/MigrationSuiteSafeIntegrationTest.class")
}

data class MigrationSuitePlugin(
    val module: String,
    val pluginId: String,
    val role: String
)

val migrationSuiteRoster = listOf(
    MigrationSuitePlugin("ui-theme", "dev.turboism.plugin.uitheme", "target"),
    MigrationSuitePlugin("log-filter", "dev.turboism.plugin.logfilter", "target"),
    MigrationSuitePlugin("core", "turboism.core", "target"),
    MigrationSuitePlugin("context-menu", "dev.turboism.plugin.context-menu", "target"),
    MigrationSuitePlugin("project-panel", "dev.turboism.plugin.project-panel", "target"),
    MigrationSuitePlugin("texture-atlas", "dev.turboism.plugin.texture-atlas", "target"),
    MigrationSuitePlugin("clip-mask", "dev.turboism.plugin.clipmask", "target"),
    MigrationSuitePlugin("bounding-box", "dev.turboism.plugin.bounding-box", "target"),
    MigrationSuitePlugin("perf-opt", "dev.turboism.plugin.perfopt", "target"),
    MigrationSuitePlugin("render-opt", "dev.turboism.plugin.renderopt", "target"),
    MigrationSuitePlugin("parameter", "dev.turboism.plugin.parameter", "target"),
    MigrationSuitePlugin("mesh", "dev.turboism.plugin.mesh", "target"),
    MigrationSuitePlugin("psd-import", "dev.turboism.plugin.psd-import", "target"),
    MigrationSuitePlugin("demo", "dev.turboism.plugin.demo", "neighbor"),
    MigrationSuitePlugin("project-inspector", "dev.turboism.plugin.project-inspector", "neighbor")
)
val migrationSuiteTargets = migrationSuiteRoster.filter { it.role == "target" }
val migrationSuiteNeighbors = migrationSuiteRoster.filter { it.role == "neighbor" }

require(migrationSuiteTargets.size == 13) {
    "migration-suite-safe must declare exactly 13 legacy target modules"
}
require(migrationSuiteRoster.map { it.module }.distinct().size == migrationSuiteRoster.size) {
    "migration-suite-safe roster modules must be unique"
}
require(migrationSuiteRoster.map { it.pluginId }.distinct().size == migrationSuiteRoster.size) {
    "migration-suite-safe roster plugin IDs must be unique"
}
require(migrationSuiteRoster.all { it.role == "target" || it.role == "neighbor" }) {
    "migration-suite-safe roster roles must be target or neighbor"
}

val migrationSuiteSafeBundleDir = layout.buildDirectory.dir("migration-suite-safe")
val migrationSuiteRosterManifest = migrationSuiteSafeBundleDir.map {
    it.file("roster.tsv")
}

val migrationSuiteSafeBundle by tasks.registering(Sync::class) {
    group = "verification"
    description = "Assembles the explicit 13-target migration suite plus demo/Project Inspector neighbors."

    val bundledProjects = migrationSuiteRoster.map { project(":plugins:${it.module}") }
    dependsOn(bundledProjects.map { it.tasks.named<org.gradle.jvm.tasks.Jar>("jar") })
    inputs.property(
        "migrationSuiteRoster",
        migrationSuiteRoster.map { "${it.role}|${it.module}|${it.pluginId}" }
    )
    outputs.file(migrationSuiteRosterManifest)
    into(migrationSuiteSafeBundleDir)
    migrationSuiteRoster.forEach { entry ->
        val pluginProject = project(":plugins:${entry.module}")
        into(if (entry.role == "target") "targets" else "neighbors") {
            from(pluginProject.tasks.named<org.gradle.jvm.tasks.Jar>("jar").flatMap { it.archiveFile }) {
                rename { "${entry.module}.jar" }
            }
        }
    }
    doLast {
        migrationSuiteRosterManifest.get().asFile.writeText(
            buildString {
                append("role\tmodule\tpluginId\n")
                migrationSuiteRoster.forEach { entry ->
                    append("${entry.role}\t${entry.module}\t${entry.pluginId}\n")
                }
            },
            Charsets.UTF_8
        )
    }
}

tasks.register<Test>("migrationSuiteSafeTest") {
    group = "verification"
    description = "Runs the deterministic child-JVM safe migration-suite lifecycle and preview-report gate."
    dependsOn(migrationSuiteSafeBundle)
    inputs.dir(migrationSuiteSafeBundleDir)
    inputs.file(migrationSuiteRosterManifest)
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
