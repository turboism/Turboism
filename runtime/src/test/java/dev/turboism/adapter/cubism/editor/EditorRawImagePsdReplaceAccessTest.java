package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdReplaceSelectorContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic host-free coverage for the internal T015 replace invocation boundary. */
class EditorRawImagePsdReplaceAccessTest {
    @BeforeEach
    void resetFixture() {
        EditorRawImagePsdReplaceNativeFixture.reset();
    }

    @Test
    void invokesTheExplicitFiveArgumentEntryOnceOnTheHostThreadAndLeavesUndoToNative(@TempDir final Path temp)
            throws Exception {
        final Path stage = readyStage(temp);
        final Object model = new Object();
        final var app = new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController();
        final var document = new EditorRawImagePsdReplaceNativeFixture.SyntheticDocument(
                new EditorRawImagePsdReplaceNativeFixture.SyntheticEditMode(false));
        final var oldTarget = new EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage("old");
        final var incoming = new EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage("incoming");
        final AtomicInteger guardCalls = new AtomicInteger();

        final EditorRawImagePsdReplaceAccess.ReplaceResult result = access(
                        resolver("5.3.02", true), (identity, current) -> {
                            assertEquals("session-a", identity);
                            assertSame(model, current);
                            assertTrue(EditorHostThread.isCurrent());
                            guardCalls.incrementAndGet();
                        })
                .replacePsd("session-a", model, app, document, List.of(oldTarget), incoming, stage);

        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.NATIVE_RETURNED_UNVERIFIED, result.status());
        assertTrue(result.preCurrentGuardPassed());
        assertTrue(result.postCurrentGuardPassed());
        assertTrue(result.nativeInvocationAttempted());
        assertTrue(result.nativeReturned());
        assertEquals(EditorRawImagePsdReplaceAccess.MutationState.UNKNOWN, result.mutationState());
        assertFalse(result.requiresPause());
        assertTrue(result.requiresReobservation());
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.NONE, result.failurePhase());
        assertEquals(2, guardCalls.get());
        assertSame(app, EditorRawImagePsdReplaceNativeFixture.lastAppController);
        assertSame(incoming, EditorRawImagePsdReplaceNativeFixture.lastIncoming);
        assertSame(document, EditorRawImagePsdReplaceNativeFixture.lastDocument);
        assertEquals(stage.toFile(), EditorRawImagePsdReplaceNativeFixture.lastStage);
        assertEquals(List.of(oldTarget), EditorRawImagePsdReplaceNativeFixture.lastTargets);
        assertEquals(
                List.of("current-edit-mode", "is-editing", "native-begin-edit", "native-mutation", "native-end-edit"),
                EditorRawImagePsdReplaceNativeFixture.events());
        assertEquals(
                1,
                EditorRawImagePsdReplaceNativeFixture.events().stream()
                        .filter("native-begin-edit"::equals)
                        .count());
        assertEquals(
                1,
                EditorRawImagePsdReplaceNativeFixture.events().stream()
                        .filter("native-end-edit"::equals)
                        .count());
        assertFalse(EditorRawImagePsdReplaceNativeFixture.events().contains("adapter-begin-edit"));
        assertFalse(EditorRawImagePsdReplaceNativeFixture.events().contains("adapter-undo-add"));
        assertTrue(EditorRawImagePsdReplaceNativeFixture.edtEvents().stream().allMatch(Boolean::booleanValue));
        assertEquals("stage-data", Files.readString(stage));
    }

    @Test
    void reportsPartialUnknownWhenPostGuardFindsAStaleModelAfterNativeReturns(@TempDir final Path temp)
            throws Exception {
        final Path stage = readyStage(temp);
        final Object model = new Object();
        final Object replacement = new Object();
        final AtomicReference<Object> currentModel = new AtomicReference<>(model);
        final AtomicInteger guardCalls = new AtomicInteger();
        EditorRawImagePsdReplaceNativeFixture.afterNative = () -> currentModel.set(replacement);

        final EditorRawImagePsdReplaceAccess.ReplaceResult result = access(
                        resolver("5.3.02", true), (identity, current) -> {
                            guardCalls.incrementAndGet();
                            if (currentModel.get() != current) {
                                throw new IllegalStateException("stale model generation after native replace");
                            }
                        })
                .replacePsd(
                        "session-a",
                        model,
                        new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController(),
                        document(false),
                        List.of(layer("old")),
                        layer("incoming"),
                        stage);

        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.PARTIAL_FAILURE, result.status());
        assertTrue(result.preCurrentGuardPassed());
        assertFalse(result.postCurrentGuardPassed());
        assertTrue(result.nativeInvocationAttempted());
        assertTrue(result.nativeReturned());
        assertEquals(EditorRawImagePsdReplaceAccess.MutationState.UNKNOWN, result.mutationState());
        assertTrue(result.requiresPause());
        assertTrue(result.requiresReobservation());
        assertEquals(
                EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.CURRENT_GUARD_AFTER_NATIVE, result.failurePhase());
        assertEquals(2, guardCalls.get());
    }

    @Test
    void rejectsAnExistingEditBeforeNativeCanCreateAnotherGroupUndo(@TempDir final Path temp) throws Exception {
        final EditorRawImagePsdReplaceAccess.ReplaceResult result = access(
                        resolver("5.3.02", true), (identity, model) -> {})
                .replacePsd(
                        "session-a",
                        new Object(),
                        new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController(),
                        document(true),
                        List.of(layer("old")),
                        layer("incoming"),
                        readyStage(temp));

        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.EDITING_REJECTED, result.status());
        assertFalse(result.nativeInvocationAttempted());
        assertEquals(List.of("current-edit-mode", "is-editing"), EditorRawImagePsdReplaceNativeFixture.events());
    }

    @Test
    void unavailableAfterThePreGuardPreservesObservedGuardSuccess(@TempDir final Path temp) throws Exception {
        final AtomicInteger guardCalls = new AtomicInteger();
        final EditorRawImagePsdReplaceAccess.ReplaceResult result = access(
                        resolver("5.3.02", true), (identity, model) -> guardCalls.incrementAndGet())
                .replacePsd(
                        "session-a",
                        new Object(),
                        new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController(),
                        new EditorRawImagePsdReplaceNativeFixture.SyntheticDocument(null),
                        List.of(layer("old")),
                        layer("incoming"),
                        readyStage(temp));

        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.UNAVAILABLE, result.status());
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.EDITING_STATE, result.failurePhase());
        assertTrue(result.preCurrentGuardPassed());
        assertFalse(result.postCurrentGuardPassed());
        assertFalse(result.nativeInvocationAttempted());
        assertEquals(1, guardCalls.get());
    }

    @Test
    void rejectsEmptyAndIdentityDuplicateTargetsBeforeStateOrNativeInvocation(@TempDir final Path temp)
            throws Exception {
        final var app = new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController();
        final var document = document(false);
        final var incoming = layer("incoming");
        final Path stage = readyStage(temp);

        final EditorRawImagePsdReplaceAccess.ReplaceResult empty = access(
                        resolver("5.3.02", true), (identity, model) -> {})
                .replacePsd("session-a", new Object(), app, document, List.of(), incoming, stage);
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.INVALID_INPUT, empty.status());
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.TARGETS, empty.failurePhase());
        assertFalse(empty.nativeInvocationAttempted());
        assertTrue(EditorRawImagePsdReplaceNativeFixture.events().isEmpty());

        EditorRawImagePsdReplaceNativeFixture.reset();
        final var duplicate = layer("duplicate");
        final EditorRawImagePsdReplaceAccess.ReplaceResult repeated = access(
                        resolver("5.3.02", true), (identity, model) -> {})
                .replacePsd("session-a", new Object(), app, document, List.of(duplicate, duplicate), incoming, stage);
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.INVALID_INPUT, repeated.status());
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.TARGETS, repeated.failurePhase());
        assertFalse(repeated.nativeInvocationAttempted());
        assertTrue(EditorRawImagePsdReplaceNativeFixture.events().isEmpty());
    }

    @Test
    void snapshotsAChangingTargetListOnceAndUsesThatSnapshotForValidationAndNative(@TempDir final Path temp)
            throws Exception {
        final var validatedTarget = layer("validated");
        final var replacementTarget = layer("replacement");
        final AtomicInteger iteratorCalls = new AtomicInteger();
        final List<EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage> mutableTargets =
                new java.util.AbstractList<>() {
                    @Override
                    public EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage get(final int index) {
                        throw new AssertionError("snapshot must use one iterator, not indexed rereads");
                    }

                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public java.util.Iterator<EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage> iterator() {
                        final int read = iteratorCalls.getAndIncrement();
                        return List.of(read == 0 ? validatedTarget : replacementTarget)
                                .iterator();
                    }
                };

        final EditorRawImagePsdReplaceAccess.ReplaceResult result = access(
                        resolver("5.3.02", true), (identity, model) -> {})
                .replacePsd(
                        "session-a",
                        new Object(),
                        new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController(),
                        document(false),
                        mutableTargets,
                        layer("incoming"),
                        readyStage(temp));

        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.NATIVE_RETURNED_UNVERIFIED, result.status());
        assertEquals(List.of(validatedTarget), EditorRawImagePsdReplaceNativeFixture.lastTargets);
        assertEquals(1, iteratorCalls.get());
    }

    @Test
    void reportsUnknownPartialForBothMutationBeforeAndAfterNativeExceptions(@TempDir final Path temp) throws Exception {
        final Path stage = readyStage(temp);
        final var app = new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController();

        EditorRawImagePsdReplaceNativeFixture.failureMode =
                EditorRawImagePsdReplaceNativeFixture.FailureMode.BEFORE_MUTATION;
        final EditorRawImagePsdReplaceAccess.ReplaceResult before = access(
                        resolver("5.3.02", true), (identity, model) -> {})
                .replacePsd(
                        "session-a",
                        new Object(),
                        app,
                        document(false),
                        List.of(layer("old")),
                        layer("incoming"),
                        stage);
        assertPartialUnknown(before);
        assertEquals(
                List.of("current-edit-mode", "is-editing", "native-begin-edit"),
                EditorRawImagePsdReplaceNativeFixture.events());
        assertEquals(0, EditorRawImagePsdReplaceNativeFixture.mutationCount);

        EditorRawImagePsdReplaceNativeFixture.reset();
        EditorRawImagePsdReplaceNativeFixture.failureMode =
                EditorRawImagePsdReplaceNativeFixture.FailureMode.AFTER_MUTATION;
        final EditorRawImagePsdReplaceAccess.ReplaceResult after = access(
                        resolver("5.3.02", true), (identity, model) -> {})
                .replacePsd(
                        "session-a",
                        new Object(),
                        app,
                        document(false),
                        List.of(layer("old")),
                        layer("incoming"),
                        stage);
        assertPartialUnknown(after);
        assertEquals(
                List.of("current-edit-mode", "is-editing", "native-begin-edit", "native-mutation"),
                EditorRawImagePsdReplaceNativeFixture.events());
        assertEquals(1, EditorRawImagePsdReplaceNativeFixture.mutationCount);
        assertEquals("stage-data", Files.readString(stage));
    }

    @Test
    void rejectsStaleUnsupportedAndUnauthorizedCallsWithoutNativeEvents(@TempDir final Path temp) throws Exception {
        final var app = new EditorRawImagePsdReplaceNativeFixture.SyntheticAppController();
        final Path stage = readyStage(temp);
        final AtomicInteger guardCalls = new AtomicInteger();
        final EditorObjectReadAccess.CurrentGuard guard = (identity, model) -> guardCalls.incrementAndGet();

        final EditorRawImagePsdReplaceAccess.ReplaceResult stale = access(
                        resolver("5.3.02", true), (identity, model) -> {
                            guardCalls.incrementAndGet();
                            throw new IllegalStateException("stale model generation");
                        })
                .replacePsd(
                        "session-a",
                        new Object(),
                        app,
                        document(false),
                        List.of(layer("old")),
                        layer("incoming"),
                        stage);
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.STALE_BEFORE_NATIVE, stale.status());
        assertFalse(stale.nativeInvocationAttempted());
        assertTrue(EditorRawImagePsdReplaceNativeFixture.events().isEmpty());

        EditorRawImagePsdReplaceNativeFixture.reset();
        final EditorRawImagePsdReplaceAccess.ReplaceResult unsupported = access(resolver("5.3.03", true), guard)
                .replacePsd(
                        "session-a",
                        new Object(),
                        app,
                        document(false),
                        List.of(layer("old")),
                        layer("incoming"),
                        stage);
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.UNAVAILABLE, unsupported.status());
        assertFalse(unsupported.preCurrentGuardPassed());
        assertFalse(unsupported.postCurrentGuardPassed());
        assertTrue(EditorRawImagePsdReplaceNativeFixture.events().isEmpty());

        EditorRawImagePsdReplaceNativeFixture.reset();
        final EditorRawImagePsdReplaceAccess.ReplaceResult unauthorized = access(resolver("5.3.02", false), guard)
                .replacePsd(
                        "session-a",
                        new Object(),
                        app,
                        document(false),
                        List.of(layer("old")),
                        layer("incoming"),
                        stage);
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.UNAVAILABLE, unauthorized.status());
        assertFalse(unauthorized.preCurrentGuardPassed());
        assertFalse(unauthorized.postCurrentGuardPassed());
        assertTrue(EditorRawImagePsdReplaceNativeFixture.events().isEmpty());
    }

    private static void assertPartialUnknown(final EditorRawImagePsdReplaceAccess.ReplaceResult result) {
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceStatus.PARTIAL_FAILURE, result.status());
        assertTrue(result.preCurrentGuardPassed());
        assertTrue(result.postCurrentGuardPassed());
        assertTrue(result.nativeInvocationAttempted());
        assertFalse(result.nativeReturned());
        assertEquals(EditorRawImagePsdReplaceAccess.MutationState.UNKNOWN, result.mutationState());
        assertTrue(result.requiresPause());
        assertTrue(result.requiresReobservation());
        assertEquals(EditorRawImagePsdReplaceAccess.ReplaceFailurePhase.NATIVE_INVOCATION, result.failurePhase());
    }

    private static Path readyStage(final Path temp) throws Exception {
        final Path stage = temp.resolve("incoming.psd");
        Files.writeString(stage, "stage-data");
        return stage;
    }

    private static EditorRawImagePsdReplaceNativeFixture.SyntheticDocument document(final boolean editing) {
        return new EditorRawImagePsdReplaceNativeFixture.SyntheticDocument(
                new EditorRawImagePsdReplaceNativeFixture.SyntheticEditMode(editing));
    }

    private static EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage layer(final String name) {
        return new EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage(name);
    }

    private static EditorRawImagePsdReplaceAccess access(
            final VerifiedMemberResolver resolver, final EditorObjectReadAccess.CurrentGuard guard) {
        return new EditorRawImagePsdReplaceAccess(resolver, guard);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean authorized) {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        for (final String alias : EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES) {
            selectors.put(alias, StaticSelector.classSelector(alias, "java/lang/Object"));
        }
        if (authorized) {
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.APP_CONTROLLER_CLASS_ALIAS,
                    StaticSelector.classSelector(
                            EditorRawImagePsdReplaceSelectorContract.APP_CONTROLLER_CLASS_ALIAS,
                            internal(EditorRawImagePsdReplaceNativeFixture.SyntheticAppController.class)));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.MODELING_DOCUMENT_CLASS_ALIAS,
                    StaticSelector.classSelector(
                            EditorRawImagePsdReplaceSelectorContract.MODELING_DOCUMENT_CLASS_ALIAS,
                            internal(EditorRawImagePsdReplaceNativeFixture.SyntheticDocument.class)));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.CURRENT_EDIT_MODE_ALIAS,
                    method(
                            EditorRawImagePsdReplaceSelectorContract.CURRENT_EDIT_MODE_ALIAS,
                            EditorRawImagePsdReplaceNativeFixture.SyntheticDocument.class,
                            "getCurrentEditMode",
                            "()" + reference(EditorRawImagePsdReplaceNativeFixture.SyntheticEditMode.class)));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.EDITING_STATE_ALIAS,
                    method(
                            EditorRawImagePsdReplaceSelectorContract.EDITING_STATE_ALIAS,
                            EditorRawImagePsdReplaceNativeFixture.SyntheticEditMode.class,
                            "isEditing",
                            "()Z"));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                    StaticSelector.classSelector(
                            EditorRawImagePsdReplaceSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                            internal(EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage.class)));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_CLASS_ALIAS,
                    StaticSelector.classSelector(
                            EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_CLASS_ALIAS,
                            internal(EditorRawImagePsdReplaceNativeFixture.SyntheticNativeProcess.class)));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_INSTANCE_ALIAS,
                    StaticSelector.field(
                            EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_INSTANCE_ALIAS,
                            internal(EditorRawImagePsdReplaceNativeFixture.SyntheticNativeProcess.class),
                            "INSTANCE",
                            reference(EditorRawImagePsdReplaceNativeFixture.SyntheticNativeProcess.class),
                            StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
            selectors.put(
                    EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_REPLACE_ALIAS,
                    method(
                            EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_REPLACE_ALIAS,
                            EditorRawImagePsdReplaceNativeFixture.SyntheticNativeProcess.class,
                            "a",
                            "(" + reference(EditorRawImagePsdReplaceNativeFixture.SyntheticAppController.class)
                                    + reference(EditorRawImagePsdReplaceNativeFixture.SyntheticLayeredImage.class)
                                    + "Ljava/io/File;"
                                    + reference(EditorRawImagePsdReplaceNativeFixture.SyntheticDocument.class)
                                    + "Ljava/util/List;)V"));
        }
        final Set<String> capabilities =
                authorized ? Set.of(EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID) : Set.of("fixture.other");
        return TestVerifiedResolvers.create(
                version,
                EditorRawImagePsdReplaceSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                new ArrayList<>(selectors.values()),
                EditorRawImagePsdReplaceNativeFixture.class.getClassLoader());
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String reference(final Class<?> type) {
        return "L" + internal(type) + ";";
    }
}
