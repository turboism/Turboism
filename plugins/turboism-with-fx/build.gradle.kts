plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    testImplementation(project(":sdk"))
}

tasks.test {
    systemProperty(
        "turboism.fxRuntimeFixtureDir",
        rootProject.file("packaging/fx-runtime").absolutePath
    )
}

tasks.processResources {
    from(rootProject.file("packaging/fx-runtime/manifest.properties")) {
        into("META-INF/turboism/fx-runtime")
    }
    from(rootProject.file("packaging/fx-runtime/LICENSE")) {
        into("META-INF/turboism/fx-runtime")
    }
    from(rootProject.file("packaging/fx-runtime/THIRD_PARTY_NOTICES.md")) {
        into("META-INF/turboism/fx-runtime")
    }
    from(rootProject.file("packaging/fx-runtime/TURBOISM-DISTRIBUTION-NOTICE.txt")) {
        into("META-INF/turboism/fx-runtime")
    }
}
