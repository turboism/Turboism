package dev.turboism.core.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.schema.plugin.PluginMetaValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionRangeTest {

    @Test
    void exactVersionContainsItself() {
        VersionRange range = VersionRange.parse("1.2.3");
        assertTrue(range.contains(PluginVersion.parse("1.2.3")));
    }

    @Test
    void halfOpenIntervalIncludesLowerExcludesUpper() {
        VersionRange range = VersionRange.parse("[0.1.0,0.2.0)");
        assertTrue(range.contains(PluginVersion.parse("0.1.0")));
        assertTrue(range.contains(PluginVersion.parse("0.1.99")));
        assertFalse(range.contains(PluginVersion.parse("0.2.0")));
    }

    @Test
    void invalidVersionThrows() {
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse("1.0"));
    }

    @Test
    void rejectsWhitespaceLeadingZerosAndNonHalfOpenForms() {
        for (String invalid : new String[]{" 1.2.3", "1.2.3 ", "01.2.3", "[1.0.0, 2.0.0)",
            "(1.0.0,2.0.0)", "[1.0.0,2.0.0]"}) {
            assertThrows(IllegalArgumentException.class, () -> VersionRange.parse(invalid), invalid);
        }
    }

    @Test
    void rejectsEqualAndInvertedIntervals() {
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse("[1.0.0,1.0.0)"));
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse("[2.0.0,1.0.0)"));
    }

    @Test
    void acceptsIntComponentBoundariesAndRejectsOverflow() {
        String maximum = "2147483647.2147483647.2147483647";
        assertEquals(maximum, VersionRange.parse(maximum).toString());
        assertEquals("[0.0.0," + maximum + ")", VersionRange.parse("[0.0.0," + maximum + ")").toString());
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse("2147483648.0.0"));
        assertThrows(IllegalArgumentException.class, () -> VersionRange.parse("[0.0.0,0.0.2147483648)"));
    }

    @Test
    void pluginMetadataAcceptsOrderedMultipleEntrypointsAndRejectsBadNames() throws Exception {
        String suffix = ",\"turboismApi\":\"1.0.0\""
            + ",\"authors\":[{\"name\":\"Author\"}]"
            + ",\"website\":\"https://turboism.dev\",\"resources\":[]"
            + ",\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]}}";
        String base = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":2,\"id\":\"a.b\","
            + "\"name\":\"A\",\"version\":\"1.0.0\",\"entrypoints\":";
        var mapper = new ObjectMapper();
        var validator = new PluginMetaValidator();
        var multiple = validator.validate(mapper.readTree(base + "[\"a.B\",\"a.C\"]" + suffix));
        assertTrue(multiple.isEmpty(), multiple.toString());
        var badName = validator.validate(mapper.readTree(base + "[\"a.bad-name\"]" + suffix));
        assertTrue(badName.stream().anyMatch(error -> error.code().equals("PLUGIN_META_BAD_ENTRYPOINT")
            && error.path().equals("entrypoints[0]")));
    }

    @Test
    void pluginMetadataAppliesStrictRangesToApiAndDependencies() throws Exception {
        String base = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":2,\"id\":\"a.b\","
            + "\"name\":\"A\",\"version\":\"1.0.0\",\"entrypoints\":[\"a.B\"],"
            + "\"authors\":[{\"name\":\"Author\"}],\"website\":\"https://turboism.dev\","
            + "\"resources\":[],\"i18n\":{\"baseName\":\"META-INF/turboism/i18n/messages\",\"locales\":[]},";
        var mapper = new ObjectMapper();
        var validator = new PluginMetaValidator();
        var apiErrors = validator.validate(mapper.readTree(base + "\"turboismApi\":\" 1.0.0\"}"));
        assertTrue(apiErrors.stream().anyMatch(error -> error.code().equals("PLUGIN_META_BAD_VERSION_RANGE")
            && error.path().equals("turboismApi")));
        var dependencyErrors = validator.validate(mapper.readTree(base + "\"turboismApi\":\"1.0.0\","
            + "\"dependencies\":[{\"id\":\"a.c\",\"version\":\"[2.0.0,1.0.0)\"}]}"));
        assertTrue(dependencyErrors.stream().anyMatch(error -> error.code().equals("DEPENDENCY_BAD_VERSION_RANGE")
            && error.path().equals("dependencies[0].version")));
    }
}
