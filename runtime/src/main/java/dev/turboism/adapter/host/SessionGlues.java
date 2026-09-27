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


    final class SessionGlues implements Glues {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final Glues delegate;
        SessionGlues(final DynamicCubismModelAccess host, final long generation, final Glues delegate) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public List<Glue> all() {
            return host.guarded(generation, () -> delegate.all().stream()
                .map(value -> (Glue) new SessionGlue(host, generation, value)).toList());
        }
        @Override public Glue find(final GlueId id) {
            return host.guarded(generation, () -> new SessionGlue(host, generation, delegate.find(id)));
        }
        @Override public java.util.Optional<String> providerVersion() {
            return host.guarded(generation, delegate::providerVersion);
        }
    }

    final class SessionGlue implements Glue {
        private final DynamicCubismModelAccess host;
        private final long generation;
        private final Glue delegate;
        SessionGlue(final DynamicCubismModelAccess host, final long generation, final Glue delegate) {
            this.host = Objects.requireNonNull(host, "host");
            this.generation = generation;
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }
        @Override public GlueId id() { return host.guarded(generation, delegate::id); }

        @Override public int index() { return host.guarded(generation, delegate::index); }
        @Override public ArtMeshId drawableAId() {
            return host.guarded(generation, delegate::drawableAId);
        }
        @Override public ArtMeshId drawableBId() {
            return host.guarded(generation, delegate::drawableBId);
        }
        @Override public List<dev.turboism.sdk.cubism.id.ParameterId> parameterIds() {
            return host.guarded(generation, delegate::parameterIds);
        }
        @Override public int drawableA() { return host.guarded(generation, delegate::drawableA); }
        @Override public int drawableB() { return host.guarded(generation, delegate::drawableB); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() {
            return new SessionIntSequence(host, generation, host.guarded(generation, delegate::parameters));
        }
        @Override public String name() { return host.guarded(generation, delegate::name); }
        @Override public float intensity() { return host.guarded(generation, delegate::intensity); }
        @Override public void setName(final String name) {
            host.guardedVoid(generation, () -> delegate.setName(name));
        }
        @Override public void setId(final GlueId id) {
            host.guardedVoid(generation, () -> delegate.setId(id));
        }
        @Override public void setIntensity(final float intensity) {
            host.guardedVoid(generation, () -> delegate.setIntensity(intensity));
        }
        @Override public void setDrawableA(final ArtMeshId id) {
            host.guardedVoid(generation, () -> delegate.setDrawableA(id));
        }
        @Override public void setDrawableB(final ArtMeshId id) {
            host.guardedVoid(generation, () -> delegate.setDrawableB(id));
        }
    }
