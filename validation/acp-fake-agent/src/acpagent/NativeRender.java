package acpagent;

import dev.turboism.sdk.json.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Test-only render-to-ACP handoff; uses java.base so it runs in the restricted host JRE. */
final class NativeRender {
    record Capture(Map<String, ?> metadata, byte[] png, Map<String, Object> rawOutput) {}

    static Capture capture(BridgeClient bridge, long timeout) throws IOException {
        final var before = structured(call(bridge, "turboism.render.state", Map.of(), timeout));
        require(Boolean.TRUE.equals(before.get("ok")), "Render state unavailable");
        final var state = object(before.get("state"));
        require(state.get("documentId") instanceof String && state.get("modelId") instanceof String
                && state.get("historyRevision") instanceof Number, "Render identity missing");
        final var response = call(bridge, "turboism.render.capture",
                Map.of("expectedState", state, "width", 256, "height", 256), timeout);
        final var metadata = structured(response);
        require(!Boolean.TRUE.equals(response.get("isError")) && Boolean.TRUE.equals(metadata.get("ok")), "Render failed");
        require(state.equals(metadata.get("state")) && before.get("parameters").equals(metadata.get("parameters")), "Render pose mismatch");
        require("cubism.native.renderPixels".equals(metadata.get("renderer")), "Wrong renderer");
        require(number(metadata.get("width")) == 256 && number(metadata.get("height")) == 256, "Wrong dimensions");
        require(response.get("content") instanceof List<?> blocks && blocks.size() == 2, "Wrong render blocks");
        final var blocks = (List<?>) response.get("content");
        final var text = object(blocks.get(0));
        final var image = object(blocks.get(1));
        require("text".equals(text.get("type")) && text.get("text") instanceof String json
                && Json.parseObject(json).equals(metadata), "Metadata text mismatch");
        require("image".equals(image.get("type")) && "image/png".equals(image.get("mimeType"))
                && image.get("data") instanceof String encoded && encoded.length() <= 4 * 1024 * 1024, "Typed image missing");
        final byte[] png = Base64.getDecoder().decode((String) image.get("data"));
        require(png.length >= 33 && Arrays.equals(Arrays.copyOf(png, 8),
                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}), "Invalid PNG signature");
        final var header = ByteBuffer.wrap(png);
        require(header.getInt(8) == 13 && header.getInt(12) == 0x49484452
                && header.getInt(16) == 256 && header.getInt(20) == 256, "Invalid PNG dimensions");
        require(number(metadata.get("byteLength")) == png.length && sha256(png).equals(metadata.get("sha256")), "PNG hash mismatch");
        final var after = structured(call(bridge, "turboism.render.state", Map.of(), timeout));
        require(state.equals(after.get("state")) && before.get("parameters").equals(after.get("parameters")), "Capture mutated state");
        return new Capture(metadata, png, response);
    }

    private static Map<String, Object> call(BridgeClient bridge, String name, Map<String, ?> arguments, long timeout) throws IOException {
        return bridge.request("tools/call", Map.of("name", name, "arguments", arguments), timeout);
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected object");
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> structured(Map<String, ?> response) throws IOException {
        require(response.get("structuredContent") instanceof Map<?, ?>, "Missing structured content");
        return (Map<String, ?>) response.get("structuredContent");
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : -1; }
    private static void require(boolean condition, String reason) throws IOException { if (!condition) throw new IOException(reason); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
