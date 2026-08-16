package dev.turboism.pluginmanagement;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.i18n.LocalizationDiagnosticSink;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarFile;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;
import java.util.Objects;

record PluginArchiveMetadata(
    String id,
    String name,
    String version,
    String description,
    java.util.Optional<String> category,
    java.util.List<String> tags
) {
    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";

    static Optional<PluginArchiveMetadata> read(final Path path) {
        return read(path, Locale.getDefault(Locale.Category.DISPLAY), ignored -> { });
    }

    static Optional<PluginArchiveMetadata> read(
        final Path path,
        final Locale locale,
        final LocalizationDiagnosticSink diagnostics
    ) {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(diagnostics, "diagnostics");
        URLClassLoader loader = null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                return Optional.empty();
            }
            try (JarFile jar = new JarFile(path.toFile())) {
                final var entry = jar.getJarEntry(DESCRIPTOR);
                if (entry == null || entry.isDirectory()) return Optional.empty();
                final PluginDescriptor descriptor;
                try (InputStream input = jar.getInputStream(entry)) {
                    descriptor = new PluginDescriptorParser().parse(input);
                }
                // Retired fake ids must not surface in management listings,
                // pending-install matching, or any metadata read: the same
                // shared boundary set the loader contract denies.
                if (dev.turboism.core.plugin.PluginJarContract.RETIRED_PLUGIN_IDS
                        .contains(descriptor.id())) {
                    return Optional.empty();
                }
                loader = new URLClassLoader(
                    new URL[]{path.toUri().toURL()}, PluginArchiveMetadata.class.getClassLoader()
                );
                final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                    descriptor.id(), loader, descriptor.i18n(), locale.toLanguageTag(), locale, locale, diagnostics
                );
                return Optional.of(new PluginArchiveMetadata(
                    descriptor.id(), metadata(localization, "plugin.name", descriptor.name()),
                    descriptor.version(), metadata(localization, "plugin.description", descriptor.description()),
                    descriptor.category(), java.util.List.copyOf(descriptor.tags())
                ));
            }
        } catch (Exception failure) {
            return Optional.empty();
        } finally {
            if (loader != null) {
                try { loader.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static String metadata(
        final RuntimePluginLocalization localization,
        final String key,
        final String fallback
    ) {
        try {
            if (localization.contains(key)) {
                final String value = localization.text(key);
                if (value != null && !value.isBlank()) return value;
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }
}
