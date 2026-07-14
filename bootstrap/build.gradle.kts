plugins {
    `java-library`
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":sdk"))
}

tasks.processResources {
    from(rootProject.file("docs/migration/verification/static/cubism-5.3.02-project-workspace.json")) {
        into("META-INF/turboism/verification")
        rename { "cubism-5.3.02-project-workspace.json" }
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
            "Can-Retransform-Classes" to "false",
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
