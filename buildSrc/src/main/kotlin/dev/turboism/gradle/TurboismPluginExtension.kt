package dev.turboism.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import javax.inject.Inject

open class TurboismPluginExtension @Inject constructor(private val project: Project) {

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
        const val SUBSCRIBER_CATALOG_GATE = "checkGeneratedSubscriberCatalogs"
    }
}
