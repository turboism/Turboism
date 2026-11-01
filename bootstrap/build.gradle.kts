plugins {
    id("turboism.java-conventions")
    alias(libs.plugins.shadow)
}

import java.util.jar.JarFile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.turboism.gradle.internal.VerificationStamps
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

dependencies {
    testImplementation(project(":testing:test-support"))
    testImplementation(libs.jackson.databind)
    implementation(project(":runtime"))
    implementation(project(":sdk"))
    // Framework composition includes only SDK, runtime and internal contracts.
    implementation(project(":core-contract"))
}

// Generated framework self-update activation resources. Unconfigured (the default) means unactivated: no policy
// and no keyring are bundled, and any previously copied activation resource is purged by processResources.
val frameworkUpdateResourceNames = listOf(
    "framework-update-client.json",
    "framework-update-trusted-keys.json"
)

// A relative path is resolved against the repository root, not this project directory, so the value handed to
// Gradle matches what scripts/release/prepare-framework-client.py --output-dir receives from a repo-root shell.
val frameworkUpdateResourcesDir =
    providers.gradleProperty("turboism.frameworkUpdateResources")
        .orElse(providers.environmentVariable("TURBOISM_FRAMEWORK_UPDATE_RESOURCES"))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { rootProject.file(it) }

tasks.named<Test>("test") {
    systemProperty("turboism.test.repositoryRoot", rootProject.projectDir.absolutePath)
    inputs.file(rootProject.file("compatibility/cubism/version-boundaries.json"))
    // Forward optional real-host evidence to the forked test JVM.
    providers.systemProperty("turboism.test.cubismEditorJar").orNull?.let {
        systemProperty("turboism.test.cubismEditorJar", it)
    }
    providers.systemProperty("turboism.cubismRefRoot").orNull?.let {
        systemProperty("turboism.cubismRefRoot", it)
    }
    configure<JacocoTaskExtension> {
        // Reference host classes must retain their original bytecode without instrumentation so offline verification mirrors the raw host jar.
        excludes = (excludes ?: emptyList()) + listOf("com.live2d.*")
    }
}

tasks.processResources {
    dependsOn(":checkReviewedReleases")
    from(rootProject.tasks.named("materializeVerificationRecords")) {
        into("META-INF/turboism/verification")
    }

    // Framework self-update activation (issue #50). The two bundled documents decide whether the updater may run
    // at all, so they are generated outside the tracked tree (scripts/release/prepare-framework-client.py) and
    // enter the agent JAR only through this generated directory. The environment variable name is part of the
    // release contract; the Gradle property is an equivalent explicit override.
    val activationResources = frameworkUpdateResourcesDir
    inputs.property("frameworkUpdateResources", activationResources?.absolutePath ?: "<unconfigured>")
    if (activationResources != null) {
        inputs.dir(activationResources)
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("frameworkUpdateResourcesContent")
        from(activationResources) {
            include(*frameworkUpdateResourceNames.toTypedArray())
            into("META-INF/turboism")
        }
    }
    doFirst {
        val configured = frameworkUpdateResourcesDir
        if (configured != null) {
            if (!configured.isDirectory) {
                throw GradleException(
                    "TURBOISM_FRAMEWORK_UPDATE_RESOURCES is not a directory: ${configured.absolutePath}"
                )
            }
            val missing = frameworkUpdateResourceNames.filterNot { configured.resolve(it).isFile }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "TURBOISM_FRAMEWORK_UPDATE_RESOURCES is missing generated activation resources $missing; " +
                        "run scripts/release/prepare-framework-client.py first"
                )
            }
        }
        // Never ship a stale activation resource: purge previous copies before the copy action runs, so switching
        // back to an unconfigured build (or to another generated directory) cannot leave old policy/keys in the
        // agent JAR. Only these two generated entries are ever removed; tracked resources are untouched.
        frameworkUpdateResourceNames.forEach { delete(destinationDir.resolve("META-INF/turboism/$it")) }
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
    finalizedBy("checkBootstrapJarLicenses")
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

val bootstrapIsolationStamp = layout.buildDirectory.file("verification-stamps/checkBootstrapJarDependencyIsolation.stamp")

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
    // The jars enter through args (doFirst), so their bytes must be inputs for
    // up-to-date tracking; the stamp is the persistent output proving a pass.
    inputs.files(
        tasks.jar.flatMap { it.archiveFile },
        isolationFixturePluginJar.flatMap { it.archiveFile },
        performanceProbeAgentJar.flatMap { it.archiveFile },
        relocatedAgentJar.archiveFile
    )
    outputs.file(bootstrapIsolationStamp)
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
    doLast {
        bootstrapIsolationStamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

// Real built-agent premain evidence for the framework launch lease (issue #50): a child JVM started with
// -javaagent:<turboism-agent.jar> runs the production premain handshake against a real helper JVM. The probe drives
// prepare -> LAUNCHING -> premain USING -> refusal while the JVM lives -> reap after it really exits, plus the
// fail-closed rejects (missing LAUNCHING, foreign token). It uses the local worktree JAR (local/dirty, unnumbered)
// and never starts Cubism, an installed home or a signed update.
val checkBootstrapLaunchLeasePremain by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the built agent through a real -javaagent launch-lease cycle in bounded child JVMs."
    dependsOn(tasks.jar, tasks.named("testClasses"))
    classpath(sourceSets.test.get().runtimeClasspath)
    mainClass.set("dev.turboism.bootstrap.FrameworkLaunchLeasePremainMain")
    // The agent JAR and the fixture bytecode reach the children through system properties, so both are declared
    // inputs: a rebuilt agent must re-run the probe instead of being swallowed as UP-TO-DATE.
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.files(sourceSets.test.get().output)
    doFirst {
        systemProperty("turboism.test.builtAgentJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("turboism.test.premainFixtureClasspath", sourceSets.test.get().output.classesDirs.asPath)
        systemProperty(
            "turboism.test.premainScratch",
            layout.buildDirectory.dir("launch-lease-premain").get().asFile.absolutePath
        )
    }
    VerificationStamps.apply(this)
}
