plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    testImplementation(project(":sdk"))
}

tasks.processResources {
    from("src/main/resources/META-INF/turboism/plugin.json") {
        into("META-INF/turboism")
        rename { "core-plugin.json" }
    }
}
