package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.event.cubism.ProjectFileLifecycleEvent;

import java.awt.Window;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.DefaultListCellRenderer;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/** Offline focused checks for the official PSD preparation gates. */
public final class OfficialPsdFixturePreparationTest {
    private static final String PSD_SHA =
        "8b760eb0b6ac5839271210aa0efc681f6a02a6537c1ab97d40a3a56879d8f02c";
    private static final String RGB_SHA =
        "12eca5a1c8d8b9096384c974d52e8f0310c4ad9ac4ea87a072684096cf2b808d";
    private static final String F1_RUN_ID = "queue-f1";
    private static final String F1_TASK_FIXTURE_NAME =
        F1_RUN_ID + "-f1-2048-20layers.psd";

    public static void main(final String[] args) {
        testInputBindingAndPsdPolicy();
        testPreparationNamespaceIsolation();
        testRelationGate();
        testF1SourceAndSharingGate();
        testF1CopyPasteAdmission();
        testWindowBindingAndChooserGate();
        testChooserCandidateRenderer();
        testInitialSourceGate();
        testPostSaveModelGate();
        testSaveCommandAdmission();
        testAwaitEdtObservationBudget();
        testSaveAfterIdentityGate();
        testOfficialJarAccessorShape();
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
        assertEquals("CUB3-0418", OfficialPsdFixturePreparation.profileKeyForTest("normal"),
            "normal selects the follow-target chooser option");
        assertEquals(0, OfficialPsdFixturePreparation.profileChooserIndexForTest("normal"),
            "normal chooser index is reviewed");
        assertEquals("prepared-control.cmo3",
            OfficialPsdFixturePreparation.profileSavedCopyBasenameForTest("normal"),
            "normal output basename is retained");

        final OfficialPsdFixturePreparation.InputIdentity legacy =
            OfficialPsdFixturePreparation.validateInputForProfileForTest(
                input.fixturePath(), PSD_SHA, input.fixtureName(), input.runId(), input.taskId(),
                "C:\\task\\home\\prepared-control-legacy.cmo3", RGB_SHA, "5.3.02", 30_000L,
                "legacy");
        assertEquals("C:\\task\\home\\prepared-control-legacy.cmo3", legacy.savedCopyPath(),
            "legacy output is task-bound to its independent basename");
        assertEquals("CUB3-4408", OfficialPsdFixturePreparation.profileKeyForTest("legacy"),
            "legacy selects the older-mode chooser option");
        assertEquals(1, OfficialPsdFixturePreparation.profileChooserIndexForTest("legacy"),
            "legacy chooser index is reviewed");
        assertEquals("prepared-control-legacy.cmo3",
            OfficialPsdFixturePreparation.profileSavedCopyBasenameForTest("legacy"),
            "legacy output basename is independent");

        expectReject("unknown preparation profile", () -> OfficialPsdFixturePreparation
            .profileKeyForTest("unsupported"));
        expectReject("legacy output cannot use normal basename", () ->
            OfficialPsdFixturePreparation.validateInputForProfileForTest(
                input.fixturePath(), PSD_SHA, input.fixtureName(), input.runId(), input.taskId(),
                input.savedCopyPath(), RGB_SHA, "5.3.02", 30_000L, "legacy"));

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

        final OfficialPsdFixturePreparation.InputIdentity f1 =
            OfficialPsdFixturePreparation.validateInputForProfileForTest(
                "C:\\task\\" + F1_TASK_FIXTURE_NAME,
                OfficialPsdFixturePreparation.F1_FIXTURE_SHA256,
                F1_TASK_FIXTURE_NAME,
                F1_RUN_ID, F1_RUN_ID, "C:\\task\\home\\prepared-f1.cmo3", "",
                "5.3.02", 30_000L, "f1");
        assertEquals(OfficialPsdFixturePreparation.F1_SAVED_COPY_BASENAME,
            OfficialPsdFixturePreparation.profileSavedCopyBasenameForTest("f1"),
            "F1 output basename is independent");
        assertEquals("CUB3-0418", OfficialPsdFixturePreparation.profileKeyForTest("f1"),
            "F1 initializes the official first chooser option");
        assertEquals(0, OfficialPsdFixturePreparation.profileChooserIndexForTest("f1"),
            "F1 chooser index is the first new-model option");
        assertEquals("", f1.targetRgbSha256(), "F1 has no seven-layer RGB admission");
        final Properties expandedRunnerInput = new Properties();
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.FIXTURE_PROPERTY,
            "C:\\task\\" + F1_TASK_FIXTURE_NAME);
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.FIXTURE_SHA256_PROPERTY,
            OfficialPsdFixturePreparation.F1_FIXTURE_SHA256);
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.FIXTURE_NAME_PROPERTY,
            F1_TASK_FIXTURE_NAME);
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.RUN_ID_PROPERTY, F1_RUN_ID);
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.TASK_ID_PROPERTY, F1_RUN_ID);
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.SAVED_COPY_PROPERTY,
            "C:\\task\\home\\prepared-f1.cmo3");
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.HOST_VERSION_PROPERTY,
            "5.3.02");
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.TIMEOUT_MILLIS_PROPERTY,
            "30000");
        expandedRunnerInput.setProperty(OfficialPsdFixturePreparation.PROFILE_PROPERTY, "f1");
        assertEquals(F1_TASK_FIXTURE_NAME,
            OfficialPsdFixturePreparation.readInputForTest(expandedRunnerInput).fixtureName(),
            "production readInput accepts the runner-expanded F1 task copy");
        expectReject("F1 cannot accept seven-layer RGB target", () ->
            OfficialPsdFixturePreparation.validateInputForProfileForTest(
                f1.fixturePath(), OfficialPsdFixturePreparation.F1_FIXTURE_SHA256,
                f1.fixtureName(), f1.runId(), f1.taskId(), f1.savedCopyPath(), RGB_SHA,
                "5.3.02", 30_000L, "f1"));
        expectReject("F1 wrong run prefix", () ->
            OfficialPsdFixturePreparation.validateInputForProfileForTest(
                f1.fixturePath(), OfficialPsdFixturePreparation.F1_FIXTURE_SHA256,
                f1.fixtureName(), "other-run", "other-run",
                f1.savedCopyPath(), "", "5.3.02",
                30_000L, "f1"));
        expectReject("F1 wrong task-copy basename", () ->
            OfficialPsdFixturePreparation.validateInputForProfileForTest(
                "C:\\task\\" + F1_RUN_ID + "-other.psd",
                OfficialPsdFixturePreparation.F1_FIXTURE_SHA256, F1_TASK_FIXTURE_NAME,
                f1.runId(), f1.taskId(), f1.savedCopyPath(), "", "5.3.02",
                30_000L, "f1"));
    }

    private static void testPreparationNamespaceIsolation() {
        final Properties values = validNormalPreparationProperties();
        values.setProperty("profile", "025-external-edit-pipeline-v1");
        values.setProperty("fixture", "C:\\wrong\\pipeline.psd");
        values.setProperty("runId", "wrong-run");
        assertEquals("normal", OfficialPsdFixturePreparation.configuredProfileForTest(values),
            "generic result profile cannot mask namespaced normal profile");
        final OfficialPsdFixturePreparation.InputIdentity namespaced =
            OfficialPsdFixturePreparation.readInputForTest(values);
        assertEquals("queue-namespace", namespaced.runId(),
            "generic result runId cannot mask namespaced runId");
        assertEquals("C:\\task\\native-seven-layer.psd", namespaced.fixturePath(),
            "generic result fixture cannot mask namespaced fixture");

        final Properties defaultValues = validNormalPreparationProperties();
        defaultValues.remove(OfficialPsdFixturePreparation.PROFILE_PROPERTY);
        defaultValues.setProperty("profile", "025-external-edit-pipeline-v1");
        assertEquals("normal", OfficialPsdFixturePreparation.configuredProfileForTest(
            defaultValues), "missing namespaced profile uses the normal default");

        final String profileProperty = OfficialPsdFixturePreparation.PROFILE_PROPERTY;
        final String previous = System.getProperty(profileProperty);
        try {
            System.setProperty(profileProperty, "legacy");
            final Properties legacyValues = validLegacyPreparationProperties();
            legacyValues.setProperty("profile", "025-external-edit-pipeline-v1");
            legacyValues.remove(profileProperty);
            assertEquals("legacy", OfficialPsdFixturePreparation.configuredProfileForTest(
                legacyValues), "namespaced system profile wins over generic result profile");
        } finally {
            if (previous == null) System.clearProperty(profileProperty);
            else System.setProperty(profileProperty, previous);
        }

        final Properties unknown = validNormalPreparationProperties();
        unknown.setProperty(profileProperty, "unsupported");
        expectReject("unknown namespaced preparation profile", () ->
            OfficialPsdFixturePreparation.configuredProfileForTest(unknown));
    }

    private static Properties validNormalPreparationProperties() {
        final Properties values = new Properties();
        values.setProperty(OfficialPsdFixturePreparation.FIXTURE_PROPERTY,
            "C:\\task\\native-seven-layer.psd");
        values.setProperty(OfficialPsdFixturePreparation.FIXTURE_SHA256_PROPERTY, PSD_SHA);
        values.setProperty(OfficialPsdFixturePreparation.FIXTURE_NAME_PROPERTY,
            "native-seven-layer.psd");
        values.setProperty(OfficialPsdFixturePreparation.RUN_ID_PROPERTY, "queue-namespace");
        values.setProperty(OfficialPsdFixturePreparation.TASK_ID_PROPERTY, "queue-namespace");
        values.setProperty(OfficialPsdFixturePreparation.SAVED_COPY_PROPERTY,
            "C:\\task\\home\\prepared-control.cmo3");
        values.setProperty(OfficialPsdFixturePreparation.TARGET_RGB_SHA256_PROPERTY, RGB_SHA);
        values.setProperty(OfficialPsdFixturePreparation.HOST_VERSION_PROPERTY, "5.3.02");
        values.setProperty(OfficialPsdFixturePreparation.TIMEOUT_MILLIS_PROPERTY, "30000");
        values.setProperty(OfficialPsdFixturePreparation.PROFILE_PROPERTY, "normal");
        return values;
    }

    private static Properties validLegacyPreparationProperties() {
        final Properties values = validNormalPreparationProperties();
        values.setProperty(OfficialPsdFixturePreparation.SAVED_COPY_PROPERTY,
            "C:\\task\\home\\prepared-control-legacy.cmo3");
        return values;
    }

    private static void testF1SourceAndSharingGate() {
        final TextureRelationsSnapshot beforeRelations = f1Relations(false);
        final var beforeIdentity = OfficialPsdFixturePreparation.validateRelationSnapshot(
            "document-f1", "model-f1", beforeRelations);
        final var before = new OfficialPsdFixturePreparation.ModelState(
            "document-f1", Optional.empty(), "documents/document-session-1/untitled",
            "model-f1", beforeIdentity);
        final var fresh = new OfficialPsdFixturePreparation.ModelState(
            "document-f1", Optional.empty(), "documents/document-session-1/untitled",
            "model-f1", beforeIdentity);
        final PsdClipMaskDocumentSnapshot source = new PsdClipMaskDocumentSnapshot(
            "raw-f1", "imports/f1-2048-20layers.psd",
            List.of(new PsdLayerSnapshot("layer-a", "Duplicate", true),
                new PsdLayerSnapshot("layer-b", "Duplicate", false)));
        final var sourceIdentity = OfficialPsdFixturePreparation.validateInitialSourceGate(
            before, fresh, beforeRelations, List.of(source),
            OfficialPsdFixturePreparation.F1_FIXTURE_NAME, false);
        assertEquals("raw-f1", sourceIdentity.rawId(), "F1 source raw GUID is verified");
        assertEquals(List.of("layer-a", "layer-b"), sourceIdentity.leafLayerIds(),
            "F1 records source leaves without equating them to raw bindings");
        expectReject("F1 wrong source basename", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, beforeRelations,
                List.of(new PsdClipMaskDocumentSnapshot("raw-f1", "imports/other.psd",
                    source.layers())), OfficialPsdFixturePreparation.F1_FIXTURE_NAME, false));
        expectReject("F1 wrong source raw GUID", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, beforeRelations,
                List.of(new PsdClipMaskDocumentSnapshot("raw-other",
                    "imports/f1-2048-20layers.psd", source.layers())),
                OfficialPsdFixturePreparation.F1_FIXTURE_NAME, false));
        expectReject("F1 changed document/model/relation identity", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, new OfficialPsdFixturePreparation.ModelState(
                    "document-f1", Optional.empty(), "documents/document-session-1/untitled",
                    "model-f1", new OfficialPsdFixturePreparation.RelationIdentity(
                        beforeIdentity.documentId(), beforeIdentity.modelId(), beforeIdentity.binding(),
                        beforeIdentity.generation() + 1, beforeIdentity.modelImageIds(),
                        beforeIdentity.currentRawIds(), beforeIdentity.linkedRawIds(),
                        beforeIdentity.rawLayerBindings())), beforeRelations, List.of(source),
                OfficialPsdFixturePreparation.F1_FIXTURE_NAME, false));
        expectReject("seven-layer leaf equality remains explicit", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, beforeRelations, List.of(source),
                OfficialPsdFixturePreparation.F1_FIXTURE_NAME, true));

        final var sharing = OfficialPsdFixturePreparation.validateF1SharedRelation(
            beforeRelations, f1Relations(true), "f1-model-image", "raw-f1", "mesh-old");
        assertEquals("mesh-new", sharing.copiedArtMeshId(),
            "F1 relation gate identifies the new pasted ArtMesh");
        assertEquals(List.of("mesh-old", "mesh-new"), sharing.usingArtMeshIds(),
            "F1 old ModelImage retains both old and new ArtMesh IDs");
        expectReject("F1 no new ArtMesh", () -> OfficialPsdFixturePreparation
            .validateF1SharedRelation(beforeRelations, beforeRelations,
                "f1-model-image", "raw-f1", "mesh-old"));
        expectReject("F1 wrong post-paste raw", () -> OfficialPsdFixturePreparation
            .validateF1SharedRelation(beforeRelations, f1RelationsWithRaw("raw-other"),
                "f1-model-image", "raw-f1", "mesh-old"));
        expectReject("F1 missing new ArtMesh binding", () -> OfficialPsdFixturePreparation
            .validateF1SharedRelation(beforeRelations, f1RelationsWithoutNewInput(),
                "f1-model-image", "raw-f1", "mesh-old"));
    }

    private static void testF1CopyPasteAdmission() {
        final var identity = OfficialPsdFixturePreparation.validateRelationSnapshot(
            "document-f1", "model-f1", f1Relations(false));
        final var expected = new OfficialPsdFixturePreparation.ModelState(
            "document-f1", Optional.empty(), "documents/document-session-1/untitled",
            "model-f1", identity);
        final Object window = new Object();
        final AtomicInteger leftClicks = new AtomicInteger();
        final AtomicInteger copies = new AtomicInteger();
        final AtomicInteger pastes = new AtomicInteger();
        final var validActions = f1Actions(leftClicks, copies, pastes,
            new OfficialPsdFixturePreparation.F1SelectionObservation(
                List.of("mesh-old"), Optional.of("mesh-old")), false);
        final java.util.concurrent.atomic.AtomicReference<OfficialPsdFixturePreparation.F1CopyPasteResult>
            result = new java.util.concurrent.atomic.AtomicReference<>();
        onEdt(() -> result.set(OfficialPsdFixturePreparation.executeF1CopyPasteOnEdt(
            expected, "mesh-old", window, () -> window, () -> expected,
            () -> false, () -> true, validActions)));
        assertTrue(result.get().accepted(), "valid F1 COPY/PASTE admission succeeds");
        assertEquals(1, leftClicks.get(), "valid F1 selection click occurs once");
        assertEquals(1, copies.get(), "valid F1 COPY occurs once");
        assertEquals(1, pastes.get(), "valid F1 PASTE occurs once");

        assertF1AdmissionRejected("F1 stopped before selection", expected, window,
            f1Actions(leftClicks, copies, pastes,
                new OfficialPsdFixturePreparation.F1SelectionObservation(
                    List.of("mesh-old"), Optional.of("mesh-old")), false),
            () -> true, () -> true, leftClicks, copies, pastes);
        assertF1AdmissionRejected("F1 changed window", expected, window,
            f1Actions(leftClicks, copies, pastes,
                new OfficialPsdFixturePreparation.F1SelectionObservation(
                    List.of("mesh-old"), Optional.of("mesh-old")), false),
            () -> false, () -> true, leftClicks, copies, pastes, new Object());
        assertF1AdmissionRejected("F1 wrong SDK selection", expected, window,
            f1Actions(leftClicks, copies, pastes,
                new OfficialPsdFixturePreparation.F1SelectionObservation(
                    List.of("mesh-other"), Optional.of("mesh-other")), false),
            () -> false, () -> true, leftClicks, copies, pastes);
        assertF1AdmissionRejected("F1 unknown PASTE dialog", expected, window,
            f1Actions(leftClicks, copies, pastes,
                new OfficialPsdFixturePreparation.F1SelectionObservation(
                    List.of("mesh-old"), Optional.of("mesh-old")), true),
            () -> false, () -> true, leftClicks, copies, pastes);
    }

    private static void assertF1AdmissionRejected(final String description,
        final OfficialPsdFixturePreparation.ModelState expected, final Object window,
        final OfficialPsdFixturePreparation.F1CopyPasteActions actions,
        final java.util.function.BooleanSupplier stopped,
        final java.util.function.BooleanSupplier taskBound,
        final AtomicInteger leftClicks, final AtomicInteger copies, final AtomicInteger pastes) {
        assertF1AdmissionRejected(description, expected, window, actions, stopped, taskBound,
            leftClicks, copies, pastes, window);
    }

    private static void assertF1AdmissionRejected(final String description,
        final OfficialPsdFixturePreparation.ModelState expected, final Object window,
        final OfficialPsdFixturePreparation.F1CopyPasteActions actions,
        final java.util.function.BooleanSupplier stopped,
        final java.util.function.BooleanSupplier taskBound,
        final AtomicInteger leftClicks, final AtomicInteger copies, final AtomicInteger pastes,
        final Object observedWindow) {
        final int leftBefore = leftClicks.get();
        final int copyBefore = copies.get();
        final int pasteBefore = pastes.get();
        final var result = new java.util.concurrent.atomic.AtomicReference<
            OfficialPsdFixturePreparation.F1CopyPasteResult>();
        onEdt(() -> result.set(OfficialPsdFixturePreparation.executeF1CopyPasteOnEdt(
            expected, "mesh-old", window, () -> observedWindow, () -> expected,
            stopped, taskBound, actions)));
        assertFalse(result.get().accepted(), description + " is rejected");
        if (result.get().diagnostic().contains("unknown visible dialog")) {
            assertEquals(leftBefore + 1, leftClicks.get(), description + " records its one UI selection");
            assertEquals(copyBefore + 1, copies.get(), description + " reaches COPY before dialog rejection");
            assertEquals(pasteBefore + 1, pastes.get(), description + " reaches PASTE before dialog rejection");
        } else if (result.get().diagnostic().contains("selection")) {
            assertEquals(leftBefore + 1, leftClicks.get(), description + " performs the reviewed left click");
            assertEquals(copyBefore, copies.get(), description + " does not COPY");
            assertEquals(pasteBefore, pastes.get(), description + " does not PASTE");
        } else if (result.get().diagnostic().contains("post-PASTE")) {
            assertEquals(leftBefore + 1, leftClicks.get(), description + " records its one UI selection");
            assertEquals(copyBefore + 1, copies.get(), description + " reaches COPY");
            assertEquals(pasteBefore + 1, pastes.get(), description + " reaches PASTE");
        } else {
            assertEquals(leftBefore, leftClicks.get(), description + " does not click on pre-action rejection");
            assertEquals(copyBefore, copies.get(), description + " does not COPY");
            assertEquals(pasteBefore, pastes.get(), description + " does not PASTE");
        }
    }

    private static OfficialPsdFixturePreparation.F1CopyPasteActions f1Actions(
        final AtomicInteger leftClicks, final AtomicInteger copies, final AtomicInteger pastes,
        final OfficialPsdFixturePreparation.F1SelectionObservation selection,
        final boolean unknownDialog) {
        return new OfficialPsdFixturePreparation.F1CopyPasteActions() {
            @Override public void leftClick() { leftClicks.incrementAndGet(); }
            @Override public OfficialPsdFixturePreparation.F1SelectionObservation selection() {
                return selection;
            }
            @Override public EditorCommandResult copy() {
                copies.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, "copy");
            }
            @Override public EditorCommandResult paste() {
                pastes.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, "paste");
            }
            @Override public boolean unknownVisibleDialog() { return unknownDialog; }
        };
    }

    private static void onEdt(final Runnable operation) {
        try {
            SwingUtilities.invokeAndWait(operation);
        } catch (Exception failure) {
            throw new AssertionError("EDT focused seam failed", failure);
        }
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

    private static void testChooserCandidateRenderer() {
        final JList<String> genericList = new JList<>();
        genericList.setCellRenderer(new DefaultListCellRenderer());
        final JList<String> psdList = new JList<>();
        psdList.setCellRenderer(new PsdTestRenderer());
        assertFalse(OfficialPsdFixturePreparation.hasReviewedRenderer(
            List.of(genericList), PsdTestRenderer.class),
            "unrelated generic host list is not a PSD chooser candidate");
        assertTrue(OfficialPsdFixturePreparation.hasReviewedRenderer(
            List.of(psdList), PsdTestRenderer.class), "reviewed renderer identifies PSD candidate");
        assertTrue(OfficialPsdFixturePreparation.hasReviewedRenderer(
            List.of(genericList, psdList), PsdTestRenderer.class),
            "extra generic lists do not hide a reviewed PSD candidate");
        psdList.setCellRenderer(null);
        assertFalse(OfficialPsdFixturePreparation.hasReviewedRenderer(
            List.of(psdList), PsdTestRenderer.class), "unknown renderer cannot identify the chooser");
    }

    private static final class PsdTestRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;
    }

    private static void testSaveCommandAdmission() {
        final var identity = OfficialPsdFixturePreparation.validateRelationSnapshot(
            "document", "model", relations(true, true, false));
        final var expected = new OfficialPsdFixturePreparation.ModelState(
            "document", Optional.of("content"), "source.psd", "model", identity);
        final var changed = new OfficialPsdFixturePreparation.ModelState(
            "other-document", Optional.of("other-content"), "other.psd", "model",
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "other-document", "model", relations(true, true, false)));
        final Object window = new Object();
        final AtomicInteger commands = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<OfficialPsdFixturePreparation.ModelState>
            current = new java.util.concurrent.atomic.AtomicReference<>(expected);
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch changedBeforeRead =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CompletableFuture<Void> checked =
            new java.util.concurrent.CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try {
                assertTrue(changedBeforeRead.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "controlled document switch occurs before the final SAVE read");
                expectSaveRejected(() -> OfficialPsdFixturePreparation.executeBoundSaveOnEdt(
                    expected, current.get(), window, window, () -> { }, commands::incrementAndGet));
                assertEquals(0, commands.get(), "changed document issues no SAVE_AS command");
                expectSaveRejected(() -> OfficialPsdFixturePreparation.executeBoundSaveOnEdt(
                    expected, expected, window, new Object(), () -> { }, commands::incrementAndGet));
                expectSaveRejected(() -> OfficialPsdFixturePreparation.executeBoundSaveOnEdt(
                    expected, expected, window, window,
                    () -> { throw new IllegalStateException("stopped"); }, commands::incrementAndGet));
                assertEquals(0, commands.get(), "changed window and stopped task issue no command");
                OfficialPsdFixturePreparation.executeBoundSaveOnEdt(
                    expected, expected, window, window, () -> { }, commands::incrementAndGet);
                assertEquals(1, commands.get(), "valid admission executes exactly one command");
                checked.complete(null);
            } catch (Throwable failure) {
                checked.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "SAVE dispatch entered the EDT");
            current.set(changed);
            changedBeforeRead.countDown();
            checked.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("SAVE command admission regression failed", failure);
        } finally {
            changedBeforeRead.countDown();
        }
        expectSaveRejected(() -> OfficialPsdFixturePreparation.executeBoundSaveOnEdt(
            expected, expected, window, window, () -> { }, commands::incrementAndGet));
        assertEquals(1, commands.get(), "off-EDT caller cannot execute another command");
    }

    private static void testAwaitEdtObservationBudget() {
        final AtomicInteger successfulObservations = new AtomicInteger();
        final long started = System.nanoTime();
        final String value;
        try {
            value = OfficialPsdFixturePreparation.awaitEdtObservationForTest(
                5_000L, () -> false, () -> true, () -> {
                    successfulObservations.incrementAndGet();
                    try {
                        Thread.sleep(2_250L);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("EDT budget test was interrupted", failure);
                    }
                    return "ready";
                });
        } catch (Exception failure) {
            throw new AssertionError("EDT observation should use the remaining total budget",
                failure);
        }
        assertEquals("ready", value, "EDT observation succeeds after more than the old 2s slice");
        assertEquals(1, successfulObservations.get(), "successful EDT observation runs once");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 2_000L,
            "budget regression really blocked the EDT for more than 2s");

        final java.util.concurrent.CountDownLatch blockerEntered =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseBlocker =
            new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blockerEntered.countDown();
            try {
                releaseBlocker.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(blockerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "EDT blocker entered before the exhausted-budget observation");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("EDT blocker setup was interrupted", failure);
        }
        final AtomicInteger lateObservations = new AtomicInteger();
        try {
            OfficialPsdFixturePreparation.awaitEdtObservationForTest(
                250L, () -> false, () -> true, () -> {
                    lateObservations.incrementAndGet();
                    return "late";
                });
            throw new AssertionError("exhausted EDT budget unexpectedly succeeded");
        } catch (IllegalStateException expected) {
            // The operation was queued behind the blocker and must be cancelled before it runs.
        } catch (Exception failure) {
            throw new AssertionError("exhausted EDT budget failed with the wrong exception",
                failure);
        } finally {
            releaseBlocker.countDown();
        }
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception failure) {
            throw new AssertionError("EDT blocker did not drain", failure);
        }
        assertEquals(0, lateObservations.get(),
            "total-budget timeout cancels the queued observation with no late read");
    }

    private static void expectSaveRejected(final Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("unsafe SAVE_AS admission was accepted");
    }

    private static void testOfficialJarAccessorShape() {
        final String configured = System.getenv("TURBOISM_EXTERNAL_PSD_SHAPE_JAR");
        if (configured == null || configured.isBlank()) {
            throw new AssertionError("TURBOISM_EXTERNAL_PSD_SHAPE_JAR is required");
        }
        try {
            final Path expectedJar = Path.of(
                "/opt/dev/projects/turboism-legacy/cubism-ref/Cubism-5.3.02/jars/"
                    + "Live2D_Cubism.jar").toRealPath();
            final Path configuredJar = Path.of(configured).toRealPath();
            assertEquals(expectedJar, configuredJar, "shape test uses the reviewed JAR");
            assertEquals("988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                sha256(configuredJar), "shape JAR SHA-256 is reviewed");

            final ClassLoader loader = ClassLoader.getSystemClassLoader();
            final Class<?> app = load(loader, "com.live2d.cubism.CEAppCtrl");
            final Thread thread = Thread.currentThread();
            final ClassLoader originalContext = thread.getContextClassLoader();
            try {
                final ClassLoader isolatedPlugin = new ClassLoader(null) { };
                thread.setContextClassLoader(isolatedPlugin);
                try {
                    Class.forName("com.live2d.cubism.CEAppCtrl", false, isolatedPlugin);
                    throw new AssertionError("isolated plugin loader unexpectedly sees Cubism");
                } catch (ClassNotFoundException expected) {
                    // Reproduce the real plugin worker's inability to resolve host classes.
                }
                assertSame(app, OfficialPsdFixturePreparation.loadHostApplication(),
                    "host bootstrap ignores the isolated plugin context loader");
            } finally {
                thread.setContextClassLoader(originalContext);
            }
            final Class<?> mainFrameController = load(loader,
                "com.live2d.cubism.view.CEMainFrameCtrl");
            final Class<?> cFrame = load(loader, "com.live2d.ui.window.CFrame");
            final Class<?> windowBase = load(loader, "com.live2d.ui.window.V");
            final Class<?> option = load(loader, "com.live2d.cubism.process.psd.a$a");
            final Class<?> modelDocument = load(loader,
                "com.live2d.cubism.doc.modeling.CModelingDocument");
            final Class<?> renderer = load(loader, "com.live2d.cubism.process.psd.e");
            final Class<?> list = load(loader, "com.live2d.ui.swingImpl.q");
            final Class<?> button = load(loader, "com.live2d.ui.swingImpl.j");
            final Class<?> buttonSubclass = load(loader, "com.live2d.ui.control.CButton$b");
            final Class<?> cButton = load(loader, "com.live2d.ui.control.CButton");
            final Class<?> action = load(loader, "com.live2d.ui.event.CAction");
            final Class<?> localizer = load(loader, "b.c");
            for (final Class<?> type : List.of(app, mainFrameController, cFrame, windowBase,
                option, modelDocument, renderer, list, button, buttonSubclass, cButton, action,
                localizer)) {
                assertSame(loader, type.getClassLoader(), "all shape classes use one loader");
                assertEquals(configuredJar, codeSource(type),
                    "shape class code source is the reviewed JAR: " + type.getName());
            }

            exactMethod(app, "access$get_instance$cp", app, true);
            exactMethod(app, "getMainFrameCtrl", mainFrameController, false);
            exactMethod(mainFrameController, "getMainFrame", cFrame, false);
            final Method swingWindow = cFrame.getMethod("getJWindow");
            assertEquals(Window.class, swingWindow.getReturnType(), "V.getJWindow return shape");
            assertEquals(windowBase, swingWindow.getDeclaringClass(),
                "CFrame window getter is inherited from V");
            exactMethod(cFrame, "getJFrame", JFrame.class, false);
            exactMethod(option, "a", modelDocument, false);
            exactMethod(option, "b", String.class, false);
            assertTrue(AbstractButton.class.isAssignableFrom(button),
                "exact j button is a Swing button");
            assertTrue(AbstractButton.class.isAssignableFrom(buttonSubclass),
                "verified CButton$b is a Swing button");
            final java.lang.reflect.Constructor<?> buttonActionConstructor =
                cButton.getConstructor(action);
            assertEquals(cButton, buttonActionConstructor.getDeclaringClass(),
                "CButton(CAction) shape is exact");

            final Field instance = localizer.getDeclaredField("a");
            assertTrue(Modifier.isPublic(instance.getModifiers())
                && Modifier.isStatic(instance.getModifiers())
                && Modifier.isFinal(instance.getModifiers())
                && instance.getType() == localizer, "localizer singleton field shape");
            final Method localize = exactMethod(localizer, "a", String.class, false,
                String.class, String[].class);
            assertTrue(instance.trySetAccessible(), "localizer singleton is readable");
            final Object localizerObject = instance.get(null);
            for (final String key : List.of("CUB3-0418", "CUB3-4408", "CUB3-0421", "CUB3-0420")) {
                final Object value = localize.invoke(localizerObject, key, new String[0]);
                assertTrue(value instanceof String text && !text.isBlank(),
                    "runtime locale text is available for " + key);
            }
        } catch (ReflectiveOperationException | IOException | URISyntaxException failure) {
            throw new AssertionError("official accessor shape failed", failure);
        }
    }

    private static Class<?> load(final ClassLoader loader, final String name)
        throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method exactMethod(final Class<?> owner, final String name,
        final Class<?> returnType, final boolean staticRequired, final Class<?>... parameters)
        throws ReflectiveOperationException {
        final Method method = owner.getDeclaredMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers())
            && Modifier.isStatic(method.getModifiers()) == staticRequired
            && method.getReturnType() == returnType, "exact method shape: " + owner.getName()
            + '.' + name);
        return method;
    }

    private static Path codeSource(final Class<?> type) throws URISyntaxException, IOException {
        final CodeSource source = type.getProtectionDomain().getCodeSource();
        assertTrue(source != null && source.getLocation() != null,
            "code source exists for " + type.getName());
        return Path.of(source.getLocation().toURI()).toRealPath();
    }

    private static String sha256(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
    }

    private static void testWindowBindingAndChooserGate() {
        final Object owner = new Object();
        final OfficialPsdFixturePreparation.WindowBindingDecision waiting =
            OfficialPsdFixturePreparation.bindWindow(null, null);
        assertFalse(waiting.ready(), "unready main frame is not bound");
        assertTrue(waiting.waiting(), "unready main frame is retryable before binding");
        final OfficialPsdFixturePreparation.WindowBindingDecision first =
            OfficialPsdFixturePreparation.bindWindow(null, owner);
        assertTrue(first.ready(), "first showing main frame binds");
        assertFalse(first.waiting(), "first binding is not a retry state");
        assertSame(owner, first.owner(), "first owner identity is retained");
        final OfficialPsdFixturePreparation.WindowBindingDecision retained =
            OfficialPsdFixturePreparation.bindWindow(owner, owner);
        assertTrue(retained.ready(), "same owner remains valid");
        assertFalse(retained.waiting(), "same owner is not a retry state");
        final OfficialPsdFixturePreparation.WindowBindingDecision disappeared =
            OfficialPsdFixturePreparation.bindWindow(owner, null);
        assertFalse(disappeared.ready(), "bound owner disappearance is terminal");
        assertFalse(disappeared.waiting(), "bound owner disappearance cannot rebind");
        assertSame(owner, disappeared.owner(), "terminal decision keeps original owner");
        final OfficialPsdFixturePreparation.WindowBindingDecision changed =
            OfficialPsdFixturePreparation.bindWindow(owner, new Object());
        assertFalse(changed.ready(), "changed owner is terminal");
        assertSame(owner, changed.owner(), "changed owner cannot replace the binding");

        final OfficialPsdFixturePreparation.ChooserGateExpectation expected = chooserExpectation();
        final Object currentOwner = new Object();
        final AtomicInteger selected = new AtomicInteger();
        final AtomicInteger clicked = new AtomicInteger();
        final OfficialPsdFixturePreparation.ChooserGateActions actions = chooserActions(
            selected, clicked);
        final Object changedWindow = new Object();
        final OfficialPsdFixturePreparation.WindowBindingDecision changedBinding =
            OfficialPsdFixturePreparation.bindWindow(currentOwner, changedWindow);
        final OfficialPsdFixturePreparation.ChooserGateResult changedResult =
            OfficialPsdFixturePreparation.verifyAndExecuteChooser(changedBinding,
                validChooserObservation(changedWindow), expected, () -> false, () -> true, actions);
        assertFalse(changedResult.accepted(), "changed window is rejected before chooser actions");
        assertEquals(0, selected.get(), "changed window does not select");
        assertEquals(0, clicked.get(), "changed window does not click");
        final OfficialPsdFixturePreparation.ChooserGateResult accepted =
            OfficialPsdFixturePreparation.verifyAndExecuteChooser(
                validChooserObservation(currentOwner), expected,
                OfficialPsdFixturePreparation.profileChooserIndexForTest("normal"),
                () -> false, () -> true, actions);
        assertTrue(accepted.accepted(), "exact chooser is accepted");
        assertEquals(1, selected.get(), "exact chooser selects once");
        assertEquals(1, clicked.get(), "exact chooser confirms once");

        assertChooserRejected("wrong chooser owner", validChooserObservation(new Object(),
            currentOwner), expected, actions, selected, clicked);
        assertChooserRejected("unknown option", withFirstOptionClass(
            validChooserObservation(currentOwner), WrongOption.class), expected, actions,
            selected, clicked);
        assertChooserRejected("multiple chooser candidates", withCandidateCount(
            validChooserObservation(currentOwner), 2), expected, actions, selected, clicked);
        assertChooserRejected("stopped queued chooser", validChooserObservation(currentOwner),
            expected, actions, selected, clicked, () -> true, () -> true);
        assertChooserRejected("unbound queued chooser", validChooserObservation(currentOwner),
            expected, actions, selected, clicked, () -> false, () -> false);

        final AtomicInteger legacySelected = new AtomicInteger(-1);
        final AtomicInteger legacyClicked = new AtomicInteger();
        final OfficialPsdFixturePreparation.ChooserGateActions legacyActions = chooserActions(
            legacySelected, legacyClicked, selected, clicked);
        final OfficialPsdFixturePreparation.ChooserGateResult legacyAccepted =
            OfficialPsdFixturePreparation.verifyAndExecuteChooser(
                validChooserObservation(currentOwner), expected,
                OfficialPsdFixturePreparation.profileChooserIndexForTest("legacy"),
                () -> false, () -> true, legacyActions);
        assertTrue(legacyAccepted.accepted(), "exact legacy chooser is accepted");
        assertEquals(1, legacySelected.get(), "legacy selects the second reviewed option");
        assertEquals(1, legacyClicked.get(), "legacy confirms once");

        final int beforeLegacySelected = legacySelected.get();
        final int beforeLegacyClicked = legacyClicked.get();
        final OfficialPsdFixturePreparation.ChooserGateResult wrongLegacyOption =
            OfficialPsdFixturePreparation.verifyAndExecuteChooser(
                withSecondLabel(validChooserObservation(currentOwner), "wrong-mode"), expected,
                OfficialPsdFixturePreparation.profileChooserIndexForTest("legacy"),
                () -> false, () -> true, legacyActions);
        assertFalse(wrongLegacyOption.accepted(), "wrong legacy label is rejected");
        assertEquals(beforeLegacySelected, legacySelected.get(),
            "wrong legacy label does not select");
        assertEquals(beforeLegacyClicked, legacyClicked.get(),
            "wrong legacy label does not click");
    }

    private static void testInitialSourceGate() {
        final TextureRelationsSnapshot relations = relations(true, true, false);
        final OfficialPsdFixturePreparation.RelationIdentity identity =
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations);
        final OfficialPsdFixturePreparation.ModelState before = new OfficialPsdFixturePreparation.ModelState(
            "document", Optional.empty(), "documents/document-session-1/untitled", "model", identity);
        final OfficialPsdFixturePreparation.ModelState fresh = new OfficialPsdFixturePreparation.ModelState(
            "document", Optional.empty(), "documents/document-session-1/untitled", "model", identity);
        final PsdClipMaskDocumentSnapshot source = psdSnapshot(
            "raw-current", "native-seven-layer.psd", "raw-layer");
        final OfficialPsdFixturePreparation.SourceSnapshotIdentity verified =
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, relations, List.of(source), "native-seven-layer.psd");
        assertEquals("raw-current", verified.rawId(), "source raw GUID is verified");
        assertEquals("native-seven-layer.psd", verified.fileName(),
            "source snapshot basename is verified");
        assertEquals(List.of("raw-layer"), verified.leafLayerIds(),
            "source leaf layer IDs match raw bindings");

        expectReject("wrong source filename", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, relations,
                List.of(psdSnapshot("raw-current", "other.psd", "raw-layer")),
                "native-seven-layer.psd"));
        expectReject("wrong source raw", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, relations,
                List.of(psdSnapshot("raw-other", "native-seven-layer.psd", "raw-layer")),
                "native-seven-layer.psd"));
        expectReject("multiple source raw IDs", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, multiRawRelations(),
                List.of(source), "native-seven-layer.psd"));
        expectReject("duplicate source snapshot", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, relations, List.of(source, source), "native-seven-layer.psd"));
        expectReject("missing source snapshot", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, relations, List.of(), "native-seven-layer.psd"));
        expectReject("source leaf binding mismatch", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, fresh, relations,
                List.of(psdSnapshot("raw-current", "native-seven-layer.psd", "wrong-layer")),
                "native-seven-layer.psd"));
        final OfficialPsdFixturePreparation.ModelState changed = new OfficialPsdFixturePreparation.ModelState(
            "document", Optional.empty(), "documents/document-session-1/untitled", "model",
            new OfficialPsdFixturePreparation.RelationIdentity(
                identity.documentId(), identity.modelId(), identity.binding(),
                identity.generation() + 1, identity.modelImageIds(), identity.currentRawIds(),
                identity.linkedRawIds(), identity.rawLayerBindings()));
        expectReject("initial source identity changed", () ->
            OfficialPsdFixturePreparation.validateInitialSourceGate(
                before, changed, relations, List.of(source), "native-seven-layer.psd"));
    }

    private static void assertChooserRejected(final String description,
        final OfficialPsdFixturePreparation.ChooserGateObservation observation,
        final OfficialPsdFixturePreparation.ChooserGateExpectation expected,
        final OfficialPsdFixturePreparation.ChooserGateActions actions,
        final AtomicInteger selected, final AtomicInteger clicked) {
        assertChooserRejected(description, observation, expected, actions, selected, clicked,
            () -> false, () -> true);
    }

    private static void assertChooserRejected(final String description,
        final OfficialPsdFixturePreparation.ChooserGateObservation observation,
        final OfficialPsdFixturePreparation.ChooserGateExpectation expected,
        final OfficialPsdFixturePreparation.ChooserGateActions actions,
        final AtomicInteger selected, final AtomicInteger clicked,
        final java.util.function.BooleanSupplier stopped,
        final java.util.function.BooleanSupplier taskBound) {
        final int selectedBefore = selected.get();
        final int clickedBefore = clicked.get();
        final OfficialPsdFixturePreparation.ChooserGateResult result =
            OfficialPsdFixturePreparation.verifyAndExecuteChooser(
                observation, expected, stopped, taskBound, actions);
        assertFalse(result.accepted(), description + " is rejected");
        assertEquals(selectedBefore, selected.get(), description + " does not select");
        assertEquals(clickedBefore, clicked.get(), description + " does not click");
    }

    private static OfficialPsdFixturePreparation.ChooserGateExpectation chooserExpectation() {
        return new OfficialPsdFixturePreparation.ChooserGateExpectation(
            ListShape.class, RendererShape.class, OptionShape.class, ExactButton.class,
            ButtonSubclass.class, ActionShape.class, "follow-target", "older-mode");
    }

    private static OfficialPsdFixturePreparation.ChooserGateObservation validChooserObservation(
        final Object owner) {
        return validChooserObservation(owner, owner);
    }

    private static OfficialPsdFixturePreparation.ChooserGateObservation validChooserObservation(
        final Object currentOwner, final Object dialogOwner) {
        return new OfficialPsdFixturePreparation.ChooserGateObservation(
            currentOwner, dialogOwner, 1, ListShape.class, RendererShape.class, 2,
            OptionShape.class, null, "follow-target", OptionShape.class, null, "older-mode",
            1, ButtonSubclass.class, ActionShape.class, "OK", true, true, true);
    }

    private static OfficialPsdFixturePreparation.ChooserGateActions chooserActions(
        final AtomicInteger selected, final AtomicInteger clicked) {
        return new OfficialPsdFixturePreparation.ChooserGateActions() {
            @Override public boolean selectFirst() {
                selected.incrementAndGet();
                return true;
            }

            @Override public void clickConfirm() {
                clicked.incrementAndGet();
            }
        };
    }

    private static OfficialPsdFixturePreparation.ChooserGateActions chooserActions(
        final AtomicInteger selectedIndex, final AtomicInteger profileClicked,
        final AtomicInteger selected, final AtomicInteger clicked) {
        return new OfficialPsdFixturePreparation.ChooserGateActions() {
            @Override public boolean selectFirst() {
                selectedIndex.set(0);
                selected.incrementAndGet();
                return true;
            }

            @Override public boolean select(final int index) {
                selectedIndex.set(index);
                selected.incrementAndGet();
                return index == 0 || index == 1;
            }

            @Override public void clickConfirm() {
                profileClicked.incrementAndGet();
                clicked.incrementAndGet();
            }
        };
    }

    private static OfficialPsdFixturePreparation.ChooserGateObservation withFirstOptionClass(
        final OfficialPsdFixturePreparation.ChooserGateObservation source,
        final Class<?> firstOptionClass) {
        return new OfficialPsdFixturePreparation.ChooserGateObservation(
            source.currentOwner(), source.dialogOwner(), source.candidateCount(), source.listClass(),
            source.rendererClass(), source.optionCount(), firstOptionClass,
            source.firstOptionModel(), source.firstLabel(), source.secondOptionClass(),
            source.secondOptionModel(), source.secondLabel(), source.confirmationCount(),
            source.confirmationClass(), source.actionClass(), source.actionName(),
            source.enabled(), source.showing(), source.displayable());
    }

    private static OfficialPsdFixturePreparation.ChooserGateObservation withCandidateCount(
        final OfficialPsdFixturePreparation.ChooserGateObservation source, final int count) {
        return new OfficialPsdFixturePreparation.ChooserGateObservation(
            source.currentOwner(), source.dialogOwner(), count, source.listClass(),
            source.rendererClass(), source.optionCount(), source.firstOptionClass(),
            source.firstOptionModel(), source.firstLabel(), source.secondOptionClass(),
            source.secondOptionModel(), source.secondLabel(), source.confirmationCount(),
            source.confirmationClass(), source.actionClass(), source.actionName(),
            source.enabled(), source.showing(), source.displayable());
    }

    private static OfficialPsdFixturePreparation.ChooserGateObservation withSecondLabel(
        final OfficialPsdFixturePreparation.ChooserGateObservation source,
        final String secondLabel) {
        return new OfficialPsdFixturePreparation.ChooserGateObservation(
            source.currentOwner(), source.dialogOwner(), source.candidateCount(), source.listClass(),
            source.rendererClass(), source.optionCount(), source.firstOptionClass(),
            source.firstOptionModel(), source.firstLabel(), source.secondOptionClass(),
            source.secondOptionModel(), secondLabel, source.confirmationCount(),
            source.confirmationClass(), source.actionClass(), source.actionName(),
            source.enabled(), source.showing(), source.displayable());
    }

    private static void testSaveAfterIdentityGate() {
        final OfficialPsdFixturePreparation.RelationIdentity identity =
            OfficialPsdFixturePreparation.validateRelationSnapshot(
                "document", "model", relations(true, true, false));
        final OfficialPsdFixturePreparation.ModelState before = new OfficialPsdFixturePreparation.ModelState(
            "document", Optional.of("content-before"), "source.psd", "model", identity);
        final OfficialPsdFixturePreparation.ModelState after = new OfficialPsdFixturePreparation.ModelState(
            "document", Optional.of("content-before"), "C:\\task\\prepared-control.cmo3",
            "model", identity);

        final OfficialPsdFixturePreparation.SaveAfterIdentity firstSave = lifecycleSave(
            Optional.empty(), Optional.of("content-before"), "content-before");
        assertEquals("", firstSave.fileName(),
            "first SAVE request has no pre-existing filename");
        assertEquals("content-before", firstSave.requestContentId(),
            "first SAVE request content identity is retained");
        assertTrue(OfficialPsdFixturePreparation.saveAfterMatches(
            firstSave, before, after, "prepared-control.cmo3", "window@1", "window@1"),
            "first SAVE accepts the bound post-SAVE content identity");

        final OfficialPsdFixturePreparation.SaveAfterIdentity oldCmoSave = lifecycleSave(
            Optional.of("old-control.cmo3"), Optional.of("content-before"), "content-before");
        assertTrue(OfficialPsdFixturePreparation.saveAfterMatches(
            oldCmoSave, before, after, "prepared-control.cmo3", "window@1", "window@1"),
            "SAVE_AS accepts a request carrying the old CMO filename");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", false, "", "content-before", "content-before"),
            before, after, "prepared-control.cmo3", "window@1", "window@1"),
            "failed SAVE is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            firstSave, before, after, "other.cmo3", "window@1", "window@1"),
            "wrong post-SAVE filename is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", true, "", "content-before", "content-after"),
            before, after, "prepared-control.cmo3", "window@1", "window@1"),
            "wrong SAVE result content ID is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            firstSave, before, new OfficialPsdFixturePreparation.ModelState(
                "document", Optional.of("content-after"), "C:\\task\\prepared-control.cmo3",
                "model", identity),
            "prepared-control.cmo3", "window@1", "window@1"),
            "post-SAVE model content ID must match SAVE result content ID");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", true, "", "wrong-content", "content-before"),
            before, after, "prepared-control.cmo3", "window@1", "window@1"),
            "wrong SAVE request content ID is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", true, "", "content-before", "unavailable"),
            before, after, "prepared-control.cmo3", "window@1", "window@1"),
            "missing SAVE After content ID is rejected");
        final OfficialPsdFixturePreparation.ModelState missingContent =
            new OfficialPsdFixturePreparation.ModelState(
                "document", Optional.empty(), "C:\\task\\prepared-control.cmo3",
                "model", identity);
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            firstSave, before, missingContent, "prepared-control.cmo3", "window@1", "window@1"),
            "missing post-SAVE model content ID is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            firstSave, before, new OfficialPsdFixturePreparation.ModelState(
                "document", Optional.of("content-after"), "C:\\task\\prepared-control.cmo3",
                "model", new OfficialPsdFixturePreparation.RelationIdentity(
                    identity.documentId(), identity.modelId(), identity.binding(),
                    identity.generation() + 1, identity.modelImageIds(), identity.currentRawIds(),
                    identity.linkedRawIds(), identity.rawLayerBindings())),
            "prepared-control.cmo3", "window@1", "window@1"),
            "changed relation generation is rejected");
        assertFalse(OfficialPsdFixturePreparation.saveAfterMatches(
            firstSave, before, after, "prepared-control.cmo3", "window@1", "window@2"),
            "changed window identity is rejected");

        final OfficialPsdFixturePreparation.SaveAfterIdentity stale =
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", true, "prepared-control.cmo3", "content-before", "content-before");
        assertTrue(OfficialPsdFixturePreparation.firstSaveAfterAfterSequenceForTest(
            List.of(stale, oldCmoSave), 1, "content-before").orElseThrow()
            .equals(oldCmoSave), "SAVE search starts after the execute boundary");
        assertTrue(OfficialPsdFixturePreparation.firstSaveAfterAfterSequenceForTest(
            List.of(stale), 1, "content-before").isEmpty(),
            "old SAVE with the same content is not reused");
        assertTrue(OfficialPsdFixturePreparation.firstSaveAfterAfterSequenceForTest(
            List.of(oldCmoSave), 1, "content-before").isEmpty(),
            "an over-large sequence boundary cannot reuse an earlier SAVE");
        final OfficialPsdFixturePreparation.SaveAfterIdentity wrongContent =
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", true, "", "content-before", "wrong-content");
        assertTrue(OfficialPsdFixturePreparation.firstSaveAfterAfterSequenceForTest(
            List.of(wrongContent, oldCmoSave), 0, "content-before").orElseThrow()
            .equals(oldCmoSave), "wrong result content is skipped while awaiting SAVE");
        final OfficialPsdFixturePreparation.SaveAfterIdentity failed =
            new OfficialPsdFixturePreparation.SaveAfterIdentity(
                "SAVE", false, "", "content-before", "content-before");
        assertTrue(OfficialPsdFixturePreparation.firstSaveAfterAfterSequenceForTest(
            List.of(failed, oldCmoSave), 0, "content-before").orElseThrow()
            .equals(oldCmoSave), "failed SAVE is skipped while awaiting a successful SAVE");
    }

    private static OfficialPsdFixturePreparation.SaveAfterIdentity lifecycleSave(
        final Optional<String> requestFileName, final Optional<String> requestContentId,
        final String resultContentId) {
        final ProjectFileOperation request = new ProjectFileOperation(
            ProjectContentKind.MODEL, ProjectFileOperationType.SAVE, requestContentId,
            "Control", requestFileName);
        final ProjectContentSnapshot content = new ProjectContentSnapshot(
            resultContentId, "Control", ProjectContentKind.MODEL, Optional.empty(),
            List.of("document"));
        final ProjectFileLifecycleEvent.After event = new ProjectFileLifecycleEvent.After(
            ProjectFileOperationResult.succeeded(request, content));
        return OfficialPsdFixturePreparation.SaveAfterIdentity.from(event);
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

    private static TextureRelationsSnapshot f1Relations(final boolean pasted) {
        return f1RelationsWithRaw("raw-f1", pasted);
    }

    private static TextureRelationsSnapshot f1RelationsWithRaw(final String rawId) {
        return f1RelationsWithRaw(rawId, true);
    }

    private static TextureRelationsSnapshot f1RelationsWithRaw(final String rawId,
        final boolean pasted) {
        final RawImageId raw = new RawImageId(rawId);
        final RawLayerBinding binding = new RawLayerBinding(raw, new RawLayerId("raw-binding"), 0,
            RawLayerBinding.DetailAvailability.AVAILABLE,
            RawLayerBinding.DetailAvailability.AVAILABLE);
        final ModelImageId modelImage = new ModelImageId("f1-model-image");
        final List<ArtMeshId> meshes = pasted
            ? List.of(new ArtMeshId("mesh-old"), new ArtMeshId("mesh-new"))
            : List.of(new ArtMeshId("mesh-old"));
        final ModelImageRelation image = new ModelImageRelation(
            modelImage, new Entry(), List.of(raw), Optional.of(raw),
            Map.of(raw, List.of(binding)), meshes);
        final List<ArtMeshTextureInputs> inputs = new java.util.ArrayList<>();
        inputs.add(new ArtMeshTextureInputs(new ArtMeshId("mesh-old"),
            List.of(TextureInputBinding.modelImage(modelImage)), java.util.OptionalInt.of(0)));
        if (pasted) {
            inputs.add(new ArtMeshTextureInputs(new ArtMeshId("mesh-new"),
                List.of(TextureInputBinding.modelImage(modelImage)), java.util.OptionalInt.of(0)));
        }
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            "f1-binding", pasted ? 8L : 7L, 1L, List.of(), List.of(image), List.of(), inputs);
    }

    private static TextureRelationsSnapshot f1RelationsWithoutNewInput() {
        final TextureRelationsSnapshot valid = f1Relations(true);
        final ModelImageRelation image = valid.modelImages().get(0);
        final ArtMeshTextureInputs old = valid.artMeshInputs().get(0);
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            valid.binding(), valid.generation(), valid.revision(), valid.rawImages(),
            List.of(image), valid.modelImageGroups(), List.of(old));
    }

    private static TextureRelationsSnapshot multiRawRelations() {
        final RawImageId current = new RawImageId("raw-current");
        final RawImageId other = new RawImageId("raw-other");
        final RawLayerBinding currentBinding = new RawLayerBinding(current,
            new RawLayerId("raw-layer"), 0, RawLayerBinding.DetailAvailability.AVAILABLE,
            RawLayerBinding.DetailAvailability.AVAILABLE);
        final RawLayerBinding otherBinding = new RawLayerBinding(other,
            new RawLayerId("other-layer"), 1, RawLayerBinding.DetailAvailability.AVAILABLE,
            RawLayerBinding.DetailAvailability.AVAILABLE);
        final ModelImageRelation image = new ModelImageRelation(
            new ModelImageId("model-image"), new Entry(), List.of(current, other),
            Optional.of(current),
            Map.of(current, List.of(currentBinding), other, List.of(otherBinding)),
            List.of(new ArtMeshId("mesh")));
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            "binding", 4L, 1L, List.of(), List.of(image), List.of(), List.of());
    }

    private static PsdClipMaskDocumentSnapshot psdSnapshot(final String rawId,
        final String fileName, final String leafLayerId) {
        return new PsdClipMaskDocumentSnapshot(rawId, "imports/" + fileName,
            List.of(new PsdLayerSnapshot(leafLayerId, "layer", true)));
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

    private static void assertSame(final Object expected, final Object actual,
        final String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected same identity");
        }
    }

    private static final class Entry implements ModelImageEntry {
        @Override public ModelImageId id() { return new ModelImageId("model-image"); }
        @Override public String name() { return "fixture"; }
        @Override public int width() { return 100; }
        @Override public int height() { return 100; }
    }

    private static final class ListShape { }
    private static final class RendererShape { }
    private static final class OptionShape { }
    private static final class ExactButton { }
    private static final class ButtonSubclass { }
    private static final class ActionShape { }
    private static final class WrongOption { }
}
