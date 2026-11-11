package dev.turboism.core.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.schema.SchemaValidationError;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the finding order of one manifest read: for every diagnostic the descriptor tier can report,
 * the document that triggers only that defect must fail with exactly that code, message and path.
 *
 * <p>The rows are also the precedence proof. A manifest is read root before fields and required
 * before value, so a document carrying two defects always reports the one this order reaches first;
 * the second half of the table states that for the pairs where the order is not obvious from the
 * field list.</p>
 */
class DescriptorFindingOrderTest {

    private static final String DIGEST = "a".repeat(64);

    private static final String EXPORT = """
        {
              "id": "demo.ready",
              "contractVersion": "1.0.0",
              "eventType": "dev.turboism.plugin.demo.DemoReadyEvent"
            }""";

    private static final String IMPORT = """
        {
              "provider": "dev.turboism.plugin.provider",
              "eventId": "provider.ready",
              "contractVersion": "[1.0.0,2.0.0)",
              "eventType": "dev.turboism.plugin.provider.ProviderReadyEvent",
              "required": false
            }""";

    private static final String CONTRACT = """
        {
              "id": "demo.events",
              "version": "1.0.0",
              "artifact": "META-INF/turboism/contracts/demo-events-1.0.0.jar",
              "sha256": "%s"
            }""".formatted(DIGEST);

    private static final String EXPORTS = "\"eventExports\": [" + EXPORT + "],";
    private static final String IMPORTS = "\"eventImports\": [" + IMPORT + "],";
    private static final String CONTRACTS = "\"eventContracts\": [" + CONTRACT + "],";
    private static final String DEPENDENCY =
            "{\"id\": \"dev.turboism.plugin.provider\", \"version\": \"[1.0.0,2.0.0)\", \"type\": \"required\", \"ordering\": \"after\"}";
    private static final String DEPENDENCIES = "\"dependencies\": [" + DEPENDENCY + "],";
    private static final String PERMISSION =
            "{\"id\": \"turboism.file.read\", \"scope\": \"application\", \"reason\": \"Reads a project file.\"}";
    private static final String PERMISSIONS = "\"permissions\": [" + PERMISSION + "],";
    private static final String AUTHOR = "{\"name\": \"Turboism Contributors\"}";
    private static final String AUTHORS = "\"authors\": [" + AUTHOR + "],";
    private static final String I18N =
            "\"i18n\": {\"baseName\": \"META-INF/turboism/i18n/messages\", \"locales\": [\"base\", \"en\"]},";
    private static final String ENVIRONMENT = "\"environment\": {\"requiresCubism\": false, \"ui\": \"none\"},";
    private static final String LIFECYCLE = "\"lifecycle\": {\"hotReload\": true},";

    private static final String VALID = """
        {
          "format": "turboism.plugin.meta",
          "schemaVersion": 5,
          "id": "dev.turboism.plugin.demo",
          "name": "Demo Plugin",
          "version": "0.1.0",
          "entrypoints": ["dev.turboism.plugin.demo.DemoPlugin"],
          "turboismApi": "[0.67.0,1.0.0)",
          %s
          "license": "MIT",
          "website": "https://turboism.dev",
          "resources": ["icons/"],
          %s
          %s
          %s
          "capabilities": ["ui.panel"],
          %s
          %s
          "category": "modeling",
          "tags": ["parameter"],
          %s
          %s
          %s
          "description": "Demo descriptor."
        }
        """.formatted(
                    AUTHORS, I18N, DEPENDENCIES, PERMISSIONS, ENVIRONMENT, LIFECYCLE, EXPORTS, IMPORTS, CONTRACTS);

    private final PluginDescriptorParser parser = new PluginDescriptorParser();

    @Test
    void baselineDescriptorIsAdmissible() {
        assertTrue(parser.check(parseTree(VALID), "baseline").isEmpty());
    }

    @ParameterizedTest(name = "{0} reports {2} at {3}")
    @MethodSource("firstFindings")
    void reportsTheFirstFindingWithItsExactCodeMessageAndPath(
            final String caseName, final String json, final String code, final String path) {
        final List<SchemaValidationError> findings = parser.check(parseTree(json), "case");
        assertEquals(code, findings.get(0).code(), caseName + " reported " + findings);
        assertEquals(path, findings.get(0).path(), caseName + " reported " + findings);
        final DescriptorParseException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DescriptorParseException.class, () -> parser.parse(parseTree(json), "case"));
        assertEquals(code, failure.code(), caseName);
        assertEquals(path, failure.path(), caseName);
        assertEquals(findings.get(0).message(), failure.getMessage(), caseName);
    }

    private static Stream<Arguments> firstFindings() {
        return Stream.of(
                // Root: the schema a document declares is read before anything else.
                row("non-object root", "[]", "PLUGIN_META_INVALID_JSON", ""),
                row(
                        "missing format",
                        mutate("\"format\": \"turboism.plugin.meta\",", ""),
                        "PLUGIN_META_MISSING",
                        "format"),
                row(
                        "wrong format",
                        mutate("\"format\": \"turboism.plugin.meta\"", "\"format\": \"turboism.plugin.other\""),
                        "PLUGIN_META_BAD_FORMAT",
                        "format"),
                row(
                        "missing schemaVersion",
                        mutate("\"schemaVersion\": 5,", ""),
                        "PLUGIN_META_MISSING",
                        "schemaVersion"),
                row(
                        "unadmitted schemaVersion",
                        mutate("\"schemaVersion\": 5", "\"schemaVersion\": 6"),
                        "PLUGIN_META_BAD_SCHEMA_VERSION",
                        "schemaVersion"),
                row(
                        "unknown top-level field",
                        mutate(
                                "\"name\": \"Demo Plugin\",",
                                "\"name\": \"Demo Plugin\", \"homepage\": \"https://turboism.dev\","),
                        "PLUGIN_META_UNKNOWN_FIELD",
                        "homepage"),
                // Required sweep: every missing required field is reported before any value is read.
                row("missing id", mutate("\"id\": \"dev.turboism.plugin.demo\",", ""), "PLUGIN_META_MISSING", "id"),
                row("missing name", mutate("\"name\": \"Demo Plugin\",", ""), "PLUGIN_META_MISSING", "name"),
                row("missing version", mutate("\"version\": \"0.1.0\",", ""), "PLUGIN_META_MISSING", "version"),
                row(
                        "missing entrypoints",
                        mutate("\"entrypoints\": [\"dev.turboism.plugin.demo.DemoPlugin\"],", ""),
                        "PLUGIN_META_MISSING",
                        "entrypoints"),
                row(
                        "missing turboismApi",
                        mutate("\"turboismApi\": \"[0.67.0,1.0.0)\",", ""),
                        "PLUGIN_META_MISSING",
                        "turboismApi"),
                row("missing authors", mutate(AUTHORS, ""), "PLUGIN_META_MISSING", "authors"),
                row(
                        "missing website",
                        mutate("\"website\": \"https://turboism.dev\",", ""),
                        "PLUGIN_META_MISSING",
                        "website"),
                row(
                        "missing resources",
                        mutate("\"resources\": [\"icons/\"],", ""),
                        "PLUGIN_META_MISSING",
                        "resources"),
                row("missing i18n", mutate(I18N, ""), "PLUGIN_META_MISSING", "i18n"),
                // Required before value: a missing field outranks an invalid one read later.
                row(
                        "missing id outranks invalid version",
                        mutate(
                                "\"id\": \"dev.turboism.plugin.demo\",",
                                "",
                                "\"version\": \"0.1.0\"",
                                "\"version\": \"0.1\""),
                        "PLUGIN_META_MISSING",
                        "id"),
                // Root before fields: the version is decided before any field is looked at.
                row(
                        "unknown field outranks missing id",
                        mutate("\"id\": \"dev.turboism.plugin.demo\",", "\"homepage\": 0,"),
                        "PLUGIN_META_UNKNOWN_FIELD",
                        "homepage"),
                row(
                        "bad format outranks missing id",
                        mutate(
                                "\"format\": \"turboism.plugin.meta\"",
                                "\"format\": \"turboism.plugin.other\"",
                                "\"id\": \"dev.turboism.plugin.demo\",",
                                ""),
                        "PLUGIN_META_BAD_FORMAT",
                        "format"),
                // Field values, in the order the tier table reads them.
                row(
                        "malformed id",
                        mutate("\"id\": \"dev.turboism.plugin.demo\"", "\"id\": \"Demo Plugin\""),
                        "PLUGIN_META_BAD_ID",
                        "id"),
                row(
                        "malformed version",
                        mutate("\"version\": \"0.1.0\"", "\"version\": \"0.1\""),
                        "PLUGIN_META_BAD_VERSION",
                        "version"),
                row(
                        "malformed turboismApi",
                        mutate("\"turboismApi\": \"[0.67.0,1.0.0)\"", "\"turboismApi\": \" 0.67.0\""),
                        "PLUGIN_META_BAD_VERSION_RANGE",
                        "turboismApi"),
                row(
                        "empty entrypoints",
                        mutate("\"entrypoints\": [\"dev.turboism.plugin.demo.DemoPlugin\"]", "\"entrypoints\": []"),
                        "PLUGIN_META_MISSING_ENTRYPOINT",
                        "entrypoints"),
                row(
                        "malformed entrypoint",
                        mutate("\"dev.turboism.plugin.demo.DemoPlugin\"", "\"dev.turboism.plugin.demo.bad-name\""),
                        "PLUGIN_META_BAD_ENTRYPOINT",
                        "entrypoints[0]"),
                row(
                        "duplicate entrypoint",
                        mutate(
                                "\"entrypoints\": [\"dev.turboism.plugin.demo.DemoPlugin\"]",
                                "\"entrypoints\": [\"dev.turboism.plugin.demo.DemoPlugin\", \"dev.turboism.plugin.demo.DemoPlugin\"]"),
                        "PLUGIN_META_DUPLICATE_ENTRYPOINT",
                        "entrypoints[1]"),
                row("empty authors", mutate(AUTHORS, "\"authors\": [],"), "PLUGIN_META_MISSING_AUTHOR", "authors"),
                row(
                        "non-object author",
                        mutate(AUTHORS, "\"authors\": [\"Turboism Contributors\"],"),
                        "PLUGIN_META_BAD_AUTHOR",
                        "authors[0]"),
                row(
                        "unknown author field",
                        mutate(AUTHOR, "{\"name\": \"Turboism Contributors\", \"phone\": \"+1\"}"),
                        "PLUGIN_META_UNKNOWN_AUTHOR_FIELD",
                        "authors[0].phone"),
                row(
                        "blank author email",
                        mutate(AUTHOR, "{\"name\": \"Turboism Contributors\", \"email\": \" \"}"),
                        "PLUGIN_META_BAD_AUTHOR",
                        "authors[0].email"),
                row(
                        "relative website",
                        mutate("\"website\": \"https://turboism.dev\"", "\"website\": \"/project\""),
                        "PLUGIN_META_BAD_WEBSITE",
                        "website"),
                row(
                        "unnormalized resource root",
                        mutate("\"resources\": [\"icons/\"]", "\"resources\": [\"/icons\"]"),
                        "PLUGIN_META_BAD_RESOURCE_ROOT",
                        "resources[0]"),
                row(
                        "duplicate resource root",
                        mutate("\"resources\": [\"icons/\"]", "\"resources\": [\"icons/\", \"icons/\"]"),
                        "PLUGIN_META_DUPLICATE_RESOURCE_ROOT",
                        "resources[1]"),
                row(
                        "unknown i18n field",
                        mutate(
                                "\"baseName\": \"META-INF/turboism/i18n/messages\"",
                                "\"baseName\": \"META-INF/turboism/i18n/messages\", \"fallback\": \"en\""),
                        "PLUGIN_META_UNKNOWN_I18N_FIELD",
                        "i18n.fallback"),
                row(
                        "malformed i18n base name",
                        mutate(
                                "\"baseName\": \"META-INF/turboism/i18n/messages\"",
                                "\"baseName\": \"messages.properties\""),
                        "PLUGIN_META_BAD_I18N_BASE",
                        "i18n.baseName"),
                row(
                        "non-array locales",
                        mutate("\"locales\": [\"base\", \"en\"]", "\"locales\": \"en\""),
                        "PLUGIN_META_BAD_I18N_LOCALES",
                        "i18n.locales"),
                row(
                        "malformed locale",
                        mutate("\"locales\": [\"base\", \"en\"]", "\"locales\": [\"base\", \"!!\"]"),
                        "PLUGIN_META_BAD_I18N_LOCALE",
                        "i18n.locales[1]"),
                row(
                        "duplicate locale",
                        mutate("\"locales\": [\"base\", \"en\"]", "\"locales\": [\"base\", \"en\", \"en\"]"),
                        "PLUGIN_META_DUPLICATE_I18N_LOCALE",
                        "i18n.locales[2]"),
                row(
                        "non-array dependencies",
                        mutate(DEPENDENCIES, "\"dependencies\": {},"),
                        "PLUGIN_META_BAD_DEPENDENCIES",
                        "dependencies"),
                row(
                        "dependencies outrank an invalid environment",
                        mutate(DEPENDENCIES, "\"dependencies\": {},", ENVIRONMENT, "\"environment\": \"none\","),
                        "PLUGIN_META_BAD_DEPENDENCIES",
                        "dependencies"),
                row(
                        "non-object dependency",
                        mutate(DEPENDENCIES, "\"dependencies\": [\"dev.turboism.plugin.provider\"],"),
                        "DEPENDENCY_INVALID",
                        "dependencies[0]"),
                row(
                        "unknown dependency field",
                        mutate(
                                DEPENDENCY,
                                DEPENDENCY.replace(
                                        "\"ordering\": \"after\"", "\"ordering\": \"after\", \"optional\": true")),
                        "DEPENDENCY_UNKNOWN_FIELD",
                        "dependencies[0].optional"),
                row(
                        "malformed dependency id",
                        mutate(DEPENDENCY, DEPENDENCY.replace("dev.turboism.plugin.provider", "Provider")),
                        "DEPENDENCY_BAD_ID",
                        "dependencies[0].id"),
                row(
                        "missing dependency version",
                        mutate(DEPENDENCY, "{\"id\": \"dev.turboism.plugin.provider\"}"),
                        "DEPENDENCY_MISSING_VERSION",
                        "dependencies[0].version"),
                row(
                        "malformed dependency range",
                        mutate(DEPENDENCY, DEPENDENCY.replace("[1.0.0,2.0.0)", "[2.0.0,1.0.0)")),
                        "DEPENDENCY_BAD_VERSION_RANGE",
                        "dependencies[0].version"),
                row(
                        "unknown dependency type",
                        mutate(DEPENDENCY, DEPENDENCY.replace("\"required\"", "\"mandatory\"")),
                        "DEPENDENCY_BAD_TYPE",
                        "dependencies[0].type"),
                row(
                        "unknown dependency ordering",
                        mutate(DEPENDENCY, DEPENDENCY.replace("\"after\"", "\"alongside\"")),
                        "DEPENDENCY_BAD_ORDERING",
                        "dependencies[0].ordering"),
                row(
                        "non-array permissions",
                        mutate(PERMISSIONS, "\"permissions\": {},"),
                        "PLUGIN_META_BAD_PERMISSIONS",
                        "permissions"),
                row(
                        "non-object permission",
                        mutate(PERMISSIONS, "\"permissions\": [\"turboism.file.read\"],"),
                        "PERMISSION_INVALID",
                        "permissions[]"),
                row(
                        "unknown permission field",
                        mutate(PERMISSION, PERMISSION.replace("\"reason\"", "\"note\"")),
                        "PERMISSION_UNKNOWN_FIELD",
                        "permissions[].note"),
                row(
                        "missing permission id",
                        mutate(PERMISSION, "{\"scope\": \"application\", \"reason\": \"Reads a project file.\"}"),
                        "PERMISSION_MISSING_ID",
                        "permissions[].id"),
                row(
                        "unknown permission id",
                        mutate(PERMISSION, PERMISSION.replace("turboism.file.read", "turboism.everything")),
                        "PERMISSION_UNKNOWN_ID",
                        "permissions[].id"),
                row(
                        "missing permission reason",
                        mutate(PERMISSION, "{\"id\": \"turboism.file.read\", \"scope\": \"application\"}"),
                        "PERMISSION_REASON_MISSING",
                        "permissions[].reason"),
                row(
                        "unknown permission scope",
                        mutate(PERMISSION, PERMISSION.replace("\"application\"", "\"global\"")),
                        "PERMISSION_BAD_SCOPE",
                        "permissions[].scope"),
                row(
                        "non-array capabilities",
                        mutate("\"capabilities\": [\"ui.panel\"]", "\"capabilities\": \"ui.panel\""),
                        "PLUGIN_META_BAD_CAPABILITIES",
                        "capabilities"),
                row(
                        "duplicate capability",
                        mutate("\"capabilities\": [\"ui.panel\"]", "\"capabilities\": [\"ui.panel\", \"ui.panel\"]"),
                        "PLUGIN_META_BAD_CAPABILITIES",
                        "capabilities[1]"),
                row(
                        "non-object environment",
                        mutate(ENVIRONMENT, "\"environment\": \"none\","),
                        "PLUGIN_META_BAD_ENVIRONMENT",
                        "environment"),
                row(
                        "non-boolean requiresCubism",
                        mutate(ENVIRONMENT, "\"environment\": {\"requiresCubism\": \"yes\", \"ui\": \"none\"},"),
                        "PLUGIN_META_BAD_ENVIRONMENT",
                        "environment.requiresCubism"),
                row(
                        "unknown environment field",
                        mutate(ENVIRONMENT, "\"environment\": {\"requiresCubism\": false, \"jvm\": \"17\"},"),
                        "PLUGIN_META_UNKNOWN_ENVIRONMENT_FIELD",
                        "environment.jvm"),
                row(
                        "unknown environment ui",
                        mutate(ENVIRONMENT, "\"environment\": {\"requiresCubism\": false, \"ui\": \"canvas\"},"),
                        "PLUGIN_META_BAD_ENVIRONMENT_UI",
                        "environment.ui"),
                row(
                        "non-object lifecycle",
                        mutate(LIFECYCLE, "\"lifecycle\": [],"),
                        "PLUGIN_META_BAD_LIFECYCLE",
                        "lifecycle"),
                row(
                        "unknown lifecycle field",
                        mutate(LIFECYCLE, "\"lifecycle\": {\"hotReload\": true, \"install\": \"always\"},"),
                        "PLUGIN_META_UNKNOWN_LIFECYCLE_FIELD",
                        "lifecycle.install"),
                row(
                        "non-boolean hotReload",
                        mutate(LIFECYCLE, "\"lifecycle\": {\"hotReload\": \"yes\"},"),
                        "PLUGIN_META_BAD_LIFECYCLE",
                        "lifecycle.hotReload"),
                // Schema v3 classification is read inside the field list, not the required sweep.
                row(
                        "missing category outranks malformed tags",
                        mutate("\"category\": \"modeling\",", "", "\"tags\": [\"parameter\"]", "\"tags\": [\"P\"]"),
                        "PLUGIN_META_MISSING",
                        "category"),
                row(
                        "malformed category",
                        mutate("\"category\": \"modeling\"", "\"category\": \"Modeling\""),
                        "PLUGIN_META_BAD_CATEGORY",
                        "category"),
                row(
                        "non-array tags",
                        mutate("\"tags\": [\"parameter\"]", "\"tags\": \"parameter\""),
                        "PLUGIN_META_BAD_TAGS",
                        "tags"),
                row(
                        "tag overflow",
                        mutate(
                                "\"tags\": [\"parameter\"]",
                                "\"tags\": [\"a-1\",\"a-2\",\"a-3\",\"a-4\",\"a-5\",\"a-6\",\"a-7\",\"a-8\",\"a-9\",\"a-10\",\"a-11\",\"a-12\",\"a-13\"]"),
                        "PLUGIN_META_BAD_TAGS",
                        "tags"),
                row(
                        "malformed tag",
                        mutate("\"tags\": [\"parameter\"]", "\"tags\": [\"Parameter\"]"),
                        "PLUGIN_META_BAD_TAGS",
                        "tags[0]"),
                row(
                        "duplicate tag",
                        mutate("\"tags\": [\"parameter\"]", "\"tags\": [\"parameter\", \"parameter\"]"),
                        "PLUGIN_META_BAD_TAGS",
                        "tags[1]"),
                // Schema v4 public events.
                row(
                        "non-array exports",
                        mutate(EXPORTS, "\"eventExports\": {},"),
                        "PLUGIN_META_BAD_EVENT_EXPORTS",
                        "eventExports"),
                row(
                        "non-object export",
                        mutate(EXPORTS, "\"eventExports\": [\"demo.ready\"],"),
                        "PLUGIN_META_BAD_EVENT_EXPORT",
                        "eventExports[0]"),
                row(
                        "unknown export field",
                        mutate(EXPORT, EXPORT.replace("\"eventType\"", "\"payloadType\"")),
                        "PLUGIN_META_UNKNOWN_EVENT_EXPORT_FIELD",
                        "eventExports[0].payloadType"),
                row(
                        "malformed export id",
                        mutate(EXPORT, EXPORT.replace("demo.ready", "Demo.Ready")),
                        "PLUGIN_META_BAD_EVENT_ID",
                        "eventExports[0].id"),
                row(
                        "malformed export contract version",
                        mutate(
                                EXPORT,
                                EXPORT.replace("\"contractVersion\": \"1.0.0\"", "\"contractVersion\": \"1.0\"")),
                        "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION",
                        "eventExports[0].contractVersion"),
                row(
                        "malformed export type",
                        mutate(EXPORT, EXPORT.replace("dev.turboism.plugin.demo.DemoReadyEvent", "DemoReadyEvent!")),
                        "PLUGIN_META_BAD_EVENT_TYPE",
                        "eventExports[0].eventType"),
                row(
                        "duplicate export id",
                        mutate(EXPORTS, "\"eventExports\": [" + EXPORT + ", " + EXPORT + "],"),
                        "PLUGIN_META_DUPLICATE_EVENT_EXPORT",
                        "eventExports[1].id"),
                row(
                        "duplicate export type",
                        mutate(
                                EXPORTS,
                                "\"eventExports\": ["
                                        + EXPORT
                                        + ", "
                                        + EXPORT.replace("\"demo.ready\"", "\"demo.started\"")
                                        + "],"),
                        "PLUGIN_META_DUPLICATE_EVENT_TYPE",
                        "eventExports[1].eventType"),
                row(
                        "exports before imports",
                        mutate(EXPORTS, "\"eventExports\": {},", IMPORTS, "\"eventImports\": {},"),
                        "PLUGIN_META_BAD_EVENT_EXPORTS",
                        "eventExports"),
                row(
                        "non-array imports",
                        mutate(IMPORTS, "\"eventImports\": {},"),
                        "PLUGIN_META_BAD_EVENT_IMPORTS",
                        "eventImports"),
                row(
                        "non-object import",
                        mutate(IMPORTS, "\"eventImports\": [\"provider.ready\"],"),
                        "PLUGIN_META_BAD_EVENT_IMPORT",
                        "eventImports[0]"),
                row(
                        "unknown import field",
                        mutate(IMPORT, IMPORT.replace("\"required\"", "\"optional\"")),
                        "PLUGIN_META_UNKNOWN_EVENT_IMPORT_FIELD",
                        "eventImports[0].optional"),
                row(
                        "malformed import provider",
                        mutate(IMPORT, IMPORT.replace("dev.turboism.plugin.provider\",\n", "Provider\",\n")),
                        "PLUGIN_META_BAD_EVENT_PROVIDER",
                        "eventImports[0].provider"),
                row(
                        "malformed import event id",
                        mutate(IMPORT, IMPORT.replace("provider.ready", "Provider.Ready")),
                        "PLUGIN_META_BAD_EVENT_ID",
                        "eventImports[0].eventId"),
                row(
                        "malformed import contract range",
                        mutate(IMPORT, IMPORT.replace("[1.0.0,2.0.0)", "[2.0.0,1.0.0)")),
                        "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION",
                        "eventImports[0].contractVersion"),
                row(
                        "malformed import type",
                        mutate(IMPORT, IMPORT.replace("ProviderReadyEvent", "ProviderReadyEvent!")),
                        "PLUGIN_META_BAD_EVENT_TYPE",
                        "eventImports[0].eventType"),
                row(
                        "non-boolean import required",
                        mutate(IMPORT, IMPORT.replace("\"required\": false", "\"required\": \"no\"")),
                        "PLUGIN_META_BAD_EVENT_IMPORT",
                        "eventImports[0].required"),
                row(
                        "duplicate import route",
                        mutate(
                                IMPORTS,
                                "\"eventImports\": ["
                                        + IMPORT
                                        + ", "
                                        + IMPORT.replace("\"required\": false", "\"required\": true")
                                        + "],"),
                        "PLUGIN_META_DUPLICATE_EVENT_IMPORT",
                        "eventImports[1]"),
                // Schema v5 contract artifacts.
                row(
                        "non-array contracts",
                        mutate(CONTRACTS, "\"eventContracts\": {},"),
                        "PLUGIN_META_BAD_EVENT_CONTRACTS",
                        "eventContracts"),
                row(
                        "non-object contract",
                        mutate(CONTRACTS, "\"eventContracts\": [\"demo.events\"],"),
                        "PLUGIN_META_BAD_EVENT_CONTRACT",
                        "eventContracts[0]"),
                row(
                        "unknown contract field",
                        mutate(CONTRACT, CONTRACT.replace("\"artifact\"", "\"classpath\"")),
                        "PLUGIN_META_UNKNOWN_EVENT_CONTRACT_FIELD",
                        "eventContracts[0].classpath"),
                row(
                        "malformed contract id",
                        mutate(CONTRACT, CONTRACT.replace("demo.events", "Demo.Events")),
                        "PLUGIN_META_BAD_EVENT_ID",
                        "eventContracts[0].id"),
                row(
                        "malformed contract version",
                        mutate(CONTRACT, CONTRACT.replace("\"version\": \"1.0.0\"", "\"version\": \"1.0\"")),
                        "PLUGIN_META_BAD_EVENT_CONTRACT_VERSION",
                        "eventContracts[0].version"),
                row(
                        "contract artifact outside contracts directory",
                        mutate(CONTRACT, CONTRACT.replace("META-INF/turboism/contracts/", "META-INF/turboism/libs/")),
                        "PLUGIN_META_BAD_EVENT_CONTRACT_ARTIFACT",
                        "eventContracts[0].artifact"),
                row(
                        "malformed contract digest",
                        mutate(CONTRACT, CONTRACT.replace(DIGEST, "not-a-digest")),
                        "PLUGIN_META_BAD_EVENT_ABI",
                        "eventContracts[0].sha256"),
                row(
                        "duplicate contract id",
                        mutate(CONTRACTS, "\"eventContracts\": [" + CONTRACT + ", " + CONTRACT + "],"),
                        "PLUGIN_META_DUPLICATE_EVENT_CONTRACT",
                        "eventContracts[1].id"),
                row(
                        "duplicate contract artifact",
                        mutate(
                                CONTRACTS,
                                "\"eventContracts\": ["
                                        + CONTRACT
                                        + ", "
                                        + CONTRACT.replace("demo.events", "demo.other")
                                        + "],"),
                        "PLUGIN_META_DUPLICATE_EVENT_CONTRACT",
                        "eventContracts[1].artifact"));
    }

    private static Arguments row(final String caseName, final String json, final String code, final String path) {
        return Arguments.of(caseName, json, code, path);
    }

    /** Applies one or more {@code search, replacement} pairs to the baseline descriptor. */
    private static String mutate(final String... pairs) {
        String json = VALID;
        for (int index = 0; index < pairs.length; index += 2) {
            final String search = pairs[index];
            assertTrue(
                    json.contains(search),
                    "baseline descriptor does not contain " + search + " in " + Arrays.toString(pairs));
            json = json.replace(search, pairs[index + 1]);
        }
        return json;
    }

    private static com.fasterxml.jackson.databind.JsonNode parseTree(final String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("test descriptor is not valid JSON", exception);
        }
    }
}
