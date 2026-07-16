plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    testImplementation(project(":sdk"))
}

val asyncHostReadConsumerTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the Project Inspector async host-read consumer and lifecycle tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    filter {
        includeTestsMatching("dev.turboism.plugin.projectinspector.*")
        isFailOnNoMatchingTests = true
    }
}
