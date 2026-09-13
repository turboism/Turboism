import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    // SDK has no implementation dependencies
}

// The published SDK artifact must keep stable Maven coordinates across
// worktree layouts, so its jar does not carry the per-worktree classifier.
tasks.named<Jar>("jar") {
    archiveClassifier.set("")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    filePermissions {
        unix("rw-r--r--")
    }
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
