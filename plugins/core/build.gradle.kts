plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    annotationProcessor(project(":event-processor"))
    testImplementation(project(":sdk"))
}

val turboismFrameworkVersion = rootProject.extra["turboismFrameworkVersion"] as String

val frameworkVersionResource = layout.buildDirectory.file(
    "generated/resources/turboism-framework-version/framework-version.properties"
)

val generateFrameworkVersionResource by tasks.registering {
    group = "build"
    description = "Generates the framework version resource packaged into the core plugin."
    inputs.property("turboismFrameworkVersion", turboismFrameworkVersion)
    outputs.file(frameworkVersionResource)
    doLast {
        val file = frameworkVersionResource.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=$turboismFrameworkVersion\n")
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
