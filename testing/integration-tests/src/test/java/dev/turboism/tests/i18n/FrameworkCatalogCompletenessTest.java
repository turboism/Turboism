package dev.turboism.tests.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the framework's own {@link java.util.ResourceBundle} catalogs to the same locale matrix the
 * official plugins already carry.
 *
 * <p>Plugins fail loudly: a plugin catalog set is verified against {@code baseline-keys.txt} and a
 * declared locale list. The framework resolves its chrome through {@code ResourceBundle} instead, so
 * a missing catalog degrades silently to the English baseline. These tests make that degradation a
 * build failure.</p>
 */
class FrameworkCatalogCompletenessTest {

    private static final Path PANEL = Path.of("dev/turboism/ui/panel");
    private static final String ALIAS = "messages_zh.properties";

    @TempDir Path tempDir;
    private int sandboxSequence;

    @Test void verifiesShippedFrameworkMessageCatalogs() {
        Path projectRoot = Path.of(System.getProperty("projectRoot"));

        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verifyFrameworkCatalogs(
            projectRoot.resolve("runtime/src/main/resources")));
    }

    /**
     * The gate is only as wide as the modules it is pointed at, so a framework module that starts
     * shipping catalogs must be added to it. These two ship none today.
     */
    @Test void frameworkModulesOutsideTheGateShipNoMessageCatalogs() throws Exception {
        Path projectRoot = Path.of(System.getProperty("projectRoot"));

        for (String module : List.of("sdk", "bootstrap")) {
            Path resources = projectRoot.resolve(module + "/src/main/resources");
            assertTrue(
                messageCatalogsUnder(resources).isEmpty(),
                module + " ships message catalogs; add it to verifiesShippedFrameworkMessageCatalogs"
            );
        }
    }

    @Test void acceptsCompleteMatrixWithoutTheAlias() throws Exception {
        Path resources = frameworkSandbox(false);

        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verifyFrameworkCatalogs(resources));
    }

    @Test void acceptsCompleteMatrixWithTheCompatibilityAlias() throws Exception {
        Path resources = frameworkSandbox(true);

        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verifyFrameworkCatalogs(resources));
    }

    @Test void rejectsMissingEnglishCatalog() throws Exception {
        Path resources = frameworkSandbox(true);
        Files.delete(catalog(resources, "messages_en.properties"));

        assertFailureContains(resources, "missing required catalog messages_en.properties");
    }

    @Test void rejectsMissingSimplifiedChineseCatalog() throws Exception {
        Path resources = frameworkSandbox(true);
        Files.delete(catalog(resources, "messages_zh_Hans.properties"));

        assertFailureContains(resources, "missing required catalog messages_zh_Hans.properties");
    }

    @Test void rejectsTheAliasStandingInForSimplifiedChinese() throws Exception {
        // The alias is a compatibility extra, never a substitute: dropping the
        // script-suffixed catalog must fail even while messages_zh.properties exists.
        Path resources = frameworkSandbox(true);
        Files.delete(catalog(resources, "messages_zh_Hans.properties"));

        assertFailureContains(resources, "missing required catalog messages_zh_Hans.properties");
    }

    @Test void rejectsUnexpectedFrameworkCatalog() throws Exception {
        Path resources = frameworkSandbox(false);
        Files.writeString(
            catalog(resources, "messages_fr.properties"), matrix("messages_fr.properties"), StandardCharsets.UTF_8);

        assertFailureContains(resources, "unexpected catalog messages_fr.properties");
    }

    @Test void rejectsAliasMissingAndExtraKeys() throws Exception {
        Path resources = frameworkSandbox(true);
        Files.writeString(
            catalog(resources, ALIAS),
            "collapsible.section.expand=展开\ncollapsible.section.extra=多余\n",
            StandardCharsets.UTF_8);

        assertFailureContains(resources, "missing key collapsible.section.collapse in " + ALIAS);
        assertFailureContains(resources, "extra key collapsible.section.extra in " + ALIAS);
    }

    @Test void rejectsCopiedEnglishValue() throws Exception {
        Path resources = frameworkSandbox(false);
        Files.writeString(
            catalog(resources, "messages_ja.properties"),
            "collapsible.section.expand=Expand\ncollapsible.section.collapse=折りたたみ\n",
            StandardCharsets.UTF_8);

        assertFailureContains(
            resources, "copied English value for collapsible.section.expand in messages_ja.properties");
    }

    @Test void rejectsBlankValue() throws Exception {
        Path resources = frameworkSandbox(false);
        Files.writeString(
            catalog(resources, "messages_ko.properties"),
            "collapsible.section.expand=\ncollapsible.section.collapse=접기\n",
            StandardCharsets.UTF_8);

        assertFailureContains(
            resources, "blank value for collapsible.section.expand in messages_ko.properties");
    }

    @Test void rejectsMissingResourcesRoot() {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verifyFrameworkCatalogs(
                tempDir.resolve("absent-" + sandboxSequence++))
        );
        assertTrue(failure.getMessage().contains("missing resources root"), failure.getMessage());
    }

    @Test void rejectsResourcesRootWithoutAnyCatalog() throws Exception {
        Path resources = Files.createDirectories(tempDir.resolve("empty-" + sandboxSequence++));

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verifyFrameworkCatalogs(resources)
        );
        assertTrue(failure.getMessage().contains("no framework message catalogs"), failure.getMessage());
    }

    /**
     * Builds a resources root carrying one complete catalog directory, mirroring how the runtime
     * ships {@code dev/turboism/ui/panel}. The optional alias is the legacy script-less zh catalog.
     */
    private Path frameworkSandbox(boolean withAlias) throws IOException {
        Path resources = Files.createDirectories(tempDir.resolve("resources-" + sandboxSequence++));
        Path directory = Files.createDirectories(resources.resolve(PANEL));
        for (String name : OfficialPluginCatalogCompleteness.CATALOG_FILES) {
            Files.writeString(directory.resolve(name), matrix(name), StandardCharsets.UTF_8);
        }
        if (withAlias) {
            Files.writeString(directory.resolve(ALIAS), matrix(ALIAS), StandardCharsets.UTF_8);
        }
        return resources;
    }

    private static Path catalog(Path resources, String name) {
        return resources.resolve(PANEL).resolve(name);
    }

    private static List<Path> messageCatalogsUnder(Path resourcesRoot) throws IOException {
        if (!Files.isDirectory(resourcesRoot)) {
            return List.of();
        }
        try (var tree = Files.walk(resourcesRoot)) {
            return tree.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("messages"))
                .filter(path -> path.getFileName().toString().endsWith(".properties"))
                .toList();
        }
    }

    /**
     * Real shipped values, so the fixture cannot drift into something the runtime never produces.
     * The alias mirrors {@code messages_zh_Hans.properties}; both are Simplified Chinese, which is
     * why the alias cannot be validated against the other catalogs by value.
     */
    private static String matrix(String catalogName) {
        String expand = switch (catalogName) {
            case "messages_ja.properties" -> "展開";
            case "messages_ko.properties" -> "펼치기";
            case "messages_zh_Hans.properties", ALIAS -> "展开";
            case "messages_zh_Hant.properties" -> "展開";
            default -> "Expand";
        };
        String collapse = switch (catalogName) {
            case "messages_ja.properties" -> "折りたたみ";
            case "messages_ko.properties" -> "접기";
            case "messages_zh_Hans.properties", ALIAS -> "收起";
            case "messages_zh_Hant.properties" -> "收合";
            default -> "Collapse";
        };
        return "collapsible.section.expand=" + expand + "\n"
            + "collapsible.section.collapse=" + collapse + "\n";
    }

    private void assertFailureContains(Path resources, String expected) {
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verifyFrameworkCatalogs(resources)
        );
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
