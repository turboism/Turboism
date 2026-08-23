import java.util.zip.ZipFile

plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    annotationProcessor(project(":event-processor"))
    testImplementation(project(":sdk"))
}

val verifyGeneratedSubscriberCatalog by tasks.registering {
    dependsOn(tasks.named("jar"))
    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        ZipFile(jarFile).use { archive ->
            check(archive.getEntry(
                "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog"
            ) != null) {
                "Scene Palette Enhancer JAR is missing generated subscriber service metadata"
            }
            check(archive.getEntry(
                "dev/turboism/plugin/scenepalette/" +
                    "ScenePaletteEnhancerPlugin__TurboismSubscriberCatalog.class"
            ) != null) {
                "Scene Palette Enhancer JAR is missing its generated subscriber catalog"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyGeneratedSubscriberCatalog)
}
