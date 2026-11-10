package dev.turboism.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import java.util.jar.JarFile
import javax.inject.Inject

open class TurboismPluginExtension @Inject constructor(private val project: Project) {

    fun verifyJar(taskName: String, requiredEntries: Set<String>, classPrefix: String) {
        val pluginJar = project.tasks.named(JAR_TASK, Jar::class.java).flatMap { it.archiveFile }
        val verifyTask = project.tasks.register(taskName) {
            group = VERIFICATION_GROUP
            description = JAR_VERIFICATION_DESCRIPTION
            dependsOn(pluginJar)
            inputs.file(pluginJar)
            inputs.property(REQUIRED_ENTRIES_INPUT, requiredEntries)
            inputs.property(CLASS_PREFIX_INPUT, classPrefix)
            doLast {
                JarFile(pluginJar.get().asFile).use { jar ->
                    val entries = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
                    check(entries.containsAll(requiredEntries)) { "${project.name} JAR is missing: ${requiredEntries - entries}" }
                    val classes = entries.filter { it.endsWith(CLASS_SUFFIX) }
                    check(classes.isNotEmpty()) { "${project.name} JAR contains no plugin classes" }
                    check(classes.all { it.startsWith(classPrefix) }) {
                        "${project.name} JAR contains non-plugin classes: ${classes.filterNot { it.startsWith(classPrefix) }}"
                    }
                }
            }
        }
        project.tasks.named(CHECK_TASK) {
            dependsOn(verifyTask)
        }
    }

    @JvmOverloads
    fun verifySubscriberCatalog(catalogClass: String? = null) {
        val verifyTask = project.tasks.register(
            "verifyGeneratedSubscriberCatalog",
            VerifyGeneratedSubscriberCatalog::class.java
        ) {
            dependsOn(project.tasks.named("jar"))
            jarFile.set(project.tasks.named("jar", Jar::class.java).flatMap { it.archiveFile })
            if (catalogClass != null) {
                expectedCatalogClass.set(catalogClass)
            }
        }
        project.tasks.named("check") {
            dependsOn(verifyTask)
        }
        // A subproject `check` is never aggregated by the daily or completed-commit
        // gates, so the per-plugin check would only run for a hand-run `:plugins:x:check`.
        // Root aggregation gives devCheck a real dependency on every registered verifier.
        subscriberCatalogGate().configure {
            dependsOn(verifyTask)
        }
    }

    private fun subscriberCatalogGate(): TaskProvider<Task> {
        val root = project.rootProject
        val extras = root.extensions.extraProperties
        val existing = extras.properties[SUBSCRIBER_CATALOG_GATE]
        if (existing is TaskProvider<*>) {
            @Suppress("UNCHECKED_CAST")
            return existing as TaskProvider<Task>
        }
        val created = root.tasks.register(SUBSCRIBER_CATALOG_GATE) {
            group = "verification"
            description = "Verifies each plugin's generated event-subscriber catalog against its packaged JAR."
        }
        extras.set(SUBSCRIBER_CATALOG_GATE, created)
        return created
    }

    private companion object {
        const val JAR_TASK = "jar"
        const val CHECK_TASK = "check"
        const val VERIFICATION_GROUP = "verification"
        const val JAR_VERIFICATION_DESCRIPTION = "Checks the official plugin JAR contains only its classes and declared metadata/resources."
        const val REQUIRED_ENTRIES_INPUT = "requiredEntries"
        const val CLASS_PREFIX_INPUT = "classPrefix"
        const val CLASS_SUFFIX = ".class"
        const val SUBSCRIBER_CATALOG_GATE = "checkGeneratedSubscriberCatalogs"
    }
}
