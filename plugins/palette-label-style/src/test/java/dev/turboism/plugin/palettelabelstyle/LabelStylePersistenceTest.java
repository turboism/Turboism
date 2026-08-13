package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelStylePersistenceTest {

    @Test
    void filePathMapsBlankProjectIdsToDefault() {
        assertEquals(
            "palette-label-style/colors-default.properties",
            LabelStylePersistence.filePath(null).relativePath()
        );
        assertEquals(
            "palette-label-style/colors-default.properties",
            LabelStylePersistence.filePath("").relativePath()
        );
        assertEquals(
            "palette-label-style/colors-default.properties",
            LabelStylePersistence.filePath("  ").relativePath()
        );
        assertEquals(
            "palette-label-style/colors-project-1.properties",
            LabelStylePersistence.filePath("project-1").relativePath()
        );
        assertEquals(StorageRoot.STATE, LabelStylePersistence.filePath("project-1").root());
        assertEquals("default", LabelStylePersistence.safeProjectId(null));
    }

    @Test
    void keyUsesPaletteObjectAndProperty() {
        assertEquals(
            "PARAMETER_TAB:ParamAngleX:text",
            LabelStylePersistence.key(Location.PARAMETER_TAB, "ParamAngleX", LabelStylePersistence.PROPERTY_TEXT)
        );
        assertEquals(
            "DEFORMER_TAB:Deformer_1:background",
            LabelStylePersistence.key(Location.DEFORMER_TAB, "Deformer_1", LabelStylePersistence.PROPERTY_BACKGROUND)
        );
    }

    @Test
    void parseKeySplitsStoredKeys() {
        final LabelStylePersistence.StoredEntry entry = LabelStylePersistence
            .parseKey("PART_TAB:Part_1:text").orElseThrow();
        assertEquals(Location.PART_TAB, entry.palette());
        assertEquals("Part_1", entry.objectId());
        assertEquals("text", entry.property());
    }

    @Test
    void parseKeyKeepsColonsInsideObjectIds() {
        final LabelStylePersistence.StoredEntry entry = LabelStylePersistence
            .parseKey("PARAMETER_TAB:group:with:colon:text").orElseThrow();
        assertEquals("group:with:colon", entry.objectId());
        assertEquals("text", entry.property());
    }

    @Test
    void parseKeyRejectsMalformedKeys() {
        assertTrue(LabelStylePersistence.parseKey(null).isEmpty());
        assertTrue(LabelStylePersistence.parseKey("").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("PARAMETER_TAB").isEmpty());
        assertTrue(LabelStylePersistence.parseKey(":obj:text").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("UNKNOWN_TAB:obj:text").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("PARAMETER_TAB::text").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("PARAMETER_TAB:obj:font").isEmpty());
        assertTrue(LabelStylePersistence.parseKey("index").isEmpty());
    }

    @Test
    void serializeParseRoundTripPreservesEntries() {
        final Map<String, String> entries = Map.of(
            "PARAMETER_TAB:ParamAngleX:text", "#E53935",
            "PARAMETER_TAB:ParamAngleX:background", "#2196F3",
            "PART_TAB:Part_1:text", "#4CAF50"
        );
        assertEquals(entries, LabelStylePersistence.parse(LabelStylePersistence.serialize(entries)));
    }

    @Test
    void serializeEmptyProducesCommentOnlyContent() {
        final String content = LabelStylePersistence.serialize(Map.of());
        assertTrue(content.startsWith("#"));
        assertTrue(LabelStylePersistence.parse(content).isEmpty());
    }

    @Test
    void parseSkipsCommentsBlankLinesAndMalformedEntries() {
        final String content = "# palette-label-style colors\n"
            + "\n"
            + "PARAMETER_TAB:Angle:text=#E53935\n"
            + "no-equals-here\n"
            + "=value\n"
            + "PARAMETER_TAB:Broken:text=not-a-color\n"
            + "index=PARAMETER_TAB:Angle:text\n"
            + "PARAMETER_TAB:Other:font=#112233\n";
        assertEquals(
            Map.of("PARAMETER_TAB:Angle:text", "#E53935"),
            LabelStylePersistence.parse(content)
        );
    }

    @Test
    void parseAcceptsLowercaseHexAndNormalizesNothing() {
        assertEquals(
            Map.of("PART_TAB:Part_1:text", "#4caf50"),
            LabelStylePersistence.parse("PART_TAB:Part_1:text=#4caf50\n")
        );
    }

    @Test
    void parseHandlesBlankAndNullContent() {
        assertTrue(LabelStylePersistence.parse(null).isEmpty());
        assertTrue(LabelStylePersistence.parse("").isEmpty());
        assertTrue(LabelStylePersistence.parse("   \n  \n").isEmpty());
    }

    @Test
    void projectsAreIsolatedByFilePath() {
        assertFalse(LabelStylePersistence.filePath("project-a")
            .equals(LabelStylePersistence.filePath("project-b")));
        assertEquals(
            "palette-label-style/colors-project-a.properties",
            LabelStylePersistence.filePath("project-a").relativePath()
        );
        assertEquals(
            "palette-label-style/colors-project-b.properties",
            LabelStylePersistence.filePath("project-b").relativePath()
        );
    }

    @Test
    void serializeSortsKeysForStableOutput() {
        final String content = LabelStylePersistence.serialize(Map.of(
            "PART_TAB:Part_1:text", "#4CAF50",
            "PARAMETER_TAB:Angle:text", "#E53935"
        ));
        assertTrue(content.indexOf("PARAMETER_TAB:Angle:text") < content.indexOf("PART_TAB:Part_1:text"),
            "PARAMETER sorts before PART in ASCII");
    }
}
