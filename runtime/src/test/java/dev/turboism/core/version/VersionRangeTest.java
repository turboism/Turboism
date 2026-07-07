package dev.turboism.core.version;

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
}
