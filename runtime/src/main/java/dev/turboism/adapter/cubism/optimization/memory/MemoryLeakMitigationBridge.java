package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Coordinates Parts palette lifecycle/reuse, GL buffer/texture deletion and reuse, bounded undo
 * history, and document/inspector/listener cleanup across Cubism 5.0.00 through 5.3.05.
 *
 * <p>All host bytecode reaches this bridge through {@link System#getProperties()} functional-interface
 * slots so woven host classes never link directly against Turboism types.</p>
 */
public final class MemoryLeakMitigationBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = MemoryLeakMitigationTarget.ENABLE_PROPERTY;

    /** Debug opt-in allowing released {@code CImageResource} users to be retained for diagnostics. */
    public static final String RETAIN_DEBUG_IMAGE_USERS_PROPERTY = "turboism.debug.retainImageUsers";

    /** Property key for {@code BiConsumer<Object, Object>} invoked before replacing Parts {@code CTreeTable}. */
    public static final String ON_BEFORE_SET_TREE_SLOT = "turboism.memory-leak.before-set-tree";

    /** Property key for {@code Predicate<Object[]>} invoked on Parts palette update to attempt reuse. */
    public static final String TRY_REUSE_PARTS_SLOT = "turboism.memory-leak.try-reuse-parts";

    /** Property key for {@code Consumer<Object>} invoked when the Parts panel closes. */
    public static final String ON_PARTS_CLOSE_SLOT = "turboism.memory-leak.parts-close";

    /** Property key for {@code Consumer<Object>} draining deferred GL deletes on active GL2ES2. */
    public static final String DRAIN_GL_SLOT = "turboism.memory-leak.drain-gl";

    /** Property key for {@code BiPredicate<Object, Object>} handling same-GL VBO reuse or cross-context gen. */
    public static final String PREPARE_VBO_SLOT = "turboism.memory-leak.prepare-vbo";

    /** Property key for {@code Consumer<Object>} invoked when {@code GEntity.dispose()} runs. */
    public static final String DISPOSE_ENTITY_SLOT = "turboism.memory-leak.dispose-entity";

    /** Property key for {@code Consumer<Object>} invoked on {@code Entities.removeAll()}. */
    public static final String ENTITIES_REMOVE_ALL_SLOT = "turboism.memory-leak.entities-remove-all";

    /** Property key for {@code Consumer<Object>} invoked on {@code EditableMeshSelectionEntity.reset()}. */
    public static final String RESET_SELECTION_ENTITY_SLOT = "turboism.memory-leak.reset-selection-entity";

    /** Property key for {@code Consumer<Object>} invoked on {@code CEViewContext_MeshEditor.deactivateView()}. */
    public static final String MESH_EDITOR_DEACTIVATE_SLOT = "turboism.memory-leak.mesh-editor-deactivate";

    /** Property key for {@code Consumer<Object>} invoked on {@code CModelingEditMode_MeshEditor.endMode()}. */
    public static final String MESH_EDITOR_END_SLOT = "turboism.memory-leak.mesh-editor-end";

    /** Property key for {@code BiConsumer<Object, Object>} observing {@code GTexture2D.setup_exe}. */
    public static final String OBSERVE_TEXTURE_SLOT = "turboism.memory-leak.observe-texture";

    /** Property key for {@code Consumer<Object>} clearing tracked GL names on {@code releaseGLResource_for41}. */
    public static final String CLEAR_TEXTURE_TRACKING_SLOT = "turboism.memory-leak.clear-texture-tracking";

    /** Property key for {@code Consumer<Object>} disposing {@code GTexture} / {@code GTexture2D}. */
    public static final String DISPOSE_TEXTURE_SLOT = "turboism.memory-leak.dispose-texture";

    /** Property key for {@code Consumer<Object>} draining deletes and pruning dead contexts on GLAutoDrawable dispose. */
    public static final String DISPOSE_GL_CONTEXT_SLOT = "turboism.memory-leak.dispose-gl-context";

    /** Property key for {@code Consumer<Object>} cleaning callbacks/image after {@code glWrapper.a.c()}. */
    public static final String GL_WRAPPER_DESTROY_SLOT = "turboism.memory-leak.gl-wrapper-destroy";

    /** Property key for {@code Consumer<Object>} enforcing {@code CUndoManager.limit}. */
    public static final String UNDO_CLEANUP_SLOT = "turboism.memory-leak.undo-cleanup";

    /** Property key for {@code Consumer<Object>} invoked after {@code CModelingDocument.closeFile} succeeds. */
    public static final String MODEL_DOC_CLOSE_SLOT = "turboism.memory-leak.model-doc-close";

    /** Property key for {@code Consumer<Object>} invoked when {@code CSceneDocument.closeDocument} completes. */
    public static final String SCENE_DOC_CLOSE_SLOT = "turboism.memory-leak.scene-doc-close";

    /** Property key for {@code Predicate<Object>} gating {@code releasedRetainUserData_forDebug.add}. */
    public static final String RETAIN_DEBUG_IMAGE_USER_SLOT = "turboism.memory-leak.retain-debug-image-user";

    /** Property key for {@code Consumer<Object>} clearing retained inspector objects on empty selection. */
    public static final String INSPECTOR_CLEAR_SLOT = "turboism.memory-leak.inspector-clear";

    /** Property key for {@code Consumer<Object>} pruning listeners after {@code CEViewContext.closeView()}. */
    public static final String VIEW_CLOSE_SLOT = "turboism.memory-leak.view-close";

    /** Property key for {@code Consumer<Object>} guarding {@code ParameterGroupRowWidget.renameFolder()} listeners. */
    public static final String PARAM_RENAME_SLOT = "turboism.memory-leak.param-rename";

    /** Diagnostic statistics property slot. */
    public static final String STATS_PROPERTY = "turboism.memory-leak.stats";

    private static final int REUSE_PARTS_ARG_COUNT = 3;
    private static final int ARG_PANEL = 0;
    private static final int ARG_SKIP_SEL = 1;
    private static final int ARG_EXPAND_SEL = 2;

    private static final String GRAPHICS_CTX_CLASS = "com/live2d/graphics3d/a";
    private static final String INSTALLED_TWICE_MESSAGE = "memory leak mitigation bridge already installed";
    private static final String SLOTS_OCCUPIED_MESSAGE = "memory leak mitigation bridge slots occupied";

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Object statsLock = new Object();

    private final GlDeleteQueue glDeleteQueue;
    private final PartsPaletteHelper partsPaletteHelper;
    private final LifecycleCleanupHelper lifecycleHelper;
    private final MethodHandle graphicsCtxGetGl3;
    private final MethodHandle modelDocGetViewContexts;
    private final MethodHandle editModeGetDoc;
    private final Class<?> meshEditorViewClass;
    private final boolean retainDebugImageUsers = Boolean.getBoolean(RETAIN_DEBUG_IMAGE_USERS_PROPERTY);

    private Properties installedProperties;

    private long partsTreesDetached;
    private long partsTreesReused;
    private long vbosReused;
    private long vbosQueued;
    private long vbosDeleted;
    private long texturesQueued;
    private long glContextsPruned;
    private long undoEntriesEvicted;
    private long undoManagersCleared;
    private long inspectorClears;
    private long staticHoldersCleared;
    private long renameListenersCleaned;

    // Slot callback references (identity-kept for symmetric removal)
    private final BiConsumer<Object, Object> onBeforeSetTreeSlot = this::onBeforeSetTree;
    private final Predicate<Object[]> tryReusePartsSlot = this::tryReuseParts;
    private final Consumer<Object> onPartsCloseSlot = this::onPartsClose;
    private final Consumer<Object> drainGlSlot = this::drainGl;
    private final BiPredicate<Object, Object> prepareVboSlot = this::prepareVbo;
    private final Consumer<Object> disposeEntitySlot = this::disposeEntity;
    private final Consumer<Object> entitiesRemoveAllSlot = this::entitiesRemoveAll;
    private final Consumer<Object> resetSelectionEntitySlot = this::resetSelectionEntity;
    private final Consumer<Object> meshEditorDeactivateSlot = this::meshEditorDeactivate;
    private final Consumer<Object> meshEditorEndSlot = this::meshEditorEnd;
    private final BiConsumer<Object, Object> observeTextureSlot = this::observeTexture;
    private final Consumer<Object> clearTextureTrackingSlot = this::clearTextureTracking;
    private final Consumer<Object> disposeTextureSlot = this::disposeTexture;
    private final Consumer<Object> disposeGlContextSlot = this::disposeGlContext;
    private final Consumer<Object> glWrapperDestroySlot = this::glWrapperDestroy;
    private final Consumer<Object> undoCleanupSlot = this::undoCleanup;
    private final Consumer<Object> modelDocCloseSlot = this::modelDocClose;
    private final Consumer<Object> sceneDocCloseSlot = this::sceneDocClose;
    private final Predicate<Object> retainDebugImageUserSlot = ignored -> retainDebugImageUsers;
    private final Consumer<Object> inspectorClearSlot = this::inspectorClear;
    private final Consumer<Object> viewCloseSlot = this::viewClose;
    private final Consumer<Object> paramRenameSlot = this::paramRename;
    private final Supplier<Map<String, Long>> statsSlot = this::snapshot;

    /**
     * Resolves all reviewed host handles across the supplied artifact contract.
     *
     * @param loader admitted host class loader
     * @param target reviewed version target contract
     * @throws ReflectiveOperationException when any required host member is absent
     */
    public MemoryLeakMitigationBridge(final ClassLoader loader, final MemoryLeakMitigationTarget target)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");

        glDeleteQueue = new GlDeleteQueue(loader, target, new GlDeleteQueue.StatsRecorder() {
            @Override
            public void onVboReused() {
                synchronized (statsLock) {
                    vbosReused++;
                }
            }

            @Override
            public void onVboQueued() {
                synchronized (statsLock) {
                    vbosQueued++;
                }
            }

            @Override
            public void onVboDeleted(final int count) {
                synchronized (statsLock) {
                    vbosDeleted += count;
                }
            }

            @Override
            public void onTextureQueued() {
                synchronized (statsLock) {
                    texturesQueued++;
                }
            }

            @Override
            public void onContextPruned() {
                synchronized (statsLock) {
                    glContextsPruned++;
                }
            }
        });

        partsPaletteHelper = new PartsPaletteHelper(loader, target, new PartsPaletteHelper.Listener() {
            @Override
            public void onTreeDetached() {
                synchronized (statsLock) {
                    partsTreesDetached++;
                }
            }

            @Override
            public void onTreeReused() {
                synchronized (statsLock) {
                    partsTreesReused++;
                }
            }
        });

        lifecycleHelper = new LifecycleCleanupHelper(loader, target, new LifecycleCleanupHelper.Listener() {
            @Override
            public void onUndoEvicted(final int count) {
                synchronized (statsLock) {
                    undoEntriesEvicted += count;
                }
            }

            @Override
            public void onUndoManagersCleared(final int count) {
                synchronized (statsLock) {
                    undoManagersCleared += count;
                }
            }

            @Override
            public void onInspectorCleared() {
                synchronized (statsLock) {
                    inspectorClears++;
                }
            }

            @Override
            public void onStaticHolderCleared() {
                synchronized (statsLock) {
                    staticHoldersCleared++;
                }
            }

            @Override
            public void onRenameListenerCleaned() {
                synchronized (statsLock) {
                    renameListenersCleaned++;
                }
            }
        });

        final Class<?> graphicsCtxClass = HostHandles.load(loader, GRAPHICS_CTX_CLASS);
        graphicsCtxGetGl3 = HostHandles.virtual(
                graphicsCtxClass, "a", "()Lcom/jogamp/opengl/GL3;", MethodType.methodType(Object.class, Object.class));
        final Class<?> modelDocClass = HostHandles.load(loader, MemoryLeakMitigationTarget.MODELING_DOC_OWNER);
        modelDocGetViewContexts = HostHandles.virtual(
                modelDocClass,
                "getViewContexts",
                "()Lcom/live2d/type/CArrayList;",
                MethodType.methodType(Object.class, Object.class));
        final Class<?> meshEditModeClass = HostHandles.load(loader, MemoryLeakMitigationTarget.MESH_EDITOR_EM_OWNER);
        editModeGetDoc = HostHandles.virtual(
                meshEditModeClass,
                "getDoc",
                "()L" + MemoryLeakMitigationTarget.MODELING_DOC_OWNER + ";",
                MethodType.methodType(Object.class, Object.class));
        meshEditorViewClass = HostHandles.load(loader, MemoryLeakMitigationTarget.MESH_EDITOR_VC_OWNER);
    }

    /** Publishes all mitigation callback slots into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException(INSTALLED_TWICE_MESSAGE);
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            final List<String> keys = List.of(
                    ON_BEFORE_SET_TREE_SLOT,
                    TRY_REUSE_PARTS_SLOT,
                    ON_PARTS_CLOSE_SLOT,
                    DRAIN_GL_SLOT,
                    PREPARE_VBO_SLOT,
                    DISPOSE_ENTITY_SLOT,
                    ENTITIES_REMOVE_ALL_SLOT,
                    RESET_SELECTION_ENTITY_SLOT,
                    MESH_EDITOR_DEACTIVATE_SLOT,
                    MESH_EDITOR_END_SLOT,
                    OBSERVE_TEXTURE_SLOT,
                    CLEAR_TEXTURE_TRACKING_SLOT,
                    DISPOSE_TEXTURE_SLOT,
                    DISPOSE_GL_CONTEXT_SLOT,
                    GL_WRAPPER_DESTROY_SLOT,
                    UNDO_CLEANUP_SLOT,
                    MODEL_DOC_CLOSE_SLOT,
                    SCENE_DOC_CLOSE_SLOT,
                    RETAIN_DEBUG_IMAGE_USER_SLOT,
                    INSPECTOR_CLEAR_SLOT,
                    VIEW_CLOSE_SLOT,
                    PARAM_RENAME_SLOT,
                    STATS_PROPERTY);
            for (final String key : keys) {
                if (properties.containsKey(key)) {
                    throw new IllegalStateException(SLOTS_OCCUPIED_MESSAGE + ": " + key);
                }
            }
            properties.put(ON_BEFORE_SET_TREE_SLOT, onBeforeSetTreeSlot);
            properties.put(TRY_REUSE_PARTS_SLOT, tryReusePartsSlot);
            properties.put(ON_PARTS_CLOSE_SLOT, onPartsCloseSlot);
            properties.put(DRAIN_GL_SLOT, drainGlSlot);
            properties.put(PREPARE_VBO_SLOT, prepareVboSlot);
            properties.put(DISPOSE_ENTITY_SLOT, disposeEntitySlot);
            properties.put(ENTITIES_REMOVE_ALL_SLOT, entitiesRemoveAllSlot);
            properties.put(RESET_SELECTION_ENTITY_SLOT, resetSelectionEntitySlot);
            properties.put(MESH_EDITOR_DEACTIVATE_SLOT, meshEditorDeactivateSlot);
            properties.put(MESH_EDITOR_END_SLOT, meshEditorEndSlot);
            properties.put(OBSERVE_TEXTURE_SLOT, observeTextureSlot);
            properties.put(CLEAR_TEXTURE_TRACKING_SLOT, clearTextureTrackingSlot);
            properties.put(DISPOSE_TEXTURE_SLOT, disposeTextureSlot);
            properties.put(DISPOSE_GL_CONTEXT_SLOT, disposeGlContextSlot);
            properties.put(GL_WRAPPER_DESTROY_SLOT, glWrapperDestroySlot);
            properties.put(UNDO_CLEANUP_SLOT, undoCleanupSlot);
            properties.put(MODEL_DOC_CLOSE_SLOT, modelDocCloseSlot);
            properties.put(SCENE_DOC_CLOSE_SLOT, sceneDocCloseSlot);
            properties.put(RETAIN_DEBUG_IMAGE_USER_SLOT, retainDebugImageUserSlot);
            properties.put(INSPECTOR_CLEAR_SLOT, inspectorClearSlot);
            properties.put(VIEW_CLOSE_SLOT, viewCloseSlot);
            properties.put(PARAM_RENAME_SLOT, paramRenameSlot);
            properties.put(STATS_PROPERTY, statsSlot);
            installedProperties = properties;
            active.set(true);
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        glDeleteQueue.close();
        lifecycleHelper.close();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(ON_BEFORE_SET_TREE_SLOT, onBeforeSetTreeSlot);
                properties.remove(TRY_REUSE_PARTS_SLOT, tryReusePartsSlot);
                properties.remove(ON_PARTS_CLOSE_SLOT, onPartsCloseSlot);
                properties.remove(DRAIN_GL_SLOT, drainGlSlot);
                properties.remove(PREPARE_VBO_SLOT, prepareVboSlot);
                properties.remove(DISPOSE_ENTITY_SLOT, disposeEntitySlot);
                properties.remove(ENTITIES_REMOVE_ALL_SLOT, entitiesRemoveAllSlot);
                properties.remove(RESET_SELECTION_ENTITY_SLOT, resetSelectionEntitySlot);
                properties.remove(MESH_EDITOR_DEACTIVATE_SLOT, meshEditorDeactivateSlot);
                properties.remove(MESH_EDITOR_END_SLOT, meshEditorEndSlot);
                properties.remove(OBSERVE_TEXTURE_SLOT, observeTextureSlot);
                properties.remove(CLEAR_TEXTURE_TRACKING_SLOT, clearTextureTrackingSlot);
                properties.remove(DISPOSE_TEXTURE_SLOT, disposeTextureSlot);
                properties.remove(DISPOSE_GL_CONTEXT_SLOT, disposeGlContextSlot);
                properties.remove(GL_WRAPPER_DESTROY_SLOT, glWrapperDestroySlot);
                properties.remove(UNDO_CLEANUP_SLOT, undoCleanupSlot);
                properties.remove(MODEL_DOC_CLOSE_SLOT, modelDocCloseSlot);
                properties.remove(SCENE_DOC_CLOSE_SLOT, sceneDocCloseSlot);
                properties.remove(RETAIN_DEBUG_IMAGE_USER_SLOT, retainDebugImageUserSlot);
                properties.remove(INSPECTOR_CLEAR_SLOT, inspectorClearSlot);
                properties.remove(VIEW_CLOSE_SLOT, viewCloseSlot);
                properties.remove(PARAM_RENAME_SLOT, paramRenameSlot);
                properties.remove(STATS_PROPERTY, statsSlot);
            }
        }
    }

    /** Returns an immutable snapshot of memory and GL mitigation diagnostic counters. */
    public Map<String, Long> snapshot() {
        synchronized (statsLock) {
            return Map.ofEntries(
                    Map.entry("partsTreesDetached", partsTreesDetached),
                    Map.entry("partsTreesReused", partsTreesReused),
                    Map.entry("vbosReused", vbosReused),
                    Map.entry("vbosQueued", vbosQueued),
                    Map.entry("vbosDeleted", vbosDeleted),
                    Map.entry("texturesQueued", texturesQueued),
                    Map.entry("glContextsPruned", glContextsPruned),
                    Map.entry("undoEntriesEvicted", undoEntriesEvicted),
                    Map.entry("undoManagersCleared", undoManagersCleared),
                    Map.entry("inspectorClears", inspectorClears),
                    Map.entry("staticHoldersCleared", staticHoldersCleared),
                    Map.entry("renameListenersCleaned", renameListenersCleaned));
        }
    }

    private void onBeforeSetTree(final Object panel, final Object nextTree) {
        if (active.get()) {
            partsPaletteHelper.onBeforeSetTreeTable(panel, nextTree);
        }
    }

    private boolean tryReuseParts(final Object[] args) {
        if (!active.get() || args == null || args.length != REUSE_PARTS_ARG_COUNT) {
            return false;
        }
        final Object panel = args[ARG_PANEL];
        final boolean skipSel = args[ARG_SKIP_SEL] instanceof Boolean b && b;
        final boolean expandSel = args[ARG_EXPAND_SEL] instanceof Boolean b && b;
        return partsPaletteHelper.tryReusePartsPalette(panel, skipSel, expandSel);
    }

    private void onPartsClose(final Object panel) {
        if (active.get()) {
            partsPaletteHelper.onPartsPanelClosed(panel);
        }
    }

    private void drainGl(final Object gl) {
        if (active.get()) {
            glDeleteQueue.drainForGl(gl);
        }
    }

    private boolean prepareVbo(final Object meshBuffer, final Object gl) {
        return active.get() && glDeleteQueue.prepareVboHandle(meshBuffer, gl);
    }

    private void disposeEntity(final Object entity) {
        if (active.get()) {
            glDeleteQueue.onEntityDisposing(entity);
        }
    }

    private void entitiesRemoveAll(final Object entities) {
        if (active.get()) {
            glDeleteQueue.onEntitiesRemoveAll(entities);
        }
    }

    private void resetSelectionEntity(final Object entity) {
        if (active.get()) {
            glDeleteQueue.onSelectionEntityReset(entity);
        }
    }

    private void meshEditorDeactivate(final Object viewCtx) {
        if (active.get()) {
            glDeleteQueue.onMeshEditorDeactivate(viewCtx);
        }
    }

    private void meshEditorEnd(final Object editMode) {
        if (!active.get() || editMode == null) {
            return;
        }
        try {
            final Object doc = (Object) editModeGetDoc.invokeExact(editMode);
            if (doc == null) {
                return;
            }
            final Object contexts = (Object) modelDocGetViewContexts.invokeExact(doc);
            if (contexts instanceof List<?> list) {
                for (final Object vc : list) {
                    if (meshEditorViewClass.isInstance(vc)) {
                        glDeleteQueue.onMeshEditorDeactivate(vc);
                    }
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void observeTexture(final Object texture, final Object graphicsContext) {
        if (!active.get() || texture == null || graphicsContext == null) {
            return;
        }
        try {
            final Object gl = (Object) graphicsCtxGetGl3.invokeExact(graphicsContext);
            glDeleteQueue.observeTexture(texture, gl);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void clearTextureTracking(final Object texture) {
        if (active.get()) {
            glDeleteQueue.clearTextureTracking(texture);
        }
    }

    private void disposeTexture(final Object texture) {
        if (active.get()) {
            glDeleteQueue.onTextureDisposing(texture);
        }
    }

    private void disposeGlContext(final Object glAutoDrawable) {
        if (active.get()) {
            glDeleteQueue.onGlDrawableDisposing(glAutoDrawable);
        }
    }

    private void glWrapperDestroy(final Object wrapper) {
        if (active.get()) {
            glDeleteQueue.onGlWrapperDestroyed(wrapper);
        }
    }

    private void undoCleanup(final Object undoManager) {
        if (active.get()) {
            lifecycleHelper.enforceUndoLimit(undoManager);
        }
    }

    private void modelDocClose(final Object modelingDoc) {
        if (active.get()) {
            lifecycleHelper.onModelingDocumentClosed(modelingDoc);
        }
    }

    private void sceneDocClose(final Object sceneDoc) {
        if (active.get()) {
            lifecycleHelper.onSceneDocumentClosed(sceneDoc);
        }
    }

    private void inspectorClear(final Object inspectorCtrl) {
        if (active.get()) {
            lifecycleHelper.onInspectorCleared(inspectorCtrl);
        }
    }

    private void viewClose(final Object viewContext) {
        if (active.get() && meshEditorViewClass.isInstance(viewContext)) {
            glDeleteQueue.onMeshEditorDeactivate(viewContext);
        }
    }

    private void paramRename(final Object rowWidget) {
        if (active.get()) {
            lifecycleHelper.onParameterRenameStarted(rowWidget);
        }
    }
}
