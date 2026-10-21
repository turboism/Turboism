plugins {
    id("turboism.plugin-conventions")
}

tasks.register<JavaExec>("capturedNestingBenchmark") {
    group = "verification"
    description = "Replay captured host contours and export measured layouts."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.turboism.plugin.atlasnesting.layout.CapturedNestingBenchmark")
    maxHeapSize = "2048m"
    if (providers.gradleProperty("nestingProfile").isPresent) {
        jvmArgs("-XX:StartFlightRecording=filename=${rootProject.layout.buildDirectory.get()}/nesting-captured-profile.jfr,settings=profile,dumponexit=true")
    }
    providers.gradleProperty("nestingCapture").orNull?.let { args(it) }
    args(providers.gradleProperty("nestingOutput").getOrElse("${rootProject.layout.buildDirectory.get()}/nesting-replay"))
    providers.gradleProperty("nestingScale").orNull?.let { args(it) }
}

dependencies {
    testImplementation(project(":plugins:atlas-maxrects-bssf"))
}

tasks.register<JavaExec>("nestingBenchmark") {
    group = "verification"
    description = "Offline BSSF/nesting comparison; synthetic contours, no host timing claims."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.turboism.plugin.atlasnesting.layout.NestingBenchmark")
    maxHeapSize = "1024m"
    args(rootProject.layout.buildDirectory.file("nesting-benchmark.csv").get().asFile.absolutePath)
    providers.gradleProperty("nestingCase").orNull?.let { args(it) }
    if (providers.gradleProperty("nestingProfile").isPresent) {
        jvmArgs("-XX:StartFlightRecording=filename=${rootProject.layout.buildDirectory.get()}/nesting-profile.jfr,settings=profile,dumponexit=true")
    }
}
