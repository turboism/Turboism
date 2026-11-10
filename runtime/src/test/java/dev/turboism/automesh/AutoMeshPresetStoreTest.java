package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contracts of the preset file: lossless round trip, and a fail-open read.
 *
 * <p>The store carries the user's preset list, so a name, an algorithm id or a parameter id must
 * survive a write and a read unchanged. It is also advisory state: every unreadable file must answer
 * an empty snapshot with one diagnostic instead of failing the dialog that reads it.</p>
 */
class AutoMeshPresetStoreTest {

    private static final String ALGORITHM_ID = "turbo-mesh.region";
    private static final String UNICODE_PRESET = "高密度 プリセット";
    private static final String FILE_NAME = "automesh-presets.properties";
    private static final int OVERSIZED_PADDING_BYTES = (int) AutoMeshPresetStore.MAX_BYTES + 1;

    @TempDir
    Path directory;

    private final List<String> diagnostics = new ArrayList<>();

    @Test
    void everyIdentifierAndNameSurvivesAWriteAndARead() {
        final AutoMeshPresetStore store = store();
        final AutoMeshPresetStore.Snapshot snapshot = new AutoMeshPresetStore.Snapshot(Map.of(
                ALGORITHM_ID,
                new AutoMeshPresetStore.AlgorithmState(
                        true,
                        List.of(new AlgorithmPreset(UNICODE_PRESET, Map.of("edge.density", "96", "weld", "false"))),
                        Optional.of(UNICODE_PRESET),
                        Map.of("edge.density", "96", "weld", "false"))));

        store.save(snapshot);

        assertEquals(snapshot.algorithms(), store.load().algorithms(), "a stored state must read back unchanged");
        assertTrue(diagnostics.isEmpty(), "a clean write and read must report nothing, got: " + diagnostics);
    }

    @Test
    void aMissingFileAnswersAnEmptySnapshot() {
        assertTrue(store().load().algorithms().isEmpty(), "an absent file means no customization yet");
        assertTrue(diagnostics.isEmpty(), "an absent file is not a problem");
    }

    @Test
    void aSymlinkedFileIsRefusedWithOneDiagnostic() throws Exception {
        final Path real = directory.resolve("elsewhere.properties");
        Files.writeString(real, "version=1\n", StandardCharsets.UTF_8);
        final Path link = directory.resolve(FILE_NAME);
        Files.createSymbolicLink(link, real);

        assertTrue(new AutoMeshPresetStore(link, diagnostics::add)
                .load()
                .algorithms()
                .isEmpty());
        assertEquals(1, diagnostics.size(), "one refused read reports exactly one line");
    }

    @Test
    void anOversizedFileIsRefusedWithOneDiagnostic() throws Exception {
        final Path file = directory.resolve(FILE_NAME);
        Files.write(file, new byte[OVERSIZED_PADDING_BYTES]);

        assertTrue(new AutoMeshPresetStore(file, diagnostics::add)
                .load()
                .algorithms()
                .isEmpty());
        assertEquals(1, diagnostics.size(), "one refused read reports exactly one line");
    }

    @Test
    void anUnrecognizedEntryIsSkippedWhileTheRestStaysReadable() throws Exception {
        final Path file = directory.resolve(FILE_NAME);
        Files.writeString(
                file,
                "version=1\n" + "nonsense=1\n" + "algorithm.turbo-mesh%2Eregion.selectedPreset=Fine\n",
                StandardCharsets.UTF_8);

        final AutoMeshPresetStore.Snapshot snapshot = new AutoMeshPresetStore(file, diagnostics::add).load();

        assertEquals(
                Optional.of("Fine"),
                snapshot.algorithms().get(ALGORITHM_ID).selectedPreset(),
                "the readable entry must survive a corrupt neighbour");
        assertEquals(1, diagnostics.size(), "the corrupt entry reports exactly one line");
    }

    @Test
    void theInMemoryStoreWritesNothingAndReadsNothing() {
        final AutoMeshPresetStore store = AutoMeshPresetStore.inMemory();

        store.save(new AutoMeshPresetStore.Snapshot(Map.of(
                ALGORITHM_ID, new AutoMeshPresetStore.AlgorithmState(true, List.of(), Optional.empty(), Map.of()))));

        assertTrue(store.load().algorithms().isEmpty(), "an in-memory store keeps no state on disk");
    }

    private AutoMeshPresetStore store() {
        return new AutoMeshPresetStore(directory.resolve(FILE_NAME), diagnostics::add);
    }
}
