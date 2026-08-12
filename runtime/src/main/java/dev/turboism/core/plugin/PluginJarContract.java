package dev.turboism.core.plugin;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Shared descriptor-to-JAR content contract used by all plugin loaders. */
public final class PluginJarContract {

    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";

    private PluginJarContract() {
    }

    public static void validate(
        final PluginDescriptor descriptor,
        final Collection<String> entryNames,
        final String logicalPath
    ) throws PluginJarContractException {
        final Set<String> content = Set.copyOf(entryNames);
        validateEntrypoints(descriptor, content, logicalPath);
        validateResourceRoots(descriptor, content, logicalPath);
        validateI18n(descriptor, content, logicalPath);
        rejectUndeclaredResources(descriptor, content, logicalPath);
    }

    private static void validateEntrypoints(
        final PluginDescriptor descriptor,
        final Set<String> content,
        final String logicalPath
    ) throws PluginJarContractException {
        for (String entrypoint : descriptor.entrypoints()) {
            final String classPath = entrypoint.replace('.', '/') + ".class";
            require(
                content.contains(classPath),
                "PLUGIN_ENTRYPOINT_CLASS_MISSING",
                logicalPath + "!/" + classPath
            );
        }
    }

    private static void validateResourceRoots(
        final PluginDescriptor descriptor,
        final Set<String> content,
        final String logicalPath
    ) throws PluginJarContractException {
        for (String root : descriptor.resources()) {
            final boolean present = content.stream().anyMatch(path -> path.startsWith(root));
            require(
                present,
                "PLUGIN_RESOURCE_ROOT_MISSING",
                logicalPath + "!/" + root
            );
        }
    }

    private static void validateI18n(
        final PluginDescriptor descriptor,
        final Set<String> content,
        final String logicalPath
    ) throws PluginJarContractException {
        final Set<String> expected = new HashSet<>();
        for (String locale : descriptor.i18n().locales()) {
            final String catalog = catalogPath(descriptor.i18n().baseName(), locale);
            expected.add(catalog);
            require(
                content.contains(catalog),
                "PLUGIN_I18N_CATALOG_MISSING",
                logicalPath + "!/" + catalog
            );
        }
        final String prefix = descriptor.i18n().baseName() + "_";
        for (String path : content) {
            final boolean catalog = path.equals(descriptor.i18n().baseName() + ".properties")
                || (path.startsWith(prefix) && path.endsWith(".properties"));
            if (catalog && !expected.contains(path)) {
                throw problem(
                    "PLUGIN_I18N_CATALOG_UNDECLARED",
                    logicalPath + "!/" + path
                );
            }
        }
    }

    private static void rejectUndeclaredResources(
        final PluginDescriptor descriptor,
        final Set<String> content,
        final String logicalPath
    ) throws PluginJarContractException {
        for (String path : content) {
            if (path.equals(DESCRIPTOR)
                || path.equalsIgnoreCase("META-INF/MANIFEST.MF")
                || path.endsWith(".class")
                || path.startsWith("META-INF/")) {
                continue;
            }
            final boolean declared = descriptor.resources().stream()
                .anyMatch(path::startsWith);
            require(
                declared,
                "PLUGIN_RESOURCE_UNDECLARED",
                logicalPath + "!/" + path
            );
        }
    }

    private static String catalogPath(final String baseName, final String locale) {
        return "base".equals(locale)
            ? baseName + ".properties"
            : baseName + "_" + locale.replace('-', '_') + ".properties";
    }

    private static void require(
        final boolean valid,
        final String code,
        final String path
    ) throws PluginJarContractException {
        if (!valid) {
            throw problem(code, path);
        }
    }

    private static PluginJarContractException problem(
        final String code,
        final String path
    ) {
        return new PluginJarContractException(
            code,
            "Plugin JAR content does not match plugin.json",
            path
        );
    }

    public static final class PluginJarContractException extends Exception {
        private final String code;
        private final String path;

        private PluginJarContractException(
            final String code,
            final String message,
            final String path
        ) {
            super(message);
            this.code = code;
            this.path = path;
        }

        public String code() {
            return code;
        }

        public String path() {
            return path;
        }
    }
}
