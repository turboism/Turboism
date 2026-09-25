package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic, host-free coverage for T011 native-shaped save/parse invocation. */
class EditorRawImagePsdNativeInvocationTest {
    @BeforeEach
    void resetFixture() {
        EditorRawImagePsdNativeFixture.reset();
    }

    @Test
    void savesWithConcreteProgressThenParsesAndReconstructsOnOneHostThread(@TempDir final Path temp)
        throws Exception {
        final Path target = temp.resolve("export.psd");
        Files.createFile(target);
        final Object model = new Object();
        final EditorRawImagePsdNativeFixture.SyntheticLayeredImage source =
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source");
        final EditorRawImagePsdAccess access = access(resolver("5.3.02", true), (identity, current) -> {
            assertEquals("session-a", identity);
            assertSame(model, current);
        });

        final EditorRawImagePsdAccess.ExportResult result = access.exportBoundPsd(
            "session-a",
            model,
            source,
            target
        );

        assertEquals(
            EditorRawImagePsdAccess.ExportStatus.READABLE_UNVERIFIED,
            result.status()
        );
        assertEquals(
            EditorRawImagePsdAccess.TargetPathSafety.FINAL_PATH_NOFOLLOW_PRE_AND_POST,
            result.targetPathSafety()
        );
        assertTrue(result.saveReturned());
        assertTrue(result.outputReadable());
        assertEquals(
            EditorRawImagePsdAccess.LayerCompleteness.UNVERIFIED,
            result.layerCompleteness()
        );
        assertEquals(
            List.of("progress", "name", "save", "parse", "construct", "dispose", "parsed-dispose", "parsed-dispose"),
            EditorRawImagePsdNativeFixture.events()
        );
        assertTrue(EditorRawImagePsdNativeFixture.edtEvents().stream().allMatch(Boolean::booleanValue));
        assertFalse(EditorRawImagePsdNativeFixture.parseFirstFlag);
        assertFalse(EditorRawImagePsdNativeFixture.parseSecondFlag);
        assertEquals(target.toFile(), EditorRawImagePsdNativeFixture.parseTarget);
        assertEquals(target.toFile(), EditorRawImagePsdNativeFixture.constructedTarget);
        assertEquals("raw-source", EditorRawImagePsdNativeFixture.constructedName);
        assertEquals(0, source.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastConstructed.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.first.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.second.disposeCalls);
        assertNotNull(EditorRawImagePsdNativeFixture.lastProgress);
        assertSame(EditorRawImagePsdNativeFixture.DEFAULT_PROGRESS, EditorRawImagePsdNativeFixture.lastProgress);
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void observesANormalVoidSaveThatSwallowsItsFailure(@TempDir final Path temp) throws Exception {
        EditorRawImagePsdNativeFixture.swallowSave = true;
        final Path target = temp.resolve("swallowed.psd");
        Files.createFile(target);
        final EditorRawImagePsdAccess.ExportResult result = access(
            resolver("5.3.02", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            target
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.OUTPUT_MISSING, result.status());
        assertTrue(result.saveReturned());
        assertFalse(result.outputReadable());
        assertEquals(
            EditorRawImagePsdAccess.TargetPathSafety.FINAL_PATH_NOFOLLOW_PRE_AND_POST,
            result.targetPathSafety()
        );
        assertEquals(EditorRawImagePsdAccess.FailurePhase.TARGET_POSTCHECK, result.failurePhase());
        assertEquals(
            List.of("progress", "name", "save"),
            EditorRawImagePsdNativeFixture.events()
        );
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS));
        assertEquals(0, Files.size(target));
    }

    @Test
    void reportsAThrownSaveWithoutAttemptingToParse(@TempDir final Path temp) {
        EditorRawImagePsdNativeFixture.throwOnSave = true;
        final EditorRawImagePsdAccess.ExportResult result = access(
            resolver("5.3.02", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            temp.resolve("thrown.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.NATIVE_FAILURE, result.status());
        assertFalse(result.saveReturned());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.SAVE, result.failurePhase());
        assertNotNull(result.failureType());
        assertNotNull(result.failureMessage());
        assertEquals(List.of("progress", "name", "save"), EditorRawImagePsdNativeFixture.events());
        final var failure = result.observation().failure().orElseThrow();
        assertEquals("SAVE", failure.phase());
        assertEquals("ILLEGAL_STATE", failure.category());
        assertFalse(failure.saveReturned());
    }

    @Test
    void retainsWrappedNativeSaveCategoryAcrossTheProductionObservationBridge(@TempDir final Path temp) {
        EditorRawImagePsdNativeFixture.saveFailure = () -> {
            throw new IllegalStateException("private native wrapper", new OutOfMemoryError("private path"));
        };
        final var result = access(resolver("5.3.02", true), (identity, model) -> { })
            .exportBoundPsd("session-a", new Object(),
                new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"), temp.resolve("failed.psd"));
        final var observation = result.observation();
        assertEquals("NATIVE_FAILURE", observation.nativeStatus());
        assertFalse(observation.readable());
        assertEquals("SAVE", observation.failure().orElseThrow().phase());
        assertEquals("OUT_OF_MEMORY", observation.failure().orElseThrow().category());
        assertFalse(observation.failure().orElseThrow().saveReturned());
        assertFalse(observation.toString().contains("private"));
        assertEquals(List.of("progress", "name", "save"), EditorRawImagePsdNativeFixture.events());
    }

    @Test
    void reportsAParserFailureAfterSaveReturned(@TempDir final Path temp) {
        EditorRawImagePsdNativeFixture.throwOnParse = true;
        final EditorRawImagePsdAccess.ExportResult result = access(
            resolver("5.3.02", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            temp.resolve("parse-failure.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.PARSE_FAILED, result.status());
        assertTrue(result.saveReturned());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.PARSE, result.failurePhase());
        assertEquals("PARSE", result.observation().failure().orElseThrow().phase());
        assertEquals("ILLEGAL_STATE", result.observation().failure().orElseThrow().category());
        assertTrue(result.observation().failure().orElseThrow().saveReturned());
        assertEquals(
            List.of("progress", "name", "save", "parse"),
            EditorRawImagePsdNativeFixture.events()
        );
    }

    @Test
    void stagedReadFailuresRetainPhaseAndCategoryWithoutMutation(@TempDir final Path temp) throws Exception {
        final Path stage = temp.resolve("staged.psd");
        Files.writeString(stage, "synthetic-psd");
        final var access = access(resolver("5.3.02", true), (identity, model) -> { });
        EditorRawImagePsdNativeFixture.throwOnParse = true;
        final var parseFailure = EditorHostThread.dispatch("stage test", () ->
            assertThrows(java.io.IOException.class, () -> access.parseStageOnHostThread(stage, "source")));
        final var parsed = EditorRawImagePsdAccess.unreadableStage(parseFailure);
        assertEquals("PARSE", parsed.failure().orElseThrow().phase());
        assertEquals("ILLEGAL_STATE", parsed.failure().orElseThrow().category());
        EditorRawImagePsdNativeFixture.throwOnParse = false;
        EditorRawImagePsdNativeFixture.constructFailure = () -> {
            throw new OutOfMemoryError("private-host-path");
        };
        final var constructFailure = EditorHostThread.dispatch("stage test", () ->
            assertThrows(java.io.IOException.class, () -> access.parseStageOnHostThread(stage, "source")));
        final var constructed = EditorRawImagePsdAccess.unreadableStage(constructFailure);
        assertEquals("CONSTRUCT", constructed.failure().orElseThrow().phase());
        assertEquals("OUT_OF_MEMORY", constructed.failure().orElseThrow().category());
        for (final var outcome : List.of(parsed, constructed)) {
            assertEquals("STAGE_UNREADABLE", outcome.nativeStatus());
            assertFalse(outcome.nativeReturned());
            assertFalse(outcome.mutationUnknown());
            assertFalse(outcome.toString().contains("private-host-path"));
        }
        assertEquals(null, constructFailure.getCause());
    }

    @Test
    void reportsTemporaryResourceCleanupFailureWithoutClaimingReadableExport(@TempDir final Path temp) {
        final var source = new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("source");
        EditorRawImagePsdNativeFixture.disposeFailure = () -> {
            throw new IllegalStateException("private disposal failure");
        };
        final var result = access(resolver("5.3.02", true), (identity, model) -> { })
            .exportBoundPsd("session-a", new Object(), source, temp.resolve("cleanup.psd"));
        assertEquals(EditorRawImagePsdAccess.ExportStatus.NATIVE_FAILURE, result.status());
        assertTrue(result.saveReturned());
        assertFalse(result.outputReadable());
        assertEquals("DISPOSE", result.failurePhase().name());
        assertEquals("DISPOSE", result.observation().failure().orElseThrow().phase());
        assertEquals("ILLEGAL_STATE", result.observation().failure().orElseThrow().category());
        assertFalse(result.observation().toString().contains("private disposal failure"));
        assertEquals(0, source.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastConstructed.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.first.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.second.disposeCalls);
    }

    @Test
    void cleansOwnedParsedImagesWhenReconstructionFails(@TempDir final Path temp) {
        EditorRawImagePsdNativeFixture.constructFailure = () -> { throw new IllegalStateException("construction failed"); };
        final var result = access(resolver("5.3.02", true), (identity, model) -> { })
            .exportBoundPsd("session-a", new Object(),
                new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("source"), temp.resolve("construct.psd"));
        assertEquals(EditorRawImagePsdAccess.ExportStatus.PARSE_FAILED, result.status());
        assertEquals("CONSTRUCT", result.failurePhase().name());
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.first.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.second.disposeCalls);
    }

    @Test
    void attemptsAllOwnedImagesAndRejectsParsedCleanupFailure(@TempDir final Path temp) {
        EditorRawImagePsdNativeFixture.throwOnParsedImageDispose = true;
        final var result = access(resolver("5.3.02", true), (identity, model) -> { })
            .exportBoundPsd("session-a", new Object(),
                new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("source"), temp.resolve("parsed-cleanup.psd"));
        assertEquals(EditorRawImagePsdAccess.ExportStatus.NATIVE_FAILURE, result.status());
        assertEquals("DISPOSE", result.failurePhase().name());
        assertFalse(result.outputReadable());
        assertFalse(result.observation().toString().contains("private parsed image"));
        assertEquals(1, EditorRawImagePsdNativeFixture.lastConstructed.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.first.disposeCalls);
        assertEquals(1, EditorRawImagePsdNativeFixture.lastParsed.second.disposeCalls);
    }

    @Test
    void leavesStagedReplacementOwnershipWithItsCaller(@TempDir final Path temp) throws Exception {
        final Path stage = temp.resolve("stage.psd");
        Files.writeString(stage, "synthetic-psd");
        final var access = access(resolver("5.3.02", true), (identity, model) -> { });
        final Object incoming = EditorHostThread.dispatch("stage ownership", () ->
            assertDoesNotThrow(() -> access.parseStageOnHostThread(stage, "external-edit.psd")));
        assertSame(EditorRawImagePsdNativeFixture.lastConstructed, incoming);
        assertEquals(0, EditorRawImagePsdNativeFixture.lastConstructed.disposeCalls);
        assertEquals(0, EditorRawImagePsdNativeFixture.lastParsed.first.disposeCalls);
        assertEquals(0, EditorRawImagePsdNativeFixture.lastParsed.second.disposeCalls);
        assertEquals(List.of("parse", "construct"), EditorRawImagePsdNativeFixture.events());
    }

    @Test
    void rejectsExistingNonEmptyTargetBeforeNativeSave(@TempDir final Path temp) throws Exception {
        final Path target = temp.resolve("existing.psd");
        Files.writeString(target, "old-psd");
        final EditorRawImagePsdAccess.ExportResult result = access(
            resolver("5.3.02", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            target
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.TARGET_REJECTED, result.status());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.TARGET_PRECHECK, result.failurePhase());
        assertEquals(
            EditorRawImagePsdAccess.TargetPathSafety.FINAL_PATH_NOFOLLOW_PRE_ONLY,
            result.targetPathSafety()
        );
        assertFalse(result.saveReturned());
        assertTrue(EditorRawImagePsdNativeFixture.events().isEmpty());
        assertEquals("old-psd", Files.readString(target));
    }

    @Test
    void rejectsSymbolicLinkTargetBeforeNativeSave(@TempDir final Path temp) throws Exception {
        final Path linkedFile = temp.resolve("existing.psd");
        Files.writeString(linkedFile, "old-psd");
        final Path target = temp.resolve("link.psd");
        Files.createSymbolicLink(target, linkedFile.getFileName());
        final EditorRawImagePsdAccess.ExportResult result = access(
            resolver("5.3.02", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            target
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.TARGET_REJECTED, result.status());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.TARGET_PRECHECK, result.failurePhase());
        assertEquals(
            EditorRawImagePsdAccess.TargetPathSafety.FINAL_PATH_NOFOLLOW_PRE_ONLY,
            result.targetPathSafety()
        );
        assertFalse(result.saveReturned());
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("old-psd", Files.readString(linkedFile));
        assertTrue(EditorRawImagePsdNativeFixture.events().isEmpty());
    }

    @Test
    void rejectsDocumentSwitchAfterNativeSaveBeforeReadableResult(@TempDir final Path temp)
        throws Exception {
        final Path target = temp.resolve("switched.psd");
        final Object model = new Object();
        final Object replacement = new Object();
        final AtomicReference<Object> currentModel = new AtomicReference<>(model);
        final AtomicInteger guardCalls = new AtomicInteger();
        EditorRawImagePsdNativeFixture.afterSave = () -> currentModel.set(replacement);

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> access(
                resolver("5.3.02", true),
                (identity, checkedModel) -> {
                    assertEquals("session-a", identity);
                    guardCalls.incrementAndGet();
                    if (currentModel.get() != checkedModel) {
                        throw new IllegalStateException("document switched during PSD export");
                    }
                }
            ).exportBoundPsd(
                "session-a",
                model,
                new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
                target
            )
        );

        assertEquals("document switched during PSD export", failure.getMessage());
        assertEquals(2, guardCalls.get());
        assertEquals(
            List.of("progress", "name", "save", "parse", "construct", "dispose", "parsed-dispose", "parsed-dispose"),
            EditorRawImagePsdNativeFixture.events()
        );
        assertTrue(EditorRawImagePsdNativeFixture.edtEvents().stream().allMatch(Boolean::booleanValue));
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.size(target) > 0);
    }

    @Test
    void rejectsABoundSourceOutsideTheVerifiedLayeredImageType(@TempDir final Path temp) {
        final EditorRawImagePsdAccess.ExportResult result = access(
            resolver("5.3.02", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new Object(),
            temp.resolve("wrong-source.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.BOUND_SOURCE_INVALID, result.status());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.SOURCE_IDENTITY, result.failurePhase());
        assertTrue(EditorRawImagePsdNativeFixture.events().isEmpty());
    }

    @Test
    void rejectsUnsupportedOrUnauthorizedPlansBeforeCallingNative(@TempDir final Path temp) {
        final EditorRawImagePsdAccess.ExportResult unauthorized = access(
            resolver("5.3.02", false),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            temp.resolve("unauthorized.psd")
        );
        assertEquals(EditorRawImagePsdAccess.ExportStatus.UNAVAILABLE, unauthorized.status());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.AVAILABILITY, unauthorized.failurePhase());
        assertTrue(EditorRawImagePsdNativeFixture.events().isEmpty());

        EditorRawImagePsdNativeFixture.reset();
        final EditorRawImagePsdAccess.ExportResult unsupported = access(
            resolver("5.3.03", true),
            (identity, model) -> { }
        ).exportBoundPsd(
            "session-a",
            new Object(),
            new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
            temp.resolve("unsupported.psd")
        );
        assertEquals(EditorRawImagePsdAccess.ExportStatus.UNAVAILABLE, unsupported.status());
        assertTrue(EditorRawImagePsdNativeFixture.events().isEmpty());
    }

    @Test
    void rejectsStaleCurrentModelBeforeProgressOrNativeInvocation(@TempDir final Path temp) {
        final Object model = new Object();
        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> access(
                resolver("5.3.02", true),
                (identity, current) -> {
                    assertEquals("session-a", identity);
                    assertSame(model, current);
                    throw new IllegalStateException("stale model generation");
                }
            ).exportBoundPsd(
                "session-a",
                model,
                new EditorRawImagePsdNativeFixture.SyntheticLayeredImage("raw-source"),
                temp.resolve("stale.psd")
            )
        );

        assertEquals("stale model generation", failure.getMessage());
        assertTrue(EditorRawImagePsdNativeFixture.events().isEmpty());
    }

    private static EditorRawImagePsdAccess access(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard guard
    ) {
        return new EditorRawImagePsdAccess(resolver, guard);
    }

    private static VerifiedMemberResolver resolver(
        final String version,
        final boolean authorized
    ) {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        for (String alias : EditorRawImagePsdSelectorContract.REQUIRED_ALIASES) {
            selectors.put(alias, StaticSelector.classSelector(alias, "java/lang/Object"));
        }
        if (authorized) {
            selectors.put(
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_CLASS_ALIAS,
                StaticSelector.classSelector(
                    EditorRawImagePsdSelectorContract.PSD_PROGRESS_CLASS_ALIAS,
                    internal(EditorRawImagePsdNativeFixture.SyntheticProgress.class)
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS,
                StaticSelector.staticMethod(
                    EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS,
                    internal(EditorRawImagePsdNativeFixture.SyntheticProgressFactory.class),
                    "e",
                    "()" + reference(EditorRawImagePsdNativeFixture.SyntheticProgress.class),
                    StaticSelector.ACCESS_PUBLIC
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                StaticSelector.classSelector(
                    EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                    internal(EditorRawImagePsdNativeFixture.SyntheticLayeredImage.class)
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_NAME_ALIAS,
                instanceMethod(
                    EditorRawImagePsdSelectorContract.LAYERED_IMAGE_NAME_ALIAS,
                    EditorRawImagePsdNativeFixture.SyntheticLayeredImage.class,
                    "getName",
                    "()Ljava/lang/String;"
                )
            );
            selectors.put("cubism.editor-model.psd-document.layers-owned",
                instanceMethod("cubism.editor-model.psd-document.layers-owned",
                    EditorRawImagePsdNativeFixture.SyntheticParsed.class, "h",
                    "()[" + reference(EditorRawImagePsdNativeFixture.SyntheticParsedLayer.class)));
            selectors.put("cubism.editor-model.psd-layer.image-owned",
                instanceMethod("cubism.editor-model.psd-layer.image-owned",
                    EditorRawImagePsdNativeFixture.SyntheticParsedLayer.class, "c",
                    "()" + reference(EditorRawImagePsdNativeFixture.SyntheticParsedImage.class)));
            selectors.put("cubism.editor-model.psd-image.dispose-owned",
                instanceMethod("cubism.editor-model.psd-image.dispose-owned",
                    EditorRawImagePsdNativeFixture.SyntheticParsedImage.class, "dispose", "()V"));
            selectors.put("cubism.editor-model.layered-image.dispose-owned",
                instanceMethod("cubism.editor-model.layered-image.dispose-owned",
                    EditorRawImagePsdNativeFixture.SyntheticLayeredImage.class, "dispose", "()V"));
            selectors.put(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
                instanceMethod(
                    EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
                    EditorRawImagePsdNativeFixture.SyntheticLayeredImage.class,
                    "save",
                    "(Ljava/io/File;"
                        + reference(EditorRawImagePsdNativeFixture.SyntheticProgress.class)
                        + ")V"
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
                StaticSelector.classSelector(
                    EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
                    internal(EditorRawImagePsdNativeFixture.SyntheticParsed.class)
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
                StaticSelector.field(
                    EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
                    internal(EditorRawImagePsdNativeFixture.SyntheticPsdDocument.class),
                    "a",
                    reference(EditorRawImagePsdNativeFixture.SyntheticCompanion.class),
                    StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
                instanceMethod(
                    EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
                    EditorRawImagePsdNativeFixture.SyntheticCompanion.class,
                    "a",
                    "(Ljava/io/File;ZZ)"
                        + reference(EditorRawImagePsdNativeFixture.SyntheticParsed.class)
                )
            );
            selectors.put(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
                StaticSelector.constructor(
                    EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
                    internal(EditorRawImagePsdNativeFixture.SyntheticLayeredImage.class),
                    "(" + reference(EditorRawImagePsdNativeFixture.SyntheticParsed.class)
                        + "Ljava/io/File;Ljava/lang/String;)V",
                    StaticSelector.ACCESS_PUBLIC
                )
            );
        }
        final Set<String> capabilities = authorized
            ? Set.of(EditorRawImagePsdSelectorContract.CAPABILITY_ID)
            : Set.of("fixture.other");
        return TestVerifiedResolvers.create(
            version,
            EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            new ArrayList<>(selectors.values()),
            EditorRawImagePsdNativeFixture.class.getClassLoader()
        );
    }

    private static StaticSelector instanceMethod(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String reference(final Class<?> type) {
        return "L" + internal(type) + ";";
    }
}
