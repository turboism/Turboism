package dev.turboism.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class VerifyGeneratedSubscriberCatalog : DefaultTask() {

    @get:InputFile
    abstract val jarFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val expectedCatalogClass: Property<String>

    init {
        group = "verification"
        description = "Verifies the generated subscriber catalog metadata and class in the plugin JAR."
    }

    @TaskAction
    fun verify() {
        val jar = jarFile.get().asFile
        ZipFile(jar).use { archive ->
            val servicePath = "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog"
            val serviceEntry = requireNotNull(archive.getEntry(servicePath)) {
                "${project.name} JAR is missing generated subscriber service metadata: $servicePath"
            }
            val providers = archive.getInputStream(serviceEntry).bufferedReader().use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            check(providers.isNotEmpty()) {
                "${project.name} JAR has empty subscriber service metadata"
            }
            if (expectedCatalogClass.isPresent) {
                val expected = expectedCatalogClass.get()
                check(providers.contains(expected)) {
                    "Unexpected generated subscriber service providers in ${project.name}: $providers (expected $expected)"
                }
                val catalogPath = expected.replace('.', '/') + ".class"
                requireNotNull(archive.getEntry(catalogPath)) {
                    "${project.name} JAR is missing its generated subscriber catalog class: $catalogPath"
                }
            } else {
                for (provider in providers) {
                    val catalogPath = provider.replace('.', '/') + ".class"
                    requireNotNull(archive.getEntry(catalogPath)) {
                        "${project.name} JAR is missing its generated subscriber catalog class: $catalogPath"
                    }
                }
            }
        }
    }
}
