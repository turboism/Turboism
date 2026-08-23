plugins {
    `java-library`
}

import java.util.jar.JarFile

dependencies {
    implementation(project(":runtime"))
    implementation(project(":sdk"))
}

tasks.processResources {
    listOf(
        "cubism-5.2.03-project-workspace.json",
        "cubism-5.3.02-project-workspace.json",
        "cubism-5.2.03-core-model-read.json",
        "cubism-5.3.02-core-model-read.json",
        "cubism-5.2.03-editor-model.json",
        "cubism-5.3.02-editor-model.json",
        "cubism-5.2.03-ui-main-toolbar.json",
        "cubism-5.3.02-ui-main-toolbar.json",
        "cubism-5.2.03-ui-embedded-panel.json",
        "cubism-5.3.02-ui-embedded-panel.json",
        "cubism-5.2.03-ui-top-menu.json",
        "cubism-5.3.02-ui-top-menu.json",
        "cubism-5.2.03-ui-bounding-box-overlay.json",
        "cubism-5.3.02-ui-bounding-box-overlay.json",
        "cubism-5.2.03-ui-status-bar.json",
        "cubism-5.3.02-ui-status-bar.json",
        "cubism-5.2.03-clipmask.json",
        "cubism-5.3.02-clipmask.json",
        "cubism-5.2.03-performance-render-scene.json",
        "cubism-5.3.02-performance-render-scene.json",
        "cubism-5.2.03-ui-control-appearance.json",
        "cubism-5.3.02-ui-control-appearance.json",
        "cubism-5.2.03-workspace-control.json",
        "cubism-5.3.02-workspace-control.json",
        "cubism-5.2.03-autobackup.json",
        "cubism-5.3.02-autobackup.json"
    ).forEach { record ->
        from(rootProject.file("cubism-ref/verification/$record")) {
            into("META-INF/turboism/verification")
        }
    }
    // Bundle the project-owned built-in themes so the bootstrap can inject the
    // persisted theme appearance before the Cubism GL scene initializes (the
    // off-canvas background color is cached in a singleton Lazy and cannot be
    // refreshed at runtime).
    from(rootProject.file("plugins/ui-theme/src/main/resources/themes")) {
        into("themes")
    }
}

val performanceProbeCarrierJar by tasks.registering(Jar::class) {
    archiveFileName.set("performance-probe-carrier.jar")
    destinationDirectory.set(layout.buildDirectory.dir("performance-probe-carrier"))
    from(sourceSets.main.get().output) {
        include("dev/turboism/bootstrap/carrier/**")
    }
}

val performanceProbeAgentJar by tasks.registering(Jar::class) {
    // Declare all runtimeClasspath producers (incl. :plugins:core:jar) so the
    // probe agent fat JAR can coexist with previewBundle in one task graph.
    dependsOn(configurations.runtimeClasspath, performanceProbeCarrierJar)
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
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath, performanceProbeCarrierJar)
    archiveBaseName.set("turboism-agent")
    archiveFileName.set("turboism-agent.jar")
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
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
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

tasks.jar {
    finalizedBy(checkBootstrapJarLicenses)
}
