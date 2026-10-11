package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.ModelTextures;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Offline coverage for {@code ModelTextures.textureInput}: the read-only native
 * texture-input observation resolves the bound model-image GUID or texture-atlas
 * GUID through the active model's manager and fails closed on every other shape.
 */
class EditorTextureInputObservationTest {

    private static final ArtMeshId MESH = new ArtMeshId("mesh");

    @Test
    void meshWithoutExtensionReportsUnbound() throws Exception {
        onEdt(f -> {
            assertEquals(Optional.empty(), f.textures().textureInput(MESH));
        });
    }

    @Test
    void extensionWithoutCurrentInputReportsUnbound() throws Exception {
        onEdt(f -> {
            f.meshSource.extensions.add(new EditorTextureRegistrationFixture.Ext(f.meshSource));
            assertEquals(Optional.empty(), f.textures().textureInput(MESH));
        });
    }

    @Test
    void modelImageInputResolvesTypedIdentity() throws Exception {
        onEdt(f -> {
            final var image = bindImage(f, "image-1");
            final Optional<ArtMeshTextureInput> observed = f.textures().textureInput(MESH);
            assertTrue(observed.isPresent());
            final ArtMeshTextureInput input = observed.get();
            assertEquals(ArtMeshTextureInput.Kind.MODEL_IMAGE, input.kind());
            assertEquals(new ModelImageId("image-1"), input.modelImageId().orElseThrow());
            assertEquals(Optional.empty(), input.textureAtlasId());
            assertEquals(MESH, input.artMeshId());
            assertEquals(new ModelId("model"), input.modelId());
            assertEquals(1, input.modelGeneration());
        });
    }

    @Test
    void atlasRegionInputResolvesAtlasIdentityNotModelImage() throws Exception {
        onEdt(f -> {
            final var atlas =
                    new EditorTextureRegistrationFixture.Atlas(new EditorTextureRegistrationFixture.Guid("atlas-1"));
            f.manager.textureAtlases.add(atlas);
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            extension.setCurrent(new EditorTextureRegistrationFixture.AtlasInput(
                    new EditorTextureRegistrationFixture.Guid("atlas-1")));
            f.meshSource.extensions.add(extension);

            final Optional<ArtMeshTextureInput> observed = f.textures().textureInput(MESH);
            assertTrue(observed.isPresent());
            final ArtMeshTextureInput input = observed.get();
            assertEquals(ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION, input.kind());
            assertEquals(new TextureAtlasId("atlas-1"), input.textureAtlasId().orElseThrow());
            assertEquals(Optional.empty(), input.modelImageId());
        });
    }

    @Test
    void unknownInputTypeFailsClosed() throws Exception {
        onEdt(f -> {
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            extension.setCurrent(new Object());
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void danglingModelImageFailsClosed() throws Exception {
        onEdt(f -> {
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            extension.setCurrent(EditorTextureRegistrationFixture.Input.dangling(extension));
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void boundImageAbsentFromManagerFailsClosed() throws Exception {
        onEdt(f -> {
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            final var foreign = new EditorTextureRegistrationFixture.ModelImage(
                    f.source, "foreign", new EditorTextureRegistrationFixture.Guid("foreign-1"));
            extension.setCurrent(new EditorTextureRegistrationFixture.Input(extension, foreign));
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void boundGuidResolvingToAnotherObjectFailsClosed() throws Exception {
        onEdt(f -> {
            final var group = new EditorTextureRegistrationFixture.ImageGroup("g");
            group.images.add(new EditorTextureRegistrationFixture.ModelImage(
                    f.source, "listed", new EditorTextureRegistrationFixture.Guid("shared-1")));
            f.manager.modelImageGroups.add(group);
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            final var sameGuidOtherObject = new EditorTextureRegistrationFixture.ModelImage(
                    f.source, "shadow", new EditorTextureRegistrationFixture.Guid("shared-1"));
            extension.setCurrent(new EditorTextureRegistrationFixture.Input(extension, sameGuidOtherObject));
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void duplicateModelImageGuidInManagerFailsClosed() throws Exception {
        onEdt(f -> {
            final var image = new EditorTextureRegistrationFixture.ModelImage(
                    f.source, "a", new EditorTextureRegistrationFixture.Guid("dup-1"));
            final var group = new EditorTextureRegistrationFixture.ImageGroup("g");
            group.images.add(image);
            group.images.add(new EditorTextureRegistrationFixture.ModelImage(
                    f.source, "b", new EditorTextureRegistrationFixture.Guid("dup-1")));
            f.manager.modelImageGroups.add(group);
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            extension.setCurrent(new EditorTextureRegistrationFixture.Input(extension, image));
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void danglingAtlasGuidFailsClosed() throws Exception {
        onEdt(f -> {
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            extension.setCurrent(new EditorTextureRegistrationFixture.AtlasInput(
                    new EditorTextureRegistrationFixture.Guid("no-such-atlas")));
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void duplicateAtlasGuidInManagerFailsClosed() throws Exception {
        onEdt(f -> {
            f.manager.textureAtlases.add(
                    new EditorTextureRegistrationFixture.Atlas(new EditorTextureRegistrationFixture.Guid("dup-atlas")));
            f.manager.textureAtlases.add(
                    new EditorTextureRegistrationFixture.Atlas(new EditorTextureRegistrationFixture.Guid("dup-atlas")));
            final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
            extension.setCurrent(new EditorTextureRegistrationFixture.AtlasInput(
                    new EditorTextureRegistrationFixture.Guid("dup-atlas")));
            f.meshSource.extensions.add(extension);
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void absentTargetFailsClosed() throws Exception {
        onEdt(f ->
                assertThrows(NoSuchElementException.class, () -> f.textures().textureInput(new ArtMeshId("absent"))));
    }

    @Test
    void staleActiveDocumentFailsClosed() throws Exception {
        onEdt(f -> {
            bindImage(f, "image-1");
            f.document.switchSource(new EditorTextureRegistrationFixture.ModelSource());
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void duplicateTargetIdFailsClosed() throws Exception {
        onEdt(f -> {
            bindImage(f, "image-1");
            f.source.extraMeshes.add(new EditorTextureRegistrationFixture.MeshSource(f.source, "mesh"));
            assertThrows(IllegalStateException.class, () -> f.textures().textureInput(MESH));
        });
    }

    @Test
    void staleGenerationGuardFailsClosed() throws Exception {
        onEdt(f -> {
            bindImage(f, "image-1");
            final AtomicInteger calls = new AtomicInteger();
            final var textures = f.textures((identity, model) -> {
                if (calls.incrementAndGet() > 1) {
                    throw new IllegalStateException("stale model");
                }
            });
            assertThrows(IllegalStateException.class, () -> textures.textureInput(MESH));
        });
    }

    @Test
    void missingSelectorEvidenceFailsClosed() throws Exception {
        onEdt(f -> {
            bindImage(f, "image-1");
            f.resolver = f.resolver("5.3.02", "cubism.editor-model.texture-atlas-guid", null);
            final var textures = f.textures();
            assertThrows(UnsupportedOperationException.class, () -> textures.textureInput(MESH));
        });
    }

    @Test
    void offEdtCallDispatchesAndObservesCurrentBinding() throws Exception {
        final var fixture = new EditorTextureRegistrationFixture[1];
        final var textures = new ModelTextures[1];
        onEdt(f -> {
            fixture[0] = f;
            bindImage(f, "image-1");
            textures[0] = f.textures();
        });
        SwingUtilities.invokeAndWait(() -> EditorTextureRegistrationFixture.CURRENT.set(fixture[0]));
        try {
            final var observed = new java.util.concurrent.atomic.AtomicReference<Optional<ArtMeshTextureInput>>();
            final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            final Thread caller = new Thread(() -> {
                try {
                    observed.set(textures[0].textureInput(MESH));
                } catch (final Throwable thrown) {
                    failure.set(thrown);
                }
            });
            caller.start();
            caller.join();
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(observed.get().isPresent());
            assertEquals(
                    new ModelImageId("image-1"),
                    observed.get().get().modelImageId().orElseThrow());
        } finally {
            SwingUtilities.invokeAndWait(EditorTextureRegistrationFixture.CURRENT::remove);
        }
    }

    @Test
    void observationPerformsNoEditOrMutation() throws Exception {
        onEdt(f -> {
            bindImage(f, "image-1");
            f.textures().textureInput(MESH);
            f.textures().textureInput(MESH);
            assertEquals(0, f.begins, "no edit envelope opened");
            assertEquals(0, f.history.size(), "no undoable admitted");
            assertFalse(f.document.dirty, "no dirty mark");
            assertTrue(f.mutationOrder.isEmpty(), "no mutation callbacks");
        });
    }

    private static EditorTextureRegistrationFixture.ModelImage bindImage(
            final EditorTextureRegistrationFixture f, final String guid) {
        final var image = new EditorTextureRegistrationFixture.ModelImage(
                f.source, "image", new EditorTextureRegistrationFixture.Guid(guid));
        final var group = new EditorTextureRegistrationFixture.ImageGroup("g");
        group.images.add(image);
        f.manager.modelImageGroups.add(group);
        final var extension = new EditorTextureRegistrationFixture.Ext(f.meshSource);
        extension.setCurrent(new EditorTextureRegistrationFixture.Input(extension, image));
        f.meshSource.extensions.add(extension);
        return image;
    }

    private static void onEdt(final Consumer<EditorTextureRegistrationFixture> action) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.accept(new EditorTextureRegistrationFixture());
            } finally {
                EditorTextureRegistrationFixture.CURRENT.remove();
            }
        });
    }
}
