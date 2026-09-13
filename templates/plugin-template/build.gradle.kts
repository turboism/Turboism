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

// SDK resolution: if libs/ holds a turboism-sdk-*.jar downloaded from a GitHub
// Release it is used as-is; otherwise the dev.turboism:sdk coordinate is resolved
// from mavenLocal() (published from the Turboism repository).
val sdkJars = fileTree("libs") { include("turboism-sdk-*.jar") }
val turboismSdkVersion = providers.gradleProperty("turboismSdkVersion").orNull

dependencies {
    if (sdkJars.isEmpty) {
        if (turboismSdkVersion.isNullOrBlank()) {
            throw GradleException(
                "No Turboism SDK found. Drop turboism-sdk-<version>.jar into libs/ " +
                    "or set turboismSdkVersion in gradle.properties after running " +
                    "`./gradlew :sdk:publishToMavenLocal` in the Turboism repository."
            )
        }
        compileOnly("dev.turboism:sdk:$turboismSdkVersion")
        // Optional: enables the @SubscribeEvent annotation processor, which
        // generates the subscriber catalog the runtime scans. Publish it with
        // `./gradlew :event-processor:publishToMavenLocal` in the Turboism repo.
        // annotationProcessor("dev.turboism:event-processor:$turboismSdkVersion")
    } else {
        compileOnly(sdkJars)
    }
}

tasks.jar {
    archiveBaseName.set("hello-turboism-plugin")
}
