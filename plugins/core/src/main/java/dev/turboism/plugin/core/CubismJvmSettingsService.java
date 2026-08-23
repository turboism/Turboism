package dev.turboism.plugin.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Runtime-owned launcher preference exposed only to the built-in Core plugin.
 *
 * <p>The setting selects the executable used on the next managed Cubism launch;
 * it cannot replace the JVM of the current Cubism process. Keeping this service
 * outside {@code PluginContext} prevents third-party plugins from changing the
 * host executable.</p>
 */
public interface CubismJvmSettingsService {

    URI GRAALVM_DOWNLOAD_URI = URI.create("https://www.graalvm.org/downloads/");

    CubismJvm read();

    CubismJvm save(CubismJvm value);

    /** Detects the GraalVM executable the managed launcher can use. */
    default Optional<Path> graalVmJava() {
        return Optional.empty();
    }

    default boolean graalVmAvailable() {
        return graalVmJava().isPresent();
    }

    static CubismJvmSettingsService unavailable() {
        return new CubismJvmSettingsService() {
            @Override public CubismJvm read() { return CubismJvm.GRAALVM; }
            @Override public CubismJvm save(final CubismJvm value) {
                throw new IllegalStateException("Cubism JVM settings are unavailable");
            }
        };
    }

    enum CubismJvm {
        GRAALVM("graalvm"),
        BUNDLED("bundled");

        private final String configValue;

        CubismJvm(final String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static CubismJvm fromConfig(final String value) {
            final String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
            for (CubismJvm candidate : values()) {
                if (candidate.configValue.equals(normalized)) return candidate;
            }
            throw new IllegalArgumentException("unsupported Cubism JVM: " + value);
        }
    }
}
