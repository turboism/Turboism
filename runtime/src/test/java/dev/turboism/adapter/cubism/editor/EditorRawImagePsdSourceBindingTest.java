package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRawImagePsdSelectorContract;
import dev.turboism.sdk.cubism.id.RawImageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic, host-free coverage for exact PSD source binding and export-fidelity observations. */
class EditorRawImagePsdSourceBindingTest {
    @BeforeEach
    void resetFixture() {
        SyntheticSourceFixture.reset();
    }

    @Test
    void resolvesTheRequestedIdFromTheCurrentSourceWhenNamesMatch(@TempDir final Path temp)
        throws Exception {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        final Path target = temp.resolve("raw-b.psd");

        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId("raw-b"),
            target
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.SAVED_UNVERIFIED, result.status());
        assertSame(fixture.rawB, SyntheticSourceFixture.lastSavedSource);
        assertNotEquals(fixture.rawA, SyntheticSourceFixture.lastSavedSource);
        assertEquals("shared-source.psd", fixture.rawA.name);
        assertEquals("shared-source.psd", fixture.rawB.name);
        assertEquals(EditorRawImagePsdIntegrityAccess.VerificationStatus.UNAVAILABLE,
            result.integrityVerification().status());
    }

    @Test
    void doesNotAcceptAnExternalSameTypeObjectByNameOrId(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        final SyntheticSourceFixture.LayeredImage external = fixture.external;

        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId(external.guid.value),
            temp.resolve("external.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.BOUND_SOURCE_INVALID, result.status());
        assertEquals(EditorRawImagePsdAccess.FailurePhase.SOURCE_BINDING, result.failurePhase());
        assertEquals("SOURCE_BINDING_NOT_FOUND", result.failureType());
        assertNull(SyntheticSourceFixture.lastSavedSource);
        assertTrue(SyntheticSourceFixture.events().stream().noneMatch("save"::equals));
    }

    @Test
    void rejectsDuplicateRawImageIdsBeforeNativeInvocation(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        fixture.manager.rawImages.add(new SyntheticSourceFixture.Wrapper(fixture.rawA));

        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId("raw-a"),
            temp.resolve("duplicate.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.BOUND_SOURCE_INVALID, result.status());
        assertEquals("SOURCE_BINDING_DUPLICATE_ID", result.failureType());
        assertNull(SyntheticSourceFixture.lastSavedSource);
        assertTrue(SyntheticSourceFixture.events().stream().noneMatch("save"::equals));
    }

    @Test
    void exportsAnOrdinaryImageWithoutRequiringSourcePsdDocument(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        final SyntheticSourceFixture.LayeredImage ordinary =
            SyntheticSourceFixture.ordinary("raw-ordinary", "ordinary.png");
        fixture.manager.rawImages.add(new SyntheticSourceFixture.Wrapper(ordinary));

        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId("raw-ordinary"),
            temp.resolve("ordinary.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.SAVED_UNVERIFIED, result.status());
        assertTrue(result.saveReturned());
        assertTrue(result.outputReadable());
        assertSame(ordinary, SyntheticSourceFixture.lastSavedSource);
        assertEquals(
            EditorRawImagePsdIntegrityAccess.VerificationStatus.UNAVAILABLE,
            result.integrityVerification().status()
        );
        assertNull(ordinary.psdDoc);
        assertTrue(SyntheticSourceFixture.events().contains("save"));
        assertFalse(SyntheticSourceFixture.events().contains("parse"));
        assertFalse(SyntheticSourceFixture.events().contains("construct"));
        assertFalse(
            SyntheticSourceFixture.events().contains("image-psd-doc"),
            "source PSD document must not be required or queried for ordinary raw export"
        );
    }

    @Test
    void keepsBindingAndNativeObservationOnOneHostThread(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();

        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId("raw-a"),
            temp.resolve("host-thread.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.SAVED_UNVERIFIED, result.status());
        assertTrue(result.saveReturned());
        assertTrue(result.outputReadable());
        assertTrue(SyntheticSourceFixture.events().contains("save"));
        assertFalse(SyntheticSourceFixture.events().contains("parse"));
        assertFalse(SyntheticSourceFixture.events().contains("construct"));
        assertTrue(
            SyntheticSourceFixture.hostEvents().stream().allMatch(Boolean::booleanValue),
            "all synthetic native reads and calls must be marshalled to the host thread"
        );
    }

    @Test
    void reportsTheVerifiedStructuralCandidateAndWrittenExportObservations(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();

        final var verification = diagnosticVerification(fixture, temp.resolve("integrity.psd"));

        assertEquals(
            EditorRawImagePsdIntegrityAccess.VerificationStatus.MATCHED_UNVERIFIED,
            verification.status()
        );
        assertTrue(verification.rootNameMatches());
        assertTrue(verification.dimensionsMatch());
        assertTrue(verification.layerTreeMatches());
        assertTrue(verification.editorLayerIdsObserved());
        assertTrue(verification.psdLayerIdsVerified());
        assertTrue(verification.pixelLayerBoundsVerified());
        assertFalse(verification.usablePixelsObserved());
        assertFalse(verification.usablePixelsMatch());
        assertFalse(verification.usablePixelsVerified());
        assertTrue(verification.detail().contains("usable pixels"));
        assertTrue(verification.detail().contains("not performed"));
        assertTrue(verification.opacityVerified(), verification.detail());
        assertTrue(verification.visibleVerified());
        assertTrue(verification.specialBlendVerified());
        assertTrue(verification.transparencyShapesVerified());
        assertTrue(verification.clippingObserved());
        assertTrue(verification.clippingMatch());
        assertFalse(verification.clippingVerified());
        assertTrue(verification.detail().contains("null preserved without GUID fallback"));
        assertTrue(verification.detail().contains("remain unverified"));
        assertTrue(verification.detail().contains("were not compared"));

        // The reconstructed root deliberately gets a new GUID; root host identity is not a
        // fidelity dimension and must not turn an otherwise matching observation into a mismatch.
        assertNotEquals(fixture.rawA.guid.value, SyntheticSourceFixture.constructedRootGuid);
    }

    @Test
    void reportsSaveWrittenFidelityMismatches(@TempDir final Path temp) {
        final Map<SyntheticSourceFixture.ParseMode, String> cases = Map.of(
            SyntheticSourceFixture.ParseMode.CHANGE_PSD_LAYER_ID, "PSD layer ID",
            SyntheticSourceFixture.ParseMode.CHANGE_BOUNDS, "pixel bounds",
            SyntheticSourceFixture.ParseMode.CHANGE_OPACITY, "opacity255",
            SyntheticSourceFixture.ParseMode.CHANGE_VISIBILITY, "visibility",
            SyntheticSourceFixture.ParseMode.CHANGE_BLEND, "PSD blend value",
            SyntheticSourceFixture.ParseMode.CHANGE_TRANSPARENCY_SHAPES, "transparency-shape state"
        );
        for (final Map.Entry<SyntheticSourceFixture.ParseMode, String> entry : cases.entrySet()) {
            final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
            SyntheticSourceFixture.parseMode = entry.getKey();
            final var verification = diagnosticVerification(fixture,
                temp.resolve(entry.getKey().name().toLowerCase() + ".psd"));
            assertEquals(
                EditorRawImagePsdIntegrityAccess.VerificationStatus.MISMATCH,
                verification.status(),
                entry.getKey().name()
            );
            assertTrue(
                verification.detail().contains(entry.getValue()),
                entry.getKey().name()
            );
        }
    }

    @Test
    void observesClippingButDoesNotCertifyTheNonSerializedAttribute(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        SyntheticSourceFixture.parseMode = SyntheticSourceFixture.ParseMode.CHANGE_CLIPPING;
        final var verification = diagnosticVerification(fixture, temp.resolve("clipping.psd"));

        assertEquals(EditorRawImagePsdIntegrityAccess.VerificationStatus.MATCHED_UNVERIFIED, verification.status());
        assertTrue(verification.clippingObserved(), verification.detail());
        assertFalse(verification.clippingMatch());
        assertFalse(verification.clippingVerified());
        assertTrue(verification.detail().contains("does not serialize isClipping()"));
    }

    @Test
    void productionExportDoesNotReadImagePixels(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId("raw-a"),
            temp.resolve("no-pixel-read.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.SAVED_UNVERIFIED, result.status());
        assertTrue(result.outputReadable());
        assertFalse(SyntheticSourceFixture.events().contains("image-children"));
        assertFalse(SyntheticSourceFixture.events().contains("image-resource-image"));
        assertFalse(SyntheticSourceFixture.events().contains("writable-image-width"));
        assertFalse(SyntheticSourceFixture.events().contains("writable-image-height"));
    }

    @Test
    void rejectsADeepLayerTreeBeforeUnboundedRecursion() {
        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> capture(SyntheticSourceFixture.deepLayerTree())
        );

        assertTrue(failure.getMessage().contains("layer tree depth"));
        assertTrue(failure.getMessage().contains(
            Integer.toString(EditorRawImagePsdIntegrityAccess.MAX_TREE_DEPTH)
        ));
    }

    @Test
    void rejectsAWholeCaptureThatExceedsTheLayerNodeBudget() {
        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> capture(SyntheticSourceFixture.nodeBudgetImage())
        );

        assertTrue(failure.getMessage().contains("layer node budget exceeded"));
        assertTrue(failure.getMessage().contains(
            Integer.toString(EditorRawImagePsdIntegrityAccess.MAX_LAYER_NODES)
        ));
    }


    @Test
    void reportsStructuralMismatchesAndObservesRegeneratedLayerIds(@TempDir final Path temp) {
        final Map<SyntheticSourceFixture.ParseMode, String> cases = Map.of(
            SyntheticSourceFixture.ParseMode.REORDER, "ordered layer/group tree",
            SyntheticSourceFixture.ParseMode.RENAME, "ordered layer/group tree",
            SyntheticSourceFixture.ParseMode.CHANGE_LAYER_ID, "Editor layer-entry GUIDs were observed",
            SyntheticSourceFixture.ParseMode.CHANGE_DIMENSIONS, "canvas dimensions"
        );

        for (final Map.Entry<SyntheticSourceFixture.ParseMode, String> entry : cases.entrySet()) {
            final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
            SyntheticSourceFixture.parseMode = entry.getKey();

            final var verification = diagnosticVerification(fixture,
                temp.resolve(entry.getKey().name().toLowerCase() + ".psd"));

            if (entry.getKey() == SyntheticSourceFixture.ParseMode.CHANGE_LAYER_ID) {
                assertEquals(
                    EditorRawImagePsdIntegrityAccess.VerificationStatus.MATCHED_UNVERIFIED,
                    verification.status(),
                    entry.getKey().name()
                );
                assertTrue(verification.layerTreeMatches());
                assertTrue(verification.editorLayerIdsObserved());
                assertTrue(verification.detail().contains("were not compared"), entry.getKey().name());
                assertTrue(verification.detail().contains(entry.getValue()), entry.getKey().name());
            } else {
                assertEquals(
                    EditorRawImagePsdIntegrityAccess.VerificationStatus.MISMATCH,
                    verification.status(),
                    entry.getKey().name()
                );
                assertTrue(verification.detail().contains(entry.getValue()), entry.getKey().name());
            }
            if (entry.getKey() == SyntheticSourceFixture.ParseMode.RENAME) {
            }
        }
    }

    @Test
    void reportsReadableButIntegrityUnavailableForMalformedReparsedTree(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        SyntheticSourceFixture.parseMode = SyntheticSourceFixture.ParseMode.INVALID_TREE;

        final EditorRawImagePsdAccess.ExportResult result = export(
            fixture,
            new RawImageId("raw-a"),
            temp.resolve("malformed.psd")
        );

        assertEquals(EditorRawImagePsdAccess.ExportStatus.SAVED_UNVERIFIED, result.status());
        assertTrue(result.outputReadable());
        assertEquals(
            EditorRawImagePsdIntegrityAccess.VerificationStatus.UNAVAILABLE,
            result.integrityVerification().status()
        );
        assertTrue(result.integrityVerification().detail().contains("not re-parsed"));
        assertFalse(SyntheticSourceFixture.events().contains("parse"));
        assertEquals(0L, SyntheticSourceFixture.events().stream().filter("dispose"::equals).count());
        assertTrue(SyntheticSourceFixture.hostEvents().stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void rejectsStaleCurrentModelBeforeReadingTheSource(@TempDir final Path temp) {
        final SyntheticSourceFixture fixture = SyntheticSourceFixture.standard();
        final AtomicInteger guardCalls = new AtomicInteger();
        final EditorRawImagePsdAccess access = new EditorRawImagePsdAccess(
            resolver(),
            (identity, model) -> {
                guardCalls.incrementAndGet();
                throw new IllegalStateException("stale current model generation");
            }
        );

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> access.exportPsd(
                "session-a",
                fixture.source,
                fixture.model,
                new RawImageId("raw-a"),
                temp.resolve("stale.psd")
            )
        );

        assertEquals("stale current model generation", failure.getMessage());
        assertEquals(1, guardCalls.get());
        assertTrue(SyntheticSourceFixture.events().isEmpty());
    }

    /** Explicit test-only fidelity diagnostic; production export must not perform this work. */
    private static EditorRawImagePsdIntegrityAccess.Verification diagnosticVerification(
        final SyntheticSourceFixture fixture, final Path target
    ) {
        final var before = capture(fixture.rawA);
        final var result = export(fixture, new RawImageId("raw-a"), target);
        assertTrue(result.outputReadable());
        assertFalse(SyntheticSourceFixture.events().contains("parse"));
        return EditorHostThread.dispatch("test-only PSD fidelity diagnostic", () -> {
            try {
                final var reconstructed = new EditorRawImagePsdAccess(resolver(), (identity, model) -> { })
                    .parseStageOnHostThread(target, fixture.rawA.name);
                final var integrity = new EditorRawImagePsdIntegrityAccess(resolver());
                return integrity.verify(before, integrity.captureOnHostThread(reconstructed));
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static EditorRawImagePsdAccess.ExportResult export(
        final SyntheticSourceFixture fixture,
        final RawImageId sourceId,
        final Path target
    ) {
        return new EditorRawImagePsdAccess(
            resolver(),
            (identity, model) -> {
                assertEquals("session-a", identity);
                assertSame(fixture.model, model);
            }
        ).exportPsd("session-a", fixture.source, fixture.model, sourceId, target);
    }

    private static EditorRawImagePsdIntegrityAccess.Snapshot capture(
        final SyntheticSourceFixture.LayeredImage image
    ) {
        return EditorHostThread.dispatch(
            "synthetic PSD integrity capture",
            () -> new EditorRawImagePsdIntegrityAccess(resolver()).captureOnHostThread(image)
        );
    }

    private static VerifiedMemberResolver resolver() {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        for (final String alias : EditorRawImagePsdSelectorContract.REQUIRED_ALIASES) {
            selectors.put(alias, StaticSelector.classSelector(alias, "java/lang/Object"));
        }

        selectors.put(
            EditorRawImagePsdSelectorContract.MODEL_SOURCE_TEXTURE_MANAGER_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.MODEL_SOURCE_TEXTURE_MANAGER_ALIAS,
                SyntheticSourceFixture.ModelSource.class,
                "getTextureManager",
                "()" + reference(SyntheticSourceFixture.TextureManager.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.TEXTURE_MANAGER_RAW_IMAGES_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.TEXTURE_MANAGER_RAW_IMAGES_ALIAS,
                SyntheticSourceFixture.TextureManager.class,
                "getRawImages",
                "()Ljava/util/List;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_WRAPPER_IMAGE_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_WRAPPER_IMAGE_ALIAS,
                SyntheticSourceFixture.Wrapper.class,
                "getImage",
                "()" + reference(SyntheticSourceFixture.LayeredImage.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                internal(SyntheticSourceFixture.LayeredImage.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_GUID_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_GUID_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "getGuid",
                "()" + reference(SyntheticSourceFixture.Guid.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_NAME_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_NAME_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "getName",
                "()Ljava/lang/String;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_WIDTH_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_WIDTH_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "getWidth",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_HEIGHT_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_HEIGHT_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "getHeight",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_PSD_DOC_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_PSD_DOC_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "getPsdDoc",
                "()" + reference(SyntheticSourceFixture.PsdDocument.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CHILDREN_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CHILDREN_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "getChildren",
                "()Ljava/util/List;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_CLASS_ALIAS,
                internal(SyntheticSourceFixture.LayerEntry.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_GUID_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_GUID_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "getGuid",
                "()" + reference(SyntheticSourceFixture.Guid.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_NAME_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_NAME_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "getName",
                "()Ljava/lang/String;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_GROUP_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.LAYER_GROUP_CLASS_ALIAS,
                internal(SyntheticSourceFixture.LayerGroup.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_GROUP_CHILDREN_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_GROUP_CHILDREN_ALIAS,
                SyntheticSourceFixture.LayerGroup.class,
                "getChildren",
                "()Ljava/util/List;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_OPACITY_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_OPACITY_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "getOpacity255",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_VISIBLE_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_VISIBLE_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "isVisible",
                "()Z"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_CLIPPING_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_CLIPPING_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "isClipping",
                "()Z"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_BLEND_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_BLEND_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "getBlend",
                "()" + reference(SyntheticSourceFixture.Blend.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_ENTRY_TRANSPARENCY_SHAPES_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_ENTRY_TRANSPARENCY_SHAPES_ALIAS,
                SyntheticSourceFixture.LayerEntry.class,
                "isTransparencyShapesLayer",
                "()Z"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.BLEND_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.BLEND_CLASS_ALIAS,
                internal(SyntheticSourceFixture.Blend.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.BLEND_PSD_VALUE_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.BLEND_PSD_VALUE_ALIAS,
                SyntheticSourceFixture.Blend.class,
                "c",
                "()" + reference(SyntheticSourceFixture.PsdBlend.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_BLEND_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.PSD_BLEND_CLASS_ALIAS,
                internal(SyntheticSourceFixture.PsdBlend.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_BLEND_KEY_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.PSD_BLEND_KEY_ALIAS,
                SyntheticSourceFixture.PsdBlend.class,
                "a",
                "()Ljava/lang/String;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_IDENTIFIER_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.LAYER_IDENTIFIER_CLASS_ALIAS,
                internal(SyntheticSourceFixture.LayerIdentifier.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_IDENTIFIER_ID_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_IDENTIFIER_ID_ALIAS,
                SyntheticSourceFixture.LayerIdentifier.class,
                "getLayerId",
                "()Ljava/lang/String;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_GROUP_LAYER_IDENTIFIER_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_GROUP_LAYER_IDENTIFIER_ALIAS,
                SyntheticSourceFixture.LayerGroup.class,
                "getLayerIdentifier",
                "()" + reference(SyntheticSourceFixture.LayerIdentifier.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.LAYER_CLASS_ALIAS,
                internal(SyntheticSourceFixture.PixelLayer.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_LAYER_IDENTIFIER_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_LAYER_IDENTIFIER_ALIAS,
                SyntheticSourceFixture.PixelLayer.class,
                "getLayerIdentifier",
                "()" + reference(SyntheticSourceFixture.LayerIdentifier.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYER_BOUNDS_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYER_BOUNDS_ALIAS,
                SyntheticSourceFixture.PixelLayer.class,
                "getBoundsOnImageDoc",
                "()" + reference(SyntheticSourceFixture.Rect.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.RECT_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.RECT_CLASS_ALIAS,
                internal(SyntheticSourceFixture.Rect.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.RECT_X_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.RECT_X_ALIAS,
                SyntheticSourceFixture.Rect.class,
                "getX",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.RECT_Y_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.RECT_Y_ALIAS,
                SyntheticSourceFixture.Rect.class,
                "getY",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.RECT_WIDTH_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.RECT_WIDTH_ALIAS,
                SyntheticSourceFixture.Rect.class,
                "getWidth",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.RECT_HEIGHT_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.RECT_HEIGHT_ALIAS,
                SyntheticSourceFixture.Rect.class,
                "getHeight",
                "()I"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.GUID_VALUE_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.GUID_VALUE_ALIAS,
                SyntheticSourceFixture.Guid.class,
                "getValue",
                "()Ljava/lang/String;"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_CLASS_ALIAS,
                internal(SyntheticSourceFixture.PsdDocument.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
            StaticSelector.field(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_ALIAS,
                internal(SyntheticSourceFixture.PsdDocument.class),
                "a",
                reference(SyntheticSourceFixture.Companion.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
                internal(SyntheticSourceFixture.Companion.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
            StaticSelector.method(
                EditorRawImagePsdSelectorContract.PSD_DOCUMENT_PARSE_FILE_ALIAS,
                internal(SyntheticSourceFixture.Companion.class),
                "a",
                "(Ljava/io/File;ZZ)" + reference(SyntheticSourceFixture.PsdDocument.class),
                StaticSelector.ACCESS_PUBLIC
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
            StaticSelector.constructor(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_FROM_PSD_ALIAS,
                internal(SyntheticSourceFixture.LayeredImage.class),
                "(" + reference(SyntheticSourceFixture.PsdDocument.class)
                    + "Ljava/io/File;Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
            instanceMethod(
                EditorRawImagePsdSelectorContract.LAYERED_IMAGE_SAVE_PSD_ALIAS,
                SyntheticSourceFixture.LayeredImage.class,
                "save",
                "(Ljava/io/File;" + reference(SyntheticSourceFixture.Progress.class) + ")V"
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_PROGRESS_CLASS_ALIAS,
            StaticSelector.classSelector(
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_CLASS_ALIAS,
                internal(SyntheticSourceFixture.Progress.class)
            )
        );
        selectors.put(
            EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS,
            StaticSelector.staticMethod(
                EditorRawImagePsdSelectorContract.PSD_PROGRESS_DEFAULT_ALIAS,
                internal(SyntheticSourceFixture.ProgressFactory.class),
                "e",
                "()" + reference(SyntheticSourceFixture.Progress.class),
                StaticSelector.ACCESS_PUBLIC
            )
        );

        return TestVerifiedResolvers.create(
            "5.3.02",
            EditorRawImagePsdSelectorContract.ADAPTER_SLICE_ID,
            Set.of(EditorRawImagePsdSelectorContract.CAPABILITY_ID),
            new ArrayList<>(selectors.values()),
            SyntheticSourceFixture.class.getClassLoader()
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

    /** A mutable model-source graph plus native-shaped PSD values used only by synthetic tests. */
    public static final class SyntheticSourceFixture {
        static final List<String> EVENTS = new ArrayList<>();
        static final List<Boolean> HOST_EVENTS = new ArrayList<>();
        static LayeredImage lastSavedSource;
        static String constructedRootGuid;
        static ParseMode parseMode = ParseMode.MATCH;

        final ModelSource source;
        final TextureManager manager;
        final Object model = new Object();
        final LayeredImage rawA;
        final LayeredImage rawB;
        final LayeredImage external;

        private SyntheticSourceFixture(
            final ModelSource source,
            final TextureManager manager,
            final LayeredImage rawA,
            final LayeredImage rawB,
            final LayeredImage external
        ) {
            this.source = source;
            this.manager = manager;
            this.rawA = rawA;
            this.rawB = rawB;
            this.external = external;
        }

        static SyntheticSourceFixture standard() {
            reset();
            final LayeredImage rawA = psd("raw-a", "shared-source.psd", "a");
            final LayeredImage rawB = psd("raw-b", "shared-source.psd", "b");
            final LayeredImage external = psd("raw-external", "shared-source.psd", "external");
            final TextureManager manager = new TextureManager(new ArrayList<>(List.of(
                new Wrapper(rawA),
                new Wrapper(rawB)
            )));
            return new SyntheticSourceFixture(new ModelSource(manager), manager, rawA, rawB, external);
        }

        static LayeredImage ordinary(final String id, final String name) {
            return new LayeredImage(
                id,
                name,
                1024,
                512,
                null,
                tree("ordinary")
            );
        }

        static LayeredImage psd(final String id, final String name, final String treePrefix) {
            return new LayeredImage(
                id,
                name,
                1024,
                512,
                new PsdDocument(),
                tree(treePrefix)
            );
        }

        static List<LayerEntry> tree(final String prefix) {
            return List.of(
                new LayerGroup(
                    prefix + "-group",
                    "Artwork",
                    "psd-" + prefix + "-group",
                    192,
                    true,
                    new Blend("normal"),
                    false,
                    false,
                    List.of(
                        new PixelLayer(
                            prefix + "-line",
                            "Line",
                            "psd-" + prefix + "-line",
                            192,
                            true,
                            new Blend("normal"),
                            false,
                            false,
                            new Rect(10, 20, 2, 2),
                            new ImageResource(new WritableImage(2, 2, new int[] {
                                0xFFFF0000, 0xFF00FF00,
                                0xFF0000FF, 0xFFFFFFFF
                            }))
                        ),
                        new PixelLayer(
                            prefix + "-color",
                            "Color",
                            null,
                            160,
                            true,
                            new Blend("multiply"),
                            false,
                            true,
                            new Rect(30, 40, 2, 2),
                            new ImageResource(new WritableImage(2, 2, new int[] {
                                0x80000000, 0x80FFFFFF,
                                0xFF123456, 0xFF654321
                            }))
                        )
                    )
                )
            );
        }

        static LayeredImage deepLayerTree() {
            LayerEntry nested = new LayerGroup(
                "deep-leaf-guid",
                "Deep",
                "deep-leaf-id",
                255,
                true,
                new Blend("normal"),
                false,
                false,
                List.of()
            );
            for (int depth = 1; depth <= EditorRawImagePsdIntegrityAccess.MAX_TREE_DEPTH; depth++) {
                nested = new LayerGroup(
                    "deep-" + depth + "-guid",
                    "Deep",
                    "deep-" + depth + "-id",
                    255,
                    true,
                    new Blend("normal"),
                    false,
                    false,
                    List.of(nested)
                );
            }
            return new LayeredImage(
                "deep-root-guid",
                "DeepRoot",
                1,
                1,
                null,
                List.of(nested)
            );
        }

        static LayeredImage nodeBudgetImage() {
            final ArrayList<LayerEntry> children = new ArrayList<>(
                EditorRawImagePsdIntegrityAccess.MAX_LAYER_NODES + 1
            );
            for (int index = 0; index <= EditorRawImagePsdIntegrityAccess.MAX_LAYER_NODES; index++) {
                children.add(new LayerGroup(
                    "node-" + index + "-guid",
                    "Node",
                    "node-" + index + "-id",
                    255,
                    true,
                    new Blend("normal"),
                    false,
                    false,
                    List.of()
                ));
            }
            return new LayeredImage(
                "node-root-guid",
                "NodeRoot",
                1,
                1,
                null,
                children
            );
        }


        static void reset() {
            synchronized (EVENTS) {
                EVENTS.clear();
                HOST_EVENTS.clear();
            }
            lastSavedSource = null;
            constructedRootGuid = null;
            parseMode = ParseMode.MATCH;
        }

        static List<String> events() {
            synchronized (EVENTS) {
                return List.copyOf(EVENTS);
            }
        }

        static List<Boolean> hostEvents() {
            synchronized (EVENTS) {
                return List.copyOf(HOST_EVENTS);
            }
        }

        static void record(final String event) {
            synchronized (EVENTS) {
                EVENTS.add(event);
                HOST_EVENTS.add(EditorHostThread.isCurrent());
            }
        }

        enum ParseMode {
            MATCH,
            REORDER,
            RENAME,
            CHANGE_LAYER_ID,
            CHANGE_PSD_LAYER_ID,
            CHANGE_BOUNDS,
            CHANGE_OPACITY,
            CHANGE_VISIBILITY,
            CHANGE_BLEND,
            CHANGE_TRANSPARENCY_SHAPES,
            CHANGE_CLIPPING,
            CHANGE_DIMENSIONS,
            INVALID_TREE
        }

        public static final class ModelSource {
            private final TextureManager textureManager;

            public ModelSource(final TextureManager textureManager) {
                this.textureManager = textureManager;
            }

            public TextureManager getTextureManager() {
                record("texture-manager");
                return textureManager;
            }
        }

        public static final class TextureManager {
            private final List<Wrapper> rawImages;

            public TextureManager(final List<Wrapper> rawImages) {
                this.rawImages = rawImages;
            }

            public List<Wrapper> getRawImages() {
                record("raw-images");
                return rawImages;
            }
        }

        public static final class Wrapper {
            private final LayeredImage image;

            public Wrapper(final LayeredImage image) {
                this.image = image;
            }

            public LayeredImage getImage() {
                record("wrapper-image");
                return image;
            }
        }

        public static final class Guid {
            private final String value;

            public Guid(final String value) {
                this.value = value;
            }

            public String getValue() {
                record("guid-value");
                return value;
            }
        }

        public static final class LayerIdentifier {
            private final String layerId;

            public LayerIdentifier(final String layerId) {
                this.layerId = layerId;
            }

            public String getLayerId() {
                record("layer-id");
                return layerId;
            }
        }

        public static final class Rect {
            private final int x;
            private final int y;
            private final int width;
            private final int height;

            public Rect(final int x, final int y, final int width, final int height) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
            }

            public int getX() {
                record("bounds-x");
                return x;
            }

            public int getY() {
                record("bounds-y");
                return y;
            }

            public int getWidth() {
                record("bounds-width");
                return width;
            }

            public int getHeight() {
                record("bounds-height");
                return height;
            }
        }

        public static final class PsdBlend {
            private final String key;

            public PsdBlend(final String key) {
                this.key = key;
            }

            public String a() {
                record("psd-blend-key");
                return key;
            }
        }

        public static final class Blend {
            private final PsdBlend psdValue;

            public Blend(final String key) {
                this.psdValue = new PsdBlend(key);
            }

            public PsdBlend c() {
                record("blend-psd-value");
                return psdValue;
            }
        }

        public static final class WritableImage {
            private final int width;
            private final int height;
            private final int[] pixels;

            public WritableImage(final int width, final int height, final int[] pixels) {
                this.width = width;
                this.height = height;
                this.pixels = pixels;
            }

            public int getWidth() {
                record("writable-image-width");
                return width;
            }

            public int getHeight() {
                record("writable-image-height");
                return height;
            }

            public int getArgb(final int x, final int y) {
                record("image-argb");
                return pixels[y * width + x];
            }
        }

        public static final class ImageResource {
            private final WritableImage image;

            public ImageResource(final WritableImage image) {
                this.image = image;
            }

            public WritableImage getImage() {
                record("image-resource-image");
                return image;
            }
        }

        public static class LayerEntry {
            private final Guid guid;
            private final String name;
            private final LayerIdentifier layerIdentifier;
            private final int opacity255;
            private final boolean visible;
            private final Blend blend;
            private final boolean clipping;
            private final boolean transparencyShapes;

            public LayerEntry(final String guid, final String name) {
                this(guid, name, "psd-" + guid, 192, true, new Blend("normal"), false, false);
            }

            protected LayerEntry(
                final String guid,
                final String name,
                final String layerId,
                final int opacity255,
                final boolean visible,
                final Blend blend,
                final boolean clipping,
                final boolean transparencyShapes
            ) {
                this.guid = new Guid(guid);
                this.name = name;
                this.layerIdentifier = new LayerIdentifier(layerId);
                this.opacity255 = opacity255;
                this.visible = visible;
                this.blend = blend;
                this.clipping = clipping;
                this.transparencyShapes = transparencyShapes;
            }

            public Guid getGuid() {
                record("layer-guid");
                return guid;
            }

            public String getName() {
                record("layer-name");
                return name;
            }

            public int getOpacity255() {
                record("layer-opacity");
                return opacity255;
            }

            public boolean isVisible() {
                record("layer-visible");
                return visible;
            }

            public Blend getBlend() {
                record("layer-blend");
                return blend;
            }

            public boolean isClipping() {
                record("layer-clipping");
                return clipping;
            }

            public boolean isTransparencyShapesLayer() {
                record("layer-transparency");
                return transparencyShapes;
            }
        }

        public static final class PixelLayer extends LayerEntry {
            private final LayerIdentifier layerIdentifier;
            private final Rect bounds;
            private final ImageResource imageResource;

            public PixelLayer(
                final String guid,
                final String name,
                final String layerId,
                final int opacity255,
                final boolean visible,
                final Blend blend,
                final boolean clipping,
                final boolean transparencyShapes,
                final Rect bounds,
                final ImageResource imageResource
            ) {
                super(guid, name, layerId, opacity255, visible, blend, clipping, transparencyShapes);
                this.layerIdentifier = new LayerIdentifier(layerId);
                this.bounds = bounds;
                this.imageResource = imageResource;
            }

            public LayerIdentifier getLayerIdentifier() {
                record("pixel-layer-identifier");
                return layerIdentifier;
            }

            public Rect getBoundsOnImageDoc() {
                record("layer-bounds");
                return bounds;
            }

            public ImageResource getImageResource() {
                record("layer-image-resource");
                return imageResource;
            }
        }

        public static final class LayerGroup extends LayerEntry {
            private final LayerIdentifier layerIdentifier;
            private final List<LayerEntry> children;

            public LayerGroup(
                final String guid,
                final String name,
                final String layerId,
                final int opacity255,
                final boolean visible,
                final Blend blend,
                final boolean clipping,
                final boolean transparencyShapes,
                final List<LayerEntry> children
            ) {
                super(guid, name, layerId, opacity255, visible, blend, clipping, transparencyShapes);
                this.layerIdentifier = new LayerIdentifier(layerId);
                this.children = children;
            }

            public LayerIdentifier getLayerIdentifier() {
                record("group-layer-identifier");
                return layerIdentifier;
            }

            public List<LayerEntry> getChildren() {
                record("group-children");
                return children;
            }
        }

        public static final class PsdDocument {
            public static final Companion a = new Companion();

            public Object[] h() { return new Object[0]; }

            private final LayeredImage source;
            private final ParseMode mode;

            public PsdDocument() {
                this(null, ParseMode.MATCH);
            }

            private PsdDocument(final LayeredImage source, final ParseMode mode) {
                this.source = source;
                this.mode = mode;
            }
        }

        public static final class Companion {
            public PsdDocument a(
                final File file,
                final boolean firstFlag,
                final boolean secondFlag
            ) {
                record("parse");
                if (firstFlag || secondFlag) {
                    throw new IllegalStateException("fixture parser flags were not false");
                }
                if (!file.isFile() || file.length() == 0) return null;
                return new PsdDocument(lastSavedSource, parseMode);
            }
        }

        public static final class Progress {
            public Progress() {
            }
        }

        public static final class ProgressFactory {
            private static final Progress DEFAULT = new Progress();

            private ProgressFactory() {
            }

            public static Progress e() {
                record("progress");
                return DEFAULT;
            }
        }

        public static final class LayeredImage {
            private final Guid guid;
            private final String name;
            private final int width;
            private final int height;
            private final PsdDocument psdDoc;
            private final List<LayerEntry> children;

            public LayeredImage(
                final String guid,
                final String name,
                final int width,
                final int height,
                final PsdDocument psdDoc,
                final List<LayerEntry> children
            ) {
                this.guid = new Guid(guid);
                this.name = name;
                this.width = width;
                this.height = height;
                this.psdDoc = psdDoc;
                this.children = children;
            }

            public LayeredImage(
                final PsdDocument parsed,
                final File file,
                final String name
            ) {
                record("construct");
                if (parsed == null || parsed.source == null || !file.isFile()) {
                    throw new IllegalStateException("fixture constructor received invalid parsed PSD");
                }
                final LayeredImage source = parsed.source;
                this.guid = new Guid("reparsed-root-" + source.guid.value);
                this.name = name;
                this.width = parsed.mode == ParseMode.CHANGE_DIMENSIONS
                    ? source.width + 1
                    : source.width;
                this.height = source.height;
                this.psdDoc = parsed;
                this.children = switch (parsed.mode) {
                    case INVALID_TREE -> Collections.singletonList(null);
                    default -> copyEntries(source.children, parsed.mode);
                };
                constructedRootGuid = this.guid.value;
            }

            public void dispose() {
                if (!guid.value.startsWith("reparsed-root-")) {
                    throw new AssertionError("source image must not be disposed");
                }
                record("dispose");
            }

            public Guid getGuid() {
                record("image-guid");
                return guid;
            }

            public String getName() {
                record("image-name");
                return name;
            }

            public int getWidth() {
                record("image-width");
                return width;
            }

            public int getHeight() {
                record("image-height");
                return height;
            }

            public PsdDocument getPsdDoc() {
                record("image-psd-doc");
                return psdDoc;
            }

            public List<LayerEntry> getChildren() {
                record("image-children");
                return children;
            }

            public void save(final File file, final Progress progress) {
                record("save");
                if (progress == null) throw new IllegalStateException("fixture progress was null");
                lastSavedSource = this;
                try {
                    Files.writeString(file.toPath(), "synthetic-psd:" + name);
                } catch (IOException exception) {
                    throw new IllegalStateException("fixture output failure", exception);
                }
            }

            private static List<LayerEntry> copyEntries(
                final List<LayerEntry> entries,
                final ParseMode mode
            ) {
                final ArrayList<LayerEntry> copied = new ArrayList<>(entries.size());
                for (final LayerEntry entry : entries) {
                    final String originalGuid = entry.guid.value;
                    final String copiedGuid = mode == ParseMode.CHANGE_LAYER_ID
                        ? originalGuid + "-changed"
                        : originalGuid;
                    final String copiedName = mode == ParseMode.RENAME
                        ? entry.name + " (reparsed)"
                        : entry.name;
                    final String copiedPsdId = switch (mode) {
                        case CHANGE_PSD_LAYER_ID -> entry.layerIdentifier.layerId == null
                            ? "generated-psd-id"
                            : entry.layerIdentifier.layerId + "-changed";
                        default -> entry.layerIdentifier.layerId;
                    };
                    final int copiedOpacity = mode == ParseMode.CHANGE_OPACITY
                        ? entry.opacity255 + 1
                        : entry.opacity255;
                    final boolean copiedVisible = mode == ParseMode.CHANGE_VISIBILITY
                        ? !entry.visible
                        : entry.visible;
                    final String copiedBlendKey = mode == ParseMode.CHANGE_BLEND
                        ? "screen"
                        : entry.blend.psdValue.key;
                    final boolean copiedClipping = mode == ParseMode.CHANGE_CLIPPING
                        ? !entry.clipping
                        : entry.clipping;
                    final boolean copiedTransparencyShapes = mode == ParseMode.CHANGE_TRANSPARENCY_SHAPES
                        ? !entry.transparencyShapes
                        : entry.transparencyShapes;
                    if (entry instanceof LayerGroup group) {
                        copied.add(new LayerGroup(
                            copiedGuid,
                            copiedName,
                            copiedPsdId,
                            copiedOpacity,
                            copiedVisible,
                            new Blend(copiedBlendKey),
                            copiedClipping,
                            copiedTransparencyShapes,
                            copyEntries(group.children, mode)
                        ));
                    } else if (entry instanceof PixelLayer pixel) {
                        final Rect copiedBounds = mode == ParseMode.CHANGE_BOUNDS
                            ? new Rect(
                                pixel.bounds.x + 1,
                                pixel.bounds.y,
                                pixel.bounds.width,
                                pixel.bounds.height
                            )
                            : pixel.bounds;
                        copied.add(new PixelLayer(
                            copiedGuid,
                            copiedName,
                            copiedPsdId,
                            copiedOpacity,
                            copiedVisible,
                            new Blend(copiedBlendKey),
                            copiedClipping,
                            copiedTransparencyShapes,
                            copiedBounds,
                            new ImageResource(copyImage(pixel.imageResource.image, mode))
                        ));
                    } else {
                        throw new IllegalStateException("unexpected synthetic layer entry type");
                    }
                }
                if (mode == ParseMode.REORDER && copied.size() > 1) {
                    Collections.reverse(copied);
                }
                return List.copyOf(copied);
            }

            private static WritableImage copyImage(
                final WritableImage source,
                final ParseMode mode
            ) {
                final int[] pixels = source.pixels.clone();
                return new WritableImage(source.width, source.height, pixels);
            }
        }
    }
}
