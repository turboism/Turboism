package dev.turboism.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Locates reviewed Cubism Editor jars for opt-in real-bytecode tests. */
final class ReviewedCubismArtifactTestPaths {

    private static final String ROOT_PROPERTY = "turboism.cubismRefRoot";
    private static final String ROOT_ENVIRONMENT_VARIABLE = "TURBOISM_CUBISM_REF_ROOT";
    private static final Path DEFAULT_ROOT = Path.of("/opt/dev/projects/turboism/cubism-ref");

    private ReviewedCubismArtifactTestPaths() {}

    /**
     * Returns the configured reference root, preferring the test system property over the
     * environment variable and falling back to the historical local path.
     */
    static Path root() {
        final String property = System.getProperty(ROOT_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property).toAbsolutePath().normalize();
        }
        final String environment = System.getenv(ROOT_ENVIRONMENT_VARIABLE);
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment).toAbsolutePath().normalize();
        }
        return DEFAULT_ROOT;
    }

    /**
     * Finds one reviewed Editor jar under either the evidence layout
     * ({@code <root>/<version>/Live2D_Cubism.jar}) or an installed Cubism layout
     * ({@code <root>/Live2D Cubism <version>/app/lib/Live2D_Cubism.jar}).
     */
    static Path jar(final Path root, final String version) {
        final List<Path> candidates = List.of(
                root.resolve(version).resolve("Live2D_Cubism.jar"),
                root.resolve("Cubism-" + version).resolve("jars").resolve("Live2D_Cubism.jar"),
                root.resolve("Live2D Cubism " + version).resolve("app/lib/Live2D_Cubism.jar"),
                root.resolve(version).resolve("app/lib/Live2D_Cubism.jar"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
