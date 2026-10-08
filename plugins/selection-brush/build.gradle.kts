import org.gradle.jvm.tasks.Jar
import java.util.jar.JarFile

plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    testImplementation(project(":sdk"))
}

val verifySelectionBrushJar by tasks.registering {
    group = "verification"
    description = "Checks the official plugin JAR contains only its classes and declared metadata/resources."
    val pluginJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(pluginJar)
    inputs.file(pluginJar)
    doLast {
        val required = setOf(
            "META-INF/turboism/plugin.json",
            "META-INF/turboism/i18n/baseline-keys.txt",
            "META-INF/turboism/i18n/messages.properties",
            "META-INF/turboism/i18n/messages_en.properties",
            "META-INF/turboism/i18n/messages_ja.properties",
            "META-INF/turboism/i18n/messages_ko.properties",
            "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "META-INF/turboism/i18n/messages_zh_Hant.properties",
            "icons/selection-brush.png",
            "icons/selection-brush-active.png",
            "icons/selection-brush-rollover.png",
            "icons/selection-brush-selected.png",
            "icons/selection-brush-disabled.png",
            "icons/selection-brush-disabled-selected.png"
        )
        JarFile(pluginJar.get().asFile).use { jar ->
            val entries = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            check(entries.containsAll(required)) { "Selection Brush JAR is missing: ${required - entries}" }
            val classes = entries.filter { it.endsWith(".class") }
            check(classes.isNotEmpty()) { "Selection Brush JAR contains no plugin classes" }
            check(classes.all { it.startsWith("dev/turboism/plugin/selectionbrush/") }) {
                "Selection Brush JAR contains non-plugin classes: ${classes.filterNot { it.startsWith("dev/turboism/plugin/selectionbrush/") }}"
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifySelectionBrushJar)
}
