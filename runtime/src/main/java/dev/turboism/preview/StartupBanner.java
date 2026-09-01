package dev.turboism.preview;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Renders and publishes the single authoritative runtime-start banner. */
final class StartupBanner {
    private static final String VERSION_RESOURCE =
        "/META-INF/turboism/framework-version.properties";

    private final AtomicBoolean published = new AtomicBoolean(false);

    void publish(final List<Consumer<String>> sinks, final Details details) {
        final List<Consumer<String>> targets = List.copyOf(
            Objects.requireNonNull(sinks, "sinks")
        );
        if (targets.isEmpty()) throw new IllegalArgumentException("sinks must not be empty");
        Objects.requireNonNull(details, "details");
        if (published.compareAndSet(false, true)) {
            final String rendered = render(details);
            targets.forEach(sink -> sink.accept(rendered));
        }
    }

    static String render(final Details details) {
        Objects.requireNonNull(details, "details");
        return """
             _____ _   _ ____  ____   ___ ___ ____  __  __
            |_   _| | | |  _ \\| __ ) / _ \\_ _/ ___||  \\/  |
              | | | | | | |_) |  _ \\| | | | |\\___ \\| |\\/| |
              | | | |_| |  _ <| |_) | |_| | | ___) | |  | |
              |_|  \\___/|_| \\_\\____/ \\___/___|____/|_|  |_|

                              For you, a bouquet.

              Cubism Extensibility Framework
              ------------------------------------------------
              Version   : %s
              Java      : %s
              GraalVM   : %s
              Cubism    : %s
              Plugins   : %d
              GraalJS   : %s

              [Turboism] Runtime initialized.
            """.formatted(
                details.version(),
                details.javaVersion(),
                details.graalVm(),
                details.cubismVersion(),
                details.pluginCount(),
                details.graalJs()
            ).stripTrailing();
    }

    static String frameworkVersion() {
        try (InputStream stream = StartupBanner.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (stream == null) return "unknown";
            final Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty("version", "unknown");
        } catch (IOException unavailable) {
            return "unknown";
        }
    }

    record Details(
        String version,
        String javaVersion,
        String graalVm,
        String cubismVersion,
        int pluginCount,
        String graalJs
    ) {
        Details {
            version = text(version);
            javaVersion = text(javaVersion);
            graalVm = text(graalVm);
            cubismVersion = text(cubismVersion);
            graalJs = text(graalJs);
            if (pluginCount < 0) throw new IllegalArgumentException("pluginCount must not be negative");
        }

        private static String text(final String value) {
            final String normalized = Objects.requireNonNullElse(value, "unavailable").trim();
            return normalized.isEmpty() ? "unavailable" : normalized;
        }
    }
}
