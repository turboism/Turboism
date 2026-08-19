import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    `java-library`
}

val generatedCoreCatalogRoot = layout.buildDirectory.dir(
    "generated/sources/cubism-core-catalog/java/main"
)
val generatedCoreCatalogFile = generatedCoreCatalogRoot.map {
    it.file(
        "dev/turboism/adapter/cubism/core/GeneratedCorePublicApiCatalog.java"
    )
}
val generatedCoreSelectorContractFile = generatedCoreCatalogRoot.map {
    it.file(
        "dev/turboism/mapping/verification/selector/CorePublicApiSelectorContract.java"
    )
}

val generateCorePublicApiCatalog by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates the complete internal Cubism Core public-member catalog."
    inputs.files(
        rootProject.file("scripts/cubism_core_api.py"),
        rootProject.file("scripts/cubism_core_policy.py"),
        rootProject.file(
            "cubism-ref/core-api/policy/cubism-core-member-policy.json"
        ),
        rootProject.file(
            "cubism-ref/core-api/observed/cubism-core-5.2.json"
        ),
        rootProject.file(
            "cubism-ref/core-api/observed/cubism-core-5.3.02.json"
        )
    )
    outputs.file(generatedCoreCatalogFile)
    doFirst {
        val output = generatedCoreCatalogFile.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            "python3",
            rootProject.file("scripts/cubism_core_policy.py"),
            "render-java",
            "--policy",
            rootProject.file(
                "cubism-ref/core-api/policy/cubism-core-member-policy.json"
            ),
            "--output",
            output,
            "--inventory",
            rootProject.file(
                "cubism-ref/core-api/observed/cubism-core-5.2.json"
            ),
            "--inventory",
            rootProject.file(
                "cubism-ref/core-api/observed/cubism-core-5.3.02.json"
            )
        )
    }
}

val generateCorePublicApiSelectorContract by tasks.registering(Exec::class) {
    group = "build"
    description = "Generates the exact Cubism Core selector/profile contract."
    inputs.files(
        rootProject.file("scripts/cubism_core_api.py"),
        rootProject.file("scripts/cubism_core_selector_policy.py"),
        rootProject.file(
            "cubism-ref/core-api/policy/cubism-core-selector-policy.json"
        ),
        rootProject.file(
            "cubism-ref/mapping-packs/draft/cubism-5.2-core-model-read.json"
        ),
        rootProject.file(
            "cubism-ref/mapping-packs/draft/cubism-5.3.02-core-model-read.json"
        )
    )
    outputs.file(generatedCoreSelectorContractFile)
    doFirst {
        val output = generatedCoreSelectorContractFile.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            "python3",
            rootProject.file("scripts/cubism_core_selector_policy.py"),
            "render-java",
            "--policy",
            rootProject.file(
                "cubism-ref/core-api/policy/cubism-core-selector-policy.json"
            ),
            "--output",
            output,
            "--pack",
            rootProject.file(
                "cubism-ref/mapping-packs/draft/cubism-5.2-core-model-read.json"
            ),
            "--pack",
            rootProject.file(
                "cubism-ref/mapping-packs/draft/cubism-5.3.02-core-model-read.json"
            )
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generatedCoreCatalogRoot)
}

tasks.named("compileJava") {
    dependsOn(
        generateCorePublicApiCatalog,
        generateCorePublicApiSelectorContract
    )
}

val protocolRecordValidationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the focused Distribution Slice 1A protocol-record validation suite."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.distribution.record.*")
        isFailOnNoMatchingTests = true
    }
}

val asyncHostReadFoundationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the runtime async host-read lane, source, and lifecycle contract tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    filter {
        includeTestsMatching("dev.turboism.hostread.*")
        isFailOnNoMatchingTests = true
    }
}

val corePublicApiProviderTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs synthetic Cubism Core admission, lease, call-site, and structural projection tests."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("dev.turboism.adapter.cubism.core.*")
        includeTestsMatching(
            "dev.turboism.mapping.verification.VerifiedMethodCallSiteTest"
        )
        isFailOnNoMatchingTests = true
    }
}

tasks.named<ProcessResources>("processTestResources") {
    from(project(":testframework").file(
        "src/main/resources/fixtures/schema/preview-report-v1"
    )) {
        into("fixtures/schema/preview-report-v1")
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation(project(":plugins:core"))

    // JSON parsing implementation stays in runtime, not in SDK
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.9")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.9")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.9")

    // Runtime-only bytecode metadata scanning for the local draft mapping review pipeline.
    implementation("org.ow2.asm:asm:9.7.1")

    implementation("io.github.resilience4j:resilience4j-bulkhead:2.1.0")
    implementation("io.github.resilience4j:resilience4j-timelimiter:2.1.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.1.0")
}
