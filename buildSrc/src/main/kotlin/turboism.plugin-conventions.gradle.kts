import dev.turboism.gradle.TurboismPluginExtension
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("turboism.java-conventions")
}

extensions.create("turboismPlugin", TurboismPluginExtension::class.java, project)

tasks.named<ProcessResources>("processResources") {
    from(layout.projectDirectory.dir(".")) {
        include("README.md", "README_zh.md", "README_ja.md")
        into("META-INF/turboism/readme")
    }
}

dependencies {
    add("compileOnly", project(":sdk"))
    add("annotationProcessor", project(":event-processor"))
    add("testImplementation", project(":sdk"))
    add("testImplementation", project(":testing:test-support"))
}
