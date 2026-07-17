plugins {
    `java-library`
}

dependencies {
    // SDK has no implementation dependencies
}

val asyncHostReadContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the frozen async host-read SDK contract tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    filter {
        includeTestsMatching("dev.turboism.sdk.hostread.AsyncHostReadContractTest")
        isFailOnNoMatchingTests = true
    }
}
