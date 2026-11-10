plugins {
    id("turboism.plugin-conventions")
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("mi3d.repo", rootProject.projectDir.absolutePath)
    val requireFixtures = providers.gradleProperty("turboism.requireImport3dFixtures")
        .orElse(providers.environmentVariable("TURBOISM_REQUIRE_IMPORT_3D_FIXTURES"))
    if (requireFixtures.isPresent) {
        systemProperty("turboism.requireImport3dFixtures", requireFixtures.get())
    }
    providers.environmentVariable("TURBOISM_IMPORT_3D_ARTIFACTS").orNull?.let {
        systemProperty("turboism.import3d.artifacts", it)
    }
    providers.environmentVariable("TURBOISM_IMPORT_3D_FIXTURES").orNull?.let {
        systemProperty("turboism.import3d.fixtures", it)
    }
    providers.environmentVariable("TURBOISM_IMPORT_3D_INPUTS").orNull?.let {
        systemProperty("turboism.import3d.inputs", it)
    }
}
