package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.json.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class AcpToolImageLabelTest {
    static Map<String, Object> capture(String id, String pose, int index) {
        var c = new LinkedHashMap<String, Object>();
        c.put("captureId", id);
        c.put("state", Map.of("documentId", "doc", "modelId", "model", "historyRevision", 7));
        c.put("width", 2);
        c.put("height", 3);
        c.put("renderWidth", 8);
        c.put("renderHeight", 9);
        c.put("crop", Map.of("x", 1, "y", 2, "width", 2, "height", 3));
        if (pose != null) {
            c.put("label", pose);
            c.put("imageContentIndex", index);
        }
        return c;
    }

    static Map<String, Object> wrap(Map<String, Object> block) {
        return Map.of("type", "content", "content", block);
    }

    static Map<String, Object> image() {
        return wrap(Map.of("type", "image", "mimeType", "image/png", "data", "not-caption-text"));
    }

    static List<Object> contents(Map<String, Object> metadata, Object... images) {
        var c = new ArrayList<Object>();
        c.add(wrap(Map.of("type", "text", "text", Json.stringify(metadata))));
        c.addAll(List.of(images));
        return c;
    }

    @Test
    void singleCaptureIncludesReportedStateAndCropWithoutBinaryText() {
        var metadata = capture("capture-one", null, 0);
        metadata.put("ok", true);
        String label = AcpToolImageLabel.label("tool-key", contents(metadata, image()), 1);
        assertTrue(label.contains("reported:"));
        assertTrue(label.contains("capture=capture-one"));
        assertTrue(label.contains("doc=doc"));
        assertTrue(label.contains("model=model"));
        assertTrue(label.contains("revision=7"));
        assertTrue(label.contains("2×3"));
        assertTrue(label.contains("crop=(1,2) of 8×9"));
        assertFalse(label.contains("not-caption-text"));
    }

    @Test
    void framesUseExactContentIndexEvenWhenFrameOrderDiffers() {
        var c = contents(
                Map.of("frames", List.of(capture("right", "right-pose", 2), capture("left", "left-pose", 1))),
                image(),
                image());
        assertTrue(AcpToolImageLabel.label("t", c, 1).contains("pose=left-pose"));
        assertFalse(AcpToolImageLabel.label("t", c, 1).contains("right-pose"));
        assertTrue(AcpToolImageLabel.label("t", c, 2).contains("capture=right"));
    }

    @Test
    void ambiguousMissingMalformedAndOversizedMetadataNeverInferPose() {
        var duplicate = contents(Map.of("frames", List.of(capture("a", "a", 1), capture("b", "b", 1))), image());
        var wrongType = contents(Map.of("frames", List.of(capture("a", "a", 0))), image());
        var absent = contents(Map.of("frames", List.of(capture("a", "a", 2))), image(), image());
        var direct = capture("a", null, 0);
        direct.put("ok", true);
        for (var c : List.of(
                duplicate,
                wrongType,
                absent,
                contents(direct, image(), image()),
                List.of(wrap(Map.of("type", "text", "text", "bad-json")), image()),
                List.of(
                        wrap(Map.of("type", "text", "text", "x".repeat(AcpToolImageLabel.MAX_METADATA_CHARS + 1))),
                        image()))) assertEquals("Tool image · t", AcpToolImageLabel.label("t", c, 1));
    }

    @Test
    void noPoseGuessAcrossSeparateUpdatesAndEmbeddedImagesRetainMapping() {
        var metadata = capture("embedded", null, 0);
        metadata.put("ok", true);
        var embedded = wrap(Map.of(
                "type",
                "resource",
                "resource",
                Map.of("mimeType", "image/png", "blob", "pixels", "uri", "turboism://render/one")));
        assertTrue(AcpToolImageLabel.label("t", contents(metadata, embedded), 1).contains("capture=embedded"));
        assertEquals("Tool image · t", AcpToolImageLabel.label("t", List.of(image()), 0));
    }

    @Test
    void labelsAreBoundedControlFreeAndRedactedBeforeTruncation() {
        var metadata = capture("capture-one", null, 0);
        metadata.put("ok", true);
        metadata.put(
                "state",
                Map.of("documentId", "private-" + "x".repeat(200), "modelId", "model\nlabel", "historyRevision", 7));
        String label = AcpToolImageLabel.label(
                "t", contents(metadata, image()), 1, value -> value.startsWith("private-") ? "<redacted>" : value);
        assertTrue(label.contains("doc=<redacted>"));
        assertFalse(label.contains("private-"));
        assertFalse(label.contains("\n"));
        assertTrue(label.length() < 1024);
        metadata.put("captureId", "c".repeat(200));
        assertTrue(AcpToolImageLabel.label("t", contents(metadata, image()), 1).contains("c".repeat(64) + "…"));
    }

    @Test
    void invalidFrameIndexStateAndCropDoNotReceiveFabricatedCoordinates() {
        var c = capture("c", "pose", 1);
        c.put("imageContentIndex", 1.5);
        assertEquals(
                "Tool image · t", AcpToolImageLabel.label("t", contents(Map.of("frames", List.of(c)), image()), 1));
        c = capture("c", null, 0);
        c.put("ok", true);
        c.put("state", Map.of("documentId", "doc", "modelId", "model", "historyRevision", -1));
        assertEquals("Tool image · t", AcpToolImageLabel.label("t", contents(c, image()), 1));
        c = capture("c", null, 0);
        c.put("ok", true);
        c.put("crop", Map.of("x", 999, "y", 2, "width", 2, "height", 3));
        assertFalse(AcpToolImageLabel.label("t", contents(c, image()), 1).contains("crop="));
    }
}
