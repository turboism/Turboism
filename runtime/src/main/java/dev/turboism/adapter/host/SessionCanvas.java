package dev.turboism.adapter.host;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.adapter.cubism.model.ModelObjectProviderUnavailableException;
import dev.turboism.adapter.cubism.model.RuntimeModelObjectCreateProvider;
import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider;
import dev.turboism.adapter.cubism.warp.RuntimeWarpMirrorProvider;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlocker;
import dev.turboism.sdk.cubism.mirror.WarpMirrorBlockerCode;
import dev.turboism.sdk.cubism.mirror.WarpMirrorRequest;
import dev.turboism.sdk.cubism.mirror.WarpMirrorResult;
import dev.turboism.sdk.cubism.mirror.WarpMirrorService;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;


    final class SessionModelTextures
        implements dev.turboism.sdk.cubism.model.ModelTextures {
    private final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.ModelTextures delegate;
    SessionModelTextures(
        final DynamicCubismModelAccess host,
        final long generation,
        final dev.turboism.sdk.cubism.model.ModelTextures delegate
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }
        @Override public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
            return host.guarded(generation, delegate::rawImages);
        }
        @Override public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
            return host.guarded(generation, delegate::modelImageGroups);
        }
        @Override public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
            return host.guarded(generation, delegate::textureAtlases);
        }
        @Override public void addModelImageGroup(final String name) {
            host.guardedVoid(generation, () -> delegate.addModelImageGroup(name));
        }
        @Override public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {
            host.guardedVoid(generation, () -> delegate.removeModelImage(id));
        }
        @Override public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
            final String name,
            final int widthPixels,
            final int heightPixels
        ) {
            return host.guarded(generation, () -> delegate.addTextureAtlas(name, widthPixels, heightPixels));
        }
        @Override public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {
            host.guardedVoid(generation, () -> delegate.removeTextureAtlas(id));
        }
        @Override public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {
            host.guardedVoid(generation, () -> delegate.removeRawImage(id));
        }
    }

    final class SessionCanvas implements Canvas {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final Canvas delegate;
        SessionCanvas(final DynamicCubismModelAccess host, final long generation, final Canvas delegate) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public float widthPixels() { return host.guarded(generation, delegate::widthPixels); }
        @Override public float heightPixels() { return host.guarded(generation, delegate::heightPixels); }
        @Override public float originXPixels() { return host.guarded(generation, delegate::originXPixels); }
        @Override public float originYPixels() { return host.guarded(generation, delegate::originYPixels); }
        @Override public float pixelsPerUnit() { return host.guarded(generation, delegate::pixelsPerUnit); }
    }
