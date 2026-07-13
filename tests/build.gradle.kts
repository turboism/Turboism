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
    testImplementation(project(":testframework"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
}

tasks.test {
    systemProperty("projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("sdkBuildDir", project(":sdk").buildDir.absolutePath)
    systemProperty("demoBuildDir", project(":plugins:demo").buildDir.absolutePath)
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
