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
        "cubism-5.3.02-ui-top-menu.json"
    ).forEach { record ->
        from(rootProject.file("docs/migration/verification/static/$record")) {
            into("META-INF/turboism/verification")
        }
    }
}

tasks.jar {
    dependsOn(":runtime:jar", ":sdk:jar")
    archiveBaseName.set("turboism-agent")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "dev.turboism.bootstrap.TurboismAgent",
            "Agent-Class" to "dev.turboism.bootstrap.TurboismAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "true",
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
