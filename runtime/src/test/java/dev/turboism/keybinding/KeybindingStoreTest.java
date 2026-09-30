package dev.turboism.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeybindingStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("keybindings.properties");
    }

    private static KeybindingStore.Snapshot snapshot(
            final Map<String, String> pluginStates,
            final Map<String, String> nativeStates,
            final Map<String, String> nativeKeys,
            final List<KeybindingStore.CustomNative> customs) {
        return new KeybindingStore.Snapshot(pluginStates, nativeStates, nativeKeys, customs, List.of());
    }

    @Test
    void missingFileLoadsEmpty() {
        KeybindingStore.Snapshot snapshot = new KeybindingStore(file()).load();
        assertTrue(snapshot.pluginStates().isEmpty());
        assertTrue(snapshot.nativeStates().isEmpty());
        assertTrue(snapshot.nativeKeyOverrides().isEmpty());
        assertTrue(snapshot.customs().isEmpty());
        assertTrue(snapshot.problems().isEmpty());
    }

    @Test
    void saveLoadRoundTrip() throws Exception {
        KeybindingStore store = new KeybindingStore(file());
        String pluginKey = "dev.example.plugin" + '\0' + "example.run";
        store.save(snapshot(
                Map.of(pluginKey, "BOUND:Ctrl+Alt+K"),
                Map.of("save", "BOUND:F2", "undo", "DISABLED"),
                Map.of("save", "Ctrl+Alt+S"),
                List.of(new KeybindingStore.CustomNative("custom.1", "My Command", "Ctrl+F9", 0))));

        KeybindingStore.Snapshot loaded = store.load();
        assertEquals(Map.of(pluginKey, "BOUND:Ctrl+Alt+K"), loaded.pluginStates());
        assertEquals(Map.of("save", "BOUND:F2", "undo", "DISABLED"), loaded.nativeStates());
        assertEquals(Map.of("save", "Ctrl+Alt+S"), loaded.nativeKeyOverrides());
        assertEquals(
                List.of(new KeybindingStore.CustomNative("custom.1", "My Command", "Ctrl+F9", 0)), loaded.customs());
        assertTrue(loaded.problems().isEmpty());
    }

    @Test
    void customRowsRestoreInPersistedOrder() throws Exception {
        KeybindingStore store = new KeybindingStore(file());
        store.save(snapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(
                        new KeybindingStore.CustomNative("custom.2", "Second", "Ctrl+F2", 2),
                        new KeybindingStore.CustomNative("custom.1", "First", "Ctrl+F1", 1))));

        KeybindingStore.Snapshot loaded = store.load();
        assertEquals("custom.1", loaded.customs().get(0).id());
        assertEquals("custom.2", loaded.customs().get(1).id());
    }

    @Test
    void malformedEntriesAreReportedAndSkipped() throws Exception {
        Files.writeString(file(), """
                version=1
                bind.plugin.dev.example/example=BOUND:Ctrl+Q
                bind.native.save=BOUND:F2
                key.native.broken=not a stroke
                nonsense.entry=1
                """);
        KeybindingStore.Snapshot loaded = new KeybindingStore(file()).load();
        assertEquals(Map.of("save", "BOUND:F2"), loaded.nativeStates());
        assertEquals(Map.of("dev.example" + '\0' + "example", "BOUND:Ctrl+Q"), loaded.pluginStates());
        assertFalse(loaded.problems().isEmpty());
    }

    @Test
    void unicodeLabelsRoundTrip() throws Exception {
        KeybindingStore store = new KeybindingStore(file());
        store.save(snapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(new KeybindingStore.CustomNative("custom.1", "自定义命令 かな", "Ctrl+F9", 0))));
        assertEquals("自定义命令 かな", store.load().customs().get(0).label());
    }
}
