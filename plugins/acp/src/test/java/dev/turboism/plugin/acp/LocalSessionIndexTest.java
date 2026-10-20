package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalSessionIndexTest {
    @TempDir
    Path directory;

    private LocalSessionIndex index(String harness, String executable) {
        return new LocalSessionIndex(directory, harness, new AgentLaunchSpec(List.of(executable), directory));
    }

    @Test
    void remembersOnlyOwnedSessionsAcrossReopen() throws Exception {
        var index = index("codex", "codex");
        assertTrue(index.sessions().isEmpty());
        index.remember("ours", "标题");
        assertEquals(
                List.of("ours"),
                index("codex", "codex").sessions().stream()
                        .map(AcpSessionSummary::sessionId)
                        .toList());
        assertEquals("标题", index.sessions().get(0).title());
    }

    @Test
    void isolatesHarnessesAndCustomConnectionConfigurations() throws Exception {
        index("codex", "codex").remember("same-id", "Codex");
        index("pi", "pi").remember("same-id", "Pi");
        assertEquals("Codex", index("codex", "codex").sessions().get(0).title());
        assertEquals("Pi", index("pi", "pi").sessions().get(0).title());
        assertTrue(index("custom", "/other/agent").sessions().isEmpty());
        index("custom", "/first/agent").remember("first", "First");
        assertTrue(index("custom", "/second/agent").sessions().isEmpty());
    }

    @Test
    void rememberingAgainUpdatesWithoutDuplicateOrErasingTitle() throws Exception {
        var index = index("pi", "pi");
        index.remember("one", "First request");
        index.remember("one", "");
        assertEquals(1, index.sessions().size());
        assertEquals("First request", index.sessions().get(0).title());
    }

    @Test
    void corruptIndexIsNotSilentlyOverwritten() throws Exception {
        var index = index("pi", "pi");
        index.remember("one", "Saved");
        Path file;
        try (var files = Files.list(directory.resolve("session-index"))) {
            file = files.findFirst().orElseThrow();
        }
        Files.writeString(file, "not json");
        assertThrows(java.io.IOException.class, () -> index.remember("two", "New"));
        assertEquals("not json", Files.readString(file));
    }
}
