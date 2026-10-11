package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.method;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.SwingUtilities;

/** Real resolver/access/coordinator with native-list and undo substitutes, never host execution. */
final class EditorArtMeshMorphFixture implements EditorAuthoringTransactionCoordinator.Host {
    @Override
    public void undoEditGroup(
            final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit) {}

    static final ThreadLocal<EditorArtMeshMorphFixture> CURRENT = new ThreadLocal<>();
    static final String M = "cubism.editor-model.", H = "cubism.editor-history.semantic.";
    final Source source = new Source();
    final Model model = new Model(source);
    final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(this);
    final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
            "morph-test", "document", 1, "model", 1, Thread.currentThread());
    VerifiedMemberResolver resolver;
    Object currentSource = source;
    final List<Undo> history = new ArrayList<>();
    final List<HistoryEntry> entries = new ArrayList<>();
    int position, revision, begins, mutations, snapshots, refreshes, dirty, counter;
    final List<String> writeLog = new ArrayList<>();
    boolean nativeAbort, denyTransaction, rejectRoot, throwRoot, rejectListener, throwListener, failSnapshot;
    boolean partialAdd, wrongSeed, doubleAdd;
    boolean forceNativeNull;
    int instanceUpdates, repaints;
    Runnable afterMutation = () -> {};
    Runnable afterRefresh = () -> {};

    EditorArtMeshMorphFixture() {
        edt();
        CURRENT.set(this);
        source.model = model;
        resolver = resolver("5.3.02", null, null);
    }

    ArtMeshMorphKeyform key(final float value) {
        return key("m", value, false);
    }

    ArtMeshMorphKeyform create(final float value) {
        return key("m", value, true);
    }

    ArtMeshMorphKeyform key(final String guid, final float value, final boolean create) {
        return EditorArtMeshMorphAccess.open(
                resolver,
                new ArtMeshId("mesh"),
                () -> currentSource,
                model,
                model,
                guid,
                value,
                create,
                () -> {
                    if (denyTransaction) throw new UnsupportedOperationException("no coordinator");
                },
                contribution -> coordinator.mutate(binding, contribution),
                this::refreshNative);
    }

    EditorArtMeshMorphState state() {
        return EditorArtMeshMorphState.read(resolver, new ArtMeshId("mesh"), source, model, model);
    }

    AuthoringTransactionResult<Void> transaction(final Runnable callback) {
        return coordinator.execute(binding, AuthoringTransactionOptions.of("morph test"), () -> {
            callback.run();
            return null;
        });
    }

    void refreshNative() {
        edt();
        refreshes++;
        final var callback = afterRefresh;
        afterRefresh = () -> {};
        callback.run();
    }

    void undo() {
        history.get(--position).undo();
        revision++;
    }

    void redo() {
        history.get(position++).redo();
        revision++;
    }

    static void edt() {
        assertTrue(SwingUtilities.isEventDispatchThread());
    }

    Drawable drawable(final VerifiedMemberResolver r, final boolean withCoordinator) {
        final EditorObjectReadAccess.CurrentGuard guard = (id, value) -> {
            edt();
            if (!"model".equals(id) || value != model || currentSource != source)
                throw new IllegalStateException("stale model");
        };
        return new EditorObjectReadAccess(
                        r,
                        guard,
                        new EditorMorphTargetAccess(r, guard::requireCurrent),
                        null,
                        null,
                        null,
                        withCoordinator ? coordinator : null,
                        withCoordinator ? () -> binding : null)
                .drawables("model", model, model)
                .find(new ArtMeshId("mesh"));
    }

    public static EditorArtMeshMorphFixture instance() {
        edt();
        return CURRENT.get();
    }

    public EditorArtMeshMorphFixture completePack() {
        edt();
        return this;
    }

    public void updatePalette(final boolean update) {
        edt();
    }

    public void repaint(final boolean update) {
        edt();
        repaints++;
        refreshNative();
    }

    VerifiedMemberResolver resolver(final String version, final String missingAlias, final String missingCapability) {
        final var s = new LinkedHashMap<String, StaticSelector>();
        java.util.function.Consumer<StaticSelector> add = selector -> s.put(selector.alias(), selector);
        add.accept(method(M + "guid.value", Guid.class, "value"));
        add.accept(method(M + "form-guid.value", Guid.class, "value"));
        add.accept(method(M + "id.value", Guid.class, "value"));
        final String[][] sourceMethods = {
            {"art-mesh-source.guid", "guid"},
            {"art-mesh-source.positions", "positions"},
            {"art-mesh-source.uvs", "uvs"},
            {"art-mesh-source.indices", "indices"},
            {"art-mesh-source.keyforms", "forms"},
            {"parameter-controllable.keyform-grid", "grid"},
            {"parameter-controllable.morph-target-set", "targets"},
            {"parameter-controllable-source.extensions", "extensions"},
            {"parameter-controllable-source.locked-in-hierarchy", "locked"},
            {"parameter-controllable-source.target-deformer-source", "deformer"},
            {"parameter-controllable-source.extended-morph-set", "extended"}
        };
        for (var pair : sourceMethods) add.accept(method(M + pair[0], Source.class, pair[1]));
        add.accept(method(M + "model.parameter-set", Model.class, "self"));
        add.accept(method(M + "parameter-set.parameters", Model.class, "parameters"));
        add.accept(method(M + "model-source.root-part", Model.class, "self"));
        add.accept(method(M + "part-source.children", Model.class, "children"));
        add.accept(method(M + "parameter.source", Definition.class, "self"));
        add.accept(method(M + "parameter.value", Definition.class, "value"));
        for (String name : List.of("guid", "id", "name", "minimum", "maximum", "repeat"))
            add.accept(method(M + "parameter-source." + name, Definition.class, name));
        add.accept(method(M + "parameter-source.default", Definition.class, "defaultValue"));
        add.accept(method(M + "parameter-source.snap-epsilon", Definition.class, "epsilon"));
        add.accept(method(M + "parameter-source.morph-target", Definition.class, "morph"));
        add.accept(method(M + "parameter-source.model-source", Definition.class, "model"));
        add.accept(method(M + "keyform-grid.bindings", Grid.class, "bindings"));
        add.accept(method(M + "keyform-binding.keys", Definition.class, "keys"));
        add.accept(method(M + "keyform-binding.parameter-guid", Definition.class, "guid"));
        add.accept(method(M + "keyform-binding.parameter-id", Definition.class, "id"));
        add.accept(method(H + "keyform-binding.parameter", Definition.class, "self"));
        add.accept(method(H + "keyform-grid.forms-for-guid", Grid.class, "rows", Guid.class));
        add.accept(method(H + "keyform-on-grid.access-key", Row.class, "key"));
        add.accept(method(H + "keyform-access-key.coordinates", AccessKey.class, "coordinates"));
        add.accept(method(H + "key-on-parameter.binding", Coordinate.class, "binding"));
        add.accept(method(H + "key-on-parameter.value", Coordinate.class, "value"));
        add.accept(method(H + "form.guid", NativeForm.class, "guid"));
        add.accept(method(H + "art-mesh-form.source", NativeForm.class, "source"));
        add.accept(method(M + "art-mesh-form.positions", NativeForm.class, "positions"));
        add.accept(method(M + "art-mesh-form.set-positions", NativeForm.class, "setPositions", float[].class));
        add.accept(method(M + "drawable-form.draw-order", NativeForm.class, "order"));
        add.accept(method(M + "drawable-form.set-draw-order", NativeForm.class, "setOrder", int.class));
        add.accept(method(M + "drawable-form.opacity", NativeForm.class, "opacity"));
        add.accept(method(M + "drawable-form.multiply-color", NativeForm.class, "multiply"));
        add.accept(method(M + "drawable-form.screen-color", NativeForm.class, "screen"));
        add.accept(method(M + "drawable-form.coord-type", NativeForm.class, "coordinate"));
        add.accept(method(M + "coord-type.label", CoordType.class, "label"));
        add.accept(method(M + "form.name", NativeForm.class, "name"));
        add.accept(method(M + "form.notes", NativeForm.class, "notes"));
        for (String name : List.of("red", "green", "blue", "alpha"))
            add.accept(method(M + "float-color." + name, Color.class, name));
        add.accept(method(M + "morph-target-set.morph-targets", TargetSet.class, "targets"));
        add.accept(method(M + "morph-target-set.owner", TargetSet.class, "owner"));
        add.accept(method(M + "morph-target-set.constraints", TargetSet.class, "constraints"));
        add.accept(method(M + "morph-constraints.entries", Constraints.class, "entries"));
        add.accept(
                method(M + "morph-target-set.can-add-key", TargetSet.class, "canAdd", Definition.class, float.class));
        add.accept(method(M + "morph-target-set.create-undo", TargetSet.class, "snapshot", String.class));
        add.accept(method(M + "morph-target.parameter-guid", Target.class, "parameterGuid"));
        add.accept(method(M + "morph-target.keyform-guid", Target.class, "formGuid"));
        add.accept(method(M + "morph-target.key-value", Target.class, "value"));
        add.accept(method(M + "morph-target.owner", Target.class, "owner"));
        add.accept(method(M + "native-list.immutable", NativeList.class, "immutable"));
        add.accept(StaticSelector.classSelector(M + "extended-interpolation.class", name(Extended.class)));
        add.accept(StaticSelector.field(
                M + "morph-authoring-utils.instance",
                name(Utils.class),
                "INSTANCE",
                "L" + name(Utils.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        add.accept(method(
                M + "morph-authoring-utils.create-key",
                Utils.class,
                "create",
                Model.class,
                Source.class,
                float.class,
                Guid.class,
                Group.class));
        add.accept(constructor("list-undo.create", ListSnapshot.class, String.class, ArrayList.class));
        add.accept(constructor("group-undo.create", Group.class, String.class));
        add.accept(method(M + "undo.add", Group.class, "add", Undo.class, boolean.class));
        add.accept(method(M + "undo.add-listener", Undo.class, "addListener", Listener.class));
        add.accept(method(M + "undo.execute-undo", Undo.class, "undo"));
        add.accept(StaticSelector.classSelector(M + "undo-listener.class", name(Listener.class)));
        add.accept(StaticSelector.classSelector(M + "art-mesh.class", name(Mesh.class)));
        add.accept(StaticSelector.classSelector(M + "art-mesh-source.class", name(Source.class)));
        add.accept(method(M + "model-source.all-art-meshes", Model.class, "children"));
        add.accept(method(M + "model.all-art-meshes", Model.class, "meshes"));
        add.accept(method(M + "art-mesh.source", Mesh.class, "source"));
        add.accept(method(M + "parameter-controllable-source.id", Source.class, "id"));
        add.accept(method(M + "model-source.update-instances", Model.class, "updateInstances"));
        add.accept(StaticSelector.staticMethod(
                M + "app-controller.instance",
                name(EditorArtMeshMorphFixture.class),
                "instance",
                "()L" + name(EditorArtMeshMorphFixture.class) + ";",
                StaticSelector.ACCESS_PUBLIC));
        add.accept(method(M + "app-controller.complete-pack", EditorArtMeshMorphFixture.class, "completePack"));
        add.accept(method(
                M + "complete-pack.update-part-palette",
                EditorArtMeshMorphFixture.class,
                "updatePalette",
                boolean.class));
        add.accept(method(
                M + "complete-pack.update-deformer-palette",
                EditorArtMeshMorphFixture.class,
                "updatePalette",
                boolean.class));
        add.accept(
                method(M + "complete-pack.repaint-canvas", EditorArtMeshMorphFixture.class, "repaint", boolean.class));
        final var legacy = new HashSet<>(EditorObjectReadSelectorContract.REQUIRED_ALIASES);
        legacy.addAll(EditorHistoryReadSelectorContract.REQUIRED_ALIASES);
        for (String alias : legacy) s.putIfAbsent(alias, method(alias, Unused.class, "fail"));
        for (String alias : EditorArtMeshMorphSelectorContract.REQUIRED_ALIASES)
            s.putIfAbsent(alias, method(alias, Unused.class, "fail"));
        s.remove(missingAlias);
        final var caps = new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS);
        caps.add(EditorArtMeshMorphSelectorContract.CAPABILITY_ID);
        caps.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        caps.add("cubism.editor-history.read");
        caps.remove(missingCapability);
        return TestVerifiedResolvers.create(
                version,
                EditorArtMeshMorphSelectorContract.ADAPTER_SLICE_ID,
                caps,
                List.copyOf(s.values()),
                getClass().getClassLoader());
    }

    private static StaticSelector constructor(final String alias, final Class<?> owner, final Class<?>... args) {
        return StaticSelector.constructor(
                M + alias, name(owner), MethodType.methodType(void.class, args).toMethodDescriptorString(), 1);
    }

    private static String name(final Class<?> c) {
        return c.getName().replace('.', '/');
    }

    public static final class Unused {
        public Object fail() {
            throw new AssertionError("unused selector invoked");
        }
    }

    public static final class Extended {}

    public record Guid(String value) {}

    public record Color(float red, float green, float blue, float alpha) {}

    public record CoordType(String label) {}

    public static final class NativeList<E> extends ArrayList<E> {
        boolean immutable;

        public boolean immutable() {
            edt();
            return immutable;
        }
    }

    public static final class Definition {
        final String id;
        final boolean morph;
        float value, minimum, defaultValue, maximum = 1, epsilon = .001f;
        boolean repeat;

        Definition(final String id, final boolean morph) {
            this.id = id;
            this.morph = morph;
            minimum = morph ? 0 : -1;
        }

        public Definition self() {
            edt();
            return this;
        }

        public Guid guid() {
            return new Guid(id);
        }

        public Guid id() {
            return new Guid("Param" + id);
        }

        public String name() {
            return id;
        }

        public Model model() {
            return CURRENT.get().model;
        }

        public float value() {
            return value;
        }

        public float minimum() {
            return minimum;
        }

        public float defaultValue() {
            return defaultValue;
        }

        public float maximum() {
            return maximum;
        }

        public float epsilon() {
            return epsilon;
        }

        public boolean morph() {
            return morph;
        }

        public boolean repeat() {
            return repeat;
        }

        public List<Float> keys() {
            return List.of(-1f, 0f, 1f);
        }
    }

    public record Coordinate(Definition binding, float value) {}

    public record AccessKey(List<Coordinate> coordinates) {}

    public record Row(AccessKey key) {}

    public static final class Grid {
        final List<Definition> bindings = List.of(new Definition("x", false), new Definition("y", false));
        final Map<String, List<Row>> rows = new LinkedHashMap<>();

        public List<Definition> bindings() {
            return bindings;
        }

        public List<Row> rows(final Guid guid) {
            return rows.getOrDefault(guid.value(), List.of());
        }
    }

    public static final class NativeForm {
        final Source source;
        final Guid guid;
        float[] positions;
        int order = 500;
        float opacity = 1;
        Color multiply = new Color(1, 1, 1, 1), screen = new Color(0, 0, 0, 1);
        String name = "form", notes, coordinate = "Canvas";

        NativeForm(final Source source, final String guid, final float[] positions) {
            this.source = source;
            this.guid = new Guid(guid);
            this.positions = positions.clone();
        }

        NativeForm copy() {
            final var f = new NativeForm(source, guid.value(), positions);
            f.order = order;
            f.opacity = opacity;
            f.multiply = multiply;
            f.screen = screen;
            f.name = name;
            f.notes = notes;
            f.coordinate = coordinate;
            return f;
        }

        void restore(final NativeForm f) {
            positions = f.positions.clone();
            order = f.order;
            opacity = f.opacity;
            multiply = f.multiply;
            screen = f.screen;
            name = f.name;
            notes = f.notes;
            coordinate = f.coordinate;
        }

        public Source source() {
            return source;
        }

        public Guid guid() {
            return guid;
        }

        public float[] positions() {
            return positions;
        }

        public int order() {
            return order;
        }

        public float opacity() {
            return opacity;
        }

        public Color multiply() {
            return multiply;
        }

        public Color screen() {
            return screen;
        }

        public CoordType coordinate() {
            return new CoordType(coordinate);
        }

        public String name() {
            return name;
        }

        public String notes() {
            return notes;
        }

        public void setPositions(final float[] positions) {
            edt();
            this.positions = positions;
            final var f = CURRENT.get();
            f.mutations++;
            f.writeLog.add("positions:" + guid.value());
            f.afterMutation.run();
        }

        public void setOrder(final int value) {
            edt();
            this.order = value;
            final var f = CURRENT.get();
            f.mutations++;
            f.writeLog.add("order:" + guid.value());
            f.afterMutation.run();
        }
    }

    public record Target(Source owner, Guid parameterGuid, Guid formGuid, Float value) {
        Target copy() {
            return new Target(owner, parameterGuid, formGuid, value);
        }
    }

    public static final class Constraints {
        final List<Object> entries = new ArrayList<>();

        public List<Object> entries() {
            return entries;
        }
    }

    public static final class TargetSet {
        final Source source;
        final NativeList<Target> targets = new NativeList<>();
        final Constraints constraints = new Constraints();

        TargetSet(final Source source) {
            this.source = source;
        }

        public Source owner() {
            return source;
        }

        public List<Target> targets() {
            return targets;
        }

        public Constraints constraints() {
            return constraints;
        }

        public boolean canAdd(final Definition p, final float value) {
            return value > p.epsilon
                    && targets.stream()
                            .noneMatch(t ->
                                    t.parameterGuid().equals(p.guid()) && Math.abs(t.value() - value) <= p.epsilon);
        }

        public Undo snapshot(final String label) {
            return new TargetSnapshot(this);
        }
    }

    public static final class Source {
        final Grid grid = new Grid();
        final NativeList<NativeForm> forms = new NativeList<>();
        final TargetSet targets = new TargetSet(this);
        final List<Object> extensions = new ArrayList<>();
        float[] positions = {8, 8, 264, 8, 264, 136, 8, 136};
        Model model;
        boolean locked;
        Object deformer, extended;

        Source() {
            for (int i = 0; i < 9; i++) {
                String id = "f" + i;
                forms.add(new NativeForm(this, id, positions));
                grid.rows.put(
                        id,
                        List.of(new Row(new AccessKey(List.of(
                                new Coordinate(grid.bindings.get(0), i / 3 - 1),
                                new Coordinate(grid.bindings.get(1), i % 3 - 1))))));
            }
        }

        public Guid guid() {
            edt();
            return new Guid("source");
        }

        public Guid id() {
            edt();
            return new Guid("mesh");
        }

        public float[] positions() {
            return positions;
        }

        public float[] uvs() {
            return new float[] {0, 0, 1, 0, 1, 1, 0, 1};
        }

        public int[] indices() {
            return new int[] {0, 1, 2, 0, 2, 3};
        }

        public List<NativeForm> forms() {
            return forms;
        }

        public Grid grid() {
            return grid;
        }

        public TargetSet targets() {
            return targets;
        }

        public List<Object> extensions() {
            return extensions;
        }

        public boolean locked() {
            return locked;
        }

        public Object deformer() {
            return deformer;
        }

        public Object extended() {
            return extended;
        }
    }

    public static final class Model {
        final Source source;
        final List<Definition> parameters = new ArrayList<>();
        Mesh mesh;

        Model(final Source source) {
            this.source = source;
            parameters.addAll(source.grid.bindings);
            parameters.add(new Definition("m", true));
            parameters.add(new Definition("n", true));
            mesh = new Mesh(source);
        }

        public Model self() {
            return this;
        }

        public List<Definition> parameters() {
            return parameters;
        }

        public List<Source> children() {
            return List.of(source);
        }

        public List<Mesh> meshes() {
            edt();
            return List.of(mesh);
        }

        public void updateInstances() {
            edt();
            CURRENT.get().instanceUpdates++;
        }
    }

    public interface Listener {
        void changed(Object event);
    }

    public record Mesh(Source source) {}

    public abstract static class Undo {
        final List<Listener> listeners = new ArrayList<>();

        public boolean addListener(final Listener listener) {
            if (CURRENT.get().throwListener) throw new IllegalStateException("listener");
            if (CURRENT.get().rejectListener) return false;
            listeners.add(listener);
            return true;
        }

        void notifyListeners() {
            listeners.forEach(l -> l.changed(this));
        }

        public abstract void undo();

        public abstract void redo();
    }

    public static final class Group extends Undo {
        final List<Undo> children = new ArrayList<>();
        boolean root;

        public Group(final String label) {}

        public boolean add(final Undo child, final boolean accepted) {
            assertTrue(accepted);
            final var f = CURRENT.get();
            if (root && f.throwRoot) throw new IllegalStateException("root admission");
            if (root && f.rejectRoot) return false;
            children.add(child);
            return true;
        }

        @Override
        public void undo() {
            for (int i = children.size() - 1; i >= 0; i--) children.get(i).undo();
            notifyListeners();
        }

        @Override
        public void redo() {
            children.forEach(Undo::redo);
            notifyListeners();
        }
    }

    public static final class ListSnapshot extends Undo {
        final ArrayList<NativeForm> list;
        final List<NativeForm> beforeRefs, beforeValues;
        List<NativeForm> afterRefs, afterValues;

        public ListSnapshot(final String label, final ArrayList<NativeForm> list) {
            final var f = CURRENT.get();
            f.snapshots++;
            if (f.failSnapshot) throw new IllegalStateException("snapshot");
            this.list = list;
            beforeRefs = List.copyOf(list);
            beforeValues = list.stream().map(NativeForm::copy).toList();
        }

        @Override
        public void undo() {
            if (afterRefs == null) {
                afterRefs = List.copyOf(list);
                afterValues = list.stream().map(NativeForm::copy).toList();
            }
            restore(beforeRefs, beforeValues);
        }

        @Override
        public void redo() {
            restore(afterRefs, afterValues);
        }

        void restore(final List<NativeForm> refs, final List<NativeForm> values) {
            for (int i = 0; i < refs.size(); i++) refs.get(i).restore(values.get(i));
            list.clear();
            list.addAll(refs);
        }
    }

    public static final class TargetSnapshot extends Undo {
        final TargetSet set;
        final List<Target> before;
        List<Target> after;

        TargetSnapshot(final TargetSet set) {
            this.set = set;
            before = set.targets.stream().map(Target::copy).toList();
        }

        @Override
        public void undo() {
            if (after == null) after = set.targets.stream().map(Target::copy).toList();
            restore(before);
        }

        @Override
        public void redo() {
            restore(after);
        }

        void restore(final List<Target> values) {
            set.targets.clear();
            values.stream().map(Target::copy).forEach(set.targets::add);
        }
    }

    public static final class Utils {
        public static final Utils INSTANCE = new Utils();

        public Target create(
                final Model model, final Source source, final float key, final Guid guid, final Group group) {
            assertNull(group, "eager native undo must not be registered");
            edt();
            final var f = CURRENT.get();
            f.mutations++;
            final var p = model.parameters.stream()
                    .filter(d -> d.guid().equals(guid))
                    .findFirst()
                    .orElseThrow();
            if (f.forceNativeNull || !source.targets.canAdd(p, key)) return null;
            // Native substitute blends every stored field, independently of the production seed oracle.
            final var targets = source.targets.targets.stream()
                    .filter(t -> t.parameterGuid().equals(guid))
                    .sorted(java.util.Comparator.comparingDouble(t -> t.value()))
                    .toList();
            final NativeForm base = source.forms.get(4);
            NativeForm left = base, right = base;
            float lo = 0, ratio = 0;
            for (Target target : targets) {
                right = source.forms.stream()
                        .filter(v -> v.guid.equals(target.formGuid()))
                        .findFirst()
                        .orElseThrow();
                if (key <= target.value()) {
                    ratio = (key - lo) / (target.value() - lo);
                    break;
                }
                lo = target.value();
                left = right;
            }
            final float[] expected = new float[base.positions.length];
            for (int i = 0; i < expected.length; i++) {
                expected[i] += left.positions[i] * (1 - ratio);
                expected[i] += right.positions[i] * ratio;
                expected[i] = (base.positions[i] + expected[i]) + base.positions[i] * -1f;
            }
            if (f.wrongSeed) for (int i = 0; i < expected.length; i++) expected[i] -= base.positions[i];
            final var form = new NativeForm(source, "new" + (++f.counter), expected);
            float order = 0;
            order += left.order * (1 - ratio);
            order += right.order * ratio;
            final int interpolated = (int) (order + (order > 0 ? .001f : order < 0 ? -.001f : 0));
            float deltaOrder = base.order;
            deltaOrder += interpolated;
            deltaOrder += base.order * -1f;
            final int delta = (int) (deltaOrder + Math.signum(deltaOrder) * .001f);
            form.order = (int) (delta + Math.signum(delta) * .001f);
            form.opacity = 0;
            form.opacity += left.opacity * (1 - ratio);
            form.opacity += right.opacity * ratio;
            form.opacity = (base.opacity + form.opacity) + base.opacity * -1f;
            form.multiply = seedColor(base.multiply, blendColor(left.multiply, right.multiply, ratio));
            form.screen = seedColor(base.screen, blendColor(left.screen, right.screen, ratio));
            form.coordinate = base.coordinate;
            form.name = p.name() + " (" + key + ")";
            form.notes = base.notes;
            source.forms.add(form);
            if (f.partialAdd) throw new IllegalStateException("after first eager add");
            final var target = new Target(source, guid, form.guid, key);
            source.targets.targets.add(target);
            if (f.doubleAdd) {
                source.forms.add(form);
                source.targets.targets.add(target);
            }
            f.afterMutation.run();
            return target;
        }

        private static Color seedColor(final Color base, final Color interpolated) {
            final float[] a = {base.red(), base.green(), base.blue(), base.alpha()};
            final float[] b = {interpolated.red(), interpolated.green(), interpolated.blue(), interpolated.alpha()};
            for (int i = 0; i < b.length; i++) {
                b[i] += a[i];
                b[i] += -a[i];
            }
            return new Color(b[0], b[1], b[2], b[3]);
        }

        private static Color blendColor(final Color left, final Color right, final float ratio) {
            final float[] a = {left.red(), left.green(), left.blue(), left.alpha()};
            final float[] b = {right.red(), right.green(), right.blue(), right.alpha()};
            final float[] result = new float[4];
            for (int i = 0; i < result.length; i++) {
                result[i] += a[i] * (1 - ratio);
                result[i] += b[i] * ratio;
            }
            return new Color(result[0], result[1], result[2], result[3]);
        }
    }

    @Override
    public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
        return currentSource == source;
    }

    HistorySnapshot history() {
        return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                1,
                revision,
                position,
                List.copyOf(entries),
                position > 0,
                position < entries.size(),
                "document-binding",
                "manager-binding");
    }

    @Override
    public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
        return history();
    }

    @Override
    public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
        begins++;
        final var root = new Group(label);
        root.root = true;
        return root;
    }

    @Override
    public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
        if (abort) {
            if (nativeAbort) ((Undo) edit).undo();
        } else {
            while (history.size() > position) {
                history.remove(position);
                entries.remove(position);
            }
            history.add((Undo) edit);
            entries.add(new HistoryEntry(position, "morph", true));
            position++;
            revision++;
        }
    }

    @Override
    public void refresh(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final Set<EditorRefreshRequirement> requirements) {
        dirty++;
    }

    @Override
    public Optional<String> prepareHistoryMetadata(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final Object edit,
            final String transactionId,
            final HistoryEntryDetail detail,
            final Optional<HistoryAction> action) {
        return Optional.empty();
    }

    @Override
    public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label) {
        return Optional.of("entry-" + after.position());
    }

    @Override
    public String diagnosticId(final String code, final Throwable failure) {
        return code;
    }
}
