package dev.turboism.tests.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.model.Color;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsHistorySeedValidationProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> MAIN_REQUIRED_PHASES = List.of(
        "baseline", "write-1", "write-2", "third-write", "group", "undo", "redo", "restored"
    );
    private static final List<String> REQUIRED_PHASES = List.of(
        "baseline", "write-1", "write-2", "third-write", "group", "undo", "redo", "restored",
        "artmesh-baseline", "artmesh-write-1", "artmesh-write-2", "artmesh-third-write",
        "artmesh-undo", "artmesh-redo",
        "relation-baseline", "relation-write-1", "relation-write-2", "relation-undo",
        "relation-redo", "relation-mcp-write"
    );

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
            assertEquals("ordinal-label-supporting-only", pair.get("nativePairing").asText());
            assertEquals(false, pair.get("nativeStableIdMatch").asBoolean());
            final JsonNode pairingValidation = JSON.readTree(lines.get(1));
            assertEquals("paired-validation", pairingValidation.get("type").asText());
            assertEquals("PASS", pairingValidation.get("status").asText());
            assertEquals("ordinal-label-supporting-only", pairingValidation.get("nativePairing").asText());
            assertEquals(false, pairingValidation.get("nativeStableIdMatch").asBoolean());
            assertTrue(pair.has("nativeManagers"));
            assertTrue(pair.has("sdkHistory"));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void omittedArtmeshCheckpointCannotProducePass() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-artmesh-omission", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            for (String phase : MAIN_REQUIRED_PHASES) {
                evidence.pairedSample(phase, snapshot("entry-1"));
            }
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            final JsonNode missing = JSON.readTree(lines.get(lines.size() - 2));
            assertEquals("paired-validation", missing.get("type").asText());
            assertTrue(missing.get("errors").toString().contains("artmesh-baseline"));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void exactAbsentOptionalManagerSentinelIsAccepted() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-optional-manager", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            for (String phase : REQUIRED_PHASES) {
                evidence.pairedSample(phase, snapshotWithOptionalAbsence("entry-1"));
            }
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            final JsonNode pair = JSON.readTree(lines.get(0));
            assertEquals("null", pair.get("nativeManagers").get(1).get("identity").asText());
            assertEquals("PASS", JSON.readTree(lines.get(1)).get("status").asText());
            assertEquals("PASS", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void malformedOptionalManagerSentinelCannotProducePass() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-malformed-manager", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            for (String phase : REQUIRED_PHASES) {
                evidence.pairedSample(phase, snapshotWithMalformedOptionalManager("entry-1"));
            }
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream().anyMatch(line -> line.contains("CURRENT-native-manager-identity-missing")));
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
            for (String phase : REQUIRED_PHASES) {
                if (phase.equals("baseline") || phase.equals("write-1")) continue;
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
            for (String phase : REQUIRED_PHASES) {
                if (phase.equals("baseline") || phase.equals("write-1")) continue;
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
            for (String phase : REQUIRED_PHASES) {
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

    @Test
    void completeGroupWithUnavailablePostStateCanPassAllRequiredPhases() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-group-post-state", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            final WindowsHistoryManagerValidationProbe.NativeDetail detail = group(
                1,
                List.of(simpleUnavailable()),
                ""
            );
            pairAll(evidence, snapshotWithNativeDetail(detail, "entry-1"));
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream()
                .filter(line -> line.contains("\"type\":\"paired-validation\""))
                .allMatch(line -> line.contains("\"status\":\"PASS\"")));
            assertTrue(lines.stream().anyMatch(line ->
                line.contains("history.detail.post-state-unavailable")));
            assertEquals("PASS", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void actualGroupTruncationCannotProducePassAcrossAllRequiredPhases() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-group-truncated", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            final WindowsHistoryManagerValidationProbe.NativeDetail detail = group(
                2,
                List.of(simpleUnavailable()),
                "history.detail.group-truncated"
            );
            pairAll(evidence, snapshotWithNativeDetail(detail, "entry-1"));
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream().anyMatch(line ->
                line.contains("DOCUMENT-native-detail-truncated")));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }

    @Test
    void decoderFailureInsideCompleteGroupCannotProducePassAcrossAllRequiredPhases() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-group-decoder-failure", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            final WindowsHistoryManagerValidationProbe.NativeDetail failed =
                WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                    "com.live2d.undo.SimpleUndo",
                    "FAILED",
                    "history.detail.decoder-failed"
                );
            pairAll(evidence, snapshotWithNativeDetail(group(1, List.of(failed), ""), "entry-1"));
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream().anyMatch(line ->
                line.contains("DOCUMENT-native-detail-failed")));
            assertEquals("FAIL", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
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

    private static WindowsHistoryManagerValidationProbe.Snapshot snapshotWithNativeDetail(
        final WindowsHistoryManagerValidationProbe.NativeDetail detail,
        final String... ids
    ) {
        final List<WindowsHistoryManagerValidationProbe.Entry> nativeEntries = new ArrayList<>();
        final List<WindowsHistoryManagerValidationProbe.SdkEntry> sdkEntries = new ArrayList<>();
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

    private static WindowsHistoryManagerValidationProbe.NativeDetail simpleUnavailable() {
        return new WindowsHistoryManagerValidationProbe.NativeDetail(
            "SIMPLE", "com.live2d.undo.SimpleUndo", "target", "", "", "", "", -1,
            0, List.of(), List.of(), true, false, "history.detail.post-state-unavailable"
        );
    }

    private static WindowsHistoryManagerValidationProbe.NativeDetail group(
        final int observedChildCount,
        final List<WindowsHistoryManagerValidationProbe.NativeDetail> children,
        final String degradationCode
    ) {
        return new WindowsHistoryManagerValidationProbe.NativeDetail(
            "GROUP", "com.live2d.undo.GroupUndo", "", "", "", "", "", -1,
            observedChildCount,
            children.stream().map(WindowsHistoryManagerValidationProbe.NativeDetail::entryClass).toList(),
            children,
            false,
            false,
            degradationCode
        );
    }

    private static void pairAll(
        final WindowsHistorySeedValidationProbe.Evidence evidence,
        final WindowsHistoryManagerValidationProbe.Snapshot snapshot
    ) {
        for (String phase : REQUIRED_PHASES) evidence.pairedSample(phase, snapshot);
    }

    private static WindowsHistoryManagerValidationProbe.Snapshot snapshotWithOptionalAbsence(final String... ids) {
        final WindowsHistoryManagerValidationProbe.Snapshot base = snapshot(ids);
        return new WindowsHistoryManagerValidationProbe.Snapshot(
            base.observedAt(), base.thread(), base.edt(), base.hostLoader(), base.documentIdentity(),
            base.currentModeClass(), base.currentModeIdentity(), base.document(),
            absentManager("CURRENT"), absentManager("MAIN"), absentManager("LINKED"), base.sdkHistory()
        );
    }

    private static WindowsHistoryManagerValidationProbe.Snapshot snapshotWithMalformedOptionalManager(
        final String... ids
    ) {
        final WindowsHistoryManagerValidationProbe.Snapshot base = snapshot(ids);
        final WindowsHistoryManagerValidationProbe.ManagerSnapshot malformed =
            new WindowsHistoryManagerValidationProbe.ManagerSnapshot(
                "CURRENT", "null", -1, false, false, 0, base.current().entries()
            );
        return new WindowsHistoryManagerValidationProbe.Snapshot(
            base.observedAt(), base.thread(), base.edt(), base.hostLoader(), base.documentIdentity(),
            base.currentModeClass(), base.currentModeIdentity(), base.document(), malformed,
            base.main(), base.linked(), base.sdkHistory()
        );
    }

    private static WindowsHistoryManagerValidationProbe.ManagerSnapshot absentManager(final String name) {
        return new WindowsHistoryManagerValidationProbe.ManagerSnapshot(
            name, "null", -1, false, false, 0, List.of()
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

    @Test
    void capturedEndpointMustEqualTheObservedNativeParent() {
        final WindowsHistorySeedValidationProbe.NativeParent partParent =
            new WindowsHistorySeedValidationProbe.NativeParent(Optional.of("PartA"), Optional.empty());
        assertTrue(WindowsHistorySeedValidationProbe.endpointMatches(
            target("PART", "PartA", "Part A"), partParent));
        assertFalse(WindowsHistorySeedValidationProbe.endpointMatches(
            target("PART", "PartB", "Part B"), partParent));
        assertTrue(WindowsHistorySeedValidationProbe.endpointMatches(
            target("WARP_DEFORMER", "WarpA", "Warp A"),
            new WindowsHistorySeedValidationProbe.NativeParent(Optional.empty(), Optional.of("WarpA"))));
        assertFalse(WindowsHistorySeedValidationProbe.endpointMatches(
            root(), partParent));
        assertTrue(WindowsHistorySeedValidationProbe.endpointMatches(
            root(),
            new WindowsHistorySeedValidationProbe.NativeParent(Optional.empty(), Optional.empty())));
    }

    @Test
    void capturedEndpointWithoutIdentityOrNameCannotMatch() {
        final WindowsHistorySeedValidationProbe.NativeParent partParent =
            new WindowsHistorySeedValidationProbe.NativeParent(Optional.of("PartA"), Optional.empty());
        assertFalse(WindowsHistorySeedValidationProbe.endpointMatches(
            new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.TARGET,
                Optional.of(new HistoryTarget("PART", Optional.of("PartA"), Optional.empty()))
            ),
            partParent));
        assertFalse(WindowsHistorySeedValidationProbe.endpointMatches(
            new HistoryRelationChange.Endpoint(HistoryRelationChange.State.UNKNOWN, Optional.empty()),
            partParent));
        assertFalse(WindowsHistorySeedValidationProbe.endpointMatches(root(), new WindowsHistorySeedValidationProbe.NativeParent(
            Optional.empty(), Optional.of("WarpA"))));
    }

    @Test
    void capturedEndpointDescriptionKeepsStateAndIdentity() {
        assertEquals("PART:PartA", WindowsHistorySeedValidationProbe.describeEndpoint(
            target("PART", "PartA", "Part A")));
        assertEquals("ROOT", WindowsHistorySeedValidationProbe.describeEndpoint(root()));
        assertEquals(
            "UNKNOWN",
            WindowsHistorySeedValidationProbe.describeEndpoint(new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.UNKNOWN,
                Optional.empty()
            ))
        );
    }

    private static HistoryRelationChange.Endpoint target(
        final String type,
        final String id,
        final String displayName
    ) {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.TARGET,
            Optional.of(new HistoryTarget(type, Optional.of(id), Optional.of(displayName)))
        );
    }

    private static HistoryRelationChange.Endpoint root() {
        return new HistoryRelationChange.Endpoint(HistoryRelationChange.State.ROOT, Optional.empty());
    }

    @Test
    void recordedObservationDoesNotGateTheSummary() throws Exception {
        final Path artifact = Files.createTempFile("history-seed-observation", ".jsonl");
        try {
            final WindowsHistorySeedValidationProbe.Evidence evidence =
                new WindowsHistorySeedValidationProbe.Evidence(artifact);
            pairAll(evidence, snapshot("entry-1"));
            evidence.observation("relation-deformer-root", "supporting only", "level=FULL");
            evidence.summary();

            final List<String> lines = Files.readAllLines(artifact);
            assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"observation\"")));
            assertEquals("PASS", JSON.readTree(lines.get(lines.size() - 1)).get("status").asText());
        } finally {
            Files.deleteIfExists(artifact);
        }
    }
}
