package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.RawLayerId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawImageDetails;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.RawLayerDetails;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Synthetic, host-free coverage for the verified 5.3.02 texture relation projection. */
class EditorTextureRelationsAccessTest {

    @Test
    void replacementObservationAllowsNativeToKeepUnmatchedImagesOnTheOriginalRaw() {
        final TextureRelationsSnapshot snapshot = replacementSnapshot();
        final ModelImageRelation untouched = snapshot.modelImage(new ModelImageId("model-b")).orElseThrow();
        final ModelImageRelation retained = new ModelImageRelation(untouched.id(), untouched.modelImage(),
            List.of(new RawImageId("raw-b")), Optional.of(new RawImageId("raw-b")), Map.of(),
            untouched.usingArtMeshIds());
        final TextureRelationsSnapshot after = withReplacementImages(snapshot,
            List.of(snapshot.modelImage(new ModelImageId("model-a")).orElseThrow(), retained));
        assertEquals(Optional.of(new RawImageId("raw-a")), EditorTextureAccess.observedRawImage(
            after, List.of(new ModelImageId("model-a"), new ModelImageId("model-b")),
            new RawImageId("raw-a")), "official matching may retain an unmatched original image");
    }

    @Test
    void replacementObservationRequiresAnOriginalImageWithCompleteIncomingBinding() {
        final TextureRelationsSnapshot snapshot = replacementSnapshot();
        final ModelImageRelation image = snapshot.modelImage(new ModelImageId("model-a")).orElseThrow();
        final List<ModelImageId> affected = List.of(image.id());
        final RawImageId incoming = new RawImageId("raw-a");
        assertEquals(Optional.of(incoming), EditorTextureAccess.observedRawImage(snapshot, affected, incoming));
        for (final ModelImageRelation invalid : List.of(
            new ModelImageRelation(image.id(), image.modelImage(), image.linkedRawImageIds(),
                image.currentRawImageId(), Map.of(), image.usingArtMeshIds()),
            new ModelImageRelation(image.id(), image.modelImage(), List.of(),
                image.currentRawImageId(), image.inputsByRawImage(), image.usingArtMeshIds()),
            new ModelImageRelation(image.id(), image.modelImage(), image.linkedRawImageIds(),
                Optional.empty(), image.inputsByRawImage(), image.usingArtMeshIds()),
            new ModelImageRelation(image.id(), image.modelImage(), image.linkedRawImageIds(),
                image.currentRawImageId(), Map.of(incoming, List.of()), image.usingArtMeshIds()))) {
            assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(
                withReplacementImages(snapshot, List.of(invalid)), affected, incoming));
        }
        assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(snapshot,
            List.of(new ModelImageId("model-b")), incoming), "an unrelated incoming binding proves nothing");
        assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(snapshot, affected,
            new RawImageId("raw-d")), "an added raw alone is not application evidence");
        assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(snapshot, List.of(), incoming));
        assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(
            TextureRelationsSnapshot.unavailable(), affected, incoming));
    }

    @Test
    void failedReplacementObservationDistinguishesImportFromLayerAssociation() {
        final TextureRelationsSnapshot snapshot = replacementSnapshot();
        final RawImageId target = new RawImageId("raw-a");
        final RawImageId incoming = new RawImageId("raw-b");
        assertEquals("INCOMING_NOT_CURRENT", EditorTextureAccess.replacementObservationFailure(
            snapshot, snapshot, target, incoming).category());
        assertEquals("INCOMING_RAW_ABSENT", EditorTextureAccess.replacementObservationFailure(
            snapshot, snapshot, target, new RawImageId("absent")).category());
        final var duplicateRaw = new ArrayList<>(snapshot.rawImages());
        duplicateRaw.add(snapshot.rawImages().stream().filter(raw -> incoming.equals(raw.id()))
            .findFirst().orElseThrow());
        final var duplicateAfter = new TextureRelationsSnapshot(snapshot.availability(), snapshot.binding(),
            snapshot.generation(), snapshot.revision(), duplicateRaw, snapshot.modelImages(),
            snapshot.groups(), snapshot.artMeshInputs());
        assertEquals("INCOMING_RAW_NOT_UNIQUE", EditorTextureAccess.replacementObservationFailure(
            snapshot, duplicateAfter, target, incoming).category());
        assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(
            duplicateAfter, List.of(new ModelImageId("model-a")), incoming));
        assertEquals("TARGET_MODEL_IMAGES_ABSENT", EditorTextureAccess.replacementObservationFailure(
            snapshot, snapshot, new RawImageId("absent"), incoming).category());
        final ModelImageRelation image = snapshot.modelImage(new ModelImageId("model-a")).orElseThrow();
        final TextureRelationsSnapshot emptyBefore = withReplacementImages(snapshot, List.of(
            new ModelImageRelation(image.id(), image.modelImage(), image.linkedRawImageIds(),
                image.currentRawImageId(), Map.of(), image.usingArtMeshIds())));
        assertEquals("TARGET_LAYER_INPUTS_EMPTY_BEFORE", EditorTextureAccess.replacementObservationFailure(
            emptyBefore, snapshot, target, incoming).category());
        final TextureRelationsSnapshot danglingCurrent = withReplacementImages(snapshot, List.of(
            new ModelImageRelation(image.id(), image.modelImage(), List.of(),
                Optional.of(incoming), Map.of(), image.usingArtMeshIds())));
        assertEquals("INCOMING_LINK_MISSING", EditorTextureAccess.replacementObservationFailure(
            snapshot, danglingCurrent, target, incoming).category());
        final TextureRelationsSnapshot emptyAfter = withReplacementImages(snapshot, List.of(
            new ModelImageRelation(image.id(), image.modelImage(), List.of(incoming),
                Optional.of(incoming), Map.of(), image.usingArtMeshIds())));
        assertEquals("INCOMING_LAYER_INPUTS_EMPTY", EditorTextureAccess.replacementObservationFailure(
            snapshot, emptyAfter, target, incoming).category());
        assertEquals(Optional.empty(), EditorTextureAccess.observedRawImage(
            emptyAfter, List.of(image.id()), incoming));
    }

    private static TextureRelationsSnapshot replacementSnapshot() {
        final Fixture fixture = new Fixture();
        return new EditorTextureRelationsAccess(resolver("5.3.02", true),
            (identity, model) -> { }, () -> 1L).relations("session", fixture.source, fixture.model);
    }

    private static TextureRelationsSnapshot withReplacementImages(final TextureRelationsSnapshot original,
        final List<ModelImageRelation> images) {
        return new TextureRelationsSnapshot(original.availability(), original.binding(), original.generation(),
            original.revision(), original.rawImages(), images, original.groups(), original.artMeshInputs());
    }

    @Test
    void scopesSourcesToSelectedArtMeshesAndTheirCurrentRawImages() {
        final Fixture fixture = new Fixture();
        final AtomicInteger guards = new AtomicInteger();
        final var access = new EditorTextureRelationsAccess(resolver("5.3.02", true),
            (identity, model) -> guards.incrementAndGet(), () -> 42L);
        final var snapshot = access.sources("session-a", fixture.source, fixture.model,
            new dev.turboism.sdk.cubism.model.TextureSourceQuery(
                java.util.Set.of(new ArtMeshId("mesh-a")), java.util.Set.of()));
        assertTrue(snapshot.isAvailable());
        assertEquals(List.of(new ArtMeshId("mesh-a")),
            snapshot.artMeshInputs().stream().map(ArtMeshTextureInputs::id).toList());
        assertEquals(List.of(new ModelImageId("model-a")),
            snapshot.modelImages().stream().map(image -> image.id()).toList());
        assertEquals(List.of(new RawImageId("raw-a")),
            snapshot.rawImages().stream().map(image -> image.id()).toList());
        for (final var raw : List.of(fixture.rawA, fixture.rawB, fixture.rawC, fixture.rawD)) {
            assertEquals(0, raw.childrenReads.get(), "source query must not walk layer trees");
        }
        final var images = fixture.source.textureManager.allModelImages;
        assertEquals(1, images.get(0).environmentReads.get());
        assertEquals(0, images.get(1).environmentReads.get(), "unselected image must not be resolved");
        for (final var image : images) {
            assertEquals(0, image.inputFilterEnv.layerReads.get(), "no selector maps in source query");
        }
        assertEquals(2, guards.get());
    }

    @Test
    void emptySourceQueryReadsBindingOnlyWithoutTextureManager() {
        final Fixture fixture = new Fixture();
        final AtomicInteger guards = new AtomicInteger();
        final var access = new EditorTextureRelationsAccess(resolver("5.3.02", true),
            (identity, model) -> guards.incrementAndGet(), () -> 42L);
        final var snapshot = access.sources("session-a", fixture.source, fixture.model,
            new dev.turboism.sdk.cubism.model.TextureSourceQuery(
                java.util.Set.of(), java.util.Set.of()));
        assertTrue(snapshot.isAvailable());
        assertEquals("session-a", snapshot.binding());
        assertEquals(42L, snapshot.generation());
        assertTrue(snapshot.rawImages().isEmpty());
        assertTrue(snapshot.modelImages().isEmpty());
        assertTrue(snapshot.artMeshInputs().isEmpty());
        assertEquals(0, fixture.source.textureManagerCalls.get());
        assertEquals(2, guards.get());
    }

    @Test
    void projectsTheVerifiedManyToManyRelationGraphWithoutLeakingHostObjects() {
        final Fixture fixture = new Fixture();
        final AtomicInteger guardCalls = new AtomicInteger();
        final EditorTextureRelationsAccess access = new EditorTextureRelationsAccess(
            resolver("5.3.02", true),
            (identity, model) -> {
                assertEquals("session-a", identity);
                assertTrue(model == fixture.model);
                guardCalls.incrementAndGet();
            },
            () -> 42L
        );

        final TextureRelationsSnapshot snapshot = access.relations(
            "session-a", fixture.source, fixture.model
        );

        assertTrue(snapshot.isAvailable());
        assertEquals(TextureRelationsSnapshot.Availability.AVAILABLE, snapshot.availability());
        assertEquals("session-a", snapshot.binding());
        assertEquals(42L, snapshot.generation());
        assertEquals(1L, snapshot.revision());
        assertEquals(
            List.of(
                new RawImageId("raw-a"),
                new RawImageId("raw-b"),
                new RawImageId("raw-c"),
                new RawImageId("raw-d")
            ),
            snapshot.rawImages().stream().map(RawImageDetails::id).toList()
        );
        assertEquals(2, guardCalls.get());

        final RawImageDetails rawA = snapshot.rawImage(new RawImageId("raw-a")).orElseThrow();
        assertEquals(RawImageDetails.SourceKind.PSD, rawA.sourceKind());
        assertTrue(rawA.isReplaced());
        assertEquals(Optional.of("import-a"), rawA.importedAt());
        assertEquals(Optional.of("modified-a"), rawA.sourceModifiedAt());
        assertEquals(Optional.empty(), rawA.projectTreeVisible());
        assertEquals(new RawLayerId("layer-group"), rawA.layers().get(0).id());
        assertEquals(RawLayerDetails.EntryKind.GROUP, rawA.layers().get(0).entryKind());
        assertEquals(
            new RawLayerId("layer-a"),
            rawA.layers().get(0).children().get(0).id()
        );
        assertEquals(
            new RawImageId("raw-a"),
            rawA.layers().get(0).children().get(0).ownerRawImageId()
        );
        assertNotEquals(
            new RawLayerId("raw-a"),
            rawA.layers().get(0).children().get(0).id(),
            "layer identity must not use the owning raw-image identity"
        );

        final RawImageDetails rawB = snapshot.rawImage(new RawImageId("raw-b")).orElseThrow();
        assertEquals(RawImageDetails.SourceKind.UNKNOWN, rawB.sourceKind());
        assertFalse(rawB.isReplaced());
        assertEquals(Optional.empty(), rawB.importedAt());
        assertEquals(new RawLayerId("raw-b-layer"), rawB.layers().get(0).id());
        assertEquals(new RawImageId("raw-b"), rawB.layers().get(0).ownerRawImageId());
        final RawImageDetails rawC = snapshot.rawImage(new RawImageId("raw-c")).orElseThrow();
        assertEquals(RawImageDetails.SourceKind.UNKNOWN, rawC.sourceKind());
        final RawImageDetails rawD = snapshot.rawImage(new RawImageId("raw-d")).orElseThrow();
        assertEquals(RawImageDetails.SourceKind.UNKNOWN, rawD.sourceKind());
        assertEquals(0, fixture.rawA.psdFileReads());
        assertEquals(0, fixture.rawB.psdFileReads());
        assertEquals(0, fixture.rawC.psdFileReads());
        assertEquals(0, fixture.rawD.psdFileReads());

        final ModelImageRelation modelA = snapshot.modelImage(new ModelImageId("model-a"))
            .orElseThrow();
        final ModelImageRelation modelB = snapshot.modelImage(new ModelImageId("model-b"))
            .orElseThrow();
        assertNotEquals(modelA.id(), modelB.id(), "same names must not merge model images");
        assertEquals(List.of(new RawImageId("raw-a")), modelA.linkedRawImageIds());
        assertEquals(Optional.of(new RawImageId("raw-a")), modelA.currentRawImageId());
        assertEquals(
            List.of(new ArtMeshId("mesh-a"), new ArtMeshId("mesh-b")),
            modelA.usingArtMeshIds()
        );
        assertEquals(2, modelA.inputsByRawImage().get(new RawImageId("raw-a")).size());
        final var layerBindingA = modelA.inputsByRawImage()
            .get(new RawImageId("raw-a"))
            .get(0);
        assertEquals(new RawLayerId("layer-a"), layerBindingA.rawLayerId());
        assertEquals(0, layerBindingA.inputOrder());
        assertEquals(RawLayerBinding.DetailAvailability.AVAILABLE, layerBindingA.transformAvailability());
        assertEquals(RawLayerBinding.DetailAvailability.AVAILABLE, layerBindingA.clippingAvailability());
        final var layerBindingB = modelA.inputsByRawImage()
            .get(new RawImageId("raw-a"))
            .get(1);
        assertEquals(new RawLayerId("layer-b"), layerBindingB.rawLayerId());
        assertEquals(RawLayerBinding.DetailAvailability.UNAVAILABLE, layerBindingB.transformAvailability());
        assertEquals(RawLayerBinding.DetailAvailability.AVAILABLE, layerBindingB.clippingAvailability());
        assertTrue(modelB.currentRawImageId().isEmpty());
        assertTrue(modelB.inputsByRawImage().isEmpty());

        assertEquals(1, snapshot.groups().size());
        assertEquals(
            List.of(new ModelImageId("model-a"), new ModelImageId("model-b")),
            snapshot.groups().get(0).modelImageIds()
        );
        assertEquals(List.of(new RawImageId("raw-a")), snapshot.groups().get(0).linkedRawImageIds());
        assertEquals(Optional.of(true), snapshot.groups().get(0).projectTreeVisible());
        assertEquals("Shared", snapshot.groups().get(0).groupName());
        assertEquals("group memo", snapshot.groups().get(0).memo());

        final ArtMeshTextureInputs meshA = snapshot.artMeshes().get(0);
        assertEquals(new ArtMeshId("mesh-a"), meshA.id());
        assertEquals(4, meshA.inputs().size());
        assertEquals(OptionalInt.of(1), meshA.currentInputIndex());
        assertEquals(TextureInputBinding.Kind.ATLAS, meshA.inputs().get(0).kind());
        assertEquals(
            Optional.of(new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas-a")),
            meshA.inputs().get(0).textureAtlasId()
        );
        assertEquals(TextureInputBinding.ResolutionState.RESOLVED, meshA.inputs().get(0).resolutionState());
        assertEquals(TextureInputBinding.Kind.MODEL_IMAGE, meshA.inputs().get(1).kind());
        assertEquals(Optional.of(new ModelImageId("model-a")), meshA.inputs().get(1).modelImageId());
        assertEquals(TextureInputBinding.ResolutionState.RESOLVED, meshA.inputs().get(1).resolutionState());
        assertEquals(TextureInputBinding.Kind.UNKNOWN, meshA.inputs().get(2).kind());
        assertEquals(TextureInputBinding.ResolutionState.UNKNOWN, meshA.inputs().get(2).resolutionState());
        assertEquals(TextureInputBinding.Kind.MODEL_IMAGE, meshA.inputs().get(3).kind());
        assertEquals(Optional.of(new ModelImageId("missing")), meshA.inputs().get(3).modelImageId());
        assertEquals(TextureInputBinding.ResolutionState.UNAVAILABLE, meshA.inputs().get(3).resolutionState());

        final ArtMeshTextureInputs meshB = snapshot.artMeshes().get(1);
        assertEquals(new ArtMeshId("mesh-b"), meshB.id());
        assertEquals(OptionalInt.empty(), meshB.currentInputIndex());
        assertEquals(List.of(new ModelImageId("model-a")), meshB.inputs().stream()
            .map(input -> input.modelImageId().orElseThrow()).toList());
        final ArtMeshTextureInputs unboundMesh = snapshot.artMeshes().get(2);
        assertEquals(new ArtMeshId("mesh-unbound"), unboundMesh.id());
        assertTrue(unboundMesh.inputs().isEmpty(), "null extension is an unbound mesh, not unavailable data");
        assertEquals(OptionalInt.empty(), unboundMesh.currentInputIndex());

        final TextureRelationsSnapshot second = access.relations(
            "session-a", fixture.source, fixture.model
        );
        assertEquals(2L, second.revision());
    }

    @Test
    void dispatchesAWorkerProjectionAsOneHostThreadRead() throws Exception {
        final Fixture fixture = new Fixture();
        final AtomicReference<TextureRelationsSnapshot> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Boolean> callerWasHostThread = new AtomicReference<>();
        final EditorTextureRelationsAccess access = new EditorTextureRelationsAccess(
            resolver("5.3.02", true),
            (identity, model) -> { },
            () -> 43L
        );

        final Thread worker = new Thread(() -> {
            callerWasHostThread.set(EditorHostThread.isCurrent());
            try {
                result.set(access.relations("session-a", fixture.source, fixture.model));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "texture-relations-worker");
        worker.start();
        worker.join();

        assertEquals(Boolean.FALSE, callerWasHostThread.get());
        assertNull(failure.get());
        assertTrue(fixture.source.textureManagerReadOnHostThread.get());
        assertTrue(result.get().isAvailable());
        assertEquals(43L, result.get().generation());
    }

    @Test
    void rejectsReadInProgressWhenSameIdDocumentIsReplaced() throws Exception {
        final Fixture fixture = new Fixture();
        final AtomicReference<Model> currentModel = new AtomicReference<>(fixture.model);
        final Model replacement = new Model("model-a", List.of());
        fixture.source.afterTextureManagerRead.set(() -> currentModel.set(replacement));
        final AtomicInteger guardCalls = new AtomicInteger();
        final EditorTextureRelationsAccess access = new EditorTextureRelationsAccess(
            resolver("5.3.02", true),
            (identity, expectedModel) -> {
                guardCalls.incrementAndGet();
                if (currentModel.get() != expectedModel) {
                    throw new IllegalStateException("stale same-ID document");
                }
            },
            () -> 44L
        );
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                access.relations("session-a", fixture.source, fixture.model);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "texture-relations-replacement-worker");
        worker.start();
        worker.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals(2, guardCalls.get(), "the replacement is detected at the closing read boundary");
        assertEquals("model-a", replacement.id(), "the binding/model ID stayed the same");

        currentModel.set(fixture.model);
        final TextureRelationsSnapshot recovered = access.relations(
            "session-a", fixture.source, fixture.model
        );
        assertEquals(1L, recovered.revision(), "a stale read is not an observation revision");
    }

    @Test
    void failsClosedBeforeHostReadsForStaleSessionsAndUnavailableEvidence() {
        final Fixture fixture = new Fixture();
        final AtomicInteger guardCalls = new AtomicInteger();
        final EditorTextureRelationsAccess stale = new EditorTextureRelationsAccess(
            resolver("5.3.02", true),
            (identity, model) -> {
                guardCalls.incrementAndGet();
                throw new IllegalStateException("stale generation");
            },
            () -> 7L
        );

        assertThrows(
            IllegalStateException.class,
            () -> stale.relations("session-a", fixture.source, fixture.model)
        );
        assertEquals(1, guardCalls.get());
        assertEquals(0, fixture.source.textureManagerCalls.get(), "stale sessions must not read host state");

        final AtomicInteger unavailableGuardCalls = new AtomicInteger();
        final EditorTextureRelationsAccess missingCapability = new EditorTextureRelationsAccess(
            resolver("5.3.02", false),
            (identity, model) -> unavailableGuardCalls.incrementAndGet(),
            () -> 8L
        );
        assertEquals(
            TextureRelationsSnapshot.unavailable(),
            missingCapability.relations("session-a", fixture.source, fixture.model)
        );
        assertEquals(0, unavailableGuardCalls.get());
        assertEquals(0, fixture.source.textureManagerCalls.get());

        final EditorTextureRelationsAccess unsupportedVersion = new EditorTextureRelationsAccess(
            resolver("5.2.03", true),
            (identity, model) -> unavailableGuardCalls.incrementAndGet(),
            () -> 9L
        );
        assertEquals(
            TextureRelationsSnapshot.unavailable(),
            unsupportedVersion.relations("session-a", fixture.source, fixture.model)
        );
        assertEquals(0, unavailableGuardCalls.get());
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean authorized) {
        final Map<String, StaticSelector> selectors = new LinkedHashMap<>();
        for (final String alias : EditorTextureRelationsSelectorContract.REQUIRED_ALIASES) {
            selectors.put(alias, StaticSelector.classSelector(alias, internal(Object.class)));
        }
        putMethod(selectors, "cubism.editor-model.model-source.texture-manager", ModelSource.class,
            "textureManager", desc(TextureManager.class));
        putMethod(selectors, "cubism.editor-model.texture-manager.raw-images", TextureManager.class,
            "rawImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.model-image-groups", TextureManager.class,
            "modelImageGroups", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.all-model-images", TextureManager.class,
            "allModelImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.texture-atlases", TextureManager.class,
            "textureAtlases", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-manager.art-mesh-usable-model-image-groups",
            TextureManager.class, "artMeshUsableModelImageGroups", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.image", Wrapper.class,
            "image", desc(LayeredImage.class));
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.import-time", Wrapper.class,
            "getImportTime", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.modified-time", Wrapper.class,
            "getModifiedTime", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image-wrapper.replaced", Wrapper.class,
            "isReplaced", "()Z");
        putClass(selectors, "cubism.editor-model.layered-image.class", LayeredImage.class);
        putMethod(selectors, "cubism.editor-model.layered-image.guid", LayeredImage.class,
            "getGuid", desc(HostId.class));
        putMethod(selectors, "cubism.editor-model.layered-image.name", LayeredImage.class,
            "getName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.layered-image.width", LayeredImage.class,
            "getWidth", "()I");
        putMethod(selectors, "cubism.editor-model.layered-image.height", LayeredImage.class,
            "getHeight", "()I");
        putMethod(selectors, "cubism.editor-model.layered-image.psd-file", LayeredImage.class,
            "getPsdFile", "()Ljava/io/File;");
        putMethod(selectors, "cubism.editor-model.layered-image.psd-doc", LayeredImage.class,
            "getPsdDoc", desc(PsdDocument.class));
        putMethod(selectors, "cubism.editor-model.layered-image.children", LayeredImage.class,
            "getChildren", "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.layer-entry.class", LayerEntry.class);
        putMethod(selectors, "cubism.editor-model.layer-entry.guid", LayerEntry.class,
            "getGuid", desc(HostId.class));
        putMethod(selectors, "cubism.editor-model.layer-entry.name", LayerEntry.class,
            "getName", "()Ljava/lang/String;");
        putClass(selectors, "cubism.editor-model.layer-group.class", LayerGroup.class);
        putMethod(selectors, "cubism.editor-model.layer-group.children", LayerGroup.class,
            "getChildren", "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.model-image.class", ModelImage.class);
        putMethod(selectors, "cubism.editor-model.model-image.guid", ModelImage.class,
            "getGuid", desc(HostId.class));
        putMethod(selectors, "cubism.editor-model.model-image.name", ModelImage.class,
            "getName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image.width", ModelImage.class,
            "getWidth", "()I");
        putMethod(selectors, "cubism.editor-model.model-image.height", ModelImage.class,
            "getHeight", "()I");
        putMethod(selectors, "cubism.editor-model.model-image.linked-raw-image-guids", ModelImage.class,
            "getLinkedRawImageGuids", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-image.input-filter-env", ModelImage.class,
            "getInputFilterEnv", desc(FilterEnv.class));
        putClass(selectors, "cubism.editor-model.model-image-filter-env.class", FilterEnv.class);
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.has-layer-input-data", FilterEnv.class,
            "getHasLayerInputData", "()Z");
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.layer-input-data", FilterEnv.class,
            "getLayerInputData", desc(SelectorMap.class));
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.has-current-image-guid", FilterEnv.class,
            "getHasCurrentImageGuid", "()Z");
        putMethod(selectors, "cubism.editor-model.model-image-filter-env.current-image-guid", FilterEnv.class,
            "getCurrentImageGuid", desc(HostId.class));
        putClass(selectors, "cubism.editor-model.layer-selector-map.class", SelectorMap.class);
        putMethod(selectors, "cubism.editor-model.layer-selector-map.image-to-layer-input", SelectorMap.class,
            "getImageToLayerInput", "()Ljava/util/Map;");
        putClass(selectors, "cubism.editor-model.layer-input-data.class", LayerInput.class);
        putMethod(selectors, "cubism.editor-model.layer-input-data.layer", LayerInput.class,
            "getLayer", desc(LayerEntry.class));
        putMethod(selectors, "cubism.editor-model.layer-input-data.affine", LayerInput.class,
            "getAffine", "()Ljava/lang/Object;");
        putMethod(selectors, "cubism.editor-model.layer-input-data.clipping-on-texture-px", LayerInput.class,
            "getClippingOnTexturePx", "()Ljava/lang/Object;");
        putClass(selectors, "cubism.editor-model.model-image-group.class", HostModelImageGroup.class);
        putMethod(selectors, "cubism.editor-model.model-image-group.group-name", HostModelImageGroup.class,
            "getGroupName", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image-group.memo", HostModelImageGroup.class,
            "getMemo", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.model-image-group.model-images", HostModelImageGroup.class,
            "getModelImages", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model-image-group.linked-raw-image-guids",
            HostModelImageGroup.class, "getLinkedRawImageGuids", "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.texture-atlas.class", TextureAtlas.class);
        putMethod(selectors, "cubism.editor-model.texture-atlas.guid", TextureAtlas.class,
            "getGuid", desc(HostId.class));
        putClass(selectors, "cubism.editor-model.texture-input-extension.class", TextureInputExtension.class);
        putMethod(selectors, "cubism.editor-model.texture-input-extension.texture-inputs",
            TextureInputExtension.class, "getTextureInputs", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.texture-input-extension.current-texture-input-data",
            TextureInputExtension.class, "getCurrentTextureInputData", "()Ljava/lang/Object;");
        putClass(selectors, "cubism.editor-model.texture-input-model-image.class", ModelInput.class);
        putMethod(selectors, "cubism.editor-model.texture-input-model-image.model-image-guid", ModelInput.class,
            "getModelImageGuid", desc(HostId.class));
        putClass(selectors, "cubism.editor-model.texture-input-texture-atlas-region.class", AtlasInput.class);
        putMethod(selectors, "cubism.editor-model.texture-input-texture-atlas-region.texture-atlas-guid",
            AtlasInput.class, "getTextureAtlasGuid", desc(HostId.class));
        putMethod(selectors, "cubism.editor-model.model-source.all-art-meshes", ModelSource.class,
            "allArtMeshes", "()Ljava/util/List;");
        putMethod(selectors, "cubism.editor-model.model.all-art-meshes", Model.class,
            "allArtMeshes", "()Ljava/util/List;");
        putClass(selectors, "cubism.editor-model.art-mesh-source.class", ArtMeshSource.class);
        putClass(selectors, "cubism.editor-model.art-mesh.class", ArtMesh.class);
        putMethod(selectors, "cubism.editor-model.art-mesh.source", ArtMesh.class,
            "getSource", desc(ArtMeshSource.class));
        putMethod(selectors, "cubism.editor-model.art-mesh-source.texture-input-extension", ArtMeshSource.class,
            "getTextureInputExtension", "()Ljava/lang/Object;");
        putMethod(selectors, "cubism.editor-model.parameter-controllable-source.id", ArtMeshSource.class,
            "getId", desc(SourceId.class));
        putMethod(selectors, "cubism.editor-model.guid.value", HostId.class, "value", "()Ljava/lang/String;");
        putMethod(selectors, "cubism.editor-model.id.value", SourceId.class, "getIdString", "()Ljava/lang/String;");

        final Set<String> capabilities = authorized
            ? Set.of(EditorTextureRelationsSelectorContract.CAPABILITY_ID)
            : Set.of("cubism.editor-model.relations.not-authorized");
        return TestVerifiedResolvers.create(
            version,
            EditorTextureRelationsSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            new ArrayList<>(selectors.values()),
            EditorTextureRelationsAccessTest.class.getClassLoader()
        );
    }

    private static void putClass(
        final Map<String, StaticSelector> selectors,
        final String alias,
        final Class<?> owner
    ) {
        selectors.put(alias, StaticSelector.classSelector(alias, internal(owner)));
    }

    private static void putMethod(
        final Map<String, StaticSelector> selectors,
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        selectors.put(alias, StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        ));
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    private static final class Fixture {
        final ModelSource source;
        final Model model;
        final LayeredImage rawA;
        final LayeredImage rawB;
        final LayeredImage rawC;
        final LayeredImage rawD;

        Fixture() {
            final LayerEntry layerA = new LayerEntry(new HostId("layer-a"), "Layer A");
            final LayerEntry layerB = new LayerEntry(new HostId("layer-b"), "Layer B");
            final LayerGroup layerGroup = new LayerGroup(
                new HostId("layer-group"), "Layer Group", List.of(layerA, layerB)
            );
            this.rawA = new LayeredImage(
                new HostId("raw-a"), "Raw A", 1024, 512,
                new File("/proven/raw-a.psd"), new PsdDocument(), List.of(layerGroup)
            );
            this.rawB = new LayeredImage(
                new HostId("raw-b"), "Raw B", 256, 128,
                new File("/proven/raw-b.png"), null,
                List.of(new LayerEntry(new HostId("raw-b-layer"), "Raw B Layer"))
            );
            this.rawC = new LayeredImage(
                new HostId("raw-c"), "Raw C", 128, 64,
                null, null,
                List.of(new LayerEntry(new HostId("raw-c-layer"), "Raw C Layer"))
            );
            this.rawD = new LayeredImage(
                new HostId("raw-d"), "Reopened PSD", 128, 64,
                new File("/proven/reopened.psd"), null,
                List.of(new LayerEntry(new HostId("raw-d-layer"), "Reopened Layer"))
            );
            final Wrapper wrapperA = new Wrapper(rawA, "import-a", "modified-a", true);
            final Wrapper wrapperB = new Wrapper(rawB, null, null, false);
            final Wrapper wrapperC = new Wrapper(rawC, null, null, false);
            final Wrapper wrapperD = new Wrapper(rawD, null, null, false);

            final LayerInput firstLayerInput = new LayerInput(layerA, new Object(), new Object());
            final LayerInput secondLayerInput = new LayerInput(layerB, null, new Object());
            final SelectorMap selectorMap = new SelectorMap(new LinkedHashMap<>(Map.of(
                new HostId("raw-a"), List.of(firstLayerInput, secondLayerInput)
            )));
            final ModelImage modelImageA = new ModelImage(
                new HostId("model-a"), "Shared", 256, 128,
                List.of(new HostId("raw-a")),
                new FilterEnv(true, selectorMap, true, new HostId("raw-a"))
            );
            final ModelImage modelImageB = new ModelImage(
                new HostId("model-b"), "Shared", 512, 256,
                List.of(new HostId("raw-a")),
                new FilterEnv(false, null, false, null)
            );
            final HostModelImageGroup group = new HostModelImageGroup(
                "Shared", "group memo", List.of(modelImageA, modelImageB), List.of(new HostId("raw-a"))
            );

            final ModelInput modelInputA = new ModelInput(new HostId("model-a"));
            final TextureInputExtension extensionA = new TextureInputExtension(
                List.of(
                    new AtlasInput(new HostId("atlas-a")),
                    modelInputA,
                    new UnknownInput(),
                    new ModelInput(new HostId("missing"))
                ),
                modelInputA
            );
            final TextureInputExtension extensionB = new TextureInputExtension(
                List.of(new ModelInput(new HostId("model-a"))), null
            );
            final ArtMeshSource meshSourceA = new ArtMeshSource(new HostId("mesh-a"), extensionA);
            final ArtMeshSource meshSourceB = new ArtMeshSource(new HostId("mesh-b"), extensionB);
            final ArtMeshSource unboundSource = new ArtMeshSource(new HostId("mesh-unbound"), null);
            final List<ArtMeshSource> meshSources = List.of(meshSourceA, meshSourceB, unboundSource);
            final List<ArtMesh> meshInstances = List.of(
                new ArtMesh(meshSourceA), new ArtMesh(meshSourceB), new ArtMesh(unboundSource)
            );
            final TextureManager manager = new TextureManager(
                List.of(wrapperA, wrapperB, wrapperC, wrapperD),
                List.of(group),
                List.of(modelImageA, modelImageB),
                List.of(new TextureAtlas(new HostId("atlas-a"))),
                List.of(group)
            );
            this.source = new ModelSource(manager, meshSources);
            this.model = new Model("model-a", meshInstances);
        }
    }


    public static final class HostId {
        private final String value;

        public HostId(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class ModelSource {
        private final TextureManager textureManager;
        private final List<ArtMeshSource> allArtMeshes;
        final AtomicInteger textureManagerCalls = new AtomicInteger();
        final AtomicReference<Boolean> textureManagerReadOnHostThread = new AtomicReference<>();
        final AtomicReference<Runnable> afterTextureManagerRead = new AtomicReference<>();

        ModelSource(final TextureManager textureManager, final List<ArtMeshSource> allArtMeshes) {
            this.textureManager = textureManager;
            this.allArtMeshes = allArtMeshes;
        }

        public TextureManager textureManager() {
            textureManagerCalls.incrementAndGet();
            textureManagerReadOnHostThread.set(EditorHostThread.isCurrent());
            final Runnable hook = afterTextureManagerRead.getAndSet(null);
            if (hook != null) hook.run();
            return textureManager;
        }

        public List<ArtMeshSource> allArtMeshes() {
            return allArtMeshes;
        }
    }

    public static final class Model {
        private final String id;
        private final List<ArtMesh> allArtMeshes;

        Model(final List<ArtMesh> allArtMeshes) {
            this("model-a", allArtMeshes);
        }

        Model(final String id, final List<ArtMesh> allArtMeshes) {
            this.id = id;
            this.allArtMeshes = allArtMeshes;
        }

        public String id() {
            return id;
        }

        public List<ArtMesh> allArtMeshes() {
            return allArtMeshes;
        }
    }

    public static final class TextureManager {
        private final List<Wrapper> rawImages;
        private final List<HostModelImageGroup> modelImageGroups;
        private final List<ModelImage> allModelImages;
        private final List<TextureAtlas> textureAtlases;
        private final List<HostModelImageGroup> artMeshUsableModelImageGroups;

        TextureManager(
            final List<Wrapper> rawImages,
            final List<HostModelImageGroup> modelImageGroups,
            final List<ModelImage> allModelImages,
            final List<TextureAtlas> textureAtlases,
            final List<HostModelImageGroup> artMeshUsableModelImageGroups
        ) {
            this.rawImages = rawImages;
            this.modelImageGroups = modelImageGroups;
            this.allModelImages = allModelImages;
            this.textureAtlases = textureAtlases;
            this.artMeshUsableModelImageGroups = artMeshUsableModelImageGroups;
        }

        public List<Wrapper> rawImages() {
            return rawImages;
        }

        public List<HostModelImageGroup> modelImageGroups() {
            return modelImageGroups;
        }

        public List<ModelImage> allModelImages() {
            return allModelImages;
        }

        public List<TextureAtlas> textureAtlases() {
            return textureAtlases;
        }

        public List<HostModelImageGroup> artMeshUsableModelImageGroups() {
            return artMeshUsableModelImageGroups;
        }
    }

    public static final class Wrapper {
        private final LayeredImage image;
        private final String importTime;
        private final String modifiedTime;
        private final boolean replaced;

        Wrapper(
            final LayeredImage image,
            final String importTime,
            final String modifiedTime,
            final boolean replaced
        ) {
            this.image = image;
            this.importTime = importTime;
            this.modifiedTime = modifiedTime;
            this.replaced = replaced;
        }

        public LayeredImage image() {
            return image;
        }

        public String getImportTime() {
            return importTime;
        }

        public String getModifiedTime() {
            return modifiedTime;
        }

        public boolean isReplaced() {
            return replaced;
        }
    }

    public static class LayerEntry {
        private final HostId guid;
        private final String name;

        LayerEntry(final HostId guid, final String name) {
            this.guid = guid;
            this.name = name;
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }
    }

    public static final class LayerGroup extends LayerEntry {
        private final List<LayerEntry> children;

        LayerGroup(final HostId guid, final String name, final List<LayerEntry> children) {
            super(guid, name);
            this.children = children;
        }

        public List<LayerEntry> getChildren() {
            return children;
        }
    }

    public static final class PsdDocument { }

    public static final class LayeredImage {
        private final HostId guid;
        private final String name;
        private final int width;
        private final int height;
        private final File psdFile;
        private final PsdDocument psdDoc;
        private final List<LayerEntry> children;
        private final AtomicInteger psdFileReads = new AtomicInteger();
        private final AtomicInteger childrenReads = new AtomicInteger();

        LayeredImage(
            final HostId guid,
            final String name,
            final int width,
            final int height,
            final File psdFile,
            final List<LayerEntry> children
        ) {
            this(guid, name, width, height, psdFile, null, children);
        }

        LayeredImage(
            final HostId guid,
            final String name,
            final int width,
            final int height,
            final File psdFile,
            final PsdDocument psdDoc,
            final List<LayerEntry> children
        ) {
            this.guid = guid;
            this.name = name;
            this.width = width;
            this.height = height;
            this.psdFile = psdFile;
            this.psdDoc = psdDoc;
            this.children = children;
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public File getPsdFile() {
            psdFileReads.incrementAndGet();
            return psdFile;
        }

        public PsdDocument getPsdDoc() {
            return psdDoc;
        }

        int psdFileReads() {
            return psdFileReads.get();
        }

        public List<LayerEntry> getChildren() {
            childrenReads.incrementAndGet();
            return children;
        }
    }

    public static final class ModelImage {
        private final HostId guid;
        private final String name;
        private final int width;
        private final int height;
        private final List<HostId> linkedRawImageGuids;
        private final FilterEnv inputFilterEnv;
        private final AtomicInteger environmentReads = new AtomicInteger();

        ModelImage(
            final HostId guid,
            final String name,
            final int width,
            final int height,
            final List<HostId> linkedRawImageGuids,
            final FilterEnv inputFilterEnv
        ) {
            this.guid = guid;
            this.name = name;
            this.width = width;
            this.height = height;
            this.linkedRawImageGuids = linkedRawImageGuids;
            this.inputFilterEnv = inputFilterEnv;
        }

        public HostId getGuid() {
            return guid;
        }

        public String getName() {
            return name;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public List<HostId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }

        public FilterEnv getInputFilterEnv() {
            environmentReads.incrementAndGet();
            return inputFilterEnv;
        }
    }

    public static final class FilterEnv {
        private final boolean hasLayerInputData;
        private final SelectorMap layerInputData;
        private final AtomicInteger layerReads = new AtomicInteger();
        private final boolean hasCurrentImageGuid;
        private final HostId currentImageGuid;

        FilterEnv(
            final boolean hasLayerInputData,
            final SelectorMap layerInputData,
            final boolean hasCurrentImageGuid,
            final HostId currentImageGuid
        ) {
            this.hasLayerInputData = hasLayerInputData;
            this.layerInputData = layerInputData;
            this.hasCurrentImageGuid = hasCurrentImageGuid;
            this.currentImageGuid = currentImageGuid;
        }

        public boolean getHasLayerInputData() {
            layerReads.incrementAndGet();
            return hasLayerInputData;
        }

        public SelectorMap getLayerInputData() {
            layerReads.incrementAndGet();
            return layerInputData;
        }

        public boolean getHasCurrentImageGuid() {
            return hasCurrentImageGuid;
        }

        public HostId getCurrentImageGuid() {
            return currentImageGuid;
        }
    }

    public static final class SelectorMap {
        private final Map<HostId, List<LayerInput>> imageToLayerInput;

        SelectorMap(final Map<HostId, List<LayerInput>> imageToLayerInput) {
            this.imageToLayerInput = imageToLayerInput;
        }

        public Map<HostId, List<LayerInput>> getImageToLayerInput() {
            return imageToLayerInput;
        }
    }

    public static final class LayerInput {
        private final LayerEntry layer;
        private final Object affine;
        private final Object clipping;

        LayerInput(final LayerEntry layer, final Object affine, final Object clipping) {
            this.layer = layer;
            this.affine = affine;
            this.clipping = clipping;
        }

        public LayerEntry getLayer() {
            return layer;
        }

        public Object getAffine() {
            return affine;
        }

        public Object getClippingOnTexturePx() {
            return clipping;
        }
    }

    public static final class HostModelImageGroup {
        private final String groupName;
        private final String memo;
        private final List<ModelImage> modelImages;
        private final List<HostId> linkedRawImageGuids;

        HostModelImageGroup(
            final String groupName,
            final String memo,
            final List<ModelImage> modelImages,
            final List<HostId> linkedRawImageGuids
        ) {
            this.groupName = groupName;
            this.memo = memo;
            this.modelImages = modelImages;
            this.linkedRawImageGuids = linkedRawImageGuids;
        }

        public String getGroupName() {
            return groupName;
        }

        public String getMemo() {
            return memo;
        }

        public List<ModelImage> getModelImages() {
            return modelImages;
        }

        public List<HostId> getLinkedRawImageGuids() {
            return linkedRawImageGuids;
        }
    }

    public static final class TextureAtlas {
        private final HostId guid;

        TextureAtlas(final HostId guid) {
            this.guid = guid;
        }

        public HostId getGuid() {
            return guid;
        }
    }

    // Native CObjectID and GUID are unrelated types; using one fixture class hid a host failure.
    public record SourceId(String value) {
        public String getIdString() { return value; }
    }
    public static final class ArtMeshSource {
        private final SourceId id;
        private final Object textureInputExtension;

        ArtMeshSource(final HostId id, final Object textureInputExtension) {
            this.id = new SourceId(id.value());
            this.textureInputExtension = textureInputExtension;
        }

        public SourceId getId() {
            return id;
        }

        public Object getTextureInputExtension() {
            return textureInputExtension;
        }
    }

    public static final class ArtMesh {
        private final ArtMeshSource source;

        ArtMesh(final ArtMeshSource source) {
            this.source = source;
        }

        public ArtMeshSource getSource() {
            return source;
        }
    }

    public static final class TextureInputExtension {
        private final List<Object> textureInputs;
        private final Object currentTextureInputData;

        TextureInputExtension(final List<Object> textureInputs, final Object currentTextureInputData) {
            this.textureInputs = textureInputs;
            this.currentTextureInputData = currentTextureInputData;
        }

        public List<Object> getTextureInputs() {
            return textureInputs;
        }

        public Object getCurrentTextureInputData() {
            return currentTextureInputData;
        }
    }

    public static final class ModelInput {
        private final HostId modelImageGuid;

        ModelInput(final HostId modelImageGuid) {
            this.modelImageGuid = modelImageGuid;
        }

        public HostId getModelImageGuid() {
            return modelImageGuid;
        }
    }

    public static final class AtlasInput {
        private final HostId textureAtlasGuid;

        AtlasInput(final HostId textureAtlasGuid) {
            this.textureAtlasGuid = textureAtlasGuid;
        }

        public HostId getTextureAtlasGuid() {
            return textureAtlasGuid;
        }
    }

    public static final class UnknownInput { }
}
