import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

val sdkJavadocTask = project(":sdk").tasks.named<Javadoc>("javadoc")
val sdkDocsDirectory = layout.buildDirectory.dir("release/sdk-docs/site")
val sdkDocsMetadata = layout.buildDirectory.file("release/sdk-docs/metadata.json")
val sdkDocsBundleDirectory = layout.buildDirectory.dir("release/sdk-docs-bundle")
val frameworkVersion = provider {
    rootProject.extra["turboismFrameworkVersion"] as String
}
val sourceRevision = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir(rootProject.layout.projectDirectory)
}.standardOutput.asText.map { output -> output.trim() }

sdkJavadocTask.configure {
    (options as StandardJavadocDocletOptions).noTimestamp(true)
}

val sdkDocs by tasks.registering {
    group = "documentation"
    description = "Generates the public SDK Javadoc from the current source checkout."
    dependsOn(sdkJavadocTask)
}

val stageSdkDocs by tasks.registering(Sync::class) {
    group = "documentation"
    description = "Stages SDK Javadoc and immutable source metadata for publication handoff."
    dependsOn(sdkDocs)
    from(sdkJavadocTask.map { task -> task.destinationDir!! })
    into(sdkDocsDirectory)
    inputs.property("frameworkVersion", frameworkVersion)
    inputs.property("sourceRevision", sourceRevision)
    outputs.file(sdkDocsMetadata)
    doLast {
        val revision = sourceRevision.get()
        if (!revision.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("SDK docs source revision is not a full Git commit SHA: $revision")
        }
        val metadata = sdkDocsMetadata.get().asFile
        metadata.parentFile.mkdirs()
        metadata.writeText(
            """{"repository":"turboism/Turboism","schemaVersion":1,"sourceRevision":"$revision","version":"${frameworkVersion.get()}"}""" + "\n"
        )
    }
}

val verifySdkDocs by tasks.registering {
    group = "verification"
    description = "Verifies staged SDK Javadoc identity before release handoff."
    dependsOn(stageSdkDocs)
    inputs.dir(sdkDocsDirectory)
    inputs.file(sdkDocsMetadata)
    inputs.property("frameworkVersion", frameworkVersion)
    inputs.property("sourceRevision", sourceRevision)
    doLast {
        val site = sdkDocsDirectory.get().asFile
        val index = site.resolve("index.html")
        val elementList = site.resolve("element-list")
        if (!index.isFile || !elementList.isFile) {
            throw GradleException("Staged SDK Javadoc is incomplete: ${site.absolutePath}")
        }
        val expectedVersion = frameworkVersion.get()
        val html = site.walkTopDown()
            .filter { file -> file.isFile && file.extension == "html" }
            .joinToString("\n") { file -> file.readText() }
        if (!html.contains("dev.turboism.sdk")) {
            throw GradleException("Staged SDK Javadoc does not contain the dev.turboism.sdk package.")
        }
        if (!html.contains("sdk $expectedVersion API")) {
            throw GradleException("Staged SDK Javadoc does not identify itself as sdk $expectedVersion API.")
        }
        if (html.contains("$expectedVersion-SNAPSHOT")) {
            throw GradleException("Staged SDK Javadoc contains a snapshot version.")
        }
        val metadata = sdkDocsMetadata.get().asFile.readText()
        if (!metadata.contains("\"sourceRevision\":\"${sourceRevision.get()}\"") ||
            !metadata.contains("\"version\":\"$expectedVersion\"")) {
            throw GradleException("Staged SDK Javadoc metadata does not match the source checkout.")
        }
    }
}

val sdkDocsBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the immutable SDK documentation handoff bundle."
    dependsOn(verifySdkDocs)
    from(layout.buildDirectory.dir("release/sdk-docs"))
    archiveFileName.set(frameworkVersion.map { version -> "turboism-sdk-docs-$version.zip" })
    destinationDirectory.set(sdkDocsBundleDirectory)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
