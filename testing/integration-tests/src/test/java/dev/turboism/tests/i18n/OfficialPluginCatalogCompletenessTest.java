package dev.turboism.tests.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialPluginCatalogCompletenessTest {
    private static final String PLUGIN_ID = "project-inspector";
    private static final String BASELINE = "action.refresh\nstatus.unavailable_with_type\nwindow.title\n";
    private static final String COMPLETE_CATALOG = """
        action.refresh=Refresh
        status.unavailable_with_type=Unavailable: {0}
        window.title=Turboism Project Inspector
        """;

    @TempDir Path tempDir;
    private int sandboxSequence;

    @Test void acceptsCompleteOfficialPluginCatalogs() throws Exception {
        Path i18n = completeSandbox();

        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verify(PLUGIN_ID, i18n));
    }

    @Test void rejectsCopiedEnglishValuesOutsideTheTechnicalAllowlist() throws Exception {
        Path i18n = completeSandbox();
        Files.writeString(i18n.resolve("messages_ja.properties"), COMPLETE_CATALOG, StandardCharsets.UTF_8);

        assertFailureContains(i18n, "copied English value for action.refresh in messages_ja.properties");
    }

    @Test void acceptsRuntimeCompatibleLogicalLinesEscapesAndUntrimmedValues() throws Exception {
        Path i18n = completeSandbox();
        String escapedCatalog = """
            action\\.refresh=Refresh\\
              now
            status.unavailable_with_type=Unavailable: {0}
            window.title=  Turboism Inspector\s\s
            """;
        writeAllCatalogs(i18n, escapedCatalog);

        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verify(PLUGIN_ID, i18n));
    }

    @Test void rejectsMissingCatalog() throws Exception {
        Path i18n = completeSandbox();
        Files.delete(i18n.resolve("messages_ko.properties"));

        assertFailureContains(i18n, "missing required catalog messages_ko.properties");
    }

    @Test void rejectsEmptyInvalidWhitespaceUnsortedAndDuplicateBaselineKeys() throws Exception {
        Path empty = completeSandbox();
        writeBaseline(empty, "# no keys\n");
        assertFailureContains(empty, "baseline-keys.txt must not be empty");

        Path invalid = completeSandbox();
        writeBaseline(invalid, "Action.Refresh\naction.refresh\nstatus.unavailable_with_type\nwindow.title\n");
        assertFailureContains(invalid, "invalid baseline key Action.Refresh");

        Path whitespace = completeSandbox();
        writeBaseline(whitespace, " action.refresh\nstatus.unavailable_with_type\nwindow.title\n");
        assertFailureContains(whitespace, "baseline key has surrounding whitespace");

        Path control = completeSandbox();
        writeBaseline(control, "action.refresh\nstatus.unavailable_with_type\nwindow.title\u0007\n");
        assertFailureContains(control, "invalid baseline key window.title");

        Path unsorted = completeSandbox();
        writeBaseline(unsorted, "window.title\naction.refresh\nstatus.unavailable_with_type\n");
        assertFailureContains(unsorted, "baseline-keys.txt must be sorted");

        Path duplicate = completeSandbox();
        writeBaseline(duplicate,
            "action.refresh\naction.refresh\nstatus.unavailable_with_type\nwindow.title\n");
        assertFailureContains(duplicate, "duplicate baseline key action.refresh");
    }

    @Test void rejectsMissingAndExtraKeys() throws Exception {
        Path i18n = completeSandbox();
        Files.writeString(i18n.resolve("messages_ja.properties"), """
            action.refresh=更新
            typo.window_title=Turboism プロジェクトインスペクター
            """, StandardCharsets.UTF_8);

        assertFailureContains(i18n, "missing key status.unavailable_with_type in messages_ja.properties");
        assertFailureContains(i18n, "extra key typo.window_title in messages_ja.properties");
    }

    @Test void rejectsDuplicateLogicalKeysIncludingEscapedEquivalent() throws Exception {
        Path i18n = completeSandbox();
        Files.writeString(i18n.resolve("messages_en.properties"), COMPLETE_CATALOG
            + "action\\.refresh=Again\n", StandardCharsets.UTF_8);

        assertFailureContains(i18n, "duplicate key action.refresh in messages_en.properties");
    }

    @Test void rejectsIncompleteContinuationBomInvalidUtf8AndInvalidPropertiesEscape() throws Exception {
        Path i18n = completeSandbox();
        Files.writeString(i18n.resolve("messages_en.properties"), COMPLETE_CATALOG + "window.title=broken\\",
            StandardCharsets.UTF_8);
        Files.write(i18n.resolve("messages_zh_Hans.properties"), withBom(COMPLETE_CATALOG));
        Files.write(i18n.resolve("messages_zh_Hant.properties"), new byte[] {(byte) 0xC3, (byte) 0x28});
        Files.writeString(i18n.resolve("messages_ja.properties"), COMPLETE_CATALOG + "bad\\u12G4=value\n",
            StandardCharsets.UTF_8);

        assertFailureContains(i18n, "incomplete continuation in messages_en.properties");
        assertFailureContains(i18n, "UTF-8 BOM is forbidden in messages_zh_Hans.properties");
        assertFailureContains(i18n, "invalid UTF-8 in messages_zh_Hant.properties");
        assertFailureContains(i18n, "invalid properties entry in messages_ja.properties");
    }

    @Test void rejectsBlankValuesAndInvalidMessageFormatPatterns() throws Exception {
        Path i18n = completeSandbox();
        Files.writeString(i18n.resolve("messages_ko.properties"), """
            action.refresh=
            status.unavailable_with_type=사용할 수 없음: {0,number
            window.title=Turboism 프로젝트 검사기
            """, StandardCharsets.UTF_8);

        assertFailureContains(i18n, "blank value for action.refresh in messages_ko.properties");
        assertFailureContains(i18n,
            "invalid MessageFormat pattern for status.unavailable_with_type in messages_ko.properties");
    }

    @Test void rejectsArgumentIndexSetDriftWhileHonoringApostrophesAndQuotedBraces() throws Exception {
        Path valid = completeSandbox();
        String basePattern = "User ''{0}'' sees '{1}' and {2}";
        writeAllCatalogs(valid, catalogWithPattern(basePattern));
        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verify(PLUGIN_ID, valid));

        Path drift = completeSandbox();
        writeAllCatalogs(drift, catalogWithPattern(basePattern));
        Files.writeString(drift.resolve("messages_ja.properties"),
            catalogWithPattern("利用者 ''{0}'' は '{2}' と {1} を見る"), StandardCharsets.UTF_8);

        assertFailureContains(drift,
            "argument index mismatch for status.unavailable_with_type in messages_ja.properties");
    }

    @Test void tracksNestedChoiceArgumentIndexesWithBalancedBraces() throws Exception {
        String choicePattern = "{0,choice,0#none|1#{1}}";
        Path valid = completeSandbox();
        writeAllCatalogs(valid, catalogWithPattern(choicePattern));
        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verify(PLUGIN_ID, valid));

        Path drift = completeSandbox();
        writeAllCatalogs(drift, catalogWithPattern(choicePattern));
        Files.writeString(drift.resolve("messages_ko.properties"),
            catalogWithPattern("{0,choice,0#없음|1#하나}"), StandardCharsets.UTF_8);

        assertFailureContains(drift,
            "argument index mismatch for status.unavailable_with_type in messages_ko.properties");
    }

    @Test void scansEveryParticipatingPluginAndKeepsKeyReferencesPluginScoped() throws Exception {
        Path plugins = Files.createDirectories(tempDir.resolve("plugins"));
        Path first = createPlugin(plugins, "first", "first.title\n", "first.title=First\n");
        Path second = createPlugin(plugins, "second", "second.title\n", "second.title=Second\n");
        Files.writeString(first.resolve("src/main/First.java"),
            "final class First { void x() { titleKey(\"second.title\"); } void titleKey(String key) {} }",
            StandardCharsets.UTF_8);
        Files.writeString(second.resolve("src/main/Second.java"),
            "final class Second { void x() { titleKey(\"second.title\"); } void titleKey(String key) {} }",
            StandardCharsets.UTF_8);
        Files.createDirectories(plugins.resolve("not-participating/src/main"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verifyAll(plugins));
        assertTrue(failure.getMessage().contains(
            "first: unknown production localization key second.title in First.java"), failure.getMessage());
    }

    @Test void rejectsUnknownProductionJavaAndJsonLocalizationKeys() throws Exception {
        Path i18n = completeSandbox();
        Path production = Files.createDirectories(tempDir.resolve("production"));
        Files.writeString(production.resolve("Contribution.java"), """
            final class Contribution {
                void configure() {
                    labelKey("action.refresh");
                    titleKey("unknown.java.title");
                }
                void labelKey(String key) {}
                void titleKey(String key) {}
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(production.resolve("contribution.json"), """
            {"messageKey":"unknown.json.message","labelKey":"action.refresh"}
            """, StandardCharsets.UTF_8);

        assertFailureContains(i18n, production,
            "unknown production localization key unknown.java.title in Contribution.java");
        assertFailureContains(i18n, production,
            "unknown production localization key unknown.json.message in contribution.json");
    }

    @Test void rejectsDeclaredLocaleThatIsNotBackedByTheOfficialCatalogMatrix() throws Exception {
        Path plugins = Files.createDirectories(tempDir.resolve("declared-locale-plugins"));
        Path pluginRoot = createPlugin(plugins, "declared-locale", BASELINE, COMPLETE_CATALOG);
        Files.writeString(
            pluginRoot.resolve("src/main/resources/META-INF/turboism/plugin.json"),
            "{\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[\"en\",\"fr\"]}}",
            StandardCharsets.UTF_8
        );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verifyAll(plugins)
        );
        assertTrue(failure.getMessage().contains("descriptor locales"), failure.getMessage());
    }

    @Test void verifiesAllParticipatingOfficialPluginResourcesAndProductionReferences() {
        Path projectRoot = Path.of(System.getProperty("projectRoot"));

        assertDoesNotThrow(() -> OfficialPluginCatalogCompleteness.verifyAll(projectRoot.resolve("plugins")));
    }

    @Test void reportsAPluginWhoseDescriptorLacksAnI18nBlock() throws Exception {
        Path plugins = Files.createDirectories(tempDir.resolve("plugins-sandbox-" + sandboxSequence++));
        Path pluginRoot = plugins.resolve("no-i18n-plugin");
        Path descriptorDir = Files.createDirectories(
            pluginRoot.resolve("src/main/resources/META-INF/turboism"));
        // Official descriptor without an i18n block and without a baseline: previously
        // skipped silently, now a completeness problem.
        Files.writeString(descriptorDir.resolve("plugin.json"), """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 2,
              "id": "dev.turboism.plugin.no-i18n",
              "name": "No I18n",
              "version": "1.0.0",
              "entrypoints": ["dev.turboism.plugin.noi18n.Plugin"],
              "turboismApi": "[0.1.0,0.2.0)"
            }
            """, StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verifyAll(plugins)
        );
        assertTrue(failure.getMessage().contains("no-i18n-plugin: plugin descriptor has no i18n block"));
    }

    private Path createPlugin(Path plugins, String pluginId, String baseline, String catalog)
        throws IOException {
        Path pluginRoot = plugins.resolve(pluginId);
        Path i18n = Files.createDirectories(
            pluginRoot.resolve("src/main/resources/META-INF/turboism/i18n"));
        writeBaseline(i18n, baseline);
        writeAllCatalogs(i18n, catalog);
        return pluginRoot;
    }

    private Path completeSandbox() throws IOException {
        Path i18n = Files.createDirectories(tempDir.resolve("i18n-" + sandboxSequence++));
        writeBaseline(i18n, BASELINE);
        writeAllCatalogs(i18n, COMPLETE_CATALOG);
        return i18n;
    }

    private static String catalogWithPattern(String pattern) {
        return "action.refresh=Refresh\n"
            + "status.unavailable_with_type=" + pattern + "\n"
            + "window.title=Turboism Project Inspector\n";
    }

    private static void writeBaseline(Path i18n, String baseline) throws IOException {
        Files.writeString(i18n.resolve("baseline-keys.txt"), baseline, StandardCharsets.UTF_8);
    }

    private static void writeAllCatalogs(Path i18n, String catalog) throws IOException {
        for (String name : OfficialPluginCatalogCompleteness.CATALOG_FILES) {
            String value = name.equals("messages.properties") || name.equals("messages_en.properties")
                ? catalog
                : localizedFixture(catalog, name);
            Files.writeString(i18n.resolve(name), value, StandardCharsets.UTF_8);
        }
    }

    private static String localizedFixture(String catalog, String catalogName) {
        String marker = switch (catalogName) {
            case "messages_zh_Hans.properties" -> " [简]";
            case "messages_zh_Hant.properties" -> " [繁]";
            case "messages_ja.properties" -> " [日]";
            case "messages_ko.properties" -> " [한]";
            default -> "";
        };
        StringBuilder result = new StringBuilder(catalog.length() + marker.length() * 8);
        boolean continuation = false;
        String[] lines = catalog.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            boolean blankOrComment = line.isBlank() || line.stripLeading().startsWith("#")
                || line.stripLeading().startsWith("!");
            boolean continues = trailingBackslashes(line) % 2 == 1;
            if (!blankOrComment && !continues && (continuation || line.contains("=") || line.contains(":"))) {
                line += marker;
            }
            result.append(line);
            if (index < lines.length - 1) {
                result.append('\n');
            }
            continuation = continues;
        }
        return result.toString();
    }

    private static int trailingBackslashes(String value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == '\\'; index--) {
            count++;
        }
        return count;
    }

    private void assertFailureContains(Path i18n, String expected) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verify(PLUGIN_ID, i18n));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    private void assertFailureContains(Path i18n, Path production, String expected) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> OfficialPluginCatalogCompleteness.verify(PLUGIN_ID, i18n, production));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    private static byte[] withBom(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bytes, 3, text.length);
        return bytes;
    }
}
