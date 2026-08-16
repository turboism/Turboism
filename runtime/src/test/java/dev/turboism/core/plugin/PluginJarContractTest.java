package dev.turboism.core.plugin;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused regression: BCP-47 locale IDs map to Java resource-bundle file names. */
class PluginJarContractTest {

    private static final String BASE_NAME = "META-INF/turboism/i18n/messages";
    private static final String ENTRYPOINT_CLASS = "dev/turboism/plugin/TestI18nPlugin.class";

    private static PluginDescriptor descriptorWithLocales(final List<String> locales) {
        return descriptorWithIdAndLocales("test.i18n", locales);
    }

    private static PluginDescriptor descriptorWithIdAndLocales(final String id, final List<String> locales) {
        return new PluginDescriptor() {
            @Override public String id() { return id; }
            @Override public String name() { return "Test I18n"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test i18n plugin"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.plugin.TestI18nPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of("META-INF/turboism/i18n/"); }
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() { return BASE_NAME; }
                    @Override public List<String> locales() { return locales; }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "swing"; }
                };
            }
        };
    }

    @Test
    void bcP47LocaleIdsMapToResourceBundleFileNames() throws Exception {
        // Given a descriptor declaring base, en, and script locales
        PluginDescriptor descriptor = descriptorWithLocales(List.of("base", "en", "zh-Hans", "zh-Hant"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + ".properties",
            BASE_NAME + "_en.properties",
            BASE_NAME + "_zh_Hans.properties",
            BASE_NAME + "_zh_Hant.properties"
        );
        // When the JAR holds the Java resource-bundle file names
        // Then the contract accepts the exact runtime catalog paths
        PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar");
    }

    @Test
    void hyphenSpellingDoesNotSatisfyDeclaredCatalog() {
        // Given a descriptor declaring zh-Hans
        PluginDescriptor descriptor = descriptorWithLocales(List.of("base", "en", "zh-Hans"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + ".properties",
            BASE_NAME + "_en.properties",
            BASE_NAME + "_zh-Hans.properties"
        );
        // When the JAR only holds the hyphen-spelled resource
        PluginJarContract.PluginJarContractException exception = assertThrows(
            PluginJarContract.PluginJarContractException.class,
            () -> PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar")
        );
        // Then the required underscore catalog is missing
        assertEquals("PLUGIN_I18N_CATALOG_MISSING", exception.code());
        assertTrue(exception.path().endsWith(BASE_NAME + "_zh_Hans.properties"));
    }

    @Test
    void hyphenSpellingRemainsUndeclared() {
        // Given a descriptor that does not declare zh-Hans
        PluginDescriptor descriptor = descriptorWithLocales(List.of("base", "en"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + ".properties",
            BASE_NAME + "_en.properties",
            BASE_NAME + "_zh-Hans.properties"
        );
        // When the JAR carries an extra hyphen-spelled catalog
        PluginJarContract.PluginJarContractException exception = assertThrows(
            PluginJarContract.PluginJarContractException.class,
            () -> PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar")
        );
        // Then the hyphen spelling is rejected as undeclared rather than admitted
        assertEquals("PLUGIN_I18N_CATALOG_UNDECLARED", exception.code());
        assertTrue(exception.path().endsWith(BASE_NAME + "_zh-Hans.properties"));
    }

    @Test
    void implicitBaseCatalogIsRequiredWithoutExplicitBaseLocale() throws Exception {
        // Given a descriptor in the current official form: locales omit base
        PluginDescriptor descriptor = descriptorWithLocales(List.of("en", "zh-Hans"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + ".properties",
            BASE_NAME + "_en.properties",
            BASE_NAME + "_zh_Hans.properties"
        );
        // Then the implicit base catalog plus normalized localized catalogs validate
        PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar");
    }

    @Test
    void missingImplicitBaseFailsClosedWithExactBasePath() {
        // Given a descriptor without base and a JAR missing the base catalog
        PluginDescriptor descriptor = descriptorWithLocales(List.of("en"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + "_en.properties"
        );
        // When validated
        PluginJarContract.PluginJarContractException exception = assertThrows(
            PluginJarContract.PluginJarContractException.class,
            () -> PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar")
        );
        // Then the implicit base is required with its exact path
        assertEquals("PLUGIN_I18N_CATALOG_MISSING", exception.code());
        assertEquals("plugins/test-i18n.jar!/" + BASE_NAME + ".properties", exception.path());
    }

    @Test
    void undeclaredLocalizedCatalogRemainsRejected() {
        // Given a descriptor declaring only en
        PluginDescriptor descriptor = descriptorWithLocales(List.of("en"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + ".properties",
            BASE_NAME + "_en.properties",
            BASE_NAME + "_ja.properties"
        );
        // When the JAR carries an undeclared localized catalog
        PluginJarContract.PluginJarContractException exception = assertThrows(
            PluginJarContract.PluginJarContractException.class,
            () -> PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar")
        );
        // Then it is rejected as undeclared
        assertEquals("PLUGIN_I18N_CATALOG_UNDECLARED", exception.code());
        assertTrue(exception.path().endsWith(BASE_NAME + "_ja.properties"));
    }

    @Test
    void legacyExplicitBaseDedupesNaturally() throws Exception {
        // Given a legacy descriptor that explicitly declares base
        PluginDescriptor descriptor = descriptorWithLocales(List.of("base", "en"));
        List<String> content = List.of(
            ENTRYPOINT_CLASS,
            BASE_NAME + ".properties",
            BASE_NAME + "_en.properties"
        );
        // Then the single base resource satisfies both the implicit and explicit forms
        PluginJarContract.validate(descriptor, content, "plugins/test-i18n.jar");
    }

    @Test
    void everyRetiredFakePluginIdIsRejectedBeforeContentChecks() {
        // Given any valid descriptor carrying one of the four retired ids and a
        // JAR whose content would otherwise satisfy the full contract
        List<String> content = List.of(ENTRYPOINT_CLASS, BASE_NAME + ".properties");
        for (String retiredId : List.of(
            "dev.turboism.plugin.logfilter",
            "dev.turboism.plugin.clipmask",
            "dev.turboism.plugin.perfopt",
            "dev.turboism.plugin.renderopt")) {
            PluginDescriptor descriptor = descriptorWithIdAndLocales(retiredId, List.of("base"));
            // When validated under a renamed filename alike
            PluginJarContract.PluginJarContractException exception = assertThrows(
                PluginJarContract.PluginJarContractException.class,
                () -> PluginJarContract.validate(
                    descriptor, content, "plugins/renamed-archive.jar")
            );
            // Then the retired id is rejected with the typed diagnostic, filename-independent
            assertEquals("PLUGIN_RETIRED_ID", exception.code());
            assertTrue(exception.path().contains(retiredId));
        }
    }

    @Test
    void retainedSuccessorIdsRemainAdmitted() throws Exception {
        // Given the retained clipmask-viewer successor id (not retired)
        PluginDescriptor descriptor = descriptorWithIdAndLocales(
            "dev.turboism.plugin.clipmask-viewer", List.of("base"));
        // Then a valid JAR carrying it still validates
        PluginJarContract.validate(
            descriptor, List.of(ENTRYPOINT_CLASS, BASE_NAME + ".properties"),
            "plugins/clipmask-viewer.jar");
    }
}
