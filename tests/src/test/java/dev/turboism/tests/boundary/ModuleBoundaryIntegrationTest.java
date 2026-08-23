package dev.turboism.tests.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModuleBoundaryIntegrationTest {

    private static final List<String> FORBIDDEN_CLASS_REFERENCES = List.of(
        "dev/turboism/core/",
        "dev/turboism/internal/",
        "dev/turboism/hook/",
        "dev/turboism/mapping/",
        "dev/turboism/adapter/",
        "com/live2d/"
    );

    @Test
    void pluginCompiledClassesDoNotReferenceRuntimeInternalOrCubismPackages() throws IOException {
        // Given
        Path pluginClasses = projectBuildRoot().resolve("classes/java/main");

        // When
        List<Path> classFiles;
        try (var stream = Files.walk(pluginClasses)) {
            classFiles = stream.filter(path -> path.toString().endsWith(".class")).toList();
        }

        // Then
        for (Path classFile : classFiles) {
            String constantPoolBytes = HexFormat.of().formatHex(Files.readAllBytes(classFile));
            for (String forbidden : FORBIDDEN_CLASS_REFERENCES) {
                String forbiddenHex = HexFormat.of().formatHex(forbidden.getBytes(StandardCharsets.UTF_8));
                assertFalse(
                    constantPoolBytes.contains(forbiddenHex),
                    () -> classFile + " references forbidden package " + forbidden
                );
            }
        }
    }

    @Test
    void pluginGradleDependenciesAreSdkOnlyCompileOnly() throws IOException {
        // Given
        Path buildFile = projectRoot().resolve("plugins/demo/build.gradle.kts");

        // When
        List<String> dependencyLines = Files.readAllLines(buildFile).stream()
            .map(String::trim)
            .filter(line -> line.contains("project(\":"))
            .toList();

        // Then
        assertEquals(List.of(
            "compileOnly(project(\":sdk\"))",
            "testImplementation(project(\":sdk\"))"
        ), dependencyLines);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("projectRoot"));
    }

    private static Path projectBuildRoot() {
        return Path.of(System.getProperty("demoBuildDir"));
    }
}
