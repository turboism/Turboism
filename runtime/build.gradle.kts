import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `java-library`
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

tasks.named<ProcessResources>("processTestResources") {
    from(project(":testframework").file(
        "src/main/resources/fixtures/schema/preview-report-v1"
    )) {
        into("fixtures/schema/preview-report-v1")
    }
}

dependencies {
    implementation(project(":sdk"))

    // JSON parsing implementation stays in runtime, not in SDK
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.2")

    // Runtime-only bytecode metadata scanning for the local draft mapping review pipeline.
    implementation("org.ow2.asm:asm:9.7.1")

    implementation("io.github.resilience4j:resilience4j-bulkhead:2.1.0")
    implementation("io.github.resilience4j:resilience4j-timelimiter:2.1.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.1.0")
}
