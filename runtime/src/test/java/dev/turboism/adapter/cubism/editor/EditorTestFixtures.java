package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorDeformerInspectorSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared mock/fixture engine for editor adapter tests.
 * Consolidates the native Cubism Editor object, hierarchy, document, host, and transaction doubles.
 */
public final class EditorTestFixtures {

    private EditorTestFixtures() {}

    public static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    public static String type(final Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    public static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    public interface Copyable {
        Object snapshot();

        void restore(Object snapshot);
    }

    public interface UndoListener {
        void executed(Object event);
    }

    public interface Action {
        void run();
    }

    public interface UpdateAction {
        void run(Object ignored);
    }

    public static final class CopyContext {}

    public static final class SimpleUndo {
        public final Action redo;
        public final Action undo;
        public final UpdateAction updated;
        public Copyable target;
        public Object snapshot;
        public Object restored;

        public SimpleUndo(final String name, final Copyable target, final Object copyParam) {
            this.target = target;
            this.snapshot = target != null ? target.snapshot() : null;
            this.redo = () -> {
                if (this.target != null) this.target.restore(this.restored);
            };
            this.undo = () -> {
                if (this.target != null) {
                    this.restored = this.target.snapshot();
                    this.target.restore(this.snapshot);
                }
            };
            this.updated = null;
        }

        public SimpleUndo(final Action redo, final Action undo, final UpdateAction updated) {
            this.redo = redo;
            this.undo = undo;
            this.updated = updated;
        }

        public void undo() {
            if (undo != null) undo.run();
            if (updated != null) updated.run(null);
        }

        public void redo() {
            if (redo != null) redo.run();
            if (updated != null) updated.run(null);
        }
    }

    public static final class LocalSimpleUndoFactory {
        public static final LocalSimpleUndoFactory INSTANCE = new LocalSimpleUndoFactory();

        public SimpleUndo create(
                final String name,
                final boolean ignored,
                final Action redo,
                final Action undo,
                final UpdateAction updated) {
            return new SimpleUndo(redo, undo, updated);
        }
    }

    public static final class ParameterOperation {
        public int refreshes;
        public int calls;
        public float lastValue;
        public boolean fail;

        public void setValue(final ParameterSource source, final float value) {
            calls++;
            if (fail) throw new IllegalStateException("native write failed");
            lastValue = value;
        }

        public void refresh(final boolean immediate) {
            refreshes++;
        }
    }

    public static final class ParameterPaletteView {
        public final ParameterOperation operation;

        public ParameterPaletteView(final ParameterOperation operation) {
            this.operation = operation;
        }

        public ParameterOperation operation() {
            return operation;
        }
    }

    public static final class ParameterPalette {
        public final ParameterPaletteView view;

        public ParameterPalette(final ParameterPaletteView view) {
            this.view = view;
        }

        public ParameterPaletteView view() {
            return view;
        }
    }

    public static final class MainFrame {
        public final ParameterPalette palette;

        public MainFrame(final ParameterPalette palette) {
            this.palette = palette;
        }

        public ParameterPalette parameterPalette() {
            return palette;
        }
    }

    public static final class RuntimeParameter {
        final ParameterSource source;

        public RuntimeParameter(final ParameterSource source) {
            this.source = source;
        }

        public float value() {
            return 0;
        }

        public Id id() {
            return new Id(source.id.value);
        }

        public ParameterSource source() {
            return source;
        }
    }

    public static final class CParameterGroupGuid {
        public CParameterGroupGuid() {}
    }

    public static final class ParameterGroup implements Copyable {
        public static Object lastGuid;
        public final Id id;
        public final String initialName;
        public final ParameterGroupHandler groupHandler = new ParameterGroupHandler();
        public final List<Object> children = new ArrayList<>();
        public String name;
        public ParameterGroup parent;

        public List<String> ids() {
            return children.stream()
                    .filter(c -> c instanceof ParameterSource)
                    .map(c -> ((ParameterSource) c).id.value)
                    .toList();
        }

        public void remove(final Id guid) {
            children.removeIf(source ->
                    source instanceof ParameterSource ps && ps.guid.value().equals(guid.value()));
        }

        public void add(final ParameterSource source, final int index) {
            if (source.parentGroup != null) source.parentGroup.children.remove(source);
            int resolved = index < 0 || index > children.size() ? children.size() : index;
            children.add(resolved, source);
            source.parentGroup = this;
        }

        @Override
        public Object snapshot() {
            return List.copyOf(children);
        }

        @Override
        public void restore(final Object snapshot) {
            children.clear();
            if (snapshot instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof ParameterSource ps) {
                        children.add(ps);
                        ps.parentGroup = this;
                    }
                }
            }
        }

        public ParameterGroup() {
            this("group");
        }

        public ParameterGroup(final String id) {
            this.id = new Id(id);
            this.name = id;
            this.initialName = id;
        }

        public ParameterGroup(final String name, final Object guid, final Id id) {
            this.id = id;
            this.name = name;
            this.initialName = name;
            lastGuid = guid;
        }

        public Id id() {
            return id;
        }

        public Id guid() {
            return new Id("g-" + id.value);
        }

        public String name() {
            return name;
        }

        public ParameterGroupHandler handler() {
            return groupHandler;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public void setFolderOpened(final boolean ignored) {}

        public List<Object> children() {
            return children;
        }

        public ParameterGroup parent() {
            return parent;
        }
    }

    public static final class ParameterGroupHandler {
        public Undo addParameterChild(final ParameterSource source, final int index) {
            final Document doc = Host.current != null ? Host.current : Host.document;
            return doc.source.handler.addParameter(source, index);
        }

        public Undo addGroupChild(final ParameterGroup group, final int index) {
            final Document doc = Host.current != null ? Host.current : Host.document;
            return doc.source.handler.addGroup(group, index);
        }

        public Undo removeDescendant(
                final ParameterGroup group, final ParameterSet set, final boolean a, final boolean b) {
            final Document doc = Host.current != null ? Host.current : Host.document;
            return doc.source.handler.removeGroup(group);
        }
    }

    public static final class ClipGuidList extends java.util.ArrayList<Id> {
        public ClipGuidList() {}

        public ClipGuidList(final java.util.Collection<? extends Id> values) {
            super(values);
        }
    }

    public static final class TexImage {
        public final Id guid;

        public TexImage(final String guid) {
            this.guid = new Id(guid);
        }

        public Id guid() {
            return guid;
        }
    }

    public static final class TexExt {
        public final java.util.List<Object> inputs = new java.util.ArrayList<>();
        public Object current;

        public Object current() {
            return current;
        }
    }

    public static final class TexInput {
        public final TexImage modelImage;

        public TexInput(final TexImage modelImage) {
            this.modelImage = modelImage;
        }

        public TexImage modelImageOrNull() {
            return modelImage;
        }
    }

    public static final class TexManager {
        public final List<TexImage> all = new java.util.ArrayList<>();

        public List<TexImage> allModelImages() {
            return java.util.List.copyOf(all);
        }
    }

    public static final class KeyformGrid {
        public boolean targetBound;

        public Object findBinding(final HostParameterGuid ignored) {
            return targetBound ? new Object() : null;
        }
    }

    public static final class HostParameterId extends Id {
        public HostParameterId(final String value) {
            super(value);
        }

        @Override
        public String value() {
            return super.value();
        }
    }

    public static final class HostParameterGuid extends Id {
        public HostParameterGuid(final String value) {
            super(value);
        }

        @Override
        public String value() {
            return super.value();
        }
    }

    public static final class ParameterSource implements Copyable {
        final HostParameterId id;
        final HostParameterGuid guid;
        public boolean morphTarget = true;
        public float minimum = -30.0F;
        public float maximum = 30.0F;
        public String name;
        public boolean repeat;
        public boolean combined;
        public float defaultValue = 0.0F;
        public Id paramType = Id.NORMAL;
        public ParameterGroup parentGroup;

        public boolean combined() {
            return combined;
        }

        public void setCombined(final boolean value) {
            combined = value;
        }

        @Override
        public Object snapshot() {
            return Boolean.valueOf(combined);
        }

        @Override
        public void restore(final Object snapshot) {
            combined = (Boolean) snapshot;
        }

        public String name() {
            return name != null ? name : id.value;
        }

        public boolean isRepeat() {
            return repeat;
        }

        public float minValue() {
            return minimum;
        }

        public float maxValue() {
            return maximum;
        }

        public float defaultValue() {
            return defaultValue;
        }

        public Id paramType() {
            return paramType;
        }

        public void setRepeat(final boolean value) {
            repeat = value;
        }

        public ParameterGroup parentGroup() {
            return parentGroup;
        }

        public ParameterSource(final Id guid, final Id id) {
            this(id != null ? id.value() : "");
        }

        public ParameterSource(final String id) {
            this(id, -30.0F, 30.0F);
        }

        public ParameterSource(final String id, final float minimum, final float maximum) {
            this.id = new HostParameterId(id);
            this.guid = new HostParameterGuid(id);
            this.minimum = minimum;
            this.maximum = maximum;
        }

        public HostParameterId id() {
            return id;
        }

        public HostParameterGuid guid() {
            return guid;
        }

        public boolean morphTarget() {
            return morphTarget;
        }

        public float minimum() {
            return minimum;
        }

        public float maximum() {
            return maximum;
        }
    }

    public static final class ParameterSourceSet {
        public final List<ParameterSource> all = new ArrayList<>();
        public final List<ParameterSource> values = all;

        public ParameterSource get(final HostParameterGuid guid) {
            return all.stream()
                    .filter(s -> s.guid.value.equals(guid.value))
                    .findFirst()
                    .orElse(null);
        }

        public ParameterSource getById(final HostParameterId id) {
            return all.stream()
                    .filter(s -> s.id.value.equals(id.value))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static final class HostMorphTarget {
        public HostParameterGuid parameter;
        public HostParameterGuid parameterGuid;
        public float keyValue;

        public HostMorphTarget(final String parameter, final float keyValue) {
            this.parameter = new HostParameterGuid(parameter);
            this.parameterGuid = this.parameter;
            this.keyValue = keyValue;
        }

        public HostParameterGuid parameterGuid() {
            return parameterGuid != null ? parameterGuid : parameter;
        }

        public Float keyValue() {
            return keyValue;
        }

        public HostParameterGuid keyformGuid() {
            return new HostParameterGuid("keyform-" + parameterGuid().value);
        }

        public void setParameterAndKeyValue(final HostParameterGuid parameter, final Float keyValue) {
            this.parameter = parameter;
            this.parameterGuid = parameter;
            this.keyValue = keyValue;
        }

        public void set(final HostParameterGuid parameter, final float keyValue) {
            setParameterAndKeyValue(parameter, keyValue);
        }

        public void setParameter(final HostParameterGuid parameter) {
            this.parameter = parameter;
            this.parameterGuid = parameter;
        }
    }

    public static final class MorphTargetSet {
        public final List<HostMorphTarget> targets = new ArrayList<>();

        public List<HostMorphTarget> morphTargets() {
            return targets;
        }

        public void remove(final HostMorphTarget target) {
            targets.remove(target);
        }
    }

    public static final class ChangeMorphTargetParameterUndoRedo extends Undo {
        final HostMorphTarget target;
        final HostParameterGuid newGuid;
        final Float newValue;
        HostParameterGuid oldGuid;
        Float oldValue;

        public ChangeMorphTargetParameterUndoRedo(
                final HostMorphTarget target, final HostParameterGuid newGuid, final Float newValue) {
            super(true);
            this.target = target;
            this.newGuid = newGuid;
            this.newValue = newValue;
            redo();
        }

        @Override
        public void undo() {
            target.setParameterAndKeyValue(oldGuid, oldValue);
        }

        @Override
        public void redo() {
            oldGuid = target.parameterGuid();
            oldValue = target.keyValue();
            target.setParameterAndKeyValue(newGuid, newValue);
        }
    }

    public static final class MorphTargetParameterUtils {
        public static final MorphTargetParameterUtils a = new MorphTargetParameterUtils();

        public Undo a(final HostMorphTarget target, final HostParameterGuid guid, final Float value) {
            return new ChangeMorphTargetParameterUndoRedo(target, guid, value);
        }
    }

    public static final class MorphTargetUtils {
        public static final MorphTargetUtils INSTANCE = new MorphTargetUtils();

        public Undo changeParameter(
                final HostMorphTarget target, final HostParameterGuid parameter, final Float value) {
            return new ChangeMorphTargetParameterUndoRedo(target, parameter, value);
        }
    }

    public static final class Point {
        public final int x;
        public final int y;

        public Point(final int x, final int y) {
            this.x = x;
            this.y = y;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }
    }

    public static final class ModelInfo {
        public final Point origin = new Point(4, 5);

        public Point origin() {
            return origin;
        }

        public float pixelsPerUnit() {
            return 10.0F;
        }
    }

    public static final class ImageCanvas {
        public int width() {
            return 1024;
        }

        public int height() {
            return 768;
        }
    }

    public record HostColor(float red, float green, float blue, float alpha) {}

    public static final class Part extends HostPart {
        public Part(final PartSource source) {
            super(source);
        }

        @Override
        public PartSource source() {
            return super.source();
        }

        public float getPartsOpacity() {
            return partsOpacity;
        }
    }

    public static final class PartView extends HostPart {
        public PartView(final String id, final PartSource source) {
            super(id, source);
        }

        @Override
        public PartSource source() {
            return super.source();
        }
    }

    /** Coordinator host whose history bank is the fixture's native edit list. */
    public static final class ScopedHost implements EditorAuthoringTransactionCoordinator.Host {
        @Override
        public void undoEditGroup(
                final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding
                        expected,
                final Object edit) {
            ((GroupUndo) edit).groupUndo();
        }

        final Fixture fixture;
        boolean nativeAbort = true;
        int begins, commits, aborts, refreshes;

        public ScopedHost(final Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return binding.hostThread() == Thread.currentThread();
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
            final var edits = fixture.editMode.edits;
            final var entries = new ArrayList<HistoryEntry>();
            for (int index = 0; index < edits.size(); index++) {
                entries.add(new HistoryEntry(index, "edit-" + index, true));
            }
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    edits.size(),
                    edits.size(),
                    List.copyOf(entries),
                    !entries.isEmpty(),
                    false,
                    "document-binding",
                    "manager-binding");
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
            begins++;
            return fixture.editMode.begin(label);
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
            final var group = (Undo) edit;
            if (!abort) {
                commits++;
                return;
            }
            aborts++;
            // Once the envelope group-undo ran, the bracket close must not replay inverses twice.
            if (nativeAbort && (!(group instanceof GroupUndo g) || g.groupUndoCount == 0)) group.undo();
            fixture.editMode.edits.remove(group);
        }

        @Override
        public void refresh(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Set<EditorRefreshRequirement> requirements) {
            refreshes++;
        }

        @Override
        public Optional<String> committedHistoryEntryId(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final HistorySnapshot before,
                final HistorySnapshot after,
                final String transactionId,
                final String label) {
            return after.position() == before.position() + 1
                    ? Optional.of("entry-" + after.position())
                    : Optional.empty();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }

    // ------------------------------------------------------------------
    // resolver + selectors
    // ------------------------------------------------------------------

    // fixture: host surface mirroring the verified native members
    // ------------------------------------------------------------------

    public static final class Host {
        private static final Host INSTANCE = new Host();
        public static Document document;
        public static Document current;
        public static Document currentDocument;
        public static CompletePack completePack;
        public static MainFrame mainFrame;
        public static UpdateManager updateManager = new UpdateManager();
        public static boolean updateManagerFails;
        public static int editLevel = 1;
        public static int commandCalls;
        public static volatile boolean readOnEdt;
        public static volatile boolean writeOnEdt;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            if (document != null) return document;
            if (currentDocument != null) return currentDocument;
            return current;
        }

        public int editLevel() {
            readOnEdt = javax.swing.SwingUtilities.isEventDispatchThread();
            return editLevel;
        }

        public void commandSetEditLevel(final int level) {
            writeOnEdt = javax.swing.SwingUtilities.isEventDispatchThread();
            editLevel = level;
            commandCalls++;
        }

        public static void install(final Object fixture) {
            try {
                final var f = fixture.getClass();
                try {
                    currentDocument = (Document) f.getDeclaredField("document").get(fixture);
                } catch (NoSuchFieldException ignored) {
                }
                try {
                    completePack =
                            (CompletePack) f.getDeclaredField("completePack").get(fixture);
                } catch (NoSuchFieldException ignored) {
                }
                try {
                    mainFrame = (MainFrame) f.getDeclaredField("mainFrame").get(fixture);
                } catch (NoSuchFieldException ignored) {
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public CompletePack completePack() {
            final Document doc = document != null ? document : current;
            if (doc != null) return doc.pack;
            return completePack;
        }

        public MainFrame mainFrame() {
            return mainFrame;
        }

        public UpdateManager updateManager() {
            if (updateManagerFails) {
                throw new IllegalStateException("simulated update manager failure");
            }
            final Document doc = document != null ? document : current;
            if (doc != null && doc.updateManager != null) return doc.updateManager;
            return updateManager;
        }

        public void commandDelete() {
            // The native command consumes selector objects, not update-manager GUIDs.
            if (document.requireNativeDeleteSelection
                    && !document.selector.selected().equals(List.of(document.source.pendingDeleteSource))) {
                throw new IllegalStateException("native delete target not selected");
            }
            // Native DELETE owns its edit lifecycle; a generic SDK edit cannot surround it.
            if (document.editMode.active) throw new IllegalStateException("previous edit not finished");
            final var edit = document.editMode.begin("Native Delete");
            try {
                edit.add(document.source.pendingDeleteSource.handler().undo("Native Delete"), true);
                document.deleteCount++;
                document.source.commandDelete();
            } finally {
                document.editMode.end(false, null);
            }
        }

        public void commandDeleteDeformerAndSetParam() {
            document.applyCount++;
            document.source.commandDeleteDeformerAndSetParam();
        }
    }

    public static final class Document {
        final ModelSource source;
        final NativeSelector selector = new NativeSelector();

        public NativeSelector selector() {
            return selector;
        }

        final EditMode editMode;
        final UndoManager undoManager;
        final CompletePack pack = new CompletePack();
        final UpdateManager updateManager = new UpdateManager();
        public final Model model;
        int deleteCount;
        int applyCount;
        boolean requireNativeDeleteSelection;
        public boolean dirty;
        public int dirtyUpdates;

        public Document() {
            this(new ModelSource());
        }

        public Document(final ModelSource source) {
            this(source, new EditMode());
        }

        public Document(final ModelSource source, final EditMode editMode) {
            this.source = source;
            this.model = source != null && source.model != null ? source.model : new Model(source);
            this.editMode = editMode != null ? editMode : new EditMode();
            this.undoManager = new UndoManager(this.editMode);
        }

        public ModelSource modelSource() {
            return source;
        }

        public EditMode editMode() {
            return editMode;
        }

        public UndoManager undoManager() {
            return undoManager;
        }

        public Object lastActiveView() {
            return null;
        }

        public void markDirty() {
            dirty = true;
            dirtyUpdates++;
        }
    }

    public static final class NativeSelector {
        final List<ObjectSource> values = new ArrayList<>();

        public List<ObjectSource> selected() {
            return List.copyOf(values);
        }

        public void replace(List<ObjectSource> sources, int level) {
            assertEquals(-1, level);
            values.clear();
            values.addAll(sources);
        }
    }

    public static final class ModelSource implements Copyable {
        static int counter;
        final Id guid;
        final List<ObjectSource> allObjects = new ArrayList<>();
        final PartSourceSet partSet = new PartSourceSet();
        final DeformerSourceSet deformerSet = new DeformerSourceSet();
        final DrawableSourceSet drawableSet = new DrawableSourceSet();
        final ModelHandler handler = new ModelHandler(this);
        public Model model = new Model(this);
        int updateCount;
        int hierarchyUpdateCount;
        boolean applyNoop;
        PartSource rootPart;
        public PartSource root;
        boolean treeMaterialization;

        public ModelSource() {
            this("model-a");
        }

        public ModelSource(final Model model) {
            this("model-source-1");
            this.model = model;
        }

        public ModelSource(final String guidValue) {
            this.guid = new Id(guidValue);
        }

        public final List<PartSource> sources = partSet.sources;
        public final List<PartSource> parts = partSet.sources;
        public final List<ObjectSource> objects = allObjects;
        public final List<ParameterSource> parameters = new ArrayList<>();

        public List<ParameterSource> allParameters() {
            return parameters;
        }

        public List<PartSource> parts() {
            return partSet.sources;
        }

        public final ParameterSourceSet parameterSourceSet = new ParameterSourceSet();
        public final ParameterSourceSet sourceSet = parameterSourceSet;

        public ParameterSourceSet parameterSourceSet() {
            return parameterSourceSet;
        }

        public final TexManager textureManager = new TexManager();

        public TexManager textureManager() {
            return textureManager;
        }

        public ParameterGroup rootGroup;

        public ParameterGroup rootGroup() {
            return rootGroup;
        }

        public ParameterGroup rootParameterGroup() {
            return rootGroup;
        }

        public ParameterGroup root() {
            return rootGroup;
        }

        public PartSource rootPart() {
            return rootPart != null ? rootPart : root;
        }

        public String name = "Fixture Model";
        public final ModelInfo modelInfo = new ModelInfo();
        public final ImageCanvas canvas = new ImageCanvas();

        public void setName(final String name) {
            this.name = name;
        }

        public ModelInfo modelInfo() {
            return modelInfo;
        }

        public ImageCanvas canvas() {
            return canvas;
        }

        public boolean defaultKeyformLocked;

        public boolean defaultKeyformLocked() {
            return defaultKeyformLocked;
        }

        public void setDefaultKeyformLocked(final boolean value) {
            defaultKeyformLocked = value;
        }

        @Override
        public Object snapshot() {
            return Boolean.valueOf(defaultKeyformLocked);
        }

        @Override
        public void restore(final Object snapshot) {
            defaultKeyformLocked = (Boolean) snapshot;
        }

        public String name() {
            return name;
        }

        public Id guid() {
            return guid;
        }

        public Model currentInstance() {
            return model;
        }

        public List<PartSource> allParts() {
            return partSet.sources;
        }

        public List<ACDeformerSource> allDeformers() {
            return deformerSet.sources;
        }

        public List<ACDrawableSource> allArtMeshes() {
            return drawableSet.sources;
        }

        public List<ObjectSource> allObjects() {
            return allObjects;
        }

        public ModelHandler handler() {
            return handler;
        }

        public PartSourceSet partSourceSet() {
            return partSet;
        }

        public DeformerSourceSet deformerSourceSet() {
            return deformerSet;
        }

        public List<Object> allGlues() {
            return List.of();
        }

        public void updateVisibleAndLockHierarchy() {
            hierarchyUpdateCount++;
            partSet.sources.forEach(source -> {
                source.visibleInHierarchy = source.visible();
                source.lockedInHierarchy = source.locked();
            });
        }

        public void updateInstances() {
            updateCount++;
            if (!treeMaterialization) return;
            final var existing = new java.util.IdentityHashMap<ACDrawableSource, ArtMesh>();
            model.meshes.forEach(mesh -> existing.put(mesh.source, mesh));
            model.meshes.clear();
            model.deformers.clear();
            materialize(rootPart, existing);
        }

        private void materialize(final PartSource part, final java.util.Map<ACDrawableSource, ArtMesh> existing) {
            if (part == null) return;
            for (ObjectSource child : part.children) {
                if (!allObjects.contains(child)) continue;
                if (child instanceof ACDrawableSource mesh && drawableSet.sources.contains(mesh)) {
                    model.meshes.add(existing.getOrDefault(mesh, new ArtMesh(mesh)));
                } else if (child instanceof ACDeformerSource deformer && deformerSet.sources.contains(deformer)) {
                    materializeDeformer(deformer);
                } else if (child instanceof PartSource nested) {
                    materialize(nested, existing);
                }
            }
        }

        private void materializeDeformer(final ACDeformerSource source) {
            if (model.deformers.stream().anyMatch(d -> d.source == source)) return;
            model.deformers.add(
                    source instanceof WarpDeformerSource
                            ? new Warp((WarpDeformerSource) source)
                            : new Rotation((RotationDeformerSource) source));
            // Deformer children attach through the target-deformer relation, not part children.
            for (ACDeformerSource candidate : deformerSet.sources) {
                if (allObjects.contains(candidate) && candidate.targetDeformerSource() == source) {
                    materializeDeformer(candidate);
                }
            }
        }

        ObjectSource findByGuid(final Guid target) {
            for (ObjectSource candidate : allObjects) {
                if (candidate.guid == target) return candidate;
            }
            return null;
        }

        void commandDelete() {
            // Simulates the native single-node DELETE command. Cascade / child re-parent is a
            // host responsibility and is intentionally NOT simulated here; the adapter only
            // selects the node and invokes the command (delegation, verified below).
            for (int i = 0; i < partSet.sources.size(); i++) {
                if (partSet.sources.get(i) == pendingDeleteSource) {
                    partSet.removeByCommand((PartSource) pendingDeleteSource);
                    return;
                }
            }
            for (int i = 0; i < deformerSet.sources.size(); i++) {
                if (deformerSet.sources.get(i) == pendingDeleteSource) {
                    deformerSet.removeByCommand((ACDeformerSource) pendingDeleteSource);
                    return;
                }
            }
            for (int i = 0; i < drawableSet.sources.size(); i++) {
                if (drawableSet.sources.get(i) == pendingDeleteSource) {
                    drawableSet.removeByCommand((ACDrawableSource) pendingDeleteSource);
                    return;
                }
            }
        }

        void commandDeleteDeformerAndSetParam() {
            if (applyNoop) return;
            if (pendingDeleteSource instanceof ACDeformerSource deformer) {
                deformerSet.removeByCommand(deformer);
            }
        }

        ObjectSource pendingDeleteSource;
    }

    public static final class Model {
        public final List<HostPart> parts = new ArrayList<>();
        public final List<Deformer> deformers = new ArrayList<>();
        public final List<ArtMesh> meshes = new ArrayList<>();
        public ParameterSet parameterSet;
        final ModelSource source;

        public Model() {
            this((ModelSource) null);
        }

        public Model(final ParameterSet set) {
            this((ModelSource) null);
            this.parameterSet = set;
        }

        public Model(final ModelSource source) {
            this.source = source;
        }

        public List<HostPart> allParts() {
            return parts;
        }

        public List<HostPart> parts() {
            return parts;
        }

        public List<Deformer> allDeformers() {
            return deformers;
        }

        public List<ArtMesh> allArtMeshes() {
            return meshes;
        }

        public ParameterSet consumed() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return new ParameterSet();
        }

        public long passTime() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return 1234;
        }

        public ParameterSet parameterSet() {
            return parameterSet != null ? parameterSet : new ParameterSet();
        }
    }

    public static final class ParameterSet {
        public final List<Parameter> parameters = new ArrayList<>();
        public final List<RuntimeParameter> runtimeParameters = new ArrayList<>();
        public Object fixtureRef;
        public int updateVersion;

        public ParameterSet() {}

        public ParameterSet(final Object fixture) {
            this.fixtureRef = fixture;
        }

        public ParameterSet copy(final CopyContext context) {
            return this;
        }

        public void restore(final ParameterSet snapshot) {
            updateVersion++;
        }

        public int version() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return 7;
        }

        public List<?> parameters() {
            if (fixtureRef != null) {
                try {
                    final var f = fixtureRef.getClass();
                    final var a = (ParameterSource) f.getDeclaredField("a").get(fixtureRef);
                    final var b = (ParameterSource) f.getDeclaredField("b").get(fixtureRef);
                    final var c = (ParameterSource) f.getDeclaredField("c").get(fixtureRef);
                    return List.of(new RuntimeParameter(a), new RuntimeParameter(b), new RuntimeParameter(c));
                } catch (Exception ignored) {
                }
            }
            if (!runtimeParameters.isEmpty()) return runtimeParameters;
            return parameters;
        }
    }

    public static final class Parameter {
        public Id id = new Id("Param");
        public ParameterSource source;

        public Parameter() {}

        public Parameter(final String id) {
            this(id, 0);
        }

        public Parameter(final String id, final float value) {
            this(id, value, new ParameterSource(id));
        }

        public Parameter(final String id, final float value, final ParameterSource source) {
            this.id = new Id(id);
            this.source = source;
        }

        public Id id() {
            return id;
        }

        public ParameterSource source() {
            return source != null ? source : new ParameterSource(id.value);
        }

        public float value() {
            assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
            return 0;
        }
    }

    /** Base source: mirrors ACParameterControllableSource. */
    public static class ObjectSource {
        public ObjectSource selection() {
            return this;
        }

        Id id;
        final ModelSource modelSource;
        Guid guid = new Guid();
        String localName;
        PartSource parent;
        Guid targetDeformerGuid;
        KeyformGridSource keyformGridSource;
        Undo currentUndo;
        public int defaultOrder;
        int setTargetCalls;
        boolean handlerUnavailable;
        boolean failLocalNameRead;

        public ObjectSource() {
            this("obj-source", null);
        }

        public ObjectSource(final Id guid, final Id id) {
            this(id != null ? id.value() : "", null);
            if (guid instanceof Guid g) this.guid = g;
            else if (guid != null) this.guid = new Guid(guid.value());
        }

        ObjectSource(final String id, final ModelSource modelSource) {
            this.id = new Id(id);
            this.modelSource = modelSource;
        }

        public final MorphTargetSet morphTargetSet = new MorphTargetSet();
        public final MorphTargetSet set = morphTargetSet;
        public final KeyformGrid keyformGrid = new KeyformGrid();

        public MorphTargetSet morphTargetSet() {
            return morphTargetSet;
        }

        public KeyformGrid keyformGrid() {
            return keyformGrid;
        }

        public Id id() {
            return id;
        }

        public Guid guid() {
            return guid;
        }

        public String localName() {
            if (failLocalNameRead) {
                failLocalNameRead = false;
                throw new IllegalStateException("fixture display name read failed");
            }
            return localName;
        }

        public void setLocalName(final String value) {
            final String previous = localName;
            record("local-name", () -> localName = previous, () -> localName = value);
            localName = value;
        }

        public PartSource parent() {
            return parent;
        }

        public void internalSetParent(final PartSource value) {
            parent = value;
        }

        public Guid targetDeformerGuid() {
            return targetDeformerGuid;
        }

        public void setTargetDeformerGuid(final Guid value) {
            setTargetCalls++;
            final Guid previous = targetDeformerGuid;
            record("target-deformer", () -> targetDeformerGuid = previous, () -> targetDeformerGuid = value);
            targetDeformerGuid = value;
        }

        public ObjectSource targetDeformerSource() {
            return targetDeformerGuid == null ? null : modelSource.findByGuid(targetDeformerGuid);
        }

        public void setKeyformGridSource(final KeyformGridSource value) {
            keyformGridSource = value;
        }

        public Iterable<ObjectSource> allParentDeformers() {
            final ArrayList<ObjectSource> ancestors = new ArrayList<>();
            ObjectSource current = targetDeformerSource();
            while (current != null) {
                ancestors.add(current);
                current = current.targetDeformerSource();
            }
            return ancestors;
        }

        public ACParameterControllableHandler handler() {
            if (handlerUnavailable) return null;
            return this instanceof PartSource part ? new PartHandler(part) : new ACParameterControllableHandler(this);
        }

        public boolean visible() {
            return true;
        }

        public boolean locked() {
            return false;
        }

        public boolean visibleInHierarchy() {
            return true;
        }

        public boolean lockedInHierarchy() {
            return false;
        }

        void record(final String label, final Runnable undo, final Runnable redo) {
            if (currentUndo != null) currentUndo.record(label, undo, redo);
        }

        void recordInto(final ObjectSource target, final String label, final Runnable undo, final Runnable redo) {
            if (target.currentUndo != null) target.currentUndo.record(label, undo, redo);
        }
    }

    /** Deformer source base: mirrors ACDeformerSource. */
    public static class ACDeformerSource extends ObjectSource {
        ACDeformerSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
        }

        public void setId(final Id value) {
            id = value;
        }
    }

    /** Drawable source: mirrors ACDrawableSource. */
    public static class ACDrawableSource extends ObjectSource {
        final ObjectList keyforms = new ObjectList();
        final List<EditableExtension> extensions = new ArrayList<>();

        public void addExtension(final EditableExtension extension, final int index) {
            if (index < 0) extensions.add(extension);
            else extensions.add(index, extension);
        }

        float[] sourcePositions = new float[0];
        float[] sourceUvs = new float[0];
        int[] sourceIndices = new int[0];

        public ACDrawableSource(final ModelSource modelSource) {
            this("ArtMesh_" + (++ModelSource.counter), modelSource);
        }

        public ACDrawableSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
        }

        public void setId(final Id value) {
            id = value;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        @Override
        public Guid guid() {
            return super.guid();
        }

        public List<Object> clipGuids() {
            return List.of();
        }

        public float[] positions() {
            return sourcePositions.clone();
        }

        public void setPositions(final float[] values) {
            sourcePositions = values.clone();
        }

        public float[] uvs() {
            return sourceUvs.clone();
        }

        public void setUvs(final float[] values) {
            sourceUvs = values.clone();
        }

        public int[] indices() {
            return sourceIndices.clone();
        }

        public void setIndices(final int[] values) {
            sourceIndices = values.clone();
        }

        public boolean culling() {
            return false;
        }

        public String userData() {
            return "";
        }

        public boolean invertedMask() {
            return false;
        }
    }

    public static final class EditableExtension {
        final ACDrawableSource owner;
        final EditableMesh mesh = new EditableMesh();

        public EditableExtension(final ACDrawableSource owner) {
            this.owner = owner;
        }

        public ACDrawableSource owner() {
            return owner;
        }

        public EditableMesh mesh() {
            return mesh;
        }

        public boolean locked() {
            return false;
        }
    }

    public static final class EditableMesh {
        float[] positions;
        int[] indices;

        public void init(final float[] positions, final int[] indices, final CoordType coord) {
            this.positions = positions.clone();
            this.indices = indices.clone();
        }
    }

    public static final class PartSource extends ObjectSource {
        final List<ObjectSource> children = new ArrayList<>();
        final ObjectList keyforms = new ObjectList();
        int addChildCalls;
        int removeChildCalls;
        String renameOnNextAdd;
        boolean failNameReadOnNextAdd;

        public boolean visible = true;
        public boolean locked;
        public boolean visibleInHierarchy = true;
        public boolean lockedInHierarchy;
        public boolean sketch;
        public HostColor editColor = new HostColor(0.1F, 0.2F, 0.3F, 0.4F);
        public PartHandler handler = new PartHandler(this);

        public PartSource(final String id) {
            this(id, (ModelSource) null);
        }

        public PartSource(final String id, final String localName) {
            this(id, (ModelSource) null);
            this.localName = localName;
        }

        public PartSource(final ModelSource modelSource) {
            this("Part_" + (++ModelSource.counter), modelSource);
        }

        public PartSource(final String name, final ModelSource modelSource) {
            super(name, modelSource);
            this.localName = name;
        }

        public void setGuid(final Id value) {
            if (value instanceof Guid g) this.guid = g;
            else this.guid = new Guid(value != null ? value.value() : "");
        }

        @Override
        public PartSource parent() {
            return super.parent();
        }

        @Override
        public String localName() {
            return super.localName();
        }

        @Override
        public void setLocalName(final String value) {
            super.setLocalName(value);
        }

        public boolean visible() {
            return visible;
        }

        public void setVisible(final boolean value) {
            visible = value;
        }

        public boolean locked() {
            return locked;
        }

        public void setLocked(final boolean value) {
            locked = value;
        }

        public boolean visibleInHierarchy() {
            return visibleInHierarchy;
        }

        public void setVisibleInHierarchy(final boolean value) {
            visibleInHierarchy = value;
        }

        public boolean lockedInHierarchy() {
            return lockedInHierarchy;
        }

        public void setLockedInHierarchy(final boolean value) {
            lockedInHierarchy = value;
        }

        public boolean sketch() {
            return sketch;
        }

        public void setSketch(final boolean value) {
            sketch = value;
        }

        public int defaultOrder() {
            return defaultOrder;
        }

        public void setDefaultOrder(final int value) {
            defaultOrder = value;
        }

        public HostColor editColor() {
            return editColor;
        }

        public void setEditColor(final HostColor value) {
            editColor = value;
        }

        public PartHandler handler() {
            return partHandler();
        }

        public Undo basicUndo(final String name) {
            return new Undo();
        }

        public void setId(final Id value) {
            id = value;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        public List<ObjectSource> children() {
            return children;
        }

        @Override
        public Id id() {
            return super.id();
        }

        @Override
        public Guid guid() {
            return super.guid();
        }

        public void addChild(final ObjectSource child, final int index) {
            addChildCalls++;
            final PartSource oldParent = child.parent;
            // Part attachment belongs to the parent's separately admitted Undo.
            recordInto(
                    child instanceof PartSource && oldParent == null ? this : child,
                    "add-child",
                    () -> {
                        children.remove(child);
                        if (oldParent != null) {
                            oldParent.children.add(child);
                            child.internalSetParent(oldParent);
                        } else {
                            child.internalSetParent(null);
                        }
                    },
                    () -> {
                        if (oldParent != null) oldParent.children.remove(child);
                        children.remove(child);
                        if (index < 0) children.add(child);
                        else children.add(Math.min(index, children.size()), child);
                        child.internalSetParent(PartSource.this);
                    });
            if (oldParent != null) oldParent.removeChild(child);
            if (index < 0) children.add(child);
            else children.add(Math.min(index, children.size()), child);
            child.internalSetParent(this);
            if (!modelSource.allObjects.contains(child)) modelSource.allObjects.add(child);
            if (renameOnNextAdd != null) {
                localName = renameOnNextAdd;
                renameOnNextAdd = null;
            }
            if (failNameReadOnNextAdd) {
                failLocalNameRead = true;
                failNameReadOnNextAdd = false;
            }
        }

        /** Mirrors the host PartHandler: attaches the child and returns the Undo entry for it. */
        public PartHandler partHandler() {
            return handlerUnavailable ? null : new PartHandler(this);
        }

        public void removeChild(final ObjectSource child) {
            removeChildCalls++;
            children.remove(child);
            child.internalSetParent(null);
            modelSource.allObjects.remove(child);
        }
    }

    public static final class WarpDeformerSource extends ACDeformerSource {
        int row = 2;
        int col = 2;
        boolean quadTransform;
        final ObjectList keyforms = new ObjectList();
        final List<Integer> rowColLog = new ArrayList<>();

        public WarpDeformerSource(final ModelSource modelSource) {
            super("Warp_" + (++ModelSource.counter), modelSource);
        }

        WarpDeformerSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
            this.localName = id;
        }

        public int row() {
            return row;
        }

        public int col() {
            return col;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        public boolean quadTransform() {
            return quadTransform;
        }

        public void setQuadTransform(final boolean value) {
            quadTransform = value;
        }

        public void setRow(final int value) {
            final int previous = row;
            record("set-row", () -> row = previous, () -> row = value);
            row = value;
            rowColLog.add(value);
        }

        public void setCol(final int value) {
            final int previous = col;
            record("set-col", () -> col = previous, () -> col = value);
            col = value;
            rowColLog.add(value);
        }
    }

    public static final class RotationDeformerSource extends ACDeformerSource {
        final ObjectList keyforms = new ObjectList();

        public RotationDeformerSource(final ModelSource modelSource) {
            super("Rotation_" + (++ModelSource.counter), modelSource);
        }

        RotationDeformerSource(final String id, final ModelSource modelSource) {
            super(id, modelSource);
            this.localName = id;
        }

        public ObjectList keyforms() {
            return keyforms;
        }

        public float baseAngle() {
            return 0F;
        }
    }

    public static final class CopyHelper {
        public static Object copy(final Object source, final Object copyParam, final int flags, final Object marker) {
            final PartSource original = (PartSource) source;
            final PartSource copy = new PartSource(original.id.value());
            copy.defaultOrder = original.defaultOrder;
            copy.parent = original.parent;
            return copy;
        }
    }

    public static final class ModelHandler {
        public static int nextId = 1;
        public static ParameterSource lastAdded;
        public static ParameterGroup lastGroup;

        public GroupUndo removeParameter(final Id guid, final ParameterSet set, final boolean flag) {
            final Document doc = Host.current != null ? Host.current : Host.document;
            final ModelSource src = doc.source;
            src.sources.removeIf(s -> s.guid.value().equals(guid.value()));
            if (src.rootGroup != null)
                src.rootGroup.children.removeIf(
                        child -> child instanceof ParameterSource s && s.guid.value.equals(guid.value()));
            return new GroupUndo(new ArrayList<>());
        }

        public Undo moveParameter(final ParameterGroup group, final ParameterSource source, final int index) {
            source.parentGroup = group;
            group.children.add(source);
            return new Undo();
        }

        public Undo addParameter(final ParameterSource source, final int index) {
            final Document doc = Host.current != null ? Host.current : Host.document;
            final ModelSource ms = doc.source;
            source.parentGroup = ms.rootGroup;
            if (ms.rootGroup != null) ms.rootGroup.children.add(source);
            ms.sourceSet.all.add(source);
            lastAdded = source;
            return new Undo();
        }

        public Undo addGroup(final ParameterGroup group, final int index) {
            final Document doc = Host.current != null ? Host.current : Host.document;
            final ModelSource ms = doc.source;
            group.parent = ms.rootGroup;
            if (ms.rootGroup != null) ms.rootGroup.children.add(group);
            lastGroup = group;
            return new Undo();
        }

        public Undo removeGroup(final ParameterGroup group) {
            if (group.parent != null) group.parent.children.remove(group);
            return new Undo();
        }

        public static Id createFreeIdDefault(
                final ModelHandler handler, final Id base, final Object idMap, final int flags, final Object marker) {
            return new Id(base.value() + "_" + (nextId++));
        }

        public final List<PartSource> lastRemoved = new ArrayList<>();

        public Undo removeObjects(final List<?> objects, final Model model, final boolean flag) {
            for (Object object : objects) {
                final PartSource source = (PartSource) object;
                if (source.parent != null) source.parent.children.remove(source);
                model.parts.removeIf(part -> part.source == source);
                if (source.modelSource != null) source.modelSource.partSet.sources.remove(source);
            }
            lastRemoved.clear();
            for (Object object : objects) lastRemoved.add((PartSource) object);
            return new Undo();
        }

        public ModelHandler() {
            this(null);
        }

        private final ModelSource source;

        ModelHandler(final ModelSource source) {
            this.source = source;
        }

        public Undo addSourceUndo(final ObjectSource item, final int index) {
            final Undo undo = new Undo();
            item.currentUndo = undo;
            if (item instanceof PartSource part) {
                source.partSet.add(part, index);
            } else if (item instanceof ACDeformerSource deformer) {
                source.deformerSet.add(deformer, index);
            } else if (item instanceof ACDrawableSource drawable) {
                source.drawableSet.add(drawable, index);
            } else {
                throw new IllegalArgumentException("Unsupported fixture source: " + item);
            }
            return undo;
        }
    }

    public static final class PartSourceSet {
        final List<PartSource> sources = new ArrayList<>();
        final List<String> addLog = new ArrayList<>();
        int removedDirectly;
        int removedByCommand;

        public void add(final PartSource source, final int index) {
            if (index < 0) sources.add(source);
            else sources.add(Math.min(index, sources.size()), source);
            source.modelSource.allObjects.add(source);
            source.modelSource.model.parts.add(new HostPart(source));
            addLog.add(source.localName == null ? source.id.value() : source.localName);
            source.record(
                    "part-add",
                    () -> {
                        sources.remove(source);
                        source.modelSource.allObjects.remove(source);
                        source.modelSource.model.parts.removeIf(part -> part.source == source);
                    },
                    () -> add(source, index));
        }

        public void remove(final PartSource source) {
            removedDirectly++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.parts.removeIf(part -> part.source == source);
        }

        void removeByCommand(final PartSource source) {
            removedByCommand++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.parts.removeIf(part -> part.source == source);
            if (source.currentUndo != null) {
                source.currentUndo.record("delete", () -> add(source, -1), () -> {
                    sources.remove(source);
                    source.modelSource.allObjects.remove(source);
                    source.modelSource.model.parts.removeIf(part -> part.source == source);
                });
            }
        }
    }

    public static final class DeformerSourceSet {
        final List<ACDeformerSource> sources = new ArrayList<>();
        int addCount;
        int removedDirectly;
        int removedByCommand;

        public void add(final ACDeformerSource source, final int index) {
            addCount++;
            if (index < 0) sources.add(source);
            else sources.add(Math.min(index, sources.size()), source);
            source.modelSource.allObjects.add(source);
            // The real host materializes instances from the tree at updateInstances, never
            // from source-set membership alone — a registered-but-unattached source has none.
            if (!source.modelSource.treeMaterialization) {
                source.modelSource.model.deformers.add(
                        source instanceof WarpDeformerSource
                                ? new Warp((WarpDeformerSource) source)
                                : new Rotation((RotationDeformerSource) source));
            }
            source.record(
                    "deformer-add",
                    () -> {
                        sources.remove(source);
                        source.modelSource.allObjects.remove(source);
                        source.modelSource.model.deformers.removeIf(d -> d.source == source);
                    },
                    () -> add(source, index));
        }

        public void remove(final ACDeformerSource source) {
            removedDirectly++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.deformers.removeIf(d -> d.source == source);
        }

        void removeByCommand(final ACDeformerSource source) {
            removedByCommand++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.deformers.removeIf(d -> d.source == source);
            if (source.currentUndo != null) {
                source.currentUndo.record("delete", () -> add(source, -1), () -> {
                    sources.remove(source);
                    source.modelSource.allObjects.remove(source);
                    source.modelSource.model.deformers.removeIf(d -> d.source == source);
                });
            }
        }
    }

    public static final class DrawableSourceSet {
        final List<ACDrawableSource> sources = new ArrayList<>();
        int removedByCommand;

        public void add(final ACDrawableSource source, final int index) {
            if (index < 0) sources.add(source);
            else sources.add(Math.min(index, sources.size()), source);
            source.modelSource.allObjects.add(source);
            if (!source.modelSource.treeMaterialization) source.modelSource.model.meshes.add(new ArtMesh(source));
            source.record(
                    "drawable-add",
                    () -> {
                        sources.remove(source);
                        source.modelSource.allObjects.remove(source);
                        source.modelSource.model.meshes.removeIf(mesh -> mesh.source == source);
                    },
                    () -> add(source, index));
        }

        public void remove(final ACDrawableSource source) {
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.meshes.removeIf(m -> m.source == source);
        }

        void removeByCommand(final ACDrawableSource source) {
            removedByCommand++;
            sources.remove(source);
            source.modelSource.allObjects.remove(source);
            source.modelSource.model.meshes.removeIf(m -> m.source == source);
            if (source.currentUndo != null) {
                source.currentUndo.record(
                        "delete",
                        () -> {
                            sources.add(source);
                            source.modelSource.allObjects.add(source);
                            source.modelSource.model.meshes.add(new ArtMesh(source));
                        },
                        () -> {
                            sources.remove(source);
                            source.modelSource.allObjects.remove(source);
                            source.modelSource.model.meshes.removeIf(m -> m.source == source);
                        });
            }
        }
    }

    public static final class UpdateManager {
        final List<SelectionCall> selectionCalls = new ArrayList<>();
        Runnable selectionCallback;
        public List<Id> selection = List.of();
        public static boolean updateManagerFails;

        public List<Id> selectionGuidList() {
            return selection;
        }

        public void setSelection(
                final Object source, final List<?> guids, final boolean append, final boolean sendEvent) {
            selectionCalls.add(new SelectionCall(source, new ArrayList<>(guids), append, sendEvent));
            if (source instanceof Document document && !guids.isEmpty()) {
                document.source.pendingDeleteSource = document.source.findByGuid((Guid) guids.get(0));
            }
            final Runnable callback = selectionCallback;
            selectionCallback = null;
            if (sendEvent && callback != null) callback.run();
        }

        public record SelectionCall(Object source, List<Object> guids, boolean append, boolean sendEvent) {
            // The adapter's first boolean publishes native selector notifications (see impl).
            public boolean notifyListeners() {
                return append;
            }
        }
    }

    public static class ACParameterControllableHandler {
        final ObjectSource source;

        ACParameterControllableHandler(final ObjectSource source) {
            this.source = source;
        }

        public Undo undo(final String name) {
            final Undo undo = new Undo();
            source.currentUndo = undo;
            return undo;
        }
    }

    /** Mirrors PartHandler's dedicated tree undo, independent of the registry-only undo. */
    public static final class PartHandler extends ACParameterControllableHandler {
        public PartHandler() {
            super(null);
        }

        public PartHandler(final PartSource part) {
            super(part);
        }

        @Override
        public Undo undo(final String name) {
            return super.undo(name);
        }

        public Undo addPartChild(final Object child, final int index) {
            return addPartChild((ObjectSource) child, index);
        }

        public Undo addPartChild(final ObjectSource child, final int index) {
            final PartSource part = (PartSource) source;
            final ModelSource model = source.modelSource;
            if (child instanceof PartSource && child == part) {
                throw new IllegalStateException("duplicate native attachment");
            }
            final Undo undo = new Undo();
            final Undo previousParentUndo = part.currentUndo;
            final Undo previousChildUndo = child.currentUndo;
            // The host attach entry records the detach/attach inverse for both ends.
            part.currentUndo = undo;
            child.currentUndo = undo;
            try {
                part.addChild(child, index);
                // A source not yet registered in the model sets is a native create-attach:
                // the same command also registers it into the owning source set.
                if (child instanceof ACDrawableSource mesh && !model.drawableSet.sources.contains(mesh)) {
                    model.drawableSet.sources.add(mesh);
                    if (!model.treeMaterialization) model.model.meshes.add(new ArtMesh(mesh));
                    undo.record(
                            "register-mesh",
                            () -> {
                                model.drawableSet.sources.remove(mesh);
                                model.model.meshes.removeIf(value -> value.source == mesh);
                            },
                            () -> {
                                model.drawableSet.sources.add(mesh);
                                if (!model.treeMaterialization) model.model.meshes.add(new ArtMesh(mesh));
                            });
                } else if (child instanceof PartSource nested && !model.partSet.sources.contains(nested)) {
                    model.partSet.sources.add(nested);
                    model.partSet.addLog.add(nested.localName);
                    model.model.parts.add(new PartView(nested.id != null ? nested.id.value() : "", nested));
                    undo.record(
                            "register-part",
                            () -> {
                                model.partSet.sources.remove(nested);
                                model.model.parts.removeIf(value -> value.source == nested);
                            },
                            () -> {
                                model.partSet.sources.add(nested);
                                model.model.parts.add(new PartView(nested.id != null ? nested.id.value() : "", nested));
                            });
                } else if (child instanceof ACDeformerSource deformer
                        && !model.deformerSet.sources.contains(deformer)) {
                    model.deformerSet.sources.add(deformer);
                    if (!model.treeMaterialization) {
                        model.model.deformers.add(
                                deformer instanceof WarpDeformerSource
                                        ? new Warp((WarpDeformerSource) deformer)
                                        : new Rotation((RotationDeformerSource) deformer));
                    }
                    undo.record(
                            "register-deformer",
                            () -> {
                                model.deformerSet.sources.remove(deformer);
                                model.model.deformers.removeIf(value -> value.source == deformer);
                            },
                            () -> {
                                model.deformerSet.sources.add(deformer);
                                if (!model.treeMaterialization) {
                                    model.model.deformers.add(
                                            deformer instanceof WarpDeformerSource
                                                    ? new Warp((WarpDeformerSource) deformer)
                                                    : new Rotation((RotationDeformerSource) deformer));
                                }
                            });
                }
            } finally {
                part.currentUndo = previousParentUndo;
                child.currentUndo = previousChildUndo;
            }
            return undo;
        }
    }

    public static final class EditMode {
        final List<Undo> edits = new ArrayList<>();
        public boolean aborted;
        public int beginCalls;
        public int beginCount;
        public int endCount;
        public int committedEdits;
        public GroupUndo committed;
        public GroupUndo current;
        boolean active;
        int rejectNextAdmission;
        int rejectAdmissionAt;
        int admissionCount;

        public GroupUndo begin(final String name) {
            beginCalls++;
            beginCount++;
            active = true;
            current = new GroupUndo(name, edits, this);
            return current;
        }

        public void end(final boolean abort, final Object ignored) {
            this.aborted = abort;
            endCount++;
            if (!abort) {
                committedEdits++;
                committed = current;
            }
            active = false;
            if (abort && !edits.isEmpty()) edits.remove(edits.size() - 1);
        }

        private final List<Undo> redoStack = new ArrayList<>();

        public void undo() {
            if (!edits.isEmpty()) {
                final Undo entry = edits.remove(edits.size() - 1);
                redoStack.add(entry);
                entry.undo();
            }
        }

        public void redo() {
            if (!redoStack.isEmpty()) {
                final Undo entry = redoStack.remove(redoStack.size() - 1);
                edits.add(entry);
                entry.redo();
            }
        }
    }

    /** Native undo-manager projection over the edit bank (one history entry per edit). */
    public static final class UndoManager {
        private final EditMode editMode;

        UndoManager(final EditMode editMode) {
            this.editMode = editMode;
        }

        public List<Undo> entries() {
            return new ArrayList<>(editMode.edits);
        }

        public int position() {
            return editMode.edits.size();
        }

        public Boolean canUndo() {
            return !editMode.edits.isEmpty();
        }

        public Boolean canRedo() {
            return false;
        }
    }

    public static final class GroupUndo extends Undo {
        private final EditMode owner;
        public final List<Undo> edits = children;

        public GroupUndo() {
            this("group", new ArrayList<>(), null);
        }

        public GroupUndo(final List<Undo> edits) {
            this("group", edits, null);
        }

        GroupUndo(final String label, final List<Undo> edits, final EditMode owner) {
            this.owner = owner;
            this.label = label;
            if (edits != null) edits.add(this);
        }

        @Override
        public boolean add(final Object raw, final boolean force) {
            if (raw instanceof Undo u) return add(u, force);
            return true;
        }

        public boolean add(final Undo undo, final boolean significant) {
            if (owner != null && ++owner.admissionCount == owner.rejectAdmissionAt) return false;
            if (owner != null && owner.rejectNextAdmission > 0) {
                owner.rejectNextAdmission--;
                return false;
            }
            children.add(undo);
            return true;
        }

        public String presentationName() {
            return label;
        }

        public Boolean significant() {
            return Boolean.TRUE;
        }

        int groupUndoCount;
        // cubism.editor-model.undo.group-undo: rewinds every admitted undo in place while the
        // edit bracket stays open, exactly like the verified session recovery path.
        public void groupUndo() {
            groupUndoCount++;
            for (int index = children.size() - 1; index >= 0; index--) {
                children.get(index).undo();
            }
        }
    }

    public static class Undo {
        public boolean captured = true;

        public Undo() {}

        public Undo(final String name, final Object target, final Object copyParam) {
            this();
        }

        public Undo(final boolean significant) {
            this();
        }

        // Native history replay does not author new commands. Suppress recording
        // across different Undo objects too (a newly created Part can later be a parent).
        private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
        String label;
        final List<Undo> children = new ArrayList<>();
        final List<Runnable> undoSteps = new ArrayList<>();
        final List<Runnable> redoSteps = new ArrayList<>();
        final List<Listener> listeners = new ArrayList<>();

        public boolean add(final Object raw, final boolean force) {
            return true;
        }

        public boolean addListener(final Listener listener) {
            listeners.add(listener);
            return true;
        }

        public boolean addListener(final UndoListener listener) {
            return true;
        }

        void record(final String label, final Runnable undo, final Runnable redo) {
            if (!REPLAYING.get()) {
                undoSteps.add(undo);
                redoSteps.add(redo);
            }
        }

        public void executeUndo() {
            undo();
        }

        public void undo() {
            replay(() -> {
                for (int i = children.size() - 1; i >= 0; i--) children.get(i).undo();
                for (int i = undoSteps.size() - 1; i >= 0; i--) undoSteps.get(i).run();
                listeners.forEach(listener -> listener.changed(null));
            });
        }

        public void redo() {
            replay(() -> {
                new ArrayList<>(redoSteps).forEach(Runnable::run);
                new ArrayList<>(children).forEach(Undo::redo);
                listeners.forEach(listener -> listener.changed(null));
            });
        }

        private static void replay(final Runnable action) {
            final boolean previous = REPLAYING.get();
            REPLAYING.set(true);
            try {
                action.run();
            } finally {
                if (previous) REPLAYING.set(true);
                else REPLAYING.remove();
            }
        }
    }

    @FunctionalInterface
    public interface Listener {
        void changed(Object ignored);
    }

    public static class Pack extends CompletePack {
        @Override
        public void updateParameter(final boolean immediate) {
            super.updateParameter(immediate);
        }

        @Override
        public void updatePartPalette(final boolean immediate) {
            super.updatePartPalette(immediate);
        }

        @Override
        public void updateDeformerPalette(final boolean immediate) {
            super.updateDeformerPalette(immediate);
        }

        @Override
        public void repaintCanvas(final boolean immediate) {
            super.repaintCanvas(immediate);
        }
    }

    public static class CompletePack {
        public int partRefreshCalls;
        public int deformerRefreshCalls;
        public int repaintCalls;
        public int partRefreshCount;
        public int deformerRefreshCount;
        public int parameterRefreshCount;
        public int repaintCount;
        public int parameterRefreshes;
        public int canvasRepaints;

        public void updatePartPalette(final boolean immediate) {
            partRefreshCalls++;
            partRefreshCount++;
        }

        public void updateParts(final boolean immediate) {
            partRefreshCalls++;
            partRefreshCount++;
        }

        public void updateDeformerPalette(final boolean immediate) {
            deformerRefreshCalls++;
            deformerRefreshCount++;
        }

        public void updateDeformers(final boolean immediate) {
            deformerRefreshCalls++;
            deformerRefreshCount++;
        }

        public void updateParameter(final boolean immediate) {
            parameterRefreshCount++;
            parameterRefreshes++;
        }

        public void repaintCanvas(final boolean immediate) {
            repaintCalls++;
            repaintCount++;
            canvasRepaints++;
        }

        public void repaint(final boolean immediate) {
            repaintCalls++;
            repaintCount++;
        }
    }

    public static class HostPart {
        public final Id id;
        public final PartSource source;
        public final PartForm form = new PartForm();
        public float partsOpacity = 1.0F;

        public HostPart(final PartSource source) {
            this.source = source;
            this.id = source != null ? source.id : null;
        }

        public HostPart(final String id, final PartSource source) {
            this.id = new Id(id);
            this.source = source;
        }

        public HostPart(final Id id, final PartSource source) {
            this.id = id;
            this.source = source;
        }

        public PartSource source() {
            return source;
        }

        public Id id() {
            return id;
        }

        public PartForm currentForm() {
            return form;
        }

        public float partsOpacity() {
            return partsOpacity;
        }

        public void setPartsOpacity(final float value) {
            partsOpacity = value;
        }

        public float opacityFor(final String cubismVersion) {
            return "5.2.03".equals(cubismVersion) ? partsOpacity : form.opacity;
        }
    }

    public abstract static class Deformer {
        final ObjectSource source;

        Deformer(final ObjectSource source) {
            this.source = source;
        }

        public ObjectSource source() {
            return source;
        }

        public Form currentForm() {
            return new WarpForm();
        }
    }

    public static class Form {
        public Guid guid;
        public float opacity = 1.0F;
        public int drawOrder = 0;

        public Form() {}

        public Form(final Guid guid) {
            this.guid = guid;
        }

        public void setGuid(final Guid value) {
            guid = value;
        }

        public float opacity() {
            return opacity;
        }

        public void setOpacity(final float value) {
            opacity = value;
        }

        public int drawOrder() {
            return drawOrder;
        }
    }

    public static final class PartForm extends Form {
        public PartForm() {}

        public PartForm(final PartSource source, final Object instance) {}

        @Override
        public float opacity() {
            return super.opacity();
        }

        @Override
        public void setOpacity(final float value) {
            super.setOpacity(value);
        }
    }

    public static final class ArtMeshForm extends Form {
        float[] values = new float[0];

        public ArtMeshForm() {}

        public ArtMeshForm(final ACDrawableSource source, final ArtMesh instance, final CoordType coordType) {}

        public CoordType coordType() {
            return CoordType.canvas();
        }

        public float[] positions() {
            return values.clone();
        }

        public void setPositions(final float[] positions) {
            values = positions.clone();
        }
    }

    public static final class WarpForm extends Form {
        float[] positions;

        WarpForm() {
            this.positions = new float[0];
        }

        WarpForm(final float[] positions) {
            this.positions = positions.clone();
        }

        public WarpForm(final WarpDeformerSource source, final Warp instance, final CoordType coordType) {
            this.positions = new float[0];
        }

        public float[] positions() {
            return positions.clone();
        }

        public void setPositions(final float[] values) {
            positions = values.clone();
        }
    }

    public static final class RotationForm extends Form {
        float angle;
        float originX;
        float originY;
        float scale = 1F;
        boolean reflectX;
        boolean reflectY;

        public RotationForm() {}

        public RotationForm(final RotationDeformerSource source, final Rotation instance, final CoordType coordType) {}

        public float angle() {
            return angle;
        }

        public void setAngle(final float value) {
            angle = value;
        }

        public float originX() {
            return originX;
        }

        public void setOriginX(final float value) {
            originX = value;
        }

        public float originY() {
            return originY;
        }

        public void setOriginY(final float value) {
            originY = value;
        }

        public float scale() {
            return scale;
        }

        public void setScale(final float value) {
            scale = value;
        }

        public boolean reflectX() {
            return reflectX;
        }

        public void setReflectX(final boolean value) {
            reflectX = value;
        }

        public boolean reflectY() {
            return reflectY;
        }

        public void setReflectY(final boolean value) {
            reflectY = value;
        }
    }

    public static final class ObjectList {
        final List<Object> values = new ArrayList<>();

        public boolean add(final Object value) {
            return values.add(value);
        }

        Object first() {
            return values.isEmpty() ? null : values.get(0);
        }
    }

    public static final class KeyformGridSource {
        final ObjectSource source;
        List<Guid> forms = List.of();

        public KeyformGridSource(final ObjectSource source) {
            this.source = source;
        }

        public void importCubism21(
                final ModelSource modelSource,
                final List<?> parameters,
                final List<Guid> formGuids,
                final Object context) {
            forms = List.copyOf(formGuids);
        }
    }

    public static final class CoordType {
        private static final CoordType CANVAS = new CoordType();

        public static CoordType canvas() {
            return CANVAS;
        }
    }

    public static final class Warp extends Deformer {
        Warp(final WarpDeformerSource source) {
            super(source);
        }

        @Override
        public Form currentForm() {
            final Object form = ((WarpDeformerSource) source).keyforms.first();
            if (form instanceof WarpForm warpForm) return warpForm;
            final WarpDeformerSource warp = (WarpDeformerSource) source;
            return new WarpForm(new float[(warp.row() + 1) * (warp.col() + 1) * 2]);
        }
    }

    public static final class Rotation extends Deformer {
        Rotation(final RotationDeformerSource source) {
            super(source);
        }

        @Override
        public Form currentForm() {
            final Object form = ((RotationDeformerSource) source).keyforms.first();
            return form instanceof RotationForm rotationForm ? rotationForm : new RotationForm();
        }
    }

    public static final class ArtMesh {
        final ACDrawableSource source;

        ArtMesh(final ACDrawableSource source) {
            this.source = source;
        }

        public ACDrawableSource source() {
            return source;
        }

        public Form calculatedForm() {
            return currentForm();
        }

        public ArtMeshForm currentForm() {
            final Object form = source.keyforms.first();
            return form instanceof ArtMeshForm artMeshForm ? artMeshForm : new ArtMeshForm();
        }
    }

    public static final class GlueSource {
        public ACDrawableSource targetA() {
            return null;
        }

        public ACDrawableSource targetB() {
            return null;
        }
    }

    public static class Id {
        public static final Id NORMAL = new Id("NORMAL");
        public static final Id MORPH_TARGET = new Id("MORPH_TARGET");
        final String value;

        public Id() {
            this.value = "";
        }

        public Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class Guid extends Id {
        private static int counter;

        public Guid() {
            super("guid-" + (++counter));
        }

        Guid(final String value) {
            super(value);
        }
    }

    public static final class Fixture {
        static {
            ModelSource.counter = 0;
        }

        public final ModelSource source = new ModelSource();
        public final PartSource rootPart = new PartSource("Root", source);
        public final PartSource parentPart = new PartSource("Parent", source);
        public final PartSource childPart = new PartSource("Child", source);
        public final WarpDeformerSource warpSource = new WarpDeformerSource("WarpA", source);
        public final WarpDeformerSource warpTargetSource = new WarpDeformerSource("WarpB", source);
        public final RotationDeformerSource rotationSource = new RotationDeformerSource("RotationA", source);
        public final ACDrawableSource meshSource = new ACDrawableSource("MeshA", source);
        public final PartSourceSet partSet = source.partSet;
        public final DeformerSourceSet deformerSet = source.deformerSet;
        public final DrawableSourceSet drawableSet = source.drawableSet;
        public final UpdateManager updateManager;
        public final Document document;
        public final EditMode editMode;
        public final CompletePack pack;
        public final Host host = Host.instance();

        public Fixture() {
            source.rootPart = rootPart;
            // Part tree: Root → Parent → Child.
            source.partSet.add(rootPart, -1);
            source.partSet.add(parentPart, -1);
            source.partSet.add(childPart, -1);
            rootPart.addChild(parentPart, -1);
            parentPart.addChild(childPart, -1);

            // Deformers and the drawable hang under Child.
            source.deformerSet.add(warpSource, -1);
            source.deformerSet.add(rotationSource, -1);
            childPart.addChild(warpSource, -1);
            childPart.addChild(rotationSource, -1);

            meshSource.localName = "MeshA";
            source.drawableSet.sources.add(meshSource);
            source.allObjects.add(meshSource);
            source.model.meshes.add(new ArtMesh(meshSource));
            childPart.addChild(meshSource, -1);

            // Reset counters polluted by fixture construction (host-side bootstrap is not a
            // hierarchy edit performed through the adapter).
            source.deformerSet.addCount = 0;
            rootPart.addChildCalls = 0;
            parentPart.addChildCalls = 0;
            childPart.addChildCalls = 0;
            rootPart.removeChildCalls = 0;
            parentPart.removeChildCalls = 0;
            childPart.removeChildCalls = 0;

            document = new Document(source);
            updateManager = document.updateManager;
            editMode = document.editMode;
            pack = document.pack;
        }

        public void replacePartWithSameId() {
            final PartSource replacement = new PartSource("Root", source);
            source.partSet.sources.clear();
            source.partSet.sources.add(replacement);
            source.model.parts.clear();
            source.model.parts.add(new HostPart(replacement));
        }

        public void replaceDeformerWithSameId() {
            final WarpDeformerSource replacement = new WarpDeformerSource("WarpA", source);
            source.deformerSet.sources.clear();
            source.deformerSet.sources.add(replacement);
            source.model.deformers.clear();
            source.model.deformers.add(new Warp(replacement));
        }

        /**
         * Registers the spare deformer target. Kept out of the constructor so the shared fixture
         * keeps its documented deformer/drawable counts for every other test.
         */
        public WarpDeformerSource attachWarpTarget() {
            source.deformerSet.add(warpTargetSource, -1);
            childPart.addChild(warpTargetSource, -1);
            source.deformerSet.addCount = 0;
            childPart.addChildCalls = 0;
            return warpTargetSource;
        }
    }

    public static List<StaticSelector> hierarchySelectors() {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                desc(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.update-manager",
                Host.class,
                "updateManager",
                desc(UpdateManager.class)));
        selectors.add(method("cubism.editor-model.app-controller.command-delete", Host.class, "commandDelete", "()V"));
        selectors.add(method(
                "cubism.editor-model.app-controller.command-delete-deformer-and-set-param",
                Host.class,
                "commandDeleteDeformerAndSetParam",
                "()V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.update-manager.class", internal(UpdateManager.class)));
        selectors.add(method(
                "cubism.editor-model.update-manager.set-selection",
                UpdateManager.class,
                "setSelection",
                "(Ljava/lang/Object;Ljava/util/List;ZZ)V"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                desc(ModelSource.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method(
                "cubism.editor-history.document.undo-manager", Document.class, "undoManager", desc(UndoManager.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.selector",
                Document.class,
                "selector",
                desc(NativeSelector.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.selection",
                ObjectSource.class,
                "selection",
                desc(ObjectSource.class)));
        selectors.add(method(
                "cubism.editor-model.selector.replace-selected",
                NativeSelector.class,
                "replace",
                "(Ljava/util/List;I)V"));
        selectors.add(method(
                "cubism.editor-model.selector.selected", NativeSelector.class, "selected", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.manager.class", internal(UndoManager.class)));
        selectors.add(
                method("cubism.editor-history.manager.entries", UndoManager.class, "entries", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-history.manager.position", UndoManager.class, "position", "()I"));
        selectors.add(method(
                "cubism.editor-history.manager.can-undo", UndoManager.class, "canUndo", "()Ljava/lang/Boolean;"));
        selectors.add(method(
                "cubism.editor-history.manager.can-redo", UndoManager.class, "canRedo", "()Ljava/lang/Boolean;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.entry.class", internal(GroupUndo.class)));
        selectors.add(method(
                "cubism.editor-history.entry.presentation-name",
                GroupUndo.class,
                "presentationName",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-history.entry.significant", GroupUndo.class, "significant", "()Ljava/lang/Boolean;"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method("cubism.editor-model.undo.execute-undo", Undo.class, "executeUndo", "()V"));
        selectors.add(method("cubism.editor-model.undo.group-undo", GroupUndo.class, "groupUndo", "()V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.current-instance",
                ModelSource.class,
                "currentInstance",
                desc(Model.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-id.class", internal(Id.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.model-source.update-visible-lock-hierarchy",
                ModelSource.class,
                "updateVisibleAndLockHierarchy",
                "()V"));
        selectors.add(
                method("cubism.editor-model.model-source.parts", ModelSource.class, "allParts", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-deformers",
                ModelSource.class,
                "allDeformers",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-art-meshes",
                ModelSource.class,
                "allArtMeshes",
                "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-objects", ModelSource.class, "allObjects", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.model-source.handler", ModelSource.class, "handler", desc(ModelHandler.class)));
        selectors.add(method(
                "cubism.editor-model.model-handler.add-source-undo",
                ModelHandler.class,
                "addSourceUndo",
                "(" + type(ObjectSource.class) + "I)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.part-source-set",
                ModelSource.class,
                "partSourceSet",
                desc(PartSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.deformer-source-set",
                ModelSource.class,
                "deformerSourceSet",
                desc(DeformerSourceSet.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.part-source-set.class", internal(PartSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.part-source-set.add",
                PartSourceSet.class,
                "add",
                "(" + type(PartSource.class) + "I)V"));
        selectors.add(method(
                "cubism.editor-model.part-source-set.remove",
                PartSourceSet.class,
                "remove",
                "(" + type(PartSource.class) + ")V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.deformer-source-set.class", internal(DeformerSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.deformer-source-set.add",
                DeformerSourceSet.class,
                "add",
                "(" + type(ACDeformerSource.class) + "I)V"));
        selectors.add(method(
                "cubism.editor-model.deformer-source-set.remove",
                DeformerSourceSet.class,
                "remove",
                "(" + type(ACDeformerSource.class) + ")V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.drawable-source-set.class", internal(DrawableSourceSet.class)));
        selectors.add(method(
                "cubism.editor-model.drawable-source-set.remove",
                DrawableSourceSet.class,
                "remove",
                "(" + type(ACDrawableSource.class) + ")V"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-source.create",
                internal(PartSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.warp-source.create",
                internal(WarpDeformerSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.rotation-source.create",
                internal(RotationDeformerSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.deformer-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.part-source.set-id", PartSource.class, "setId", "(" + type(Id.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.deformer-source.set-id",
                ACDeformerSource.class,
                "setId",
                "(" + type(Id.class) + ")V"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-form.create",
                internal(PartForm.class),
                "(" + type(PartSource.class) + "Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.warp-form.create",
                internal(WarpForm.class),
                "(" + type(WarpDeformerSource.class) + type(Warp.class) + type(CoordType.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.rotation-form.create",
                internal(RotationForm.class),
                "(" + type(RotationDeformerSource.class) + type(Rotation.class) + type(CoordType.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.form-guid.create", internal(Guid.class), "()V", StaticSelector.ACCESS_PUBLIC));
        selectors.add(
                method("cubism.editor-model.form.set-guid", Form.class, "setGuid", "(" + type(Guid.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.part-source.keyforms", PartSource.class, "keyforms", desc(ObjectList.class)));
        selectors.add(method(
                "cubism.editor-model.warp-source.keyforms",
                WarpDeformerSource.class,
                "keyforms",
                desc(ObjectList.class)));
        selectors.add(method(
                "cubism.editor-model.rotation-source.keyforms",
                RotationDeformerSource.class,
                "keyforms",
                desc(ObjectList.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.root-part", ModelSource.class, "rootPart", desc(PartSource.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.part-handler.class", internal(PartHandler.class)));
        selectors.add(method(
                "cubism.editor-model.part-handler.add-part-child",
                PartHandler.class,
                "addPartChild",
                "(" + type(ObjectSource.class) + "I)" + type(Undo.class)));
        selectors.add(method("cubism.editor-model.c-array-list.add", ObjectList.class, "add", "(Ljava/lang/Object;)Z"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.keyform-grid-source.create",
                internal(KeyformGridSource.class),
                "(" + type(ObjectSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.keyform-grid-source.import-cubism21",
                KeyformGridSource.class,
                "importCubism21",
                "(" + type(ModelSource.class) + "Ljava/util/List;Ljava/util/List;Ljava/lang/Object;)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
                ObjectSource.class,
                "setKeyformGridSource",
                "(" + type(KeyformGridSource.class) + ")V"));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.coord-type.canvas",
                internal(CoordType.class),
                "canvas",
                desc(CoordType.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method("cubism.editor-model.warp-source.set-col", WarpDeformerSource.class, "setCol", "(I)V"));
        selectors.add(method("cubism.editor-model.warp-source.set-row", WarpDeformerSource.class, "setRow", "(I)V"));
        selectors.add(method(
                "cubism.editor-model.warp-source.set-quad-transform",
                WarpDeformerSource.class,
                "setQuadTransform",
                "(Z)V"));
        selectors.add(method("cubism.editor-model.warp-source.col", WarpDeformerSource.class, "col", "()I"));
        selectors.add(method("cubism.editor-model.warp-source.row", WarpDeformerSource.class, "row", "()I"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.part-source.local-name", ObjectSource.class, "localName", "()Ljava/lang/String;"));
        selectors.add(
                method("cubism.editor-model.part-source.parent", ObjectSource.class, "parent", desc(PartSource.class)));
        selectors.add(
                method("cubism.editor-model.part-source.children", PartSource.class, "children", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.part-source.add-child",
                PartSource.class,
                "addChild",
                "(" + type(ObjectSource.class) + "I)V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(HostPart.class)));
        selectors.add(method("cubism.editor-model.part.source", HostPart.class, "source", desc(PartSource.class)));
        selectors.add(method("cubism.editor-model.model.parts", Model.class, "allParts", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.warp-source.class", internal(WarpDeformerSource.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.rotation-source.class", internal(RotationDeformerSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.warp.class", internal(Warp.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.rotation.class", internal(Rotation.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.art-mesh-source.class", internal(ACDrawableSource.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.art-mesh-source.create",
                internal(ACDrawableSource.class),
                "(" + type(ModelSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.drawable-id.create",
                internal(Id.class),
                "(Ljava/lang/String;)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.drawable-source.set-id",
                ACDrawableSource.class,
                "setId",
                "(" + type(Id.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.keyforms",
                ACDrawableSource.class,
                "keyforms",
                desc(ObjectList.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.art-mesh-form.create",
                internal(ArtMeshForm.class),
                "(" + type(ACDrawableSource.class) + type(ArtMesh.class) + type(CoordType.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(
                method("cubism.editor-model.art-mesh-form.set-positions", ArtMeshForm.class, "setPositions", "([F)V"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.set-positions", ACDrawableSource.class, "setPositions", "([F)V"));
        selectors.add(method("cubism.editor-model.art-mesh-source.set-uvs", ACDrawableSource.class, "setUvs", "([F)V"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.set-indices", ACDrawableSource.class, "setIndices", "([I)V"));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.editable-mesh-extension.class", internal(EditableExtension.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.editable-mesh-extension.create",
                internal(EditableExtension.class),
                "(" + type(ACDrawableSource.class) + ")V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method(
                "cubism.editor-model.editable-mesh-extension.mesh",
                EditableExtension.class,
                "mesh",
                desc(EditableMesh.class)));
        selectors.add(method(
                "cubism.editor-model.editable-mesh-extension.owner",
                EditableExtension.class,
                "owner",
                desc(ACDrawableSource.class)));
        selectors.add(
                method("cubism.editor-model.editable-mesh-extension.locked", EditableExtension.class, "locked", "()Z"));
        selectors.add(method(
                "cubism.editor-model.editable-mesh.init-by-indices",
                EditableMesh.class,
                "init",
                "([F[I" + type(CoordType.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.add-extension",
                ACDrawableSource.class,
                "addExtension",
                "(" + type(EditableExtension.class) + "I)V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.art-mesh.class", internal(ArtMesh.class)));
        selectors.add(
                method("cubism.editor-model.deformer.source", Deformer.class, "source", desc(ObjectSource.class)));
        selectors.add(
                method("cubism.editor-model.art-mesh.source", ArtMesh.class, "source", desc(ACDrawableSource.class)));
        selectors.add(method(
                "cubism.editor-model.art-mesh.current-keyform", ArtMesh.class, "currentForm", desc(ArtMeshForm.class)));
        selectors.add(method("cubism.editor-model.art-mesh-form.positions", ArtMeshForm.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.drawable-form.opacity", Form.class, "opacity", "()F"));
        selectors.add(method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"));
        selectors.add(
                method("cubism.editor-model.art-mesh-source.guid", ACDrawableSource.class, "guid", desc(Guid.class)));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.clip-guid-list",
                ACDrawableSource.class,
                "clipGuids",
                "()Ljava/util/List;"));
        selectors.add(
                method("cubism.editor-model.art-mesh-source.positions", ACDrawableSource.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.art-mesh-source.uvs", ACDrawableSource.class, "uvs", "()[F"));
        selectors.add(method("cubism.editor-model.art-mesh-source.indices", ACDrawableSource.class, "indices", "()[I"));
        selectors.add(method("cubism.editor-model.art-mesh-source.culling", ACDrawableSource.class, "culling", "()Z"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.user-data",
                ACDrawableSource.class,
                "userData",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.art-mesh-source.inverted-mask", ACDrawableSource.class, "invertedMask", "()Z"));
        selectors.add(method(
                "cubism.editor-model.deformer.current-keyform", Deformer.class, "currentForm", desc(Form.class)));
        selectors.add(method("cubism.editor-model.deformer-form.opacity", Form.class, "opacity", "()F"));
        selectors.add(method(
                "cubism.editor-model.warp-source.quad-transform", WarpDeformerSource.class, "quadTransform", "()Z"));
        selectors.add(method("cubism.editor-model.warp-form.positions", WarpForm.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.warp-form.set-positions", WarpForm.class, "setPositions", "([F)V"));
        selectors.add(method(
                "cubism.editor-model.rotation-source.base-angle", RotationDeformerSource.class, "baseAngle", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.angle", RotationForm.class, "angle", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.origin-x", RotationForm.class, "originX", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.origin-y", RotationForm.class, "originY", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.scale", RotationForm.class, "scale", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.reflect-x", RotationForm.class, "reflectX", "()Z"));
        selectors.add(method("cubism.editor-model.rotation-form.reflect-y", RotationForm.class, "reflectY", "()Z"));
        selectors.add(method("cubism.editor-model.rotation-form.set-angle", RotationForm.class, "setAngle", "(F)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-origin-x", RotationForm.class, "setOriginX", "(F)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-origin-y", RotationForm.class, "setOriginY", "(F)V"));
        selectors.add(method("cubism.editor-model.rotation-form.set-scale", RotationForm.class, "setScale", "(F)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-reflect-x", RotationForm.class, "setReflectX", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.rotation-form.set-reflect-y", RotationForm.class, "setReflectY", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.model-source.all-glues", ModelSource.class, "allGlues", "()Ljava/util/List;"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.glue-source.class", internal(GlueSource.class)));
        selectors.add(method(
                "cubism.editor-model.glue-source.target-art-mesh-a",
                GlueSource.class,
                "targetA",
                desc(ACDrawableSource.class)));
        selectors.add(method(
                "cubism.editor-model.glue-source.target-art-mesh-b",
                GlueSource.class,
                "targetB",
                desc(ACDrawableSource.class)));
        selectors.add(method(
                "cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-set.parameters",
                ParameterSet.class,
                "parameters",
                "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(Parameter.class)));
        selectors.add(method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.visible", ObjectSource.class, "visible", "()Z"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.locked", ObjectSource.class, "locked", "()Z"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
                ObjectSource.class,
                "visibleInHierarchy",
                "()Z"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
                ObjectSource.class,
                "lockedInHierarchy",
                "()Z"));
        selectors.add(
                method("cubism.editor-model.model.all-deformers", Model.class, "allDeformers", "()Ljava/util/List;"));
        selectors.add(
                method("cubism.editor-model.model.all-art-meshes", Model.class, "allArtMeshes", "()Ljava/util/List;"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", desc(Id.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.local-name",
                ObjectSource.class,
                "localName",
                "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-local-name",
                ObjectSource.class,
                "setLocalName",
                "(Ljava/lang/String;)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.guid",
                ObjectSource.class,
                "guid",
                desc(Guid.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.handler",
                ObjectSource.class,
                "handler",
                desc(ACParameterControllableHandler.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.target-deformer-guid",
                ObjectSource.class,
                "targetDeformerGuid",
                desc(Guid.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
                ObjectSource.class,
                "setTargetDeformerGuid",
                "(" + type(Guid.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
                ObjectSource.class,
                "allParentDeformers",
                "()Ljava/lang/Iterable;"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                ObjectSource.class,
                "targetDeformerSource",
                desc(ObjectSource.class)));
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.parameter-controllable-handler.class",
                internal(ACParameterControllableHandler.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                ACParameterControllableHandler.class,
                "undo",
                "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.part-source.handler",
                PartSource.class,
                "partHandler",
                "()" + type(PartHandler.class)));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-part-palette",
                CompletePack.class,
                "updatePartPalette",
                "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-deformer-palette",
                CompletePack.class,
                "updateDeformerPalette",
                "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaintCanvas", "(Z)V"));

        selectors.add(StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.id.class", internal(Id.class)));
        selectors.add(
                method("cubism.editor-model.model-source.name", ModelSource.class, "name", "()Ljava/lang/String;"));
        selectors.add(method(
                "cubism.editor-model.part-source.set-local-name",
                PartSource.class,
                "setLocalName",
                "(Ljava/lang/String;)V"));
        selectors.add(method("cubism.editor-model.part-source.default-order", PartSource.class, "defaultOrder", "()I"));
        selectors.add(method(
                "cubism.editor-model.part-source.set-default-order", PartSource.class, "setDefaultOrder", "(I)V"));
        selectors.add(method("cubism.editor-model.part-source.sketch", PartSource.class, "sketch", "()Z"));
        selectors.add(method("cubism.editor-model.part-source.set-sketch", PartSource.class, "setSketch", "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.part-source.edit-color", PartSource.class, "editColor", desc(HostColor.class)));
        selectors.add(method(
                "cubism.editor-model.part-source.set-edit-color",
                PartSource.class,
                "setEditColor",
                "(" + type(HostColor.class) + ")V"));
        selectors.add(method(
                "cubism.editor-model.part-source.create-undo-for-basic-settings",
                PartSource.class,
                "basicUndo",
                "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-visible",
                PartSource.class,
                "setVisible",
                "(Z)V"));
        selectors.add(method(
                "cubism.editor-model.parameter-controllable-source.set-locked", PartSource.class, "setLocked", "(Z)V"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.color.class", internal(HostColor.class)));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.color.create",
                internal(HostColor.class),
                "(FFFF)V",
                StaticSelector.ACCESS_PUBLIC));
        selectors.add(method("cubism.editor-model.color.red", HostColor.class, "red", "()F"));
        selectors.add(method("cubism.editor-model.color.green", HostColor.class, "green", "()F"));
        selectors.add(method("cubism.editor-model.color.blue", HostColor.class, "blue", "()F"));
        selectors.add(method("cubism.editor-model.color.alpha", HostColor.class, "alpha", "()F"));
        selectors.add(method(
                "cubism.editor-model.part-handler.create-undo-for-all-edit",
                PartHandler.class,
                "undo",
                "(Ljava/lang/String;)" + type(Undo.class)));
        selectors.add(method("cubism.editor-model.part.parts-opacity", HostPart.class, "partsOpacity", "()F"));
        selectors.add(method("cubism.editor-model.part.set-parts-opacity", HostPart.class, "setPartsOpacity", "(F)V"));
        selectors.add(method(
                "cubism.editor-model.part.current-keyform", HostPart.class, "currentForm", desc(PartForm.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-form.class", internal(PartForm.class)));
        selectors.add(method("cubism.editor-model.part-form.opacity", PartForm.class, "opacity", "()F"));
        selectors.add(method("cubism.editor-model.part-form.set-opacity", PartForm.class, "setOpacity", "(F)V"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.model-handler.class", internal(ModelHandler.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.model-handler.create-free-id-default",
                internal(ModelHandler.class),
                "createFreeIdDefault",
                "(L" + internal(ModelHandler.class) + ";L" + internal(Id.class) + ";L" + internal(Object.class)
                        + ";ILjava/lang/Object;)L" + internal(Id.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.model-handler.remove-objects",
                ModelHandler.class,
                "removeObjects",
                "(Ljava/util/List;L" + internal(Model.class) + ";Z)L" + internal(Undo.class) + ";"));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.copy-helper.copy",
                internal(CopyHelper.class),
                "copy",
                "(L" + internal(Object.class) + ";L" + internal(Object.class) + ";ILjava/lang/Object;)L"
                        + internal(Object.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.part-source.set-guid",
                PartSource.class,
                "setGuid",
                "(L" + internal(Id.class) + ";)V"));
        selectors.add(StaticSelector.constructor(
                "cubism.editor-model.part-guid.create", internal(Id.class), "()V", StaticSelector.ACCESS_PUBLIC));

        return selectors;
    }

    /**
     * Selectors for the public target-deformer routes. Both routes are gated on the reviewed
     * Inspector admission, so the fixture record must carry each contract's complete
     * required-alias inventory. Aliases this fixture never invokes are recorded with a placeholder
     * owner because the fixture host only mirrors the members the tests actually exercise; an
     * invoked alias must still resolve against a real fixture class.
     */
    public static List<StaticSelector> hierarchyRelationSelectors() {
        final List<StaticSelector> selectors = new ArrayList<>(hierarchySelectors());
        selectors.add(StaticSelector.classSelector(
                "cubism.editor-model.deformer-source.class", internal(ACDeformerSource.class)));
        selectors.add(method("cubism.editor-model.deformer-source.guid", ObjectSource.class, "guid", desc(Guid.class)));
        final java.util.Set<String> recorded = new java.util.HashSet<>();
        for (final StaticSelector selector : selectors) recorded.add(selector.alias());
        final java.util.Set<String> required =
                new java.util.HashSet<>(EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES);
        required.addAll(EditorDeformerInspectorSelectorContract.REQUIRED_ALIASES);
        required.addAll(EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract.REQUIRED_ALIASES);
        for (final String alias : required) {
            if (!recorded.contains(alias)) {
                selectors.add(StaticSelector.method(alias, "fixture/uninvoked/Host", "uninvoked", "()V"));
            }
        }
        return selectors;
    }

    public static final class HierarchyScopedFixture {
        final Fixture fixture = new Fixture();
        final ScopedHost host = new ScopedHost(fixture);
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
                "hierarchy-test", "document", 1, "model", 1, Thread.currentThread());
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(host);
        final EditorObjectHierarchyEditAccess access;

        HierarchyScopedFixture(final VerifiedMemberResolver resolver) {
            Host.document = fixture.document;
            final EditorParameterCombinedAccess.ModelGuard guard = (identity, model) -> {};
            access = new EditorObjectHierarchyEditAccess(resolver, guard, coordinator, () -> binding);
        }

        // The fixture must live on the Swing EDT: the coordinator's ambient scope and the
        // binding's host thread are thread-confined, and access writes dispatch to the EDT.
        static HierarchyScopedFixture onEdt(final VerifiedMemberResolver resolver) {
            final java.util.concurrent.atomic.AtomicReference<HierarchyScopedFixture> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> ref.set(new HierarchyScopedFixture(resolver)));
            } catch (java.lang.reflect.InvocationTargetException failure) {
                final Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return ref.get();
        }

        <T> dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options,
                final dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork<T> work) {
            final java.util.concurrent.atomic.AtomicReference<
                            dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult<T>>
                    ref = new java.util.concurrent.atomic.AtomicReference<>();
            try {
                javax.swing.SwingUtilities.invokeAndWait(() -> ref.set(coordinator.execute(binding, options, work)));
            } catch (java.lang.reflect.InvocationTargetException failure) {
                final Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return ref.get();
        }
    }
}
