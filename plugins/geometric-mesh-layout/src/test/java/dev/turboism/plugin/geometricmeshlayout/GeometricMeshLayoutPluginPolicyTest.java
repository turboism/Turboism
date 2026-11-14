package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GeometricMeshLayoutPluginPolicyTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/dev/turboism/plugin/geometricmeshlayout");

    @Test
    void thePluginOwnedDialogIsTheOnlySourceThatUsesSwing() throws Exception {
        final List<String> swingSources = new ArrayList<>();
        for (Path source : productionSources()) {
            final String text = Files.readString(source);
            if (text.contains("java.awt") || text.contains("javax.swing")) {
                swingSources.add(source.getFileName().toString());
            }
        }

        // The canvas overlay, projection and commit stay runtime-owned; only the plugin's own
        // configuration window is a plugin-owned JDK window (the TurboismWindowFactory exception).
        assertEquals(List.of("GeometricMeshLayoutDialog.java"), swingSources);
    }

    private static List<Path> productionSources() throws Exception {
        try (Stream<Path> sources = Files.list(SOURCE_ROOT)) {
            return sources.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
