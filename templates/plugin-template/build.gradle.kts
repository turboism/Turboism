plugins {
    `java-library`
}

group = "dev.example"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()
}

// SDK and annotation-processor resolution:
// - When libs/ holds turboism-sdk-*.jar (placed by scripts/setup.sh or scripts/setup.ps1),
//   it is used directly as compileOnly, and turboism-event-processor-*.jar in libs/
//   is wired as annotationProcessor (with sdkJars for processor contract types).
// - Otherwise, dev.turboism:sdk and dev.turboism:event-processor are resolved from
//   mavenLocal() using turboismSdkVersion in gradle.properties.
val sdkJars = fileTree("libs") { include("turboism-sdk-*.jar") }
val eventProcessorJars = fileTree("libs") { include("turboism-event-processor-*.jar") }
val turboismSdkVersion = providers.gradleProperty("turboismSdkVersion").orNull

dependencies {
    if (sdkJars.isEmpty) {
        if (turboismSdkVersion.isNullOrBlank()) {
            throw GradleException(
                "No Turboism SDK found. Run ./scripts/setup.sh (or .\\scripts\\setup.ps1) " +
                    "to download the SDK into libs/, or set turboismSdkVersion in " +
                    "gradle.properties after running " +
                    "`./gradlew :sdk:publishToMavenLocal :event-processor:publishToMavenLocal` " +
                    "in the Turboism repository."
            )
        }
        compileOnly("dev.turboism:sdk:$turboismSdkVersion")
        if (eventProcessorJars.isEmpty) {
            annotationProcessor("dev.turboism:event-processor:$turboismSdkVersion")
        } else {
            annotationProcessor(eventProcessorJars)
            annotationProcessor("dev.turboism:sdk:$turboismSdkVersion")
        }
    } else {
        compileOnly(sdkJars)
        if (!eventProcessorJars.isEmpty) {
            annotationProcessor(eventProcessorJars)
            annotationProcessor(sdkJars)
        }
    }
}

tasks.jar {
    archiveBaseName.set("hello-turboism-plugin")
}
