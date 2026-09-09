plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    annotationProcessor(project(":event-processor"))
    testImplementation(project(":sdk"))
}

@Suppress("UNCHECKED_CAST")
val buildMetadata = rootProject.extra["turboismBuildMetadata"] as Map<String, String>
val turboismFrameworkVersion = buildMetadata.getValue("version")

val frameworkVersionResource = layout.buildDirectory.file(
    "generated/resources/turboism-framework-version/framework-version.properties"
)

val generateFrameworkVersionResource by tasks.registering {
    group = "build"
    description = "Generates the framework version resource packaged into the core plugin."
    inputs.properties(buildMetadata)
    outputs.file(frameworkVersionResource)
    doLast {
        val file = frameworkVersionResource.get().asFile
        file.parentFile.mkdirs()
        file.writeText(buildMetadata.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }
}

tasks.processResources {
    dependsOn(generateFrameworkVersionResource)
    from("src/main/resources/META-INF/turboism/plugin.json") {
        into("META-INF/turboism")
        rename { "core-plugin.json" }
    }
    from(frameworkVersionResource) {
        into("META-INF/turboism")
    }
}

tasks.test {
    systemProperty("turboism.expectedFrameworkVersion", turboismFrameworkVersion)
}
