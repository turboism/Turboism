package dev.turboism.preview;

import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Discovers valid preview plugin candidates in deterministic filename order. */
final class PreviewPluginDiscovery {

    private static final String DESCRIPTOR_PATH = "META-INF/turboism/plugin.json";
    private static final PluginVersion TURBOISM_API_VERSION = PluginVersion.parse("0.1.0");

    private final Path pluginDirectory;
    private final PreviewLog log;

    PreviewPluginDiscovery(final Path pluginDirectory, final PreviewLog log) {
        this.pluginDirectory = pluginDirectory;
        this.log = log;
    }

    Map<String, PreviewPluginCandidate> discover(
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        final Map<String, PreviewPluginCandidate> candidates = new LinkedHashMap<>();
        try {
            for (Path jar : jarFiles()) {
                addCandidate(candidates, jar, failures);
            }
        } catch (IOException exception) {
            failures.add(new LocalPluginRuntime.PluginFailure(
                "<discovery>", pluginDirectory, "PLUGIN_DIRECTORY_FAILED", exception.getMessage()
            ));
            log.error("plugin-loader", "Plugin discovery failed", exception);
        }
        return candidates;
    }

    private List<Path> jarFiles() throws IOException {
        Files.createDirectories(pluginDirectory);
        try (var entries = Files.list(pluginDirectory)) {
            return entries.filter(Files::isRegularFile)
                .filter(this::isJar)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private boolean isJar(final Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private void addCandidate(
        final Map<String, PreviewPluginCandidate> candidates,
        final Path jar,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        final PreviewPluginCandidate candidate = readCandidate(jar, failures);
        if (candidate == null) {
            return;
        }
        final PreviewPluginCandidate previous = candidates.putIfAbsent(candidate.descriptor().id(), candidate);
        if (previous != null) {
            failures.add(new LocalPluginRuntime.PluginFailure(
                candidate.descriptor().id(), jar, "DUPLICATE_PLUGIN_ID",
                "Plugin ID already provided by " + previous.jar().getFileName()
            ));
        }
    }

    private PreviewPluginCandidate readCandidate(
        final Path jar,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            final JarEntry entry = archive.getJarEntry(DESCRIPTOR_PATH);
            if (entry == null || entry.isDirectory()) {
                failures.add(new LocalPluginRuntime.PluginFailure(
                    "<unknown>", jar, "PLUGIN_DESCRIPTOR_MISSING", DESCRIPTOR_PATH + " is required"
                ));
                return null;
            }
            return parseCandidate(archive, entry, jar, failures);
        } catch (DescriptorParseException exception) {
            failures.add(new LocalPluginRuntime.PluginFailure(
                "<invalid>", jar, exception.code(), exception.getMessage()
            ));
        } catch (IOException | RuntimeException exception) {
            failures.add(new LocalPluginRuntime.PluginFailure(
                "<invalid>", jar, "PLUGIN_JAR_READ_FAILED", exception.getMessage()
            ));
        }
        return null;
    }

    private PreviewPluginCandidate parseCandidate(
        final JarFile archive,
        final JarEntry entry,
        final Path jar,
        final List<LocalPluginRuntime.PluginFailure> failures
    ) throws IOException, DescriptorParseException {
        try (InputStream source = archive.getInputStream(entry)) {
            final PluginDescriptor descriptor = new PluginDescriptorParser().parse(source);
            if (!supportsCurrentApi(descriptor)) {
                failures.add(new LocalPluginRuntime.PluginFailure(
                    descriptor.id(), jar, "TURBOISM_API_INCOMPATIBLE",
                    "Plugin requires Turboism API " + descriptor.turboismApi() + ", runtime is 0.1.0"
                ));
                return null;
            }
            return new PreviewPluginCandidate(jar.toAbsolutePath().normalize(), descriptor);
        }
    }

    private static boolean supportsCurrentApi(final PluginDescriptor descriptor) {
        try {
            return VersionRange.parse(descriptor.turboismApi()).contains(TURBOISM_API_VERSION);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}

record PreviewPluginCandidate(Path jar, PluginDescriptor descriptor) {
}
