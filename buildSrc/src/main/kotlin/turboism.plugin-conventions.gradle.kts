import dev.turboism.gradle.TurboismPluginExtension
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("turboism.java-conventions")
}

extensions.create("turboismPlugin", TurboismPluginExtension::class.java, project)

tasks.named<ProcessResources>("processResources") {
    // Plugin descriptors require strict X.Y.Z, including local/beta/nightly builds.
    // Use the same framework base version accepted by release market staging.
    val pluginVersion = providers.provider {
        (rootProject.extra["turboismFrameworkVersion"] as String).substringBefore('-')
    }
    inputs.property("pluginVersion", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("META-INF/turboism/plugin.json") {
        var rewritten = false
        val versionField = Regex("""^(\s*"version"\s*:\s*")[^"]+(".*)$""")
        filter { line: String ->
            val field = if (rewritten) null else versionField.matchEntire(line)
            if (field == null) line else {
                rewritten = true
                field.groupValues[1] + pluginVersion.get() + field.groupValues[2]
            }
        }
    }
    from(layout.projectDirectory.dir(".")) {
        include("README.md", "README_zh.md", "README_ja.md", "README_ko.md")
        into("META-INF/turboism/readme")
    }
}

dependencies {
    add("compileOnly", project(":sdk"))
    add("annotationProcessor", project(":event-processor"))
    add("testImplementation", project(":sdk"))
    add("testImplementation", project(":testing:test-support"))
}
