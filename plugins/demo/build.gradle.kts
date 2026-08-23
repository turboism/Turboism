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
    group = "verification"
    description = "Verifies the packaged demo annotation subscriber catalog."
    dependsOn(tasks.named("jar"))
    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        ZipFile(jarFile).use { archive ->
            val servicePath =
                "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog"
            val catalogPath =
                "dev/turboism/plugin/demo/DemoPlugin__TurboismSubscriberCatalog.class"
            val service = requireNotNull(archive.getEntry(servicePath)) {
                "Missing generated subscriber service metadata: $servicePath"
            }
            requireNotNull(archive.getEntry(catalogPath)) {
                "Missing generated subscriber catalog class: $catalogPath"
            }
            val providers = archive.getInputStream(service).bufferedReader().use { reader ->
                reader.readLines().filter(String::isNotBlank)
            }
            check(providers == listOf(
                "dev.turboism.plugin.demo.DemoPlugin__TurboismSubscriberCatalog"
            )) {
                "Unexpected generated subscriber service providers: $providers"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyGeneratedSubscriberCatalog)
}
