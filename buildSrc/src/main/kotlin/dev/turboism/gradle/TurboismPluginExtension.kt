package dev.turboism.gradle

import org.gradle.api.Project
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
    }
}
