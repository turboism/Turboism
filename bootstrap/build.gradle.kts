plugins {
    `java-library`
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":sdk"))
}

tasks.processResources {
    listOf(
        "cubism-5.2-project-workspace.json",
        "cubism-5.3.02-project-workspace.json",
        "cubism-5.2-core-model-read.json",
        "cubism-5.3.02-core-model-read.json",
        "cubism-5.2-editor-model.json",
        "cubism-5.3.02-editor-model.json",
        "cubism-5.2-ui-main-toolbar.json",
        "cubism-5.3.02-ui-main-toolbar.json",
        "cubism-5.2-ui-embedded-panel.json",
        "cubism-5.3.02-ui-embedded-panel.json",
        "cubism-5.2-ui-top-menu.json",
        "cubism-5.3.02-ui-top-menu.json",
        "cubism-5.2-ui-bounding-box-overlay.json",
        "cubism-5.3.02-ui-bounding-box-overlay.json",
        "cubism-5.2-ui-status-bar.json",
        "cubism-5.3.02-ui-status-bar.json",
        "cubism-5.3.02-clipmask.json",
        "cubism-5.2-ui-control-appearance.json",
        "cubism-5.3.02-ui-control-appearance.json",
        "cubism-5.2-workspace-control.json",
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
    dependsOn(":runtime:jar", ":sdk:jar", ":plugins:core:jar", performanceProbeCarrierJar)
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
