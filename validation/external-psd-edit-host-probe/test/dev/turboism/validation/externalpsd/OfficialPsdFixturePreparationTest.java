package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Offline focused checks for the official PSD preparation gates. */
public final class OfficialPsdFixturePreparationTest {
    private static final String PSD_SHA =
        "8b760eb0b6ac5839271210aa0efc681f6a02a6537c1ab97d40a3a56879d8f02c";
    private static final String RGB_SHA =
        "12eca5a1c8d8b9096384c974d52e8f0310c4ad9ac4ea87a072684096cf2b808d";

    public static void main(final String[] args) {
        testInputBindingAndPsdPolicy();
        testRelationGate();
        testPostSaveModelGate();
        testSaveAfterIdentityGate();
        System.out.println("PASS: OfficialPsdFixturePreparationTest");
    }

    private static void testInputBindingAndPsdPolicy() {
        final OfficialPsdFixturePreparation.InputIdentity input =
            OfficialPsdFixturePreparation.validateInputForTest(
                "C:\\task\\run-native-seven-layer.psd", PSD_SHA,
                "run-native-seven-layer.psd", "queue-1", "queue-1",
                "C:\\task\\home\\prepared-control.cmo3", RGB_SHA, "5.3.02", 30_000L);
        assertEquals("queue-1", input.runId(), "run identity is retained");
        assertEquals("run-native-seven-layer.psd", input.fixtureName(),
            "task fixture basename is retained");
        assertEquals(30_000L, input.timeoutMillis(), "timeout is retained");

        expectReject("run/task mismatch", () -> OfficialPsdFixturePreparation.validateInputForTest(
            input.fixturePath(), PSD_SHA, input.fixtureName(), "queue-1", "task-2",
            input.savedCopyPath(), RGB_SHA, "5.3.02", 30_000L));
        expectReject("wrong PSD digest", () -> OfficialPsdFixturePreparation.validateInputForTest(
            input.fixturePath(), repeat('a'), input.fixtureName(), input.runId(), input.taskId(),
            input.savedCopyPath(), RGB_SHA, "5.3.02", 30_000L));
        expectReject("non-PSD task copy", () -> OfficialPsdFixturePreparation.validateInputForTest(
            "C:\\task\\run-native-seven-layer.cmo3", PSD_SHA, "run-native-seven-layer.cmo3",
            input.runId(), input.taskId(), input.savedCopyPath(), RGB_SHA, "5.3.02", 30_000L));
        expectReject("saved copy outside fixed basename", () -> OfficialPsdFixturePreparation
            .validateInputForTest(input.fixturePath(), PSD_SHA, input.fixtureName(), input.runId(),
                input.taskId(), "C:\\task\\home\\other.cmo3", RGB_SHA, "5.3.02", 30_000L));
        expectReject("fixture traversal", () -> OfficialPsdFixturePreparation.validateInputForTest(
            "C:\\task\\..\\outside.psd", PSD_SHA, "outside.psd", input.runId(), input.taskId(),
            input.savedCopyPath(), RGB_SHA, "5.3.02", 30_000L));
    }

    private static void testRelationGate() {
        final TextureRelationsSnapshot valid = relations(true, true, false);
        final OfficialPsdFixturePreparation.RelationIdentity identity =
            OfficialPsdFixturePreparation.validateRelationSnapshot("document", "model", valid);
        assertEquals(List.of("model-image"), identity.modelImageIds(),
            "all model-image IDs are recorded");
        assertEquals(List.of("raw-current"), identity.currentRawIds(),
            "all currentRaw IDs are recorded");
        assertEquals(List.of("raw-current"), identity.linkedRawIds(),
            "all linkedRaw IDs are recorded");
        assertTrue(identity.rawLayerBindings().contains("raw-layer"),
            "rawLayerBindings are recorded");

        expectReject("unavailable relations", () ->
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", TextureRelationsSnapshot.unavailable()));
        expectReject("missing currentRaw", () ->
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations(false, true, false)));
        expectReject("empty rawLayerBindings", () ->
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations(true, false, false)));
        expectReject("linkedRaw mismatch", () ->
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations(true, true, true)));
        expectReject("duplicate model-image identity", () ->
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", duplicateModelImages()));
    }

    private static void testSaveAfterIdentityGate() {
        final OfficialPsdFixturePreparation.RelationIdentity before =
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations(true, true, false));
        final OfficialPsdFixturePreparation.SaveAfterIdentity success =
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", true, "prepared-control.cmo3", "content");
        assertTrue(OfficialPsdFixturePreparation.saveAfterMatches(
            success, before, before, "prepared-control.cmo3", "window@1", "window@1"),
            "SAVE After accepts the unchanged relation/window identity");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", false, "prepared-control.cmo3", "content"),
            before, before, "prepared-control.cmo3", "window@1", "window@1"),
            "failed SAVE is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            success, before, before, "other.cmo3", "window@1", "window@1"),
            "wrong saved filename is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            success, before, new OfficialPsdFixturePreparation.RelationIdentity(
                before.documentId(), before.modelId(), before.binding(), before.generation() + 1,
                before.modelImageIds(), before.currentRawIds(), before.linkedRawIds(),
                before.rawLayerBindings()), "prepared-control.cmo3", "window@1", "window@1"),
            "changed relation generation is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            success, before, before, "prepared-control.cmo3", "window@1", "window@2"),
            "changed window identity is rejected");
    }

    private static void testPostSaveModelGate() {
        final OfficialPsdFixturePreparation.RelationIdentity identity =
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations(true, true, false));
        final OfficialPsdFixturePreparation.ModelState before =
            new OfficialPsdFixturePreparation.ModelState(
                "document", Optional.of("content-before"),
                "run-native-seven-layer.psd", "model", identity);
        final OfficialPsdFixturePreparation.ModelState after =
            new OfficialPsdFixturePreparation.ModelState(
                "document", Optional.of("content-after"),
                "C:\\task\\prepared-control.cmo3", "model", identity);
        assertTrue(OfficialPsdFixturePreparation.savedModelMatchesForTest(
            before, after, "prepared-control.cmo3"),
            "SAVE_AS accepts the target CMO filename with unchanged model identity");
        assertFalse(OfficialPsdFixturePreparation.savedModelMatchesForTest(
            before, after, "run-native-seven-layer.psd"),
            "post-SAVE_AS gate does not accept the source PSD filename");
        final OfficialPsdFixturePreparation.ModelState changed =
            new OfficialPsdFixturePreparation.ModelState(
                "document", Optional.of("content-after"),
                "C:\\task\\prepared-control.cmo3", "model",
                new OfficialPsdFixturePreparation.RelationIdentity(
                    identity.documentId(), identity.modelId(), identity.binding(),
                    identity.generation() + 1, identity.modelImageIds(), identity.currentRawIds(),
                    identity.linkedRawIds(), identity.rawLayerBindings()));
        assertFalse(OfficialPsdFixturePreparation.savedModelMatchesForTest(
            before, changed, "prepared-control.cmo3"),
            "post-SAVE_AS gate rejects changed relation generation");
    }

    private static TextureRelationsSnapshot relations(final boolean currentRaw,
        final boolean bindings, final boolean linkedMismatch) {
        final RawImageId raw = new RawImageId("raw-current");
        final RawImageId linked = linkedMismatch ? new RawImageId("raw-other") : raw;
        final RawLayerBinding binding = new RawLayerBinding(raw, new RawLayerId("raw-layer"), 0,
            RawLayerBinding.DetailAvailability.AVAILABLE,
            RawLayerBinding.DetailAvailability.AVAILABLE);
        final ModelImageRelation image = new ModelImageRelation(
            new ModelImageId("model-image"), new Entry(), List.of(linked),
            currentRaw ? Optional.of(raw) : Optional.empty(),
            bindings ? Map.of(raw, List.of(binding)) : Map.of(), List.of(new ArtMeshId("mesh")));
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            "binding", 4L, 1L, List.of(), List.of(image), List.of(), List.of());
    }

    private static TextureRelationsSnapshot duplicateModelImages() {
        final TextureRelationsSnapshot one = relations(true, true, false);
        final ModelImageRelation image = one.modelImages().get(0);
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            one.binding(), one.generation(), one.revision(), one.rawImages(),
            List.of(image, image), one.modelImageGroups(), one.artMeshInputs());
    }

    private static String repeat(final char value) {
        return String.valueOf(value).repeat(64);
    }

    private static void expectReject(final String description, final Runnable action) {
        try {
            action.run();
            throw new AssertionError(description + " unexpectedly accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank(),
                description + " has a diagnostic");
        }
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(final boolean condition, final String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(final Object expected, final Object actual,
        final String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static final class Entry implements ModelImageEntry {
        @Override public ModelImageId id() { return new ModelImageId("model-image"); }
        @Override public String name() { return "fixture"; }
        @Override public int width() { return 100; }
        @Override public int height() { return 100; }
    }
}
