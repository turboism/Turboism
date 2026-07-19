import dev.turboism.gradle.internal.MappingReviewArgsFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("java")
    id("java-library")
}

val defaultWorktreeId = providers.exec {
    commandLine("bash", "scripts/dev/worktree-id.sh")
    workingDir(rootProject.layout.projectDirectory)
}.standardOutput.asText.get().trim().ifBlank { rootProject.layout.projectDirectory.asFile.name }

rootProject.extra["turboismResolvedWorktreeId"] = providers.gradleProperty("turboismWorktreeId")
    .orElse(providers.environmentVariable("TURBOISM_WORKTREE_ID"))
    .orElse(defaultWorktreeId)
    .get()

allprojects {
    repositories {
        mavenCentral()
    }
}

tasks.register("checkPluginInspectionRuntime") {
    group = "verification"
    description = "Runs static plugin inspection gates and the production-backed strict ZIP mutation matrix."
    dependsOn("checkPluginInspectionContract", ":tests:pluginInspectionMutationTest")
}

tasks.register<JavaExec>("mappingReview") {
    group = "verification"
    description = "Run the local draft mapping review CLI; apply is dry-run unless --write is passed."
    dependsOn(":runtime:classes")
    classpath = project(":runtime").extensions.getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("dev.turboism.mapping.draft.MappingReviewCli")
    val cliArgsFile = providers.gradleProperty("turboismMappingReviewArgsFile")
    val legacyCliArgs = providers.gradleProperty("turboismMappingReviewArgs")
    systemProperty("turboism.worktree.id", rootProject.extra["turboismResolvedWorktreeId"] as String)
    doFirst {
        if (legacyCliArgs.isPresent) {
            throw GradleException("-PturboismMappingReviewArgs is unsupported; pass -PturboismMappingReviewArgsFile=<path> instead.")
        }
        if (!cliArgsFile.isPresent || cliArgsFile.get().isBlank()) {
            throw GradleException("Pass -PturboismMappingReviewArgsFile=<path> containing one Base64-encoded UTF-8 argument per line.")
        }
        setArgs(MappingReviewArgsFile.readAndDelete(file(cliArgsFile.get()).toPath()))
    }
}

apply(from = "gradle/common-java.gradle.kts")
apply(from = "gradle/module-boundaries.gradle.kts")
apply(from = "gradle/asm-admission.gradle.kts")
apply(from = "gradle/runtime-verification.gradle.kts")
apply(from = "gradle/sdk-api.gradle.kts")
apply(from = "gradle/distribution-preview.gradle.kts")
apply(from = "gradle/verification.gradle.kts")
