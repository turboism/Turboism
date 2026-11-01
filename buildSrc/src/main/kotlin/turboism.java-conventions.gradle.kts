plugins {
    id("java")
    id("java-library")
    id("jacoco")
    id("com.diffplug.spotless")
}

val resolvedWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String
layout.buildDirectory.set(
    file("${rootProject.layout.buildDirectory.get()}/worktree/$resolvedWorktreeId/${project.name}")
)

tasks.named<Jar>("jar") {
    archiveClassifier.set(resolvedWorktreeId)
}

extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        target("src/**/*.java")
        palantirJavaFormat(libs.findVersion("palantirJavaFormat").get().requiredVersion)
    }
}

val errorprone = configurations.create("errorprone") {
    isVisible = false
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Error Prone javac plugin artifacts"
}

dependencies {
    add("errorprone", libs.findLibrary("errorprone-core").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    if (name == "compileJava") {
        options.isFork = true
        options.forkOptions.jvmArgs = options.forkOptions.jvmArgs.orEmpty() + listOf(
            "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
        )
        options.annotationProcessorPath =
            (options.annotationProcessorPath ?: files()) + errorprone
        options.compilerArgs = options.compilerArgs.orEmpty() + listOf(
            "-XDcompilePolicy=simple",
            "--should-stop=ifError=FLOW",
            "-Xplugin:ErrorProne " +
                "-XepDisableAllChecks " +
                "-Xep:EqualsHashCode:ERROR " +
                "-Xep:ArrayEquals:ERROR " +
                "-Xep:BoxedPrimitiveEquality:ERROR " +
                "-Xep:ReferenceEquality:ERROR " +
                "-Xep:CollectionIncompatibleType:ERROR " +
                "-Xep:NarrowingCompoundAssignment:ERROR " +
                "-Xep:IdentityBinaryExpression:ERROR"
        )
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        // CI logs must carry the full assertion message; SHORT hides multi-line details.
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Every Test task proves it executed tests and that each skip is registered with a
// reason (gradle/test-skip-allowlist.txt). A suite that selects nothing, or an
// environment that silently disables a suite, used to report success.
val testSkipAllowlist = rootProject.file("gradle/test-skip-allowlist.txt")
val testZeroExecutedAllowlist = rootProject.file("gradle/test-zero-executed-allowlist.txt")
tasks.withType<Test>().configureEach {
    dev.turboism.gradle.internal.TestExecutionGuard.apply(this, testSkipAllowlist, testZeroExecutedAllowlist)
}

dependencies {
    add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
