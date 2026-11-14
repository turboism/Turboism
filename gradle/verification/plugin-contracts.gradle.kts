import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import java.io.File
import dev.turboism.gradle.internal.JavaToolchainHomes
import dev.turboism.gradle.internal.VerificationStamps

/*
 * First-party plugin contracts: the descriptor, README, API-range, icon and installer-section
 * rules that keep a plugin's published identity consistent with what it ships.
 *
 * Leaves only; gradle/verification.gradle.kts owns every aggregate membership.
 */

// The external-consumer gate compiles a fixture plugin against the SDK with this exact JDK, so it
// shares the toolchain the Javadoc rule already resolves instead of a second lookup.
val codeQualityJavaHome = JavaToolchainHomes.compilerHome(project, 17)

tasks.register("checkOfficialPluginI18nCompleteness") {
    group = "verification"
    description = "Verifies the full locale matrix for official-plugin and framework catalogs."
    dependsOn(":testing:integration-tests:officialPluginI18nCompletenessTest")
}

val checkAsyncHostReadStructuralBoundaries by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects plugin-owned Thread, Executor, and Timer resources and synchronous host reads in the async-host consumer (ProjectInspectorPlugin)."
    workingDir(rootDir)
    inputs.files(
        "plugins/project-inspector/src/main/java/dev/turboism/plugin/projectinspector/ProjectInspectorPlugin.java",
        "scripts/test/test_async_host_read_foundation.py"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_async_host_read_foundation.py")
}

val checkAsyncHostReadFoundation by tasks.registering {
    group = "integration verification"
    description = "Verifies async host-read contracts, runtime behavior, consumer behavior, and structural boundaries."
    dependsOn(
        ":sdk:asyncHostReadContractTest",
        ":runtime:asyncHostReadFoundationTest",
        ":plugins:project-inspector:asyncHostReadConsumerTest",
        ":testing:integration-tests:asyncHostReadPreviewIntegrationTest",
        checkAsyncHostReadStructuralBoundaries
    )
}

val checkPluginApiRangesSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests plugin-owned SDK API ranges and README synchronization."
    inputs.files("scripts/sync_plugin_api.py", "scripts/turboism_dev/versions.py", "scripts/test/test_sync_plugin_api.py")
    commandLine("python3", "-m", "unittest", "scripts.test.test_sync_plugin_api")
    VerificationStamps.apply(this)
}

val checkPluginApiRanges by tasks.registering(Exec::class) {
    dependsOn(checkPluginApiRangesSelfTest)
    group = "verification"
    description = "Validate plugin SDK API ranges and synchronize README declarations."
    inputs.files("scripts/sync_plugin_api.py", "scripts/turboism_dev/versions.py", "gradle/common-java.gradle.kts")
    inputs.files(fileTree("plugins") { include("*/src/main/resources/META-INF/turboism/plugin.json", "*/README*.md") })
    commandLine("python3", "scripts/sync_plugin_api.py")
    VerificationStamps.apply(this)
}

val checkPluginApiFloorsSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests SDK first-release tracking and plugin API floor evaluation."
    inputs.files("scripts/sdk_api_since.py", "scripts/test/test_sdk_api_since.py")
    commandLine("python3", "-m", "unittest", "scripts.test.test_sdk_api_since")
    VerificationStamps.apply(this)
}

/*
 * Every plugin's turboismApi lower bound must cover the newest SDK member the plugin links. The
 * class directories are resolved once into plain paths while the task is configured, so the action
 * closure captures strings and a list instead of a Project or a Task: the configuration cache can
 * store that, and the argument list stays identical.
 */
val checkPluginApiFloors by tasks.registering(Exec::class) {
    dependsOn(checkPluginApiFloorsSelfTest)
    group = "verification"
    description = "Fails when a plugin's turboismApi lower bound is older than the newest SDK member it links."
    val pluginProjects = subprojects.filter { it.path.startsWith(":plugins:") }
    val sdkClasses = project(":sdk").extensions.getByType<SourceSetContainer>().named("main").map { it.output.classesDirs }
    dependsOn(":sdk:classes", pluginProjects.map { "${it.path}:classes" })
    inputs.files("scripts/sdk_api_since.py", "scripts/SdkApiRefs.java", "compatibility/sdk-api-since.json")
    inputs.files(sdkClasses)
    inputs.files(fileTree("plugins") { include("*/src/main/resources/META-INF/turboism/plugin.json") })
    pluginProjects.forEach { inputs.files(it.extensions.getByType<SourceSetContainer>().named("main").map { set -> set.output.classesDirs }) }
    val pluginClassDirectories = pluginProjects.associate { plugin ->
        plugin.name to plugin.extensions.getByType<SourceSetContainer>()
            .named("main").map { set -> set.output.classesDirs.singleFile.absolutePath }
    }
    val sdkClassDirectory = sdkClasses.map { it.singleFile.absolutePath }
    val sharedGradleHome = gradle.gradleUserHomeDir.absolutePath
    doFirst {
        val args = mutableListOf(
            "python3", "scripts/sdk_api_since.py", "--check",
            "--sdk-classes", sdkClassDirectory.get()
        )
        pluginClassDirectories.forEach { (name, directory) ->
            args += listOf("--plugin", "$name=${directory.get()}")
        }
        commandLine(args)
    }
    environment("JAVA_HOME", System.getProperty("java.home"))
    VerificationStamps.apply(this)
}

val checkOfficialPluginReadmesSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests the README documented-version contract."
    inputs.files("scripts/test/check_official_plugin_readmes.py", "scripts/test/test_check_official_plugin_readmes.py")
    commandLine("python3", "-m", "unittest", "scripts.test.test_check_official_plugin_readmes")
    VerificationStamps.apply(this)
}

val checkOfficialPluginReadmes by tasks.registering(Exec::class) {
    dependsOn(checkOfficialPluginReadmesSelfTest)
    group = "verification"
    description = "Verifies every first-party plugin has a README matching its descriptor and delivery role."
    workingDir(rootDir)
    inputs.file("packaging/release-plugins.txt")
    inputs.file("packaging/market-plugins.txt")
    inputs.file("scripts/test/check_official_plugin_readmes.py")
    inputs.files(fileTree("plugins") {
        include("*/README.md")
        include("*/README_zh.md")
        include("*/README_ja.md")
        include("*/README_ko.md")
        include("*/src/main/resources/META-INF/turboism/plugin.json")
    })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/check_official_plugin_readmes.py")
}

/*
 * A mesh-toolbar icon without DPI variants makes the host enlarge the 32px bitmap on a scaled
 * display, so the button turns blurry. The check is cheap: it reads only descriptors and PNG
 * headers, so it stays in the daily gate.
 */
val checkToolbarIconScaleVariants by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects a mesh-toolbar icon that ships without its 40, 48, 56 and 64 pixel variants."
    workingDir(rootDir)
    inputs.file("scripts/test/check_toolbar_icon_scale_variants.py")
    inputs.files(fileTree("plugins") {
        include("*/src/main/resources/META-INF/turboism/plugin.json")
        include("*/src/main/resources/icons/**/*.png")
    })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/check_toolbar_icon_scale_variants.py")
}

val pluginEventReferenceReport = layout.buildDirectory.file(
    "reports/plugin-events/plugin-public-events.md"
)

val generatePluginEventReference by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Generates the first-party plugin public-event report under build/reports."
    workingDir(rootDir)
    inputs.files(
        "scripts/plugin_event_metadata.py",
        "scripts/test/generate_plugin_event_reference.py",
        fileTree("plugins") {
            include("*/src/main/resources/META-INF/turboism/plugin.json")
        }
    )
    outputs.file(pluginEventReferenceReport)
    commandLine(
        "python3",
        "scripts/test/generate_plugin_event_reference.py",
        "--root",
        rootDir.absolutePath,
        "--output",
        pluginEventReferenceReport.get().asFile.absolutePath
    )
}

val checkPluginEventReference by tasks.registering {
    group = "verification"
    description = "Proves the first-party plugin public-event report generator is deterministic."
    dependsOn(generatePluginEventReference)
    val verificationReport = layout.buildDirectory.file(
        "tmp/plugin-event-reference-check/plugin-public-events.md"
    )
    inputs.files(
        "scripts/plugin_event_metadata.py",
        "scripts/test/generate_plugin_event_reference.py",
        fileTree("plugins") {
            include("*/src/main/resources/META-INF/turboism/plugin.json")
        }
    )
    inputs.file(pluginEventReferenceReport)
    outputs.file(verificationReport)
    outputs.cacheIf { true }
    // Plain values only: the action must not capture the Project or the script scope, and a nested
    // Gradle exec would. A plain process keeps the action free of build state.
    val repositoryRoot = rootDir.absolutePath
    val generatedReport = pluginEventReferenceReport.get().asFile
    doLast {
        val report = verificationReport.get().asFile
        report.parentFile.mkdirs()
        val generator = ProcessBuilder(
            "python3",
            "scripts/test/generate_plugin_event_reference.py",
            "--root", repositoryRoot,
            "--output", report.absolutePath
        ).directory(File(repositoryRoot)).redirectErrorStream(true).start()
        val output = generator.inputStream.bufferedReader().use { it.readText() }
        if (generator.waitFor() != 0) {
            throw GradleException("plugin event reference generation failed:\n$output")
        }
        if (!generatedReport.readBytes().contentEquals(report.readBytes())) {
            throw GradleException("plugin event reference generation is not deterministic")
        }
    }
}

val checkMarketReleaseMetadata by tasks.registering(Exec::class) {
    group = "release verification"
    description = "Verifies schema-v4 plugin event metadata in market release sidecars."
    workingDir(rootDir)
    inputs.files(
        "scripts/plugin_event_metadata.py",
        "scripts/release/prepare-market-release.py",
        "scripts/test/test_prepare_market_release.py",
        "packaging/release-plugins.txt",
        "packaging/market-plugins.txt",
        "packaging/market-plugins.json",
        ".github/workflows/publish-selected-plugins.yml"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_prepare_market_release.py")
}

val checkInstallerPluginSections by tasks.registering(Exec::class) {
    group = "verification"
    description = "Byte-compares packaging/windows-installer/plugin-sections.nsh with a fresh regeneration."
    workingDir(rootDir)
    inputs.files(
        "packaging/windows-installer/check_plugin_sections.py",
        "packaging/windows-installer/assemble-release.sh",
        "packaging/windows-installer/plugin-sections.nsh",
        "packaging/release-plugins.txt",
        "packaging/windows-installer/script-engine.json",
        "gradle/common-java.gradle.kts"
    )
    inputs.files(
        fileTree("plugins") {
            include("*/src/main/resources/META-INF/turboism/plugin.json")
            include("*/src/main/resources/META-INF/turboism/i18n/messages_*.properties")
            include("README.md", "README_zh.md", "README_ja.md", "README_ko.md")
        }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "packaging/windows-installer/check_plugin_sections.py")
}

/*
 * The four host probes share one fail-closed terminal rule (PASS/FAIL/BLOCKED/SKIPPED
 * plus zero-assertion rejection). The shared self check compiles and runs that rule
 * offline, so a probe-side regression cannot reach the compatibility matrix unnoticed.
 * Inputs cover the shared sources, every probe that consumes them, and the evidence
 * parser that interprets their output; a change in any of them re-runs the check
 * instead of reporting an up-to-date success.
 */
tasks.register<Exec>("checkExternalPluginTemplate") {
    group = "verification"
    description = "Builds a minimal external-consumer plugin fixture and validates the plugin JAR through production contract tools."
    dependsOn(":sdk:jar", ":event-processor:jar", ":runtime:classes")
    workingDir(rootDir)
    inputs.files("scripts/test/check_external_plugin_template.py")
    // Every path is a plain provider resolved from the owning task, so no input lambda captures
    // the script scope. The configuration cache stores the providers, not a closure over Project.
    val sdkJar = project(":sdk").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val eventProcessorJar = project(":event-processor").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtimeClasspath = project(":runtime").extensions.getByType<SourceSetContainer>()
        .named("main").map { it.runtimeClasspath.asPath }
    val runtimeMainClasspath = project(":runtime").extensions.getByType<SourceSetContainer>()
        .named("main").map { set -> set.runtimeClasspath.filter { !it.path.endsWith("/resources/main") } }
    val sdkApiVersion = (rootProject.extra["turboismBuildMetadata"] as Map<*, *>)["apiVersion"].toString()
    inputs.file(sdkJar).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
    inputs.file(eventProcessorJar).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
    inputs.files(runtimeMainClasspath).withNormalizer(org.gradle.api.tasks.ClasspathNormalizer::class)
    inputs.property("sdkVersion", sdkApiVersion)
    inputs.property("turboismCodeQualityJavaHome", codeQualityJavaHome)
    VerificationStamps.apply(this)
    // The action captures plain values only; `file(...)` would capture the script scope.
    val repositoryRoot = rootDir.absolutePath
    val workDirectory = layout.buildDirectory.dir("tmp/external-plugin-template/check")
    val sharedGradleHome = gradle.gradleUserHomeDir.absolutePath
    val javaExecutable = codeQualityJavaHome.map { home -> File(home, "bin/java").absolutePath }
    doFirst {
        commandLine(
            "python3", "scripts/test/check_external_plugin_template.py", "check",
            "--repo-root", repositoryRoot,
            "--sdk-jar", sdkJar.get().asFile.absolutePath,
            "--event-processor-jar", eventProcessorJar.get().asFile.absolutePath,
            "--sdk-version", sdkApiVersion,
            "--runtime-classpath", runtimeClasspath.get(),
            "--java", javaExecutable.get(),
            "--work-dir", workDirectory.get().asFile.absolutePath,
            "--shared-gradle-home", sharedGradleHome
        )
    }
}

