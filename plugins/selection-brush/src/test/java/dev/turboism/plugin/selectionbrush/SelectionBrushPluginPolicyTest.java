package dev.turboism.plugin.selectionbrush;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SelectionBrushPluginPolicyTest {
    @Test
    void productionSourceIsSdkOnlyAndGradleHasNoRuntimeDependency() throws Exception {
        String source =
                Files.readString(Path.of("src/main/java/dev/turboism/plugin/selectionbrush/SelectionBrushPlugin.java"));
        for (String forbidden : new String[] {
            "java.awt", "javax.swing", "java.lang.reflect", "dev.turboism.runtime",
            "dev.turboism.mapping", "VerifiedMemberResolver", "ClassLoader", "getDeclared"
        }) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("dev.turboism.sdk."));

        String gradle = Files.readString(Path.of("build.gradle.kts"));
        assertTrue(gradle.contains("id(\"turboism.plugin-conventions\")"));
        assertFalse(gradle.contains("project(\":runtime\")"));
        assertFalse(gradle.contains("implementation(project"));

        String conventions =
                Files.readString(Path.of("../../buildSrc/src/main/kotlin/turboism.plugin-conventions.gradle.kts"));
        assertTrue(conventions.contains("add(\"compileOnly\", project(\":sdk\"))"));
        assertFalse(conventions.contains("project(\":runtime\")"));
        assertFalse(conventions.contains("\"implementation\""));
    }
}
