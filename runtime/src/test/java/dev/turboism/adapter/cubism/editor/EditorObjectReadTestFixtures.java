package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/** Shared native object graph and failure injection for editor read/write contracts. */
public final class EditorObjectReadTestFixtures {
    private EditorObjectReadTestFixtures() {}

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public CompletePack completePack() {
            return document.pack;
        }
    }

    public interface Listener {
        void changed(Object ignored);
    }

    public static class Undo {
        final String target;

        Undo(final ObjectSource source) {
            target = source.id.value;
        }

        public boolean addListener(final Listener listener) {
            return true;
        }
    }

    public static final class GroupUndo {
        final List<String> undoTargets = new java.util.ArrayList<>();
        int undoAddCount;

        public boolean add(final Undo undo, final boolean merge) {
            if (undo == null) return false;
            undoAddCount++;
            undoTargets.add(undo.target);
            return true;
        }
    }

    public static final class EditMode {
        final java.util.List<GroupUndo> edits = new java.util.ArrayList<>();

        public GroupUndo begin(final String action) {
            final GroupUndo value = new GroupUndo();
            edits.add(value);
            return value;
        }

        public void end(final boolean aborted, final Object ignored) {
            if (aborted && !edits.isEmpty()) edits.remove(edits.size() - 1);
        }
    }

    public static final class CompletePack {
        int partRefreshCount;
        int deformerRefreshCount;
        int parameterRefreshCount;
        int repaintCount;

        public void updateParts(final boolean force) {
            partRefreshCount++;
        }

        public void updateDeformers(final boolean force) {
            deformerRefreshCount++;
        }

        public void updateParameter(final boolean force) {
            parameterRefreshCount++;
        }

        public void repaint(final boolean force) {
            repaintCount++;
        }
    }

    public static final class Handler {
        private final ObjectSource source;

        Handler(final ObjectSource source) {
            this.source = source;
        }

        public Undo undo(final String action) {
            return source.failures.rejectUndo() ? null : new Undo(source);
        }
    }

    public static final class Document {
        final ModelSource source;
        final EditMode editMode = new EditMode();
        final CompletePack pack = new CompletePack();
        boolean dirty;

        Document(final ModelSource source) {
            this.source = source;
        }

        public ModelSource modelSource() {
            return source;
        }

        public GridHistoryManager undoManager() {
            return new GridHistoryManager();
        }

        public EditMode editMode() {
            return editMode;
        }

        public void markDirty() {
            dirty = true;
        }
    }

    public static final class Id {
        final String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public Id guid() {
            return this;
        }
    }

    public static final class Failures {
        private int call;
        private int failAt = Integer.MAX_VALUE;
        final List<String> setterEvents = new java.util.ArrayList<>();

        void failOn(final int value) {
            call = 0;
            failAt = value;
        }

        void reset() {
            call = 0;
            failAt = Integer.MAX_VALUE;
            setterEvents.clear();
            undoAdmissionCount = 0;
            rejectUndoAt = Integer.MAX_VALUE;
        }

        void setter() {
            setter("generic");
        }

        void setter(final String event) {
            setterEvents.add(event);
            call++;
            if (call == failAt) {
                failAt = Integer.MAX_VALUE;
                throw new IllegalStateException("injected host setter failure");
            }
        }

        private int undoAdmissionCount;
        private int rejectUndoAt = Integer.MAX_VALUE;

        void rejectUndoAdmission(final int value) {
            undoAdmissionCount = 0;
            rejectUndoAt = value;
        }

        boolean rejectUndo() {
            undoAdmissionCount++;
            if (undoAdmissionCount == rejectUndoAt) {
                rejectUndoAt = Integer.MAX_VALUE;
                return true;
            }
            return false;
        }
    }

    public static final class KeyformBinding {
        Id parameterId = new Id("ParamAngleX");
        List<Float> keys = new java.util.ArrayList<>(List.of(-30F, 0F, 30F));

        KeyformBinding() {
            this(List.of(-30.0F, 0.0F, 30.0F));
        }

        KeyformBinding(final List<Float> keys) {
            this.keys = new java.util.ArrayList<>(keys);
        }

        public Id parameterId() {
            return parameterId;
        }

        public Id parameterGuid() {
            return parameterId;
        }

        public List<Float> keys() {
            return keys;
        }
    }

    public static final class KeyformGrid {
        final List<KeyformBinding> bindings = new java.util.ArrayList<>(List.of(new KeyformBinding()));

        public List<KeyformBinding> bindings() {
            return bindings;
        }

        public void addKey(final float value, final Id parameterGuid) {
            KeyformBinding binding = binding(parameterGuid);
            if (binding == null) {
                binding = new KeyformBinding(List.of());
                bindings.add(binding);
            }
            binding.keys.add(value);
            binding.keys.sort(Float::compare);
        }

        public void removeKey(final float value, final Id parameterGuid) {
            final KeyformBinding binding = binding(parameterGuid);
            if (binding != null) binding.keys.remove(Float.valueOf(value));
        }

        public void removeAllKey(final ParameterSet ignored, final Id parameterGuid) {
            bindings.removeIf(value -> value.parameterId.value.equals(parameterGuid.value));
        }

        public void rearrange(final Id parameterGuid, final List<Float> before, final List<Float> after) {
            final KeyformBinding binding = binding(parameterGuid);
            if (binding == null || !binding.keys.equals(before)) throw new IllegalStateException("stale key list");
            binding.keys.clear();
            binding.keys.addAll(after);
        }

        public KeyformBinding findBinding(final Id parameterGuid) {
            return bindings.stream()
                    .filter(value -> value.parameterId.value.equals(parameterGuid.value))
                    .findFirst()
                    .orElse(null);
        }

        public void reverse(final Id parameterGuid) {
            final KeyformBinding binding = findBinding(parameterGuid);
            final java.util.ArrayList<Float> reversed = new java.util.ArrayList<>(binding.keys);
            java.util.Collections.reverse(reversed);
            binding.keys = reversed;
        }

        public void change(final Id from, final Id to) {
            final KeyformBinding binding = findBinding(from);
            binding.parameterId = to;
        }

        private KeyformBinding binding(final Id parameterGuid) {
            return bindings.stream()
                    .filter(value -> value.parameterId.value.equals(parameterGuid.value))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static final class MorphTargetSet {
        final List<HostMorphTarget> targets = new java.util.ArrayList<>();

        public List<HostMorphTarget> morphTargets() {
            return targets;
        }
    }

    public static final class HostMorphTarget {
        final Id parameterGuid;
        final Float keyValue;

        HostMorphTarget(final String parameterId, final float keyValue) {
            this.parameterGuid = new Id(parameterId);
            this.keyValue = keyValue;
        }

        public Id parameterGuid() {
            return parameterGuid;
        }

        public Float keyValue() {
            return keyValue;
        }

        public Id keyformGuid() {
            return new Id("keyform-" + parameterGuid.value);
        }
    }

    public static final class ParameterSourceSet {
        final java.util.Map<String, ParameterSource> byGuid = new java.util.HashMap<>();
        final java.util.Map<String, ParameterSource> byId = new java.util.HashMap<>();

        public ParameterSource get(final Id guid) {
            return byGuid.get(guid.value);
        }

        public ParameterSource getById(final Id id) {
            return byId.get(id.value);
        }

        void register(final ParameterSource source) {
            byGuid.put(source.guid.value, source);
            byId.put(source.guid.value, source);
        }

        void clear() {
            byGuid.clear();
            byId.clear();
        }
    }

    public static class ObjectSource {
        final Id id;
        final String localName;
        final Handler handler;
        final Failures failures;
        final KeyformGrid keyformGrid = new KeyformGrid();
        final MorphTargetSet morphTargetSet = new MorphTargetSet();
        PartSource parent;
        ObjectSource targetDeformer;
        boolean visible = true;
        boolean locked;

        ObjectSource(final String id, final String localName, final Failures failures) {
            this.id = new Id(id);
            this.localName = localName;
            this.failures = failures;
            this.handler = new Handler(this);
        }

        public Id id() {
            return id;
        }

        public String localName() {
            return localName;
        }

        public PartSource parent() {
            return parent;
        }

        public ObjectSource targetDeformerSource() {
            return targetDeformer;
        }

        public boolean visible() {
            return visible;
        }

        public void setVisible(final boolean value) {
            failures.setter();
            visible = value;
        }

        public boolean locked() {
            return locked;
        }

        public void setLocked(final boolean value) {
            failures.setter();
            locked = value;
        }

        public boolean visibleInHierarchy() {
            return visible;
        }

        public boolean lockedInHierarchy() {
            return locked;
        }

        public Handler handler() {
            return handler;
        }

        public KeyformGrid keyformGrid() {
            return keyformGrid;
        }

        public MorphTargetSet morphTargetSet() {
            return morphTargetSet;
        }
    }

    public static class Form {
        float opacity;

        Form(final float opacity) {
            this.opacity = opacity;
        }

        public float opacity() {
            return opacity;
        }

        public void setOpacity(final float value) {
            opacity = value;
        }

        public int drawOrder() {
            return 0;
        }
    }

    public static final class ArtMeshSource extends ObjectSource {
        final List<Form> forms = new java.util.ArrayList<>();

        public List<Form> keyforms() {
            return forms;
        }

        final Id guid;
        final boolean culling;
        ClipGuidList clipGuids = new ClipGuidList();
        boolean invertedMask;
        float[] sourcePositions = new float[] {0, 0, 1, 0, 0, 1};
        float[] sourceUvs = new float[] {0, 0, 1, 0, 0, 1};
        int[] sourceIndices = new int[] {0, 1, 2};

        ArtMeshSource(final String id, final String name, final boolean culling, final Failures failures) {
            super(id, name, failures);
            this.guid = new Id("guid:" + id);
            this.culling = culling;
            this.invertedMask = "ArtMeshFace".equals(id);
        }

        public Id guid() {
            return guid;
        }

        public ClipGuidList clipGuids() {
            return clipGuids;
        }

        public void setClipGuidList(final ClipGuidList values) {
            failures.setter("clip:" + id.value);
            clipGuids = new ClipGuidList(values);
        }

        public float[] positions() {
            return sourcePositions.clone();
        }

        public void setPositions(final float[] values) {
            failures.setter();
            sourcePositions = values.clone();
        }

        public float[] uvs() {
            return sourceUvs.clone();
        }

        public void setUvs(final float[] values) {
            failures.setter();
            sourceUvs = values.clone();
        }

        public int[] indices() {
            return sourceIndices.clone();
        }

        public void setIndices(final int[] values) {
            failures.setter();
            sourceIndices = values.clone();
        }

        public boolean culling() {
            return culling;
        }

        public String userData() {
            return "ArtMeshFace".equals(id.value) ? "face" : "";
        }

        public boolean invertedMask() {
            return invertedMask;
        }

        public void setInvertClippingMask(final boolean value) {
            failures.setter("invert:" + id.value);
            invertedMask = value;
        }

        Object textureInputExtension;

        public Object textureInputExtension() {
            return textureInputExtension;
        }
    }

    /** {@code CTextureManager} stand-in: flattened model-image table in atlas order. */
    public static final class TexManager {
        final List<TexImage> all = new java.util.ArrayList<>();

        public List<TexImage> allModelImages() {
            return java.util.List.copyOf(all);
        }
    }
    /** {@code CModelImage} stand-in. */
    public static final class TexImage {
        final Id guid;

        TexImage(final String guid) {
            this.guid = new Id(guid);
        }

        public Id guid() {
            return guid;
        }
    }
    /** {@code CTextureInputExtension} stand-in. */
    public static final class TexExt {
        final java.util.List<Object> inputs = new java.util.ArrayList<>();
        Object current;

        public Object current() {
            return current;
        }
    }
    /** {@code CTextureInput_ModelImage} stand-in. */
    public static final class TexInput {
        final TexImage modelImage;

        TexInput(final TexImage modelImage) {
            this.modelImage = modelImage;
        }

        public TexImage modelImageOrNull() {
            return modelImage;
        }
    }

    public static final class ClipGuidList extends java.util.ArrayList<Id> {
        public ClipGuidList() {}

        public ClipGuidList(final java.util.Collection<? extends Id> values) {
            super(values);
        }
    }

    public static final class PartSource {
        final Id id = new Id("PartFace");

        public Id id() {
            return id;
        }
    }

    public static final class GlueSource extends ObjectSource {
        final ArtMeshSource targetA;
        final ArtMeshSource targetB;

        GlueSource(final ArtMeshSource targetA, final ArtMeshSource targetB, final Failures failures) {
            super("GlueFace", "Face Glue", failures);
            this.targetA = targetA;
            this.targetB = targetB;
        }

        public ArtMeshSource targetA() {
            return targetA;
        }

        public ArtMeshSource targetB() {
            return targetB;
        }
    }

    public static final class ArtMeshForm extends Form {
        final Failures failures;
        float[] positions = new float[] {0, 0, 1, 0, 0, 1};

        ArtMeshForm(final Failures failures) {
            super(0.75F);
            this.failures = failures;
        }

        @Override
        public int drawOrder() {
            return 7;
        }

        public float[] positions() {
            return positions.clone();
        }

        public void setPositions(final float[] values) {
            failures.setter();
            positions = values.clone();
        }

        public Id coordType() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return new Id("Canvas");
        }
    }

    public static final class ArtMesh {
        final ArtMeshSource source;
        final ArtMeshForm form;
        final ArtMeshForm calculated = new ArtMeshForm(new Failures());

        public ArtMeshForm calculatedForm() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return calculated;
        }

        ArtMesh(final ArtMeshSource source) {
            this.source = source;
            this.form = new ArtMeshForm(source.failures);
            source.forms.add(form);
        }

        public ArtMeshSource source() {
            return source;
        }

        public ArtMeshForm currentForm() {
            return form;
        }
    }

    public static class Deformer {
        final ObjectSource source;
        final Form form;

        Deformer(final ObjectSource source, final Form form) {
            this.source = source;
            this.form = form;
        }

        public ObjectSource source() {
            return source;
        }

        public Form currentForm() {
            return form;
        }
    }

    public static final class WarpSource extends ObjectSource {
        final List<Form> forms = new java.util.ArrayList<>();

        public List<Form> keyforms() {
            return forms;
        }

        int row = 2;
        int col = 3;
        boolean quadTransform;

        WarpSource(final Failures failures) {
            super("WarpFace", "Face Warp", failures);
        }

        public int row() {
            return row;
        }

        public void setRow(final int value) {
            failures.setter();
            row = value;
        }

        public int col() {
            return col;
        }

        public void setCol(final int value) {
            failures.setter();
            col = value;
        }

        public boolean quadTransform() {
            return quadTransform;
        }

        public void setQuadTransform(final boolean value) {
            failures.setter();
            quadTransform = value;
        }
    }

    public static final class WarpForm extends Form {
        final Failures failures;
        float[] positions;

        WarpForm(final Failures failures) {
            super(0.8F);
            this.failures = failures;
            positions = initialPositions();
        }

        private static float[] initialPositions() {
            final float[] values = new float[24];
            for (int i = 0; i < 12; i++) {
                values[i * 2] = i;
                values[i * 2 + 1] = i + 0.5F;
            }
            return values;
        }

        public float[] positions() {
            return positions.clone();
        }

        public void setPositions(final float[] values) {
            failures.setter();
            positions = values.clone();
        }
    }

    public static final class Warp extends Deformer {
        Warp(final WarpSource source) {
            super(source, new WarpForm(source.failures));
            source.forms.add(form);
        }
    }

    public static final class RotationSource extends ObjectSource {
        float baseAngle = 30.0F;

        RotationSource(final Failures failures) {
            super("RotationHead", "Head Rotation", failures);
        }

        public float baseAngle() {
            return baseAngle;
        }

        public void setBaseAngle(final float value) {
            failures.setter();
            baseAngle = value;
        }
    }

    public static final class RotationForm extends Form {
        final Failures failures;
        float angle = 15.0F;
        float originX = 2.0F;
        float originY = 3.0F;
        float scale = 1.25F;
        boolean reflectX = true;
        boolean reflectY;

        RotationForm(final Failures failures) {
            super(0.9F);
            this.failures = failures;
        }

        public float angle() {
            return angle;
        }

        public void setAngle(final float value) {
            failures.setter();
            angle = value;
        }

        public float originX() {
            return originX;
        }

        public void setOriginX(final float value) {
            failures.setter();
            originX = value;
        }

        public float originY() {
            return originY;
        }

        public void setOriginY(final float value) {
            failures.setter();
            originY = value;
        }

        public float scale() {
            return scale;
        }

        public void setScale(final float value) {
            failures.setter();
            scale = value;
        }

        public boolean reflectX() {
            return reflectX;
        }

        public void setReflectX(final boolean value) {
            failures.setter();
            reflectX = value;
        }

        public boolean reflectY() {
            return reflectY;
        }

        public void setReflectY(final boolean value) {
            failures.setter();
            reflectY = value;
        }
    }

    public static final class Rotation extends Deformer {
        Rotation(final RotationSource source) {
            super(source, new RotationForm(source.failures));
        }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final ParameterSourceSet sourceSet = new ParameterSourceSet();
        final List<PartSource> partSources = new java.util.ArrayList<>();
        final List<ArtMeshSource> artMeshSources = new java.util.ArrayList<>();
        final List<ObjectSource> deformerSources = new java.util.ArrayList<>();
        final List<GlueSource> glueSources = new java.util.ArrayList<>();
        final TexManager textureManager = new TexManager();
        Model model;
        int updateCount;

        public Id guid() {
            return guid;
        }

        public Model currentInstance() {
            return model;
        }

        public List<PartSource> allParts() {
            return partSources;
        }

        public List<ArtMeshSource> allArtMeshes() {
            return artMeshSources;
        }

        public List<ObjectSource> allDeformers() {
            return deformerSources;
        }

        public boolean defaultKeyformLocked() {
            return false;
        }

        public List<GlueSource> allGlues() {
            return glueSources;
        }

        public TexManager textureManager() {
            return textureManager;
        }

        public ParameterSourceSet parameterSourceSet() {
            return sourceSet;
        }

        public void updateInstances() {
            updateCount++;
        }
    }

    public static final class Model {
        final List<ArtMesh> artMeshes = new java.util.ArrayList<>();
        final List<Deformer> deformers = new java.util.ArrayList<>();
        final ParameterSet parameterSet = new ParameterSet();
        final ParameterSet consumed = new ParameterSet();

        public ParameterSet consumed() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return consumed;
        }

        public long passTime() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return 1234;
        }

        public List<ArtMesh> allArtMeshes() {
            return artMeshes;
        }

        public List<Deformer> allDeformers() {
            return deformers;
        }

        public ParameterSet parameterSet() {
            return parameterSet;
        }
    }

    public static final class ParameterSet {
        final List<Parameter> parameters =
                new java.util.ArrayList<>(List.of(new Parameter("ParamAngleX"), new Parameter("ParamAngleY")));

        public List<Parameter> parameters() {
            return parameters;
        }

        public int version() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return 7;
        }
    }

    public static final class ParameterSource {
        final Id guid;
        boolean combined;

        ParameterSource(String id) {
            guid = new Id(id);
        }

        public Id guid() {
            return guid;
        }

        public Id id() {
            return guid;
        }

        public boolean combined() {
            return combined;
        }

        public float minimum() {
            return -30.0F;
        }

        public float maximum() {
            return 30.0F;
        }
    }

    public static final class Parameter {
        final Id id;
        final ParameterSource source;

        Parameter(String id) {
            this.id = new Id(id);
            this.source = new ParameterSource(id);
        }

        public Id id() {
            return id;
        }

        public ParameterSource source() {
            return source;
        }

        public float value() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return 0;
        }
    }

    static final class Fixture {
        final Failures failures = new Failures();
        final ModelSource source = new ModelSource();
        final Document document = new Document(source);

        Fixture() {
            install();
        }

        void install() {
            failures.reset();
            final PartSource partSource = new PartSource();
            final ArtMeshSource meshSource = new ArtMeshSource("ArtMeshFace", "Face Mesh", true, failures);
            final ArtMeshSource maskSource = new ArtMeshSource("ArtMeshMask", "Mask Mesh", false, failures);
            final WarpSource warpSource = new WarpSource(failures);
            final RotationSource rotationSource = new RotationSource(failures);
            meshSource.parent = partSource;
            meshSource.targetDeformer = warpSource;
            meshSource.clipGuids.add(maskSource.guid);
            maskSource.keyformGrid.bindings.clear();
            warpSource.parent = partSource;
            warpSource.targetDeformer = rotationSource;
            source.partSources.clear();
            source.artMeshSources.clear();
            source.deformerSources.clear();
            source.glueSources.clear();
            source.partSources.add(partSource);
            source.artMeshSources.add(meshSource);
            source.artMeshSources.add(maskSource);
            source.deformerSources.add(warpSource);
            source.deformerSources.add(rotationSource);
            source.glueSources.add(new GlueSource(meshSource, maskSource, failures));
            source.model = new Model();
            source.sourceSet.clear();
            for (final Parameter parameter : source.model.parameterSet.parameters) {
                source.sourceSet.register(parameter.source);
            }
            source.model.artMeshes.add(new ArtMesh(meshSource));
            source.model.artMeshes.add(new ArtMesh(maskSource));
            source.model.deformers.add(new Warp(warpSource));
            source.model.deformers.add(new Rotation(rotationSource));
        }

        void replaceAllWithSameIds() {
            install();
        }

        void resetPublishedEffects() {
            failures.reset();
            document.editMode.edits.clear();
            document.dirty = false;
            source.updateCount = 0;
            document.pack.partRefreshCount = 0;
            document.pack.deformerRefreshCount = 0;
            document.pack.repaintCount = 0;
        }

        ArtMeshSource meshSource() {
            return source.artMeshSources.get(0);
        }

        ArtMeshSource maskSource() {
            return source.artMeshSources.get(1);
        }

        void addParameter(final String id) {
            final Parameter parameter = new Parameter(id);
            source.model.parameterSet.parameters.add(parameter);
            source.sourceSet.register(parameter.source);
        }

        ArtMesh mesh() {
            return source.model.artMeshes.get(0);
        }

        Warp warp() {
            return (Warp) source.model.deformers.get(0);
        }

        RotationSource rotationSource() {
            return (RotationSource) source.deformerSources.get(1);
        }
    }

    public static final class GridHistoryManager {
        public List<GridHistoryEntry> entries() {
            return List.of();
        }

        public int position() {
            return 0;
        }

        public boolean canUndo() {
            return false;
        }

        public boolean canRedo() {
            return false;
        }
    }

    public static final class GridHistoryEntry {
        public String label() {
            return "grid";
        }

        public boolean significant() {
            return true;
        }
    }
}
