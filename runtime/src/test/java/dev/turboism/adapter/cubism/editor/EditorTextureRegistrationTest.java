package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Offline coverage for the frozen register-and-bind slice: decode oracle, member
 * ownership, admission/mutation ordering, compensation, rejection matrix, and
 * no-change rebinding — all against native substitutes, never a real host.
 */
class EditorTextureRegistrationTest {

    static final ArtMeshId MESH = new ArtMeshId("mesh");
    static final ArtMeshId MESH2 = new ArtMeshId("mesh2");

    static byte[] png() {
        try {
            final var image = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
            final var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static AuthorizedImageContent grant() {
        return AuthorizedImageContent.of(png(), "test.png");
    }

    @Test
    void explicitPaddedImageDecodesAndBindsWithinTransaction() throws Exception {
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB), "PNG", out);
        var padded = AuthorizedImageContent.ofDimensions(out.toByteArray(), "padded", 512, 256);
        onEdt(f -> {
            var binding = f.textures().registerImageAndBind(padded, MESH);
            assertEquals(java.util.Optional.of(MESH), binding.artMeshId());
            assertEquals(1, f.manager.rawImages.size());
            final var texture = (EditorTextureRegistrationFixture.GLTexture) f.meshSource.texture;
            assertEquals(512, texture.image().width());
            assertEquals(256, texture.image().height());
            assertEquals(512, f.manager.rawImages.get(0).image.width());
            assertEquals(256, f.manager.rawImages.get(0).image.height());
        });
    }

    @Test
    void sharedMaterialJoinsAmbientRootWithSingleUndoRedo() throws Exception {
        onEdt(f -> {
            final var textures = f.textures();
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("whole import"), () -> {
                final var image = textures.registerImageAndBind(grant(), MESH);
                textures.bindModelImage(image.modelImageId(), MESH2);
                return image;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.begins);
            assertEquals(1, f.history.size());
            final var image = f.manager.modelImageGroups.get(0).images.get(0);
            final var texture = f.meshSource.texture;
            final var secondTexture = f.source.meshSource2.texture;
            assertEquals(1, f.manager.rawImages.size());
            assertEquals(1, f.manager.modelImageGroups.size());
            for (final var mesh : List.of(f.meshSource, f.source.meshSource2)) {
                final var extension = (EditorTextureRegistrationFixture.Ext) mesh.extensions.get(0);
                assertSame(image, ((EditorTextureRegistrationFixture.Input) extension.current).modelImageOrNull());
            }
            f.history.get(0).undo();
            assertTrue(f.manager.rawImages.isEmpty());
            assertTrue(f.manager.modelImageGroups.isEmpty());
            assertTrue(f.meshSource.extensions.isEmpty());
            assertTrue(f.source.meshSource2.extensions.isEmpty());
            assertNull(f.meshSource.texture);
            assertNull(f.source.meshSource2.texture);
            f.history.get(0).redo();
            assertSame(image, f.manager.modelImageGroups.get(0).images.get(0));
            assertSame(texture, f.meshSource.texture);
            assertSame(secondTexture, f.source.meshSource2.texture);
        });
    }

    @Test
    void ambientMaterialFailureRestoresResourcesBindingsAndHistory() throws Exception {
        for (final boolean nativeAbort : List.of(false, true))
            onEdt(f -> {
                f.nativeAbort = nativeAbort;
                final var textures = f.textures();
                final var before = f.history(f.binding);
                final var result =
                        f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("failed import"), () -> {
                            final var image = textures.registerImageAndBind(grant(), MESH);
                            textures.bindModelImage(image.modelImageId(), MESH2);
                            throw new IllegalStateException("after shared material");
                        });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertEquals(1, f.begins);
                assertEquals(before, f.history(f.binding));
                assertTrue(f.manager.rawImages.isEmpty());
                assertTrue(f.manager.modelImageGroups.isEmpty());
                assertTrue(f.meshSource.extensions.isEmpty());
                assertTrue(f.source.meshSource2.extensions.isEmpty());
                assertNull(f.meshSource.texture);
                assertNull(f.source.meshSource2.texture);
            });
    }

    /**
     * Host parity (job 3a64f0f9): {@code CModelSource.getAllArtMeshes()} yields
     * {@code CArtMeshSource} elements carrying their id directly via
     * {@code parameter-controllable-source.id}. The fixture must mirror that
     * shape, and every element must satisfy the production validation predicate.
     */
    @Test
    void meshCollectionMirrorsHostSourceShape() throws Exception {
        onEdt(f -> {
            final var resolver = f.resolver;
            final List<?> elements = f.source.meshes();
            assertEquals(2, elements.size());
            for (final Object element : elements) {
                assertTrue(
                        resolver.isInstance("cubism.editor-model.art-mesh-source.class", element),
                        "collection elements must be art-mesh-source shaped");
                final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", element);
                assertNotNull(resolver.invoke("cubism.editor-model.id.value", id));
            }
        });
    }

    /**
     * Legacy instance-shaped resolution must fail on the host-shaped collection:
     * elements are not {@code CArtMesh} instances and the {@code getSource} hop
     * is unavailable on them — exactly why the r1 semantics aborted on host.
     */
    @Test
    void legacyInstanceShapedResolutionFailsOnHostCollection() throws Exception {
        onEdt(f -> {
            final var resolver = f.resolver;
            for (final Object element : f.source.meshes()) {
                assertFalse(
                        resolver.isInstance("cubism.editor-model.art-mesh.class", element),
                        "source elements must not validate as CArtMesh instances");
                assertThrows(
                        RuntimeException.class, () -> resolver.invoke("cubism.editor-model.art-mesh.source", element));
            }
        });
    }

    @Test
    void registersAndBindsInsideOneTransaction() throws Exception {
        onEdt(f -> {
            final int loadedBefore = EditorTextureRegistrationFixture.ImageResource.loadedCount;
            final int bytesBefore = EditorTextureRegistrationFixture.ImageResource.loadedBytes;
            final ModelImageBinding bound = f.textures().registerImageAndBind(grant(), MESH);

            assertTrue(bound.bound());
            assertEquals(MESH, bound.artMeshId().orElseThrow());
            assertEquals("texture-test", bound.scope());
            assertEquals(1, bound.modelGeneration());
            assertEquals("model", bound.modelId().value());

            assertEquals(1, f.manager.rawImages.size());
            assertEquals(1, f.manager.modelImageGroups.size());
            final var group = f.manager.modelImageGroups.get(0);
            assertEquals(1, group.images.size());
            final var image = group.images.get(0);
            // Host parity (job b9cc6bc0): FilterSet.verify rejects a null collector,
            // so the wiring must pass a default-constructed collector instance.
            assertNotNull(image.inputFilter.lastVerifyCollector, "verify collector must be constructed");
            assertEquals(image.guid.value(), bound.modelImageId().value());
            final var wrapper = f.manager.rawImages.get(0);
            assertEquals(wrapper.image.guid.value(), bound.rawImageId().value());
            assertEquals(List.of(wrapper.image.guid), group.linked);
            assertEquals(List.of(wrapper.image.guid), image.linkedGuids);
            assertTrue(image.inputFilter.verified, "filter graph verified");

            assertEquals(1, f.meshSource.extensions.size());
            final var ext = (EditorTextureRegistrationFixture.Ext) f.meshSource.extensions.get(0);
            assertEquals(1, ext.inputs.size());
            final var input = (EditorTextureRegistrationFixture.Input) ext.inputs.get(0);
            assertSame(input, ext.current);
            assertSame(image, input.modelImageOrNull());
            final var texture = (EditorTextureRegistrationFixture.GLTexture) f.meshSource.texture;
            assertEquals(256, texture.image().width());
            assertEquals(128, texture.image().height());
            assertSame(EditorTextureRegistrationFixture.TextureState.MODEL_IMAGE, f.meshSource.textureState);
            assertArrayEquals(new float[] {0, 0, 1, 0, 1, 1, 0, 1}, f.meshSource.uvs(), "UVs untouched");

            assertEquals(
                    List.of("+Wrapper", "+ImageGroup", "+ModelImage", "+Ext", "texture", "textureState"),
                    f.mutationOrder,
                    "raw -> group -> image -> ext -> texture -> state");
            assertEquals(1, f.begins);
            assertEquals(1, f.position);
            assertEquals(loadedBefore + 1, EditorTextureRegistrationFixture.ImageResource.loadedCount);
            assertEquals(bytesBefore + 131_072, EditorTextureRegistrationFixture.ImageResource.loadedBytes);
        });
    }

    /**
     * Faithful failure regression for job 460fa263: the native save-time check
     * {@code TextureManagerHandler.b()} marks a raw image unused unless some
     * model image's {@code inputFilterEnv} carries
     * {@code currentImageGuid == rawGuid} — {@code linkedRawImageGuids} is never
     * consulted. Registration must publish that env value so the saved document
     * reaches the save dialog without the CUB3-3054 prompt.
     */
    @Test
    void registeredImageSatisfiesNativeUnusedRawImageCheck() throws Exception {
        onEdt(f -> {
            final ModelImageBinding bound = f.textures().registerImageAndBind(grant(), MESH);
            final var image = f.manager.modelImageGroups.get(0).images.get(0);
            final var env = image.inputFilterEnv();
            assertTrue(env.hasCurrentImageGuid(), "native unused check requires the env current-image guid");
            assertEquals(bound.rawImageId().value(), env.currentImageGuid().value());
            assertTrue(f.unusedRawImages().isEmpty(), "TextureManagerHandler.b() must report no unused raw images");
        });
    }

    /**
     * The replica must discriminate: a raw image that no model image env claims
     * is still flagged unused even though a bound registration exists.
     */
    @Test
    void nativeUnusedCheckStillFlagsUnclaimedRawImages() throws Exception {
        onEdt(f -> {
            f.textures().registerImageAndBind(grant(), MESH);
            final var orphan = new EditorTextureRegistrationFixture.Wrapper(
                    new EditorTextureRegistrationFixture.LayeredImage(
                            new EditorTextureRegistrationFixture.ImageResource(
                                    png(), EditorTextureRegistrationFixture.ColorType.c, true),
                            null,
                            "orphan.png",
                            "orphan.png"),
                    -1L,
                    -1L,
                    false);
            f.manager.rawImages.add(orphan);
            assertEquals(List.of(orphan), f.unusedRawImages(), "raw images claimed by no model image env stay unused");
        });
    }

    /**
     * The env marker lives on the retained model image object: undo removes
     * membership, redo re-adds the same instance, and the native unused check
     * must stay satisfied without any re-registration.
     */
    @Test
    void unusedPredicateSurvivesUndoAndRedo() throws Exception {
        onEdt(f -> {
            final ModelImageBinding bound = f.textures().registerImageAndBind(grant(), MESH);
            f.undo();
            f.redo();
            final var image = f.manager.modelImageGroups.get(0).images.get(0);
            assertEquals(
                    bound.rawImageId().value(),
                    image.inputFilterEnv().currentImageGuid().value());
            assertTrue(f.unusedRawImages().isEmpty());
        });
    }

    @Test
    void undoThenRedoRestoresMembershipIdentityAndFields() throws Exception {
        onEdt(f -> {
            final ModelImageBinding bound = f.textures().registerImageAndBind(grant(), MESH);
            f.undo();
            assertTrue(f.manager.rawImages.isEmpty());
            assertTrue(f.manager.modelImageGroups.isEmpty());
            assertTrue(f.meshSource.extensions.isEmpty());
            assertNull(f.meshSource.texture);
            assertNull(f.meshSource.textureState);
            f.redo();
            assertEquals(1, f.manager.rawImages.size());
            assertEquals(1, f.manager.modelImageGroups.size());
            final var ext = (EditorTextureRegistrationFixture.Ext) f.meshSource.extensions.get(0);
            final var input = (EditorTextureRegistrationFixture.Input) ext.current;
            assertEquals(
                    bound.modelImageId().value(), input.modelImageOrNull().guid.value());
            assertNotNull(f.meshSource.texture);
            assertSame(EditorTextureRegistrationFixture.TextureState.MODEL_IMAGE, f.meshSource.textureState);
        });
    }

    @Test
    void sameImageRebindingIsNoChangeAndZeroTouch() throws Exception {
        onEdt(f -> {
            final ModelImageBinding bound = f.textures().registerImageAndBind(grant(), MESH);
            final int historyBefore = f.entries.size();
            final int beginsBefore = f.begins;
            final int mutationsBefore = f.mutationOrder.size();
            final ModelImageBinding again = f.textures().bindModelImage(bound.modelImageId(), MESH);
            assertEquals(bound.modelImageId(), again.modelImageId());
            assertEquals(MESH, again.artMeshId().orElseThrow());
            assertEquals(historyBefore, f.entries.size(), "no new history entry");
            assertEquals(beginsBefore, f.begins, "no new edit");
            assertEquals(mutationsBefore, f.mutationOrder.size(), "zero-touch");
        });
    }

    @Test
    void differentImageRebindingIsRejectedWithoutMutation() throws Exception {
        onEdt(f -> {
            f.textures().registerImageAndBind(grant(), MESH);
            final ModelImageBinding second = f.textures().registerImageAndBind(grant(), MESH2);
            final int mutations = f.mutationOrder.size();
            final int entries = f.entries.size();
            assertThrows(IllegalStateException.class, () -> f.textures().bindModelImage(second.modelImageId(), MESH));
            assertEquals(mutations, f.mutationOrder.size());
            assertEquals(entries, f.entries.size());
        });
    }

    @Test
    void ownedEditableMeshSurvivesTextureBindingUndoRedo() throws Exception {
        onEdt(f -> {
            final var editable = new EditorTextureRegistrationFixture.EditableExtension(f.meshSource);
            f.meshSource.extensions.add(editable);
            f.textures().registerImageAndBind(grant(), MESH);
            assertEquals(2, f.meshSource.extensions.size());
            f.history.get(0).undo();
            assertEquals(List.of(editable), f.meshSource.extensions);
            f.history.get(0).redo();
            assertEquals(2, f.meshSource.extensions.size());
            assertTrue(f.meshSource.extensions.contains(editable));
        });
    }

    @Test
    void invalidEditableMeshExtensionsAreRejectedBeforeRegistration() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            final int selected = scenario;
            onEdt(f -> {
                final var editable = new EditorTextureRegistrationFixture.EditableExtension(
                        selected == 0 ? f.source.meshSource2 : f.meshSource);
                editable.locked = selected == 1;
                f.meshSource.extensions.add(editable);
                if (selected == 2) f.meshSource.extensions.add(editable);
                assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
                assertTrue(f.manager.rawImages.isEmpty());
                assertTrue(f.history.isEmpty());
            });
        }
    }

    @Test
    void foreignExtensionTargetIsRejected() throws Exception {
        onEdt(f -> {
            f.meshSource.extensions.add(new Object());
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
            assertEquals(1, f.meshSource.extensions.size());
        });
    }

    @Test
    void lockedTargetIsRejected() throws Exception {
        onEdt(f -> {
            f.meshSource.locked = true;
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
            assertTrue(f.meshSource.extensions.isEmpty());
        });
    }

    @Test
    void deformerParentedTargetIsRejected() throws Exception {
        onEdt(f -> {
            f.meshSource.parents.add(new Object());
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
        });
    }

    @Test
    void staleModelBindingIsRejected() throws Exception {
        onEdt(f -> {
            f.currentModel = new EditorTextureRegistrationFixture.Model();
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
            assertEquals(0, f.entries.size());
        });
    }

    @Test
    void missingTargetAndMissingImageFailClosed() throws Exception {
        onEdt(f -> {
            assertThrows(
                    NoSuchElementException.class,
                    () -> f.textures().registerImageAndBind(grant(), new ArtMeshId("absent")));
            assertThrows(
                    NoSuchElementException.class, () -> f.textures().bindModelImage(new ModelImageId("absent"), MESH));
            assertEquals(0, f.begins);
        });
    }

    @Test
    void rejectedRootAdmissionLeavesZeroMutation() throws Exception {
        onEdt(f -> {
            f.rejectAdd = true;
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
            assertTrue(f.manager.modelImageGroups.isEmpty());
            assertTrue(f.meshSource.extensions.isEmpty());
            assertNull(f.meshSource.texture);
            assertEquals(0, f.entries.size());
        });
    }

    @Test
    void throwingRootAdmissionLeavesZeroMutation() throws Exception {
        onEdt(f -> {
            f.throwAdd = true;
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
            assertTrue(f.meshSource.extensions.isEmpty());
        });
    }

    @Test
    void partialRegistrationFailureCompensatesInReverseOrder() throws Exception {
        onEdt(f -> {
            f.failImageAdd = true;
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty(), "raw wrapper removed");
            assertTrue(f.manager.modelImageGroups.isEmpty(), "group removed");
            assertEquals(
                    List.of("+Wrapper", "+ImageGroup", "-ImageGroup", "-Wrapper"),
                    f.mutationOrder,
                    "reverse-order compensation without double remove");
            assertEquals(0, f.entries.size());
        });
    }

    @Test
    void bindingFailureCompensatesRegisteredMembers() throws Exception {
        onEdt(f -> {
            f.failCachedImage = true;
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(f.manager.rawImages.isEmpty());
            assertTrue(f.manager.modelImageGroups.isEmpty());
            assertTrue(f.meshSource.extensions.isEmpty());
            assertNull(f.meshSource.texture);
            assertNull(f.meshSource.textureState);
            assertEquals(0, f.entries.size());
        });
    }

    @Test
    void restorationFailureSurfacesAsRecoveryFailure() throws Exception {
        onEdt(f -> {
            f.failCachedImage = true;
            f.failUndo = true;
            final IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertTrue(failure.getMessage().contains("transaction failed"));
        });
    }

    @Test
    void authorizedBytesAreDefensivelyCopied() throws Exception {
        onEdt(f -> {
            final byte[] png = png();
            final AuthorizedImageContent image = AuthorizedImageContent.of(png, "test.png");
            png[9] = 0x00;
            png[10] = 0x00;
            assertDoesNotThrow(() -> f.textures().registerImageAndBind(image, MESH));
            assertEquals(1, f.manager.rawImages.size());
        });
    }

    @Test
    void consumedGrantCannotBeRegisteredTwice() throws Exception {
        onEdt(f -> {
            final AuthorizedImageContent image = grant();
            f.textures().registerImageAndBind(image, MESH);
            final int mutations = f.mutationOrder.size();
            assertThrows(IllegalStateException.class, () -> f.textures().registerImageAndBind(image, MESH2));
            assertEquals(mutations, f.mutationOrder.size());
        });
    }

    @Test
    void registerAndBindDispatchesToEdt() throws Exception {
        final var reference = new AtomicReference<EditorTextureRegistrationFixture>();
        SwingUtilities.invokeAndWait(() -> reference.set(new EditorTextureRegistrationFixture()));
        final var fixture = reference.get();
        final var textures = new AtomicReference<dev.turboism.sdk.cubism.model.ModelTextures>();
        SwingUtilities.invokeAndWait(() -> textures.set(fixture.textures()));
        final ModelImageBinding bound = textures.get().registerImageAndBind(grant(), MESH);
        assertTrue(bound.bound());
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, fixture.manager.rawImages.size());
            EditorTextureRegistrationFixture.CURRENT.remove();
        });
    }

    @Test
    void registerBindRequiresCapability() throws Exception {
        onEdt(f -> {
            f.resolver = f.resolver(
                    "5.3.02",
                    null,
                    dev.turboism.mapping.verification.selector.EditorTextureSelectorContract
                            .REGISTER_BIND_CAPABILITY_ID);
            assertThrows(UnsupportedOperationException.class, () -> f.textures().registerImageAndBind(grant(), MESH));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> f.textures().bindModelImage(new ModelImageId("image"), MESH));
        });
    }

    @Test
    void containerBudgetAndHeaderPrecheckRejectBadInput() throws Exception {
        onEdt(f -> {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AuthorizedImageContent.of(new byte[8_388_609], "too-big.png"));
            assertThrows(
                    IllegalArgumentException.class, () -> AuthorizedImageContent.of(new byte[] {1, 2, 3}, "not.png"));
            final byte[] truncated = new byte[20];
            System.arraycopy(png(), 0, truncated, 0, 20);
            assertThrows(IllegalArgumentException.class, () -> AuthorizedImageContent.of(truncated, "truncated.png"));
            assertEquals(0, f.begins, "no native touch for rejected input");
        });
    }

    @Test
    void decodeHappensExactlyOnceAcrossRegisterAndBind() throws Exception {
        onEdt(f -> {
            f.textures();
            final int before = f.decodeOracle().get("loadedCount");
            final ModelImageBinding bound = f.textures().registerImageAndBind(grant(), MESH);
            assertEquals(
                    before + 1,
                    f.decodeOracle().get("loadedCount"),
                    "single decode for construction, preflight and bound texture");
            final ModelImageBinding second = f.textures().bindModelImage(bound.modelImageId(), MESH2);
            assertTrue(second.bound());
            assertEquals(
                    before + 1,
                    f.decodeOracle().get("loadedCount"),
                    "rebinding reuses the cached texture without another decode");
        });
    }

    static void onEdt(final Consumer<EditorTextureRegistrationFixture> action) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.accept(new EditorTextureRegistrationFixture());
            } finally {
                EditorTextureRegistrationFixture.CURRENT.remove();
            }
        });
    }
}
