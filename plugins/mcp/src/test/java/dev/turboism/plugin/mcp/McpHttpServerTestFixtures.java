package dev.turboism.plugin.mcp;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

final class McpHttpServerTestFixtures {

    static class FakeReadServices {
        final FakeHierarchyQuery hierarchy = new FakeHierarchyQuery();
        final FakeSelectionQuery selection = new FakeSelectionQuery();
        final FakeRead read = new FakeRead();
        final FakeClipMasks clipMasks = new FakeClipMasks();
    }

    static final class FakeHierarchyQuery implements ModelHierarchyQueryService {
        final LinkedHashMap<String, HierarchyNode> nodes = new LinkedHashMap<>();

        void put(final HierarchyNode node) {
            nodes.put(node.id().value(), node);
        }

        @Override
        public Optional<ModelHierarchy> currentHierarchy() {
            final HierarchyNode root = nodes.values().stream()
                    .filter(node -> node.parentId().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (root == null) return Optional.empty();
            return Optional.of(new ModelHierarchy(root, List.copyOf(nodes.values())));
        }

        @Override
        public List<HierarchyNode> childrenOf(final ModelObjectId id) {
            final HierarchyNode node = nodes.get(id.value());
            if (node == null) return List.of();
            return node.childIds().stream()
                    .map(child -> nodes.get(child.value()))
                    .filter(Objects::nonNull)
                    .toList();
        }

        @Override
        public Optional<HierarchyNode> findNode(final ModelObjectId id) {
            return Optional.ofNullable(nodes.get(id.value()));
        }
    }

    static final class FakeSelectionQuery implements SelectionQueryService {
        private SelectionSummary current = SelectionSummary.empty();

        void set(final SelectionSummary value) {
            current = value;
        }

        @Override
        public SelectionSummary currentSelection() {
            return current;
        }

        @Override
        public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) {
            throw new UnsupportedOperationException("selectedIds is not used by MCP tools");
        }
    }

    static final class FakeRead implements CubismReadCapabilityService {
        private Optional<ProjectSnapshot> project = Optional.empty();
        private Optional<DocumentSnapshot> document = Optional.empty();
        private Optional<ModelSnapshot> model = Optional.empty();
        private SelectionSnapshot selection =
                new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        private List<ParameterSnapshot> parameters = List.of();
        private List<ModelObjectSnapshot> modelObjects = List.of();
        private List<ArtMeshSnapshot> meshes = List.of();
        private List<DeformerSnapshot> deformers = List.of();
        private List<PsdDocumentSnapshot> psdDocuments = List.of();
        private List<ClipMaskSnapshot> clipMasks = List.of();
        private List<TextureAtlasSnapshot> textureAtlases = List.of();
        private Optional<RenderStatusSnapshot> renderStatus = Optional.empty();
        private Optional<WorkspaceSnapshot> workspace = Optional.empty();
        private Optional<ThemeStatusSnapshot> themeStatus = Optional.empty();

        void project(final ProjectSnapshot value) {
            project = Optional.of(value);
        }

        void document(final DocumentSnapshot value) {
            document = Optional.of(value);
        }

        void model(final ModelSnapshot value) {
            model = Optional.of(value);
        }

        void selection(final SelectionSnapshot value) {
            selection = value;
        }

        void parameters(final List<ParameterSnapshot> value) {
            parameters = value;
        }

        void modelObjects(final List<ModelObjectSnapshot> value) {
            modelObjects = value;
        }

        void meshes(final List<ArtMeshSnapshot> value) {
            meshes = value;
        }

        void deformers(final List<DeformerSnapshot> value) {
            deformers = value;
        }

        void psdDocuments(final List<PsdDocumentSnapshot> value) {
            psdDocuments = value;
        }

        void clipMasks(final List<ClipMaskSnapshot> value) {
            clipMasks = value;
        }

        void textureAtlases(final List<TextureAtlasSnapshot> value) {
            textureAtlases = value;
        }

        void renderStatus(final Optional<RenderStatusSnapshot> value) {
            renderStatus = value;
        }

        void workspace(final Optional<WorkspaceSnapshot> value) {
            workspace = value;
        }

        void themeStatus(final Optional<ThemeStatusSnapshot> value) {
            themeStatus = value;
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return project;
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            return document;
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return model;
        }

        @Override
        public SelectionSnapshot selection() {
            return selection;
        }

        @Override
        public List<ParameterSnapshot> parameters() {
            return parameters;
        }

        @Override
        public List<ModelObjectSnapshot> modelObjects() {
            return modelObjects;
        }

        @Override
        public List<ArtMeshSnapshot> meshes() {
            return meshes;
        }

        @Override
        public List<DeformerSnapshot> deformers() {
            return deformers;
        }

        @Override
        public List<PsdDocumentSnapshot> psdDocuments() {
            return psdDocuments;
        }

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            return clipMasks;
        }

        @Override
        public List<TextureAtlasSnapshot> textureAtlases() {
            return textureAtlases;
        }

        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            return renderStatus;
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return workspace;
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return themeStatus;
        }
    }

    static final class FakeClipMasks implements CubismClipMaskService {
        final ArrayList<ClipMaskRecord> records = new ArrayList<>();

        void add(final ClipMaskRecord record) {
            records.add(record);
        }

        @Override
        public List<ClipMaskRecord> collectClipMaskRecords() {
            return List.copyOf(records);
        }
    }

    static class MutableObjects implements ModelObjectService {
        final LinkedHashMap<ModelObjectReference, ModelObjectDescriptor> values = new LinkedHashMap<>();
        final AtomicInteger generated = new AtomicInteger();
        ModelObjectCreateRequest lastCreate;
        ModelObjectReference lastReparentTarget;
        ModelObjectReference lastReparentParent;
        int lastReparentIndex = Integer.MIN_VALUE;
        ModelObjectReference lastDelete;
        ModelObjectDeletePolicy lastDeletePolicy;

        void put(final ModelObjectDescriptor value) {
            values.put(value.reference(), value);
        }

        ModelObjectDescriptor find(final ModelObjectKind kind, final String id) {
            final ModelObjectDescriptor result = values.get(new ModelObjectReference(kind, id));
            if (result == null) throw new NoSuchElementException(id);
            return result;
        }

        boolean contains(final ModelObjectKind kind, final String id) {
            return values.containsKey(new ModelObjectReference(kind, id));
        }

        @Override
        public List<ModelObjectDescriptor> list() {
            return List.copyOf(values.values());
        }

        @Override
        public ModelObjectDescriptor rename(final ModelObjectReference target, final String name) {
            final ModelObjectDescriptor current = values.get(target);
            if (current == null) throw new NoSuchElementException(target.id());
            final ModelObjectDescriptor renamed =
                    new ModelObjectDescriptor(current.reference(), name, current.parent());
            values.put(target, renamed);
            return renamed;
        }

        @Override
        public ModelObjectDescriptor reparent(
                final ModelObjectReference target, final ModelObjectReference parent, final int index) {
            final ModelObjectDescriptor current = values.get(target);
            if (current == null) throw new NoSuchElementException(target.id());
            if (!values.containsKey(parent)) throw new NoSuchElementException(parent.id());
            lastReparentTarget = target;
            lastReparentParent = parent;
            lastReparentIndex = index;
            final ModelObjectDescriptor reparented =
                    new ModelObjectDescriptor(current.reference(), current.name(), Optional.of(parent));
            values.put(target, reparented);
            return reparented;
        }

        @Override
        public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
            lastCreate = request;
            final ModelObjectReference reference =
                    new ModelObjectReference(request.kind(), "Generated" + generated.incrementAndGet());
            final ModelObjectDescriptor created =
                    new ModelObjectDescriptor(reference, request.name(), request.parent());
            values.put(reference, created);
            return created;
        }

        @Override
        public void delete(final ModelObjectReference target, final ModelObjectDeletePolicy policy) {
            lastDelete = target;
            lastDeletePolicy = policy;
            if (values.remove(target) == null) throw new NoSuchElementException(target.id());
        }
    }

    static final class RecordingUi implements ActionRegistry, MenuRegistry {
        final LinkedHashMap<String, Action> actions = new LinkedHashMap<>();
        final ArrayList<MenuContribution> menus = new ArrayList<>();
        final DisposableScope scope = new DisposableScope();
        final PluginLocalization localization = new PluginLocalization() {
            @Override
            public java.util.Locale locale() {
                return java.util.Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return "menu.connection".equals(key) ? "MCP Connection" : key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                return "menu.connection".equals(key);
            }
        };
        int actionRevocations;
        int menuRevocations;
        boolean failNextAction;
        boolean failNextMenu;
        java.util.function.Consumer<String> closeListener;

        @Override
        public Registration register(final String id, final Action action) {
            if (failNextAction) {
                failNextAction = false;
                throw new IllegalStateException("fixture action failure");
            }
            if (!id.equals(action.id())) throw new IllegalArgumentException("action id mismatch");
            if (actions.putIfAbsent(id, action) != null) {
                throw new IllegalStateException("fixture action already registered");
            }
            final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                actions.remove(id, action);
                actionRevocations++;
                if (closeListener != null) closeListener.accept("unpublish-action");
            };
        }

        @Override
        public Registration contribute(final MenuContribution contribution) {
            if (failNextMenu) {
                failNextMenu = false;
                throw new IllegalStateException("fixture menu failure");
            }
            menus.add(contribution);
            final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                menus.remove(contribution);
                menuRevocations++;
                if (closeListener != null) closeListener.accept("unpublish-menu");
            };
        }
    }

    static final class RecordingConnections implements McpConnectionService {
        Optional<McpHttpConnection> current = Optional.empty();
        boolean failNextPublish;
        int revocations;
        java.util.function.Consumer<String> closeListener;

        @Override
        public Optional<McpHttpConnection> current() {
            return current;
        }

        @Override
        public Registration publish(final McpHttpConnection connection) {
            if (failNextPublish) {
                failNextPublish = false;
                throw new IllegalStateException("fixture publication failure");
            }
            if (current.isPresent()) {
                throw new IllegalStateException("fixture already has a connection");
            }
            current = Optional.of(connection);
            final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                current = Optional.empty();
                revocations++;
                if (closeListener != null) closeListener.accept("unpublish-connection");
            };
        }
    }

    static final class CapturingLogger implements PluginLogger {
        final List<String> messages = new ArrayList<>();

        @Override
        public void debug(final String message) {
            messages.add(message);
        }

        @Override
        public void info(final String message) {
            messages.add(message);
        }

        @Override
        public void warn(final String message) {
            messages.add(message);
        }

        @Override
        public void error(final String message) {
            messages.add(message);
        }

        @Override
        public void error(final String message, final Throwable throwable) {
            messages.add(message);
        }
    }
}
