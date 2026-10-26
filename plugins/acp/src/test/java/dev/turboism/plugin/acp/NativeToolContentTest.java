package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class NativeToolContentTest {
    @Test
    void embeddedImageAndResourceLinkRetainTypesAndIdentity() {
        var resource = Map.of("uri", "turboism://render/7", "mimeType", "image/png", "blob", "cGl4ZWxz");
        var result = NativeToolContent.from(Map.of(
                "content",
                List.of(
                        Map.of("type", "resource", "resource", resource),
                        Map.of("type", "resource_link", "uri", "turboism://snapshot/7", "name", "snapshot"))));
        assertEquals(resource, ((Map<?, ?>) result.get(0).get("content")).get("resource"));
        assertEquals("resource_link", ((Map<?, ?>) result.get(1).get("content")).get("type"));
    }

    @Test
    void unsupportedBinaryAndMalformedImageAreSummarizedWithoutDumpingData() {
        var result = NativeToolContent.from(List.of(
                Map.of("type", "audio", "data", "sensitive-audio-bytes"),
                Map.of("type", "image", "source", Map.of("type", "url", "url", "https://example.invalid/image"))));
        assertFalse(result.toString().contains("sensitive-audio-bytes"));
        assertFalse(result.toString().contains("example.invalid"));
        assertTrue(result.toString().contains("unsupported image representation"));
    }

    @Test
    void ordinaryStructuredResultsAndStringsRemainReadable() {
        assertEquals(
                "hello", ((Map<?, ?>) NativeToolContent.from("hello").get(0).get("content")).get("text"));
        var result = NativeToolContent.from(Map.of("changed", List.of("head"), "ok", true));
        assertTrue(((Map<?, ?>) result.get(0).get("content"))
                .get("text")
                .toString()
                .contains("head"));
    }
}
