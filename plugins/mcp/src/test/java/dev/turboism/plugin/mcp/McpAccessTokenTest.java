package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class McpAccessTokenTest {

    @TempDir
    Path stateDir;

    @Test
    void persistsAndReusesASixtyFourHexToken() throws Exception {
        final McpAccessToken first = McpAccessToken.loadOrCreate(stateDir);
        assertEquals(stateDir.resolve(McpAccessToken.FILE_NAME), first.file());

        final McpAccessToken second = McpAccessToken.loadOrCreate(stateDir);
        assertTrue(second.accepts("Bearer " + readToken()));
    }

    @Test
    void regeneratesAMalformedTokenFile() throws Exception {
        Files.writeString(stateDir.resolve(McpAccessToken.FILE_NAME), "not-a-token", StandardCharsets.UTF_8);

        final McpAccessToken token = McpAccessToken.loadOrCreate(stateDir);

        assertTrue(readToken().matches("[0-9a-f]{64}"));
        assertTrue(token.accepts("Bearer " + readToken()));
    }

    @Test
    void acceptsOnlyTheExactBearerValue() throws Exception {
        final McpAccessToken token = McpAccessToken.loadOrCreate(stateDir);
        final String value = readToken();

        assertTrue(token.accepts("Bearer " + value));
        assertTrue(token.accepts("bearer " + value));
        assertFalse(token.accepts(null));
        assertFalse(token.accepts(""));
        assertFalse(token.accepts(value));
        assertFalse(token.accepts("Token " + value));
        assertFalse(token.accepts("Bearer"));
        assertFalse(token.accepts("Bearer " + value.substring(1)));
        assertFalse(token.accepts("Bearer " + value + "ff"));
    }

    private String readToken() throws Exception {
        return Files.readString(stateDir.resolve(McpAccessToken.FILE_NAME), StandardCharsets.UTF_8)
                .strip();
    }
}
