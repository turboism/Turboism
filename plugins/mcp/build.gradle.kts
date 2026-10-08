plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":sdk"))
    annotationProcessor(project(":event-processor"))
    testImplementation(project(":sdk"))
}

/*
 * The stdio bridge is compiled into the plugin JAR for the credential-free ACP
 * launch descriptor; the same source is also published verbatim (minus the
 * package line, so it stays a default-package single-file program) next to
 * mcp.token for external `java TurboismMcpBridge.java` clients.
 */
tasks.named<ProcessResources>("processResources") {
    from("src/main/java/dev/turboism/plugin/mcp/TurboismMcpBridge.java") {
        into("META-INF/turboism/mcp")
        filter { line: String -> if (line.startsWith("package ")) "" else line }
    }
}
