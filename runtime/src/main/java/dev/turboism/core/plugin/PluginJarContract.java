package dev.turboism.core.plugin;

import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shared descriptor-to-JAR content contract used by all plugin loaders. */
public final class PluginJarContract {

    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";

    /**
     * Retired fake plugin ids (retirement slice): every valid JAR descriptor
     * carrying one of these ids is rejected before entrypoint loading or
     * staging, on every distribution path (installer payloads, preview
     * discovery, manual/NSIS leftovers, renamed JARs). The installer's managed
     * cleanup deletes identity-proven retired JARs; this boundary denies the
     * remainder.
     */
    public static final Set<String> RETIRED_PLUGIN_IDS = Set.of(
        "dev.turboism.plugin.logfilter",
        "dev.turboism.plugin.clipmask",
        "dev.turboism.plugin.perfopt",
        "dev.turboism.plugin.renderopt");

    private PluginJarContract() {
    }

    /**
     * Checks a plugin descriptor against the actual contents of its JAR.
     *
     * <p>Enforced in order: the id is not one of {@link #RETIRED_PLUGIN_IDS}; every declared
     * entrypoint class is present; declared resource roots exist; declared i18n bundles exist; and
     * the JAR carries no resources the descriptor did not declare. Returning normally means all five
     * hold.
     *
     * @param descriptor the descriptor read from the JAR
     * @param entryNames every entry name in the JAR; copied before inspection
     * @param logicalPath path used to identify the JAR in problem messages
     * @throws PluginJarContractException on the first violation, carrying a stable
     *         {@link PluginJarContractException#code()} and the offending path
     */
    public static void validate(
        final PluginDescriptor descriptor,
        final Collection<String> entryNames,
        final String logicalPath
    ) throws PluginJarContractException {
        rejectRetiredId(descriptor, logicalPath);
        final Set<String> content = Set.copyOf(entryNames);
        validateEntrypoints(descriptor, content, logicalPath);
        validatePublicEventTypes(descriptor, content, logicalPath);
        validateResourceRoots(descriptor, content, logicalPath);
        validateI18n(descriptor, content, logicalPath);
        rejectUndeclaredResources(descriptor, content, logicalPath);
    }

    private static void rejectRetiredId(
        final PluginDescriptor descriptor,
        final String logicalPath
    ) throws PluginJarContractException {
        if (RETIRED_PLUGIN_IDS.contains(descriptor.id())) {
            throw problem(
                "PLUGIN_RETIRED_ID",
                logicalPath + " (plugin id " + descriptor.id() + " is retired and must not load)"
            );
        }
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

    private static void validatePublicEventTypes(
        final PluginDescriptor descriptor,
        final Set<String> content,
        final String logicalPath
    ) throws PluginJarContractException {
        final Set<String> contractTypes = new LinkedHashSet<>();
        descriptor.eventExports().forEach(exported -> contractTypes.add(exported.eventType()));
        descriptor.eventImports().forEach(imported -> contractTypes.add(imported.eventType()));
        for (String eventType : contractTypes) {
            final String classPath = eventType.replace('.', '/') + ".class";
            require(
                !content.contains(classPath),
                "PLUGIN_PUBLIC_EVENT_API_EMBEDDED",
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
        // baseName() implicitly declares exactly one required base catalog;
        // a legacy explicit "base" locale maps to the same path and dedupes.
        final Set<String> expected = new LinkedHashSet<>();
        expected.add(descriptor.i18n().baseName() + ".properties");
        for (String locale : descriptor.i18n().locales()) {
            expected.add(catalogPath(descriptor.i18n().baseName(), locale));
        }
        for (String catalog : expected) {
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

        /** @return the stable machine-readable violation code, for example {@code PLUGIN_RETIRED_ID} */
        public String code() {
            return code;
        }

        /** @return the JAR path or entry name the violation is attributed to */
        public String path() {
            return path;
        }
    }
}
