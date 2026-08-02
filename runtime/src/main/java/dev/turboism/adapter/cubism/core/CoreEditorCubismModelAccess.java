package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Canvas;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runtime-internal Editor/Core join. Editor owns authoring; Core supplies evaluated reads. */
public final class CoreEditorCubismModelAccess implements CubismModelAccess {
    private final CubismModelAccess editorAccess;
    private final CubismModelAccess coreAccess;

    public CoreEditorCubismModelAccess(
        final CubismModelAccess editorAccess,
        final CubismModelAccess coreAccess
    ) {
        this.editorAccess = Objects.requireNonNull(editorAccess, "editorAccess");
        this.coreAccess = Objects.requireNonNull(coreAccess, "coreAccess");
    }

    @Override
    public CubismModel active() {
        return new JoinedModel(editorAccess.active(), coreAccess.active());
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("Core/Editor Cubism model reference is stale or no longer joinable.");
    }

    private static IllegalStateException mismatch(final String message) {
        return new IllegalStateException("Core/Editor Cubism model join rejected: " + message);
    }

    private static <K, E, C> Map<K, Pair<E, C>> join(
        final List<E> editor,
        final List<C> core,
        final Function<E, K> editorKey,
        final Function<C, K> coreKey,
        final String family
    ) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(core, "core");
        final Map<K, E> editorById = unique(editor, editorKey, "Editor " + family);
        final Map<K, C> coreById = unique(core, coreKey, "Core " + family);
        if (!editorById.keySet().equals(coreById.keySet())) {
            throw mismatch(family + " identifiers are not identical.");
        }
        final Map<K, Pair<E, C>> result = new LinkedHashMap<>();
        for (K key : editorById.keySet()) {
            result.put(key, new Pair<>(editorById.get(key), coreById.get(key)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static <K, V> Map<K, V> unique(
        final List<V> values,
        final Function<V, K> key,
        final String family
    ) {
        final Map<K, V> result = new LinkedHashMap<>();
        for (V value : values) {
            if (value == null) throw mismatch(family + " contains null.");
            final K id = Objects.requireNonNull(key.apply(value), family + " identifier");
            if (result.put(id, value) != null) throw mismatch(family + " identifiers are not unique.");
        }
        return result;
    }

    private static void sameId(final ModelId editorId, final ModelId coreId) {
        if (editorId == null || coreId == null || !editorId.equals(coreId)) {
            throw mismatch("model identifiers are not identical.");
        }
    }

    private record Pair<E, C>(E editor, C core) { }

    private final class JoinedModel implements CubismModel {
        private final CubismModel editor;
        private final CubismModel core;
        private final ModelId id;
        private final Map<ParameterId, Pair<Parameter, Parameter>> parameters;
        private final Map<PartId, Pair<Part, Part>> parts;
        private final Map<ArtMeshId, Pair<Drawable, Drawable>> drawables;
        private final Map<DeformerId, Pair<Deformer, Deformer>> deformers;
        private final Map<GlueId, Glue> glues;

        private JoinedModel(final CubismModel editor, final CubismModel core) {
            this.editor = Objects.requireNonNull(editor, "editor");
            this.core = Objects.requireNonNull(core, "core");
            sameId(editor.id(), core.id());
            this.id = editor.id();
            this.parameters = join(editor.parameters().all(), core.parameters().all(), Parameter::id, Parameter::id, "Parameter");
            this.parts = join(editor.parts().all(), core.parts().all(), Part::id, Part::id, "Part");
            this.drawables = join(editor.drawables().all(), core.drawables().all(), Drawable::id, Drawable::id, "Drawable");
            this.deformers = join(editor.deformers().all(), core.deformers().all(), Deformer::id, Deformer::id, "Deformer");
            this.glues = keyed(core.glues().all(), Glue::id, "Glue");
        }

        private void current() {
            try {
                sameId(id, editor.id());
                sameId(id, core.id());
                join(editor.parameters().all(), core.parameters().all(), Parameter::id, Parameter::id, "Parameter");
                join(editor.parts().all(), core.parts().all(), Part::id, Part::id, "Part");
                join(editor.drawables().all(), core.drawables().all(), Drawable::id, Drawable::id, "Drawable");
                join(editor.deformers().all(), core.deformers().all(), Deformer::id, Deformer::id, "Deformer");
                keyed(core.glues().all(), Glue::id, "Glue");
            } catch (RuntimeException failure) {
                if (failure instanceof IllegalStateException) throw failure;
                throw stale();
            }
        }

        @Override public ModelId id() { current(); return id; }
        @Override public boolean defaultKeyformLocked() { current(); return editor.defaultKeyformLocked(); }
        @Override public void setDefaultKeyformLocked(final boolean value) { current(); editor.setDefaultKeyformLocked(value); }
        @Override public Canvas canvas() { current(); return new JoinedCanvas(this, core.canvas()); }
        @Override public Parameters parameters() { current(); return new JoinedParameters(); }
        @Override public ParameterGroups parameterGroups() { current(); return editor.parameterGroups(); }
        @Override public Parts parts() { current(); return new JoinedParts(); }
        @Override public Drawables drawables() { current(); return new JoinedDrawables(); }
        @Override public Deformers deformers() { current(); return new JoinedDeformers(); }
        @Override public WarpDeformers warpDeformers() { current(); return new JoinedWarpDeformers(editor.warpDeformers()); }
        @Override public RotationDeformers rotationDeformers() { current(); return new JoinedRotationDeformers(editor.rotationDeformers()); }
        @Override public Glues glues() { current(); return new JoinedGlues(); }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(final ParameterId id) { current(); return editor.parameterBindings(id); }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() { current(); return editor.parameterBindingBatch(); }
        @Override public void update() { current(); editor.update(); }

        private final class JoinedParameters implements Parameters {
            @Override public List<Parameter> all() {
                current();
                return parameters.values().stream().map(pair -> (Parameter) new JoinedParameter(pair.editor(), pair.core())).toList();
            }
            @Override public Parameter find(final ParameterId id) {
                current();
                final Pair<Parameter, Parameter> pair = parameters.get(Objects.requireNonNull(id, "id"));
                if (pair == null) throw new java.util.NoSuchElementException("Cubism parameter is absent: " + id.value());
                return new JoinedParameter(pair.editor(), pair.core());
            }
        }

        private final class JoinedParts implements Parts {
            @Override public List<Part> all() { current(); return parts.values().stream().map(pair -> (Part) new JoinedPart(pair.editor(), pair.core())).toList(); }
            @Override public Part find(final PartId id) {
                current();
                final Pair<Part, Part> pair = parts.get(Objects.requireNonNull(id, "id"));
                if (pair == null) throw new java.util.NoSuchElementException("Cubism Part is absent: " + id.value());
                return new JoinedPart(pair.editor(), pair.core());
            }
        }

        private final class JoinedDrawables implements Drawables {
            @Override public List<Drawable> all() { current(); return drawables.values().stream().map(pair -> (Drawable) new JoinedDrawable(pair.editor(), pair.core())).toList(); }
            @Override public Drawable find(final ArtMeshId id) {
                current();
                final Pair<Drawable, Drawable> pair = drawables.get(Objects.requireNonNull(id, "id"));
                if (pair == null) throw new java.util.NoSuchElementException("Cubism ArtMesh is absent: " + id.value());
                return new JoinedDrawable(pair.editor(), pair.core());
            }
        }

        private final class JoinedDeformers implements Deformers {
            @Override public List<Deformer> all() { current(); return deformers.values().stream().map(pair -> (Deformer) new JoinedDeformer(pair.editor(), pair.core())).toList(); }
            @Override public Deformer find(final DeformerId id) {
                current();
                final Pair<Deformer, Deformer> pair = deformers.get(Objects.requireNonNull(id, "id"));
                if (pair == null) throw new java.util.NoSuchElementException("Cubism Deformer is absent: " + id.value());
                return new JoinedDeformer(pair.editor(), pair.core());
            }
        }

        private final class JoinedGlues implements Glues {
            @Override public List<Glue> all() { current(); return glues.values().stream().toList(); }
            @Override public Glue find(final GlueId id) {
                current();
                final Glue glue = glues.get(Objects.requireNonNull(id, "id"));
                if (glue == null) throw new java.util.NoSuchElementException("Cubism Glue is absent: " + id.value());
                return glue;
            }
        }

        private final class JoinedWarpDeformers implements WarpDeformers {
            private final WarpDeformers editorDeformers;
            private JoinedWarpDeformers(final WarpDeformers editorDeformers) { this.editorDeformers = editorDeformers; }
            @Override public List<WarpDeformer> all() { current(); return editorDeformers.all().stream().map(value -> (WarpDeformer) new JoinedWarp(value, coreDeformer(value.id()))).toList(); }
            @Override public WarpDeformer find(final DeformerId id) { current(); return new JoinedWarp(editorDeformers.find(id), coreDeformer(id)); }
        }

        private final class JoinedRotationDeformers implements RotationDeformers {
            private final RotationDeformers editorDeformers;
            private JoinedRotationDeformers(final RotationDeformers editorDeformers) { this.editorDeformers = editorDeformers; }
            @Override public List<RotationDeformer> all() { current(); return editorDeformers.all().stream().map(value -> (RotationDeformer) new JoinedRotation(value, coreDeformer(value.id()))).toList(); }
            @Override public RotationDeformer find(final DeformerId id) { current(); return new JoinedRotation(editorDeformers.find(id), coreDeformer(id)); }
        }

        private Deformer coreDeformer(final DeformerId id) {
            final Pair<Deformer, Deformer> pair = deformers.get(id);
            if (pair == null) throw new java.util.NoSuchElementException("Cubism Deformer is absent: " + id.value());
            return pair.core();
        }

        private <K, V> Map<K, V> keyed(final List<V> values, final Function<V, K> key, final String family) {
            return CoreEditorCubismModelAccess.keyed(values, key, family);
        }
    }

    private static <K, V> Map<K, V> keyed(final List<V> values, final Function<V, K> key, final String family) {
        final Map<K, V> result = new LinkedHashMap<>();
        for (V value : values) {
            if (value == null || result.put(Objects.requireNonNull(key.apply(value), family + " identifier"), value) != null) {
                throw mismatch(family + " identifiers are not unique.");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private final class JoinedParameter implements Parameter {
        private final Parameter editor;
        private final Parameter core;
        private JoinedParameter(final Parameter editor, final Parameter core) { this.editor = editor; this.core = core; }
        private void current() { editor.id(); core.id(); }
        @Override public ParameterId id() { current(); return editor.id(); }
        @Override public Optional<String> name() { current(); return editor.name(); }
        @Override public ParameterType type() { current(); return editor.type(); }
        @Override public Optional<Boolean> repeat() { current(); return editor.repeat(); }
        @Override public Optional<Boolean> combined() { current(); return editor.combined(); }
        @Override public Optional<ParameterId> combinedWith() { current(); return editor.combinedWith(); }
        @Override public List<ParameterBinding> getParameterBindings() { current(); return editor.getParameterBindings(); }
        @Override public void combineWith(final ParameterId id) { current(); editor.combineWith(id); }
        @Override public void uncombine() { current(); editor.uncombine(); }
        @Override public float getValue() { current(); return core.getValue(); }
        @Override public float getMinimumValue() { current(); return editor.getMinimumValue(); }
        @Override public float getMaximumValue() { current(); return editor.getMaximumValue(); }
        @Override public float getDefaultValue() { current(); return editor.getDefaultValue(); }
        @Override public void setValue(final float value) { current(); editor.setValue(value); }
        @Override public void updateDefinition(final ParameterDefinition definition) { current(); editor.updateDefinition(definition); }
    }

    private final class JoinedPart implements Part {
        private final Part editor;
        private final Part core;
        private JoinedPart(final Part editor, final Part core) { this.editor = editor; this.core = core; }
        private void current() { editor.id(); core.id(); }
        @Override public PartId id() { current(); return editor.id(); }
        @Override public String name() { current(); return editor.name(); }
        @Override public void setName(final String name) { current(); editor.setName(name); }
        @Override public float getOpacity() { current(); return core.getOpacity(); }
        @Override public int parentIndex() { current(); return core.parentIndex(); }
        @Override public void setOpacity(final float value) { current(); editor.setOpacity(value); }
    }

    private final class JoinedDrawable implements Drawable {
        private final Drawable editor;
        private final Drawable core;
        private JoinedDrawable(final Drawable editor, final Drawable core) { this.editor = editor; this.core = core; }
        private void current() { editor.id(); core.id(); }
        @Override public ArtMeshId id() { current(); return editor.id(); }
        @Override public String name() { current(); return editor.name(); }
        @Override public boolean visible() { current(); return editor.visible(); }
        @Override public void setVisible(final boolean value) { current(); editor.setVisible(value); }
        @Override public boolean locked() { current(); return editor.locked(); }
        @Override public void setLocked(final boolean value) { current(); editor.setLocked(value); }
        @Override public boolean visibleInHierarchy() { current(); return editor.visibleInHierarchy(); }
        @Override public boolean lockedInHierarchy() { current(); return editor.lockedInHierarchy(); }
        @Override public byte constantFlag() { current(); return core.constantFlag(); }
        @Override public byte dynamicFlag() { current(); return core.dynamicFlag(); }
        @Override public BlendMode blendMode() { current(); return core.blendMode(); }
        @Override public int textureIndex() { current(); return core.textureIndex(); }
        @Override public int drawOrder() { current(); return core.drawOrder(); }
        @Override public int renderOrder() { current(); return core.renderOrder(); }
        @Override public float getOpacity() { current(); return core.getOpacity(); }
        @Override public void setOpacity(final float value) { current(); editor.setOpacity(value); }
        @Override public dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry() { current(); return editor.geometry(); }
        @Override public void replaceGeometry(final dev.turboism.sdk.cubism.model.ArtMeshGeometry value) { current(); editor.replaceGeometry(value); }
        @Override public IntSequence masks() { current(); return new GuardedInts(() -> core.masks(), this::current); }
        @Override public boolean invertedMask() { current(); return editor.invertedMask(); }
        @Override public boolean culling() { current(); return editor.culling(); }
        @Override public String userData() { current(); return editor.userData(); }
        @Override public FloatSequence vertexPositions() { current(); return new GuardedFloats(() -> core.vertexPositions(), this::current); }
        @Override public FloatSequence vertexUvs() { current(); return new GuardedFloats(() -> core.vertexUvs(), this::current); }
        @Override public IntSequence indices() { current(); return new GuardedInts(() -> core.indices(), this::current); }
        @Override public Color multiplyColor() { current(); return core.multiplyColor(); }
        @Override public Color screenColor() { current(); return core.screenColor(); }
        @Override public int parentPartIndex() { current(); return core.parentPartIndex(); }
        @Override public int parentDeformerIndex() { current(); return core.parentDeformerIndex(); }
        @Override public IntSequence parameters() { current(); return new GuardedInts(() -> core.parameters(), this::current); }
        @Override public List<ParameterBinding> getParameterBindings() { current(); return editor.getParameterBindings(); }
    }

    private class JoinedDeformer implements Deformer {
        protected final Deformer editor;
        protected final Deformer core;
        private JoinedDeformer(final Deformer editor, final Deformer core) { this.editor = editor; this.core = core; }
        protected void current() { editor.id(); core.id(); }
        @Override public DeformerId id() { current(); return editor.id(); }
        @Override public String name() { current(); return editor.name(); }
        @Override public boolean visible() { current(); return editor.visible(); }
        @Override public void setVisible(final boolean value) { current(); editor.setVisible(value); }
        @Override public boolean locked() { current(); return editor.locked(); }
        @Override public void setLocked(final boolean value) { current(); editor.setLocked(value); }
        @Override public boolean visibleInHierarchy() { current(); return editor.visibleInHierarchy(); }
        @Override public boolean lockedInHierarchy() { current(); return editor.lockedInHierarchy(); }
        @Override public float getOpacity() { current(); return editor.getOpacity(); }
        @Override public void setOpacity(final float value) { current(); editor.setOpacity(value); }
        @Override public Color multiplyColor() { current(); return core.multiplyColor(); }
        @Override public Color screenColor() { current(); return core.screenColor(); }
        @Override public int parentPartIndex() { current(); return core.parentPartIndex(); }
        @Override public int parentDeformerIndex() { current(); return core.parentDeformerIndex(); }
        @Override public IntSequence parameters() { current(); return new GuardedInts(() -> core.parameters(), this::current); }
        @Override public List<ParameterBinding> getParameterBindings() { current(); return editor.getParameterBindings(); }
    }

    private final class JoinedWarp extends JoinedDeformer implements WarpDeformer {
        private final WarpDeformer editorWarp;
        private JoinedWarp(final WarpDeformer editor, final Deformer core) { super(editor, core); this.editorWarp = editor; }
        @Override public WarpGrid grid() { current(); return editorWarp.grid(); }
        @Override public void replaceGrid(final WarpGrid grid) { current(); editorWarp.replaceGrid(grid); }
    }

    private final class JoinedRotation extends JoinedDeformer implements RotationDeformer {
        private final RotationDeformer editorRotation;
        private JoinedRotation(final RotationDeformer editor, final Deformer core) { super(editor, core); this.editorRotation = editor; }
        @Override public float baseAngle() { current(); return editorRotation.baseAngle(); }
        @Override public void setBaseAngle(final float value) { current(); editorRotation.setBaseAngle(value); }
        @Override public RotationDeformerForm form() { current(); return editorRotation.form(); }
        @Override public void replaceForm(final RotationDeformerForm form) { current(); editorRotation.replaceForm(form); }
    }

    private final class JoinedCanvas implements Canvas {
        private final JoinedModel owner;
        private final Canvas core;

        private JoinedCanvas(final JoinedModel owner, final Canvas core) {
            this.owner = owner;
            this.core = core;
        }

        private void current() { owner.current(); }

        @Override public float widthPixels() { current(); return core.widthPixels(); }
        @Override public float heightPixels() { current(); return core.heightPixels(); }
        @Override public float originXPixels() { current(); return core.originXPixels(); }
        @Override public float originYPixels() { current(); return core.originYPixels(); }
        @Override public float pixelsPerUnit() { current(); return core.pixelsPerUnit(); }
    }

    private static final class GuardedInts implements IntSequence {
        private final Supplier<IntSequence> source;
        private final Runnable current;
        private GuardedInts(final Supplier<IntSequence> source, final Runnable current) { this.source = source; this.current = current; }
        @Override public int size() { current.run(); return source.get().size(); }
        @Override public int get(final int index) { current.run(); return source.get().get(index); }
    }

    private static final class GuardedFloats implements FloatSequence {
        private final Supplier<FloatSequence> source;
        private final Runnable current;
        private GuardedFloats(final Supplier<FloatSequence> source, final Runnable current) { this.source = source; this.current = current; }
        @Override public int size() { current.run(); return source.get().size(); }
        @Override public float get(final int index) { current.run(); return source.get().get(index); }
    }
}
