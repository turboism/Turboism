package dev.turboism.pluginmanagement;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.i18n.LocalizationDiagnosticSink;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.plugin.core.CorePluginManagement;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

record PluginArchiveMetadata(
    String id,
    String name,
    String version,
    String description,
    Optional<String> category,
    List<String> tags,
    String turboismApi,
    List<CorePluginManagement.Author> authors,
    String license,
    Optional<String> website,
    List<CorePluginManagement.Dependency> dependencies,
    List<CorePluginManagement.Permission> permissions,
    List<String> capabilities,
    boolean requiresCubism,
    String ui,
    List<String> entrypoints,
    List<String> resources,
    String i18nBaseName,
    List<String> locales,
    List<CorePluginManagement.EventExport> eventExports,
    List<CorePluginManagement.EventImport> eventImports,
    Optional<String> readme
) {
    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";
    private static final String CORE_DESCRIPTOR = "META-INF/turboism/core-plugin.json";
    private static final List<String> README_PATHS = List.of(
        "META-INF/turboism/readme/README.md", "README.md", "README.markdown", "README.txt",
        "readme.md", "readme.markdown", "readme.txt"
    );
    static final int MAX_README_BYTES = 1024 * 1024;

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
                final JarEntry entry = jar.getJarEntry(DESCRIPTOR);
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
                return Optional.of(metadata(descriptor, localization, readme(jar)));
            }
        } catch (Exception failure) {
            return Optional.empty();
        } finally {
            if (loader != null) {
                try { loader.close(); } catch (Exception ignored) { }
            }
        }
    }

    static Optional<PluginArchiveMetadata> readCore(
        final ClassLoader loader,
        final Locale locale,
        final LocalizationDiagnosticSink diagnostics
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(diagnostics, "diagnostics");
        try (InputStream input = loader.getResourceAsStream(CORE_DESCRIPTOR)) {
            if (input == null) return Optional.empty();
            final PluginDescriptor descriptor = new PluginDescriptorParser().parse(input);
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                descriptor.id(), loader, descriptor.i18n(), locale.toLanguageTag(), locale, locale, diagnostics
            );
            return Optional.of(metadata(descriptor, localization, readme(loader)));
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    private static PluginArchiveMetadata metadata(
        final PluginDescriptor descriptor,
        final RuntimePluginLocalization localization,
        final Optional<String> readme
    ) {
        return new PluginArchiveMetadata(
            descriptor.id(), localized(localization, "plugin.name", descriptor.name()),
            descriptor.version(), localized(localization, "plugin.description", descriptor.description()),
            descriptor.category(), List.copyOf(descriptor.tags()), descriptor.turboismApi(),
            descriptor.authors().stream()
                .map(author -> new CorePluginManagement.Author(author.name(), author.email()))
                .toList(),
            descriptor.license(), descriptor.website(),
            descriptor.dependencies().stream()
                .map(dependency -> new CorePluginManagement.Dependency(
                    dependency.id(), dependency.type(), dependency.version(), dependency.ordering(), dependency.reason()
                ))
                .toList(),
            descriptor.permissions().stream()
                .map(permission -> new CorePluginManagement.Permission(
                    permission.id(), permission.scope(), permission.reason()
                ))
                .toList(),
            List.copyOf(descriptor.capabilities()), descriptor.environment().requiresCubism(),
            descriptor.environment().ui(), List.copyOf(descriptor.entrypoints()),
            List.copyOf(descriptor.resources()), descriptor.i18n().baseName(),
            List.copyOf(descriptor.i18n().locales()),
            descriptor.eventExports().stream()
                .map(exported -> new CorePluginManagement.EventExport(
                    exported.id(), exported.contractVersion(), exported.eventType(), exported.abiSha256()
                ))
                .toList(),
            descriptor.eventImports().stream()
                .map(imported -> new CorePluginManagement.EventImport(
                    imported.providerId(), imported.eventId(), imported.contractVersion(), imported.eventType(),
                    imported.abiSha256(), imported.required()
                ))
                .toList(),
            readme
        );
    }

    private static Optional<String> readme(final JarFile jar) {
        for (String path : README_PATHS) {
            final JarEntry entry = jar.getJarEntry(path);
            if (entry == null || entry.isDirectory()) continue;
            if (entry.getSize() > MAX_README_BYTES) return Optional.empty();
            try (InputStream input = jar.getInputStream(entry)) {
                return readUtf8(input);
            } catch (Exception unavailable) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> readme(final ClassLoader loader) {
        for (String path : README_PATHS) {
            try (InputStream input = loader.getResourceAsStream(path)) {
                if (input != null) return readUtf8(input);
            } catch (Exception unavailable) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> readUtf8(final InputStream input) throws java.io.IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > MAX_README_BYTES) return Optional.empty();
            output.write(buffer, 0, count);
        }
        return Optional.of(new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String localized(
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
