package dev.turboism.tests.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.cubism.model.Color;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsHistorySeedValidationProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void choosesFiniteDistinctValueInsideRange() {
        final float selected = WindowsHistorySeedValidationProbe.alternate(0.0F, -1.0F, 1.0F);
        assertNotEquals(0.0F, selected);
        assertEquals(-0.26F, selected, 0.0001F);
    }

    @Test
    void fallsBackWhenFirstCandidateMatches() {
        assertEquals(0.63F, WindowsHistorySeedValidationProbe.alternate(0.37F, 0.0F, 1.0F), 0.0001F);
    }

    @Test
    void selectsDeterministicDistinctHistoryValues() {
        assertEquals(-6.6F, WindowsHistorySeedValidationProbe.valueAt(-10.0F, 10.0F, 0.17F), 0.0001F);
        assertEquals(-1.4F, WindowsHistorySeedValidationProbe.valueAt(-10.0F, 10.0F, 0.43F), 0.0001F);
        assertEquals(4.2F, WindowsHistorySeedValidationProbe.valueAt(-10.0F, 10.0F, 0.71F), 0.0001F);
    }

    @Test
    void usesTwoDistinctDeterministicArtmeshColors() {
        final Color first = WindowsHistorySeedValidationProbe.artMeshProbeColorA();
        final Color second = WindowsHistorySeedValidationProbe.artMeshProbeColorB();

        assertNotEquals(first, second);
        assertEquals("#224466", WindowsHistorySeedValidationProbe.rgb(first));
        assertEquals("#6688aa", WindowsHistorySeedValidationProbe.rgb(second));
        assertEquals(1.0F, first.alpha());
        assertEquals(1.0F, second.alpha());
    }

    @Test
    void serializesNativeAndSdkEvidenceWithItsCheckpointPhase() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-phase", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            evidence.pairedSample("baseline", snapshot("entry-1"));
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            final JsonNode pair = JSON.readTree(lines.get(0));
            assertEquals("paired-snapshot", pair.get("type").asText());
            assertEquals("baseline", pair.get("phase").asText());
            assertEquals("same-edt-read-only-manager-sampler", pair.get("nativeEvidence").asText());
            assertEquals("captured-operation-metadata", pair.get("sdkEvidence").asText());
            assertTrue(pair.has("nativeManagers"));
            assertTrue(pair.has("sdkHistory"));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void samplerFailureCannotProducePass() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-failure", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            evidence.pairedSample("baseline", snapshot("entry-1"));
            evidence.pairedFailure("write-1", new IllegalStateException("sampler failed"));
            for (String phase : List.of("write-2", "third-write", "group", "undo", "redo", "restored")) {
                evidence.pairedSample(phase, snapshot("entry-1"));
            }
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream().anyMatch(line -> line.contains("paired-snapshot-failure")));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void identityOrSequenceMismatchCannotProducePass() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-mismatch", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            evidence.pairedSample("baseline", snapshot("entry-1"));
            evidence.pairedSample("write-1", snapshot("different-entry"));
            for (String phase : List.of("write-2", "third-write", "group", "undo", "redo", "restored")) {
                evidence.pairedSample(phase, snapshot("entry-1"));
            }
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream().anyMatch(line -> line.contains("sdk-sequence-identity-mismatch")));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void primaryEvidenceIsBoundedJsonlWithTerminalSummary() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-jsonl", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            for (String phase : List.of(
                "baseline", "write-1", "write-2", "third-write", "group", "undo", "redo", "restored"
            )) {
                evidence.pairedSample(phase, snapshot("entry-1"));
            }
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            for (String line : lines) {
                JSON.readTree(line);
                assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    <= WindowsHistoryManagerValidationProbe.MAX_EVIDENCE_BYTES);
            }
            assertTrue(Files.size(artifact) <= WindowsHistoryManagerValidationProbe.MAX_EVIDENCE_BYTES);
            assertEquals("PASS", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    private static WindowsHistoryManagerValidationProbe.Snapshot snapshot(final String... ids) {
        final List<WindowsHistoryManagerValidationProbe.Entry> nativeEntries = new ArrayList<>();
        final List<WindowsHistoryManagerValidationProbe.SdkEntry> sdkEntries = new ArrayList<>();
        final WindowsHistoryManagerValidationProbe.NativeDetail detail =
            new WindowsHistoryManagerValidationProbe.NativeDetail(
                "PROPERTY", "com.live2d.undo.PropertyUndo", "target", "value",
                "0", "1", "", -1, 0, List.of(), List.of(), true, true, ""
            );
        for (int index = 0; index < ids.length; index++) {
            nativeEntries.add(new WindowsHistoryManagerValidationProbe.Entry(
                index, "entry-" + index, true, detail
            ));
            sdkEntries.add(new WindowsHistoryManagerValidationProbe.SdkEntry(
                index, ids[index], "entry-" + index, "{}"
            ));
        }
        final WindowsHistoryManagerValidationProbe.ManagerSnapshot document = manager(
            "DOCUMENT", nativeEntries, ids.length
        );
        return new WindowsHistoryManagerValidationProbe.Snapshot(
            "2024-01-01T00:00:00Z", "AWT-EventQueue-0", true, "loader@1",
            "document@1", "ModelingMode", "mode@1", document,
            manager("CURRENT", nativeEntries, ids.length),
            manager("MAIN", nativeEntries, ids.length),
            manager("LINKED", nativeEntries, ids.length),
            new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot(
                "AVAILABLE", 1L, 1L, ids.length, true, false,
                "history-document-1", "history-manager-1", ids.length, sdkEntries
            )
        );
    }

    private static WindowsHistoryManagerValidationProbe.ManagerSnapshot manager(
        final String name,
        final List<WindowsHistoryManagerValidationProbe.Entry> entries,
        final int position
    ) {
        return new WindowsHistoryManagerValidationProbe.ManagerSnapshot(
            name, "native-" + name, position, true, false, entries.size(), entries
        );
    }
}
