plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.11"
}

import java.util.jar.JarFile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    implementation(project(":runtime"))
    implementation(project(":sdk"))
    // Framework composition includes only SDK, runtime and internal contracts.
    implementation(project(":core-contract"))
}

tasks.processResources {
    // The record list below is hand-maintained; the root-project gate derives it
    // from compatibility/cubism/verification/ and fails this build on drift.
    dependsOn(":checkVerificationRecordIndex")
    listOf(
        "cubism-5.2.03-project-workspace.json",
        "cubism-5.3.02-project-workspace.json",
        "cubism-5.3.03-project-workspace.json",
        "cubism-5.2.03-core-model-read.json",
        "cubism-5.3.02-core-model-read.json",
        "cubism-5.2.03-editor-model.json",
        "cubism-5.3.02-editor-model.json",
        "cubism-5.3.03-editor-model.json",
        "cubism-5.2.03-ui-main-toolbar.json",
        "cubism-5.3.02-ui-main-toolbar.json",
        "cubism-5.3.03-ui-main-toolbar.json",
        "cubism-5.2.03-ui-embedded-panel.json",
        "cubism-5.3.02-ui-embedded-panel.json",
        "cubism-5.3.03-ui-embedded-panel.json",
        "cubism-5.2.03-ui-top-menu.json",
        "cubism-5.3.02-ui-top-menu.json",
        "cubism-5.3.03-ui-top-menu.json",
        "cubism-5.2.03-ui-bounding-box-overlay.json",
        "cubism-5.3.02-ui-bounding-box-overlay.json",
        "cubism-5.3.03-ui-bounding-box-overlay.json",
        "cubism-5.2.03-ui-status-bar.json",
        "cubism-5.3.02-ui-status-bar.json",
        "cubism-5.3.03-ui-status-bar.json",
        "cubism-5.2.03-clipmask.json",
        "cubism-5.3.02-clipmask.json",
        "cubism-5.3.03-clipmask.json",
        "cubism-5.2.03-performance-render-scene.json",
        "cubism-5.3.02-performance-render-scene.json",
        "cubism-5.3.03-performance-render-scene.json",
        "cubism-5.2.03-ui-control-appearance.json",
        "cubism-5.3.02-ui-control-appearance.json",
        "cubism-5.3.03-ui-control-appearance.json",
        "cubism-5.2.03-workspace-control.json",
        "cubism-5.3.02-workspace-control.json",
        "cubism-5.3.03-workspace-control.json",
        "cubism-5.2.03-autobackup.json",
        "cubism-5.3.02-autobackup.json",
        "cubism-5.3.03-autobackup.json",
        "cubism-5.2.03-protected-export.json",
        "cubism-5.3.02-protected-export.json",
        "cubism-5.3.03-protected-export.json"
    ).forEach { record ->
        from(rootProject.file("compatibility/cubism/verification/$record")) {
            into("META-INF/turboism/verification")
        }
    }
}

val agentRuntimeClasspath = configurations.runtimeClasspath

// Private implementation libraries bundled into the agent fat JAR are relocated
// under dev.turboism.agent.shaded so the Boot-Class-Path entry no longer leaks
// their original package names to the host or plugin classloaders. The mapping
// covers every third-party component on :bootstrap:runtimeClasspath (vavr is a
// guard only; resilience4j 2.x no longer resolves it).
val relocatedLibraryPrefixes = mapOf(
    "com.fasterxml.jackson" to "dev.turboism.agent.shaded.jackson",
    "org.objectweb.asm" to "dev.turboism.agent.shaded.asm",
    "org.slf4j" to "dev.turboism.agent.shaded.slf4j",
    "io.github.resilience4j" to "dev.turboism.agent.shaded.resilience4j",
    "io.vavr" to "dev.turboism.agent.shaded.vavr"
)

fun ShadowJar.relocatePrivateRuntimeLibraries() {
    relocatedLibraryPrefixes.forEach { (source, target) -> relocate(source, target) }
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
}

val performanceProbeCarrierJar by tasks.registering(Jar::class) {
    archiveFileName.set("performance-probe-carrier.jar")
    destinationDirectory.set(layout.buildDirectory.dir("performance-probe-carrier"))
    from(sourceSets.main.get().output) {
        include("dev/turboism/bootstrap/carrier/**")
    }
}

// Canonical relocated producer. The archive stays outside libs/ so the
// turboism-agent*.jar consumers keep a single unambiguous artifact.
val relocatedAgentJar by tasks.named<ShadowJar>("shadowJar") {
    dependsOn(agentRuntimeClasspath, performanceProbeCarrierJar)
    archiveFileName.set("turboism-agent-relocated.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent-relocation"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "dev.turboism.bootstrap.TurboismAgent",
            "Agent-Class" to "dev.turboism.bootstrap.TurboismAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "true",
            "Boot-Class-Path" to "turboism-agent.jar",
            "Implementation-Title" to "Turboism Developer Preview Agent",
            "Implementation-Version" to project.version
        )
    }
    relocatePrivateRuntimeLibraries()
}

val performanceProbeAgentJar by tasks.registering(ShadowJar::class) {
    // Declare all runtimeClasspath producers (incl. :runtime:jar) so the
    // probe agent fat JAR can coexist with previewBundle in one task graph.
    dependsOn(agentRuntimeClasspath, performanceProbeCarrierJar)
    from(sourceSets.main.get().output)
    configurations = listOf(agentRuntimeClasspath.get())
    archiveBaseName.set("turboism-performance-probe-agent")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "dev.turboism.bootstrap.TurboismAgent",
            "Agent-Class" to "dev.turboism.bootstrap.TurboismAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "true",
            "Implementation-Title" to "Turboism Validation Performance Probe Agent",
            "Implementation-Version" to project.version
        )
    }
    relocatePrivateRuntimeLibraries()
}

tasks.jar {
    dependsOn(relocatedAgentJar)
    archiveBaseName.set("turboism-agent")
    archiveFileName.set("turboism-agent.jar")
    // The canonical artifact is a byte copy of the shaded producer: no raw
    // sourceSets.main.output can bypass relocation (Jar keeps its task type so
    // tasks.named<Jar>("jar") consumers and archiveFile resolution are intact).
    inputs.file(relocatedAgentJar.archiveFile)
    val canonicalArchive = archiveFile
    setActions(emptyList())
    doLast {
        relocatedAgentJar.archiveFile.get().asFile.copyTo(
            canonicalArchive.get().asFile,
            overwrite = true
        )
    }
}

// Executable gate on the built bootstrap fat JAR: every component in the
// license/notice matrix must be present under stable META-INF/licenses/ paths.
val checkBootstrapJarLicenses by tasks.registering {
    group = "verification"
    description = "Asserts the built bootstrap fat JAR bundles all required license/notice entries."
    dependsOn(tasks.jar)
    doLast {
        val jar = tasks.jar.get().archiveFile.get().asFile
        val required = listOf(
            "META-INF/licenses/THIRD-PARTY-NOTICES.md",
            "META-INF/licenses/turboism/LICENSE",
            "META-INF/licenses/turboism/NOTICE",
            "META-INF/licenses/jackson/LICENSE",
            "META-INF/licenses/jackson/NOTICE",
            "META-INF/licenses/asm/LICENSE",
            "META-INF/licenses/asm/NOTICE",
            "META-INF/licenses/slf4j/LICENSE",
            "META-INF/licenses/slf4j/NOTICE",
            "META-INF/licenses/resilience4j/LICENSE",
            "META-INF/licenses/resilience4j/NOTICE",
            "META-INF/licenses/vavr/LICENSE",
            "META-INF/licenses/vavr/NOTICE"
        )
        val missing = mutableListOf<String>()
        JarFile(jar).use { archive ->
            for (entry in required) {
                if (archive.getJarEntry(entry) == null) {
                    missing += entry
                }
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException("Bootstrap fat JAR is missing license/notice entries: $missing")
        }
    }
}

// Fixtures proving the relocated agent no longer overrides host/plugin copies of
// the same third-party library names. Each side carries its own
// com.fasterxml.jackson.databind.ObjectMapper with a distinct marker().
sourceSets {
    create("isolationHost") {
        java.srcDir("src/isolationFixtures/host/java")
    }
    create("isolationPlugin") {
        java.srcDir("src/isolationFixtures/plugin/java")
    }
}

val isolationFixturePluginJar = tasks.register("isolationFixturePluginJar", Jar::class) {
    archiveFileName.set("isolation-fixture-plugin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("isolation-fixtures"))
    from(sourceSets["isolationPlugin"].output)
}

val bootstrapDependencyIsolationHome = layout.buildDirectory.dir("isolation-probe/home")

val checkBootstrapJarDependencyIsolation by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the built agent JAR dependency-isolation probe in a synthetic child JVM."
    dependsOn(
        tasks.jar,
        isolationFixturePluginJar,
        performanceProbeAgentJar,
        tasks.named("testClasses"),
        tasks.named("compileIsolationHostJava")
    )
    classpath(sourceSets.test.get().output, sourceSets["isolationHost"].output)
    mainClass.set("dev.turboism.bootstrap.BootstrapDependencyIsolationMain")
    doFirst {
        val home = bootstrapDependencyIsolationHome.get().asFile
        home.mkdirs()
        home.resolve("config.json").writeText(
            "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1," +
                "\"worktreeId\":\"isolation-probe\",\"safeMode\":true}\n"
        )
        val agent = tasks.jar.get().archiveFile.get().asFile
        jvmArgs(
            "-Djava.awt.headless=true",
            "-javaagent:${agent.absolutePath}=home=${home.absolutePath};hostClass=missing.Host;timeoutSeconds=1"
        )
        args(
            agent.absolutePath,
            isolationFixturePluginJar.get().archiveFile.get().asFile.absolutePath,
            home.absolutePath,
            performanceProbeAgentJar.get().archiveFile.get().asFile.absolutePath,
            relocatedAgentJar.archiveFile.get().asFile.absolutePath
        )
    }
}

tasks.jar {
    finalizedBy(checkBootstrapJarLicenses)
}
