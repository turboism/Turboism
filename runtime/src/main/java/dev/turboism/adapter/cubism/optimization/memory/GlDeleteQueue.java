package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates GLContext-safe VBO/texture/sampler reuse, deferred deletion, Cleaner backstop tracking,
 * and dead GL context pruning.
 */
final class GlDeleteQueue {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final int DRAIN_BATCH_LIMIT = 64;
    private static final int INVALID_GL_NAME = 0;
    private static final int UNINITIALIZED_TEXTURE_NAME = -1;
    private static final int SCRATCH_INITIAL_CAPACITY = 32;
    private static final int SCRATCH_RETAIN_CAP = 512;

    private static final String GL_INTERFACE = "com/jogamp/opengl/GL";
    private static final String GL2ES2_INTERFACE = "com/jogamp/opengl/GL2ES2";
    private static final String GL3_INTERFACE = "com/jogamp/opengl/GL3";
    private static final String GL_CONTEXT_CLASS = "com/jogamp/opengl/GLContext";
    private static final String GL_AUTO_DRAWABLE_INTERFACE = "com/jogamp/opengl/GLAutoDrawable";
    private static final String G_ENTITY_CLASS = MemoryLeakMitigationTarget.G_ENTITY_OWNER;
    private static final String ENTITIES_CLASS = MemoryLeakMitigationTarget.ENTITIES_OWNER;
    private static final String COMPONENTS_CLASS = "com/live2d/graphics3d/entity/Components";
    private static final String G_TEXTURE_CLASS = MemoryLeakMitigationTarget.G_TEXTURE_OWNER;
    private static final String G_TEXTURE_2D_CLASS = MemoryLeakMitigationTarget.G_TEXTURE_2D_OWNER;
    private static final String G_MESH_RENDERER_CLASS = MemoryLeakMitigationTarget.G_MESH_RENDERER_OWNER;
    private static final String MESH_CONTAINER_CLASS = "com/live2d/graphics3d/component/GMeshContainer";
    private static final String RENDER_TEXTURE_CLASS = "com/live2d/graphics3d/mesh/a";
    private static final String LINE_RENDERER_CLASS = MemoryLeakMitigationTarget.G_LINE_RENDERER_OWNER;
    private static final String POINT_RENDERER_CLASS = MemoryLeakMitigationTarget.G_POINT_RENDERER_OWNER;
    private static final String GROUP_RENDERER_CLASS = MemoryLeakMitigationTarget.G_GROUP_RENDERER_OWNER;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger pendingQueueCount = new AtomicInteger(0);
    private final WeakIdentityMap<Object, ContextDeletes> queues = new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, BufferSlot> bufferSlots = new WeakIdentityMap<>();
    private final WeakIdentityMap<Object, TextureSlot> textureSlots = new WeakIdentityMap<>();
    private final ThreadLocal<TraversalScratch> traversalScratch =
            ThreadLocal.withInitial(TraversalScratch::new);
    private final ThreadLocal<int[]> deleteBatchScratch =
            ThreadLocal.withInitial(() -> new int[DRAIN_BATCH_LIMIT]);

    // Buffer accessors on mesh/a/a
    private final Class<?> bufferBaseClass;
    private final MethodHandle bufGetGl;
    private final MethodHandle bufSetGl;
    private final MethodHandle bufGetIntBuffer;
    private final MethodHandle bufSetReinit;
    private final MethodHandle bufSetDirty;

    // Entity/Component tree accessors
    private final Class<?> entityClass;
    private final Class<?> entitiesClass;
    private final Class<?> componentsClass;
    private final MethodHandle entityGetChildren;
    private final MethodHandle entityGetComponents;
    private final MethodHandle entitiesGetList;
    private final MethodHandle componentsGetList;
    private final MethodHandle meshRendererGetContainer;
    private final MethodHandle containerGetMesh;

    // GTexture / GTexture2D fields & accessors
    private final HostHandles.HostField texNameField;
    private final HostHandles.HostField samplerNameField;
    private final HostHandles.HostField reinitTexField;
    private final HostHandles.HostField reinitSamplerField;
    private final HostHandles.HostField initializedGlField;
    private final HostHandles.HostField preBufferField;
    private final HostHandles.HostField srcImageResourceField;
    private final Class<?> texture2dClass;

    // Rendering cache & glWrapper handles
    private final Object renderingLock;
    private final Map<?, ?> renderingMap;
    private final HostHandles.HostField wrapperCallbacksField;
    private final HostHandles.HostField wrapperImageField;

    // JOGL reflection handles (optional when testing without JOGL on loader)
    private final MethodHandle drawableGetGl;
    private final MethodHandle glGetContext;
    private final MethodHandle glGetRootGl;
    private final MethodHandle glIsGl3;
    private final MethodHandle glGetGl3;
    private final MethodHandle contextGetCurrent;
    private final MethodHandle contextIsCreated;
    private final MethodHandle glDeleteBuffersArray;
    private final MethodHandle glGenBuffersArray;
    private final MethodHandle glDeleteTexturesArray;
    private final MethodHandle glDeleteSamplersArray;

    // Stats callbacks
    private final AtomicReference<StatsRecorder> stats = new AtomicReference<>();

    interface StatsRecorder {
        void onVboReused();

        void onVboQueued();

        void onVboDeleted(int count);

        void onTextureQueued();

        void onContextPruned();
    }

    private final ClassValue<HostHandles.HostField[]> bufferFieldsCache = new ClassValue<>() {
        @Override
        protected HostHandles.HostField[] computeValue(final Class<?> type) {
            return scanResourceFields(type);
        }
    };

    GlDeleteQueue(final ClassLoader loader, final MemoryLeakMitigationTarget target, final StatsRecorder recorder)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");
        stats.set(Objects.requireNonNull(recorder, "recorder"));

        bufferBaseClass = HostHandles.load(loader, MemoryLeakMitigationTarget.GL_BUFFER_BASE_OWNER);
        bufGetGl = HostHandles.virtual(
                bufferBaseClass,
                "h",
                "()L" + GL2ES2_INTERFACE + ";",
                MethodType.methodType(Object.class, Object.class));
        bufSetGl = HostHandles.virtual(
                bufferBaseClass,
                "a",
                "(L" + GL2ES2_INTERFACE + ";)V",
                MethodType.methodType(void.class, Object.class, Object.class));
        bufGetIntBuffer = HostHandles.virtual(
                bufferBaseClass,
                "i",
                "()Ljava/nio/IntBuffer;",
                MethodType.methodType(IntBuffer.class, Object.class));
        bufSetReinit =
                HostHandles.virtual(bufferBaseClass, "c", "(Z)V", MethodType.methodType(void.class, Object.class, boolean.class));
        bufSetDirty =
                HostHandles.virtual(bufferBaseClass, "d", "(Z)V", MethodType.methodType(void.class, Object.class, boolean.class));

        entityClass = HostHandles.load(loader, G_ENTITY_CLASS);
        entitiesClass = HostHandles.load(loader, ENTITIES_CLASS);
        componentsClass = HostHandles.load(loader, COMPONENTS_CLASS);
        entityGetChildren = HostHandles.virtual(
                entityClass,
                "getChildren",
                "()Lcom/live2d/graphics3d/entity/Entities;",
                MethodType.methodType(Object.class, Object.class));
        entityGetComponents = HostHandles.virtual(
                entityClass,
                "getComponents",
                "()Lcom/live2d/graphics3d/entity/Components;",
                MethodType.methodType(Object.class, Object.class));
        entitiesGetList = resolveOptionalListGetter(entitiesClass, "getList");
        componentsGetList = resolveOptionalListGetter(componentsClass, "a");
        final Class<?> meshRendererClass = HostHandles.load(loader, G_MESH_RENDERER_CLASS);
        meshRendererGetContainer = HostHandles.virtual(
                meshRendererClass,
                "getMeshContainer",
                "()L" + MESH_CONTAINER_CLASS + ";",
                MethodType.methodType(Object.class, Object.class));
        final Class<?> containerClass = HostHandles.load(loader, MESH_CONTAINER_CLASS);
        containerGetMesh = HostHandles.virtual(
                containerClass,
                "getMesh",
                "()L" + RENDER_TEXTURE_CLASS + ";",
                MethodType.methodType(Object.class, Object.class));

        final Class<?> gTexClass = HostHandles.load(loader, G_TEXTURE_CLASS);
        texNameField = HostHandles.field(gTexClass, "glTextureName");
        samplerNameField = HostHandles.field(gTexClass, "glSamplerName");
        reinitTexField = HostHandles.field(gTexClass, "reinitTextureLater");
        reinitSamplerField = HostHandles.field(gTexClass, "reinitSamplerLater");
        final Class<?> gTex2dClass = HostHandles.load(loader, G_TEXTURE_2D_CLASS);
        texture2dClass = gTex2dClass;
        initializedGlField = HostHandles.field(gTex2dClass, "initializedGl");
        preBufferField = HostHandles.field(gTex2dClass, "_preBuffer");
        srcImageResourceField = HostHandles.field(gTex2dClass, "srcImageResource");

        final Class<?> renderingClass = HostHandles.load(loader, target.renderingCacheOwner());
        Object lockObj;
        Map<?, ?> mapObj;
        try {
            lockObj = HostHandles.field(renderingClass, MemoryLeakMitigationTarget.RENDERING_CACHE_LOCK_FIELD)
                    .get(null);
            mapObj = (Map<?, ?>) HostHandles.field(renderingClass, MemoryLeakMitigationTarget.RENDERING_CACHE_MAP_FIELD)
                    .get(null);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException("Cannot open rendering cache lock/map", failure);
        }
        renderingLock = lockObj;
        renderingMap = mapObj;

        final Class<?> wrapperClass = HostHandles.load(loader, MemoryLeakMitigationTarget.GL_WRAPPER_OWNER);
        wrapperCallbacksField = HostHandles.field(wrapperClass, "k");
        wrapperImageField = HostHandles.field(wrapperClass, "o");

        final Class<?> drawableIf = HostHandles.load(loader, GL_AUTO_DRAWABLE_INTERFACE);
        final Class<?> glIf = HostHandles.load(loader, GL_INTERFACE);
        final Class<?> gl2es2If = HostHandles.load(loader, GL2ES2_INTERFACE);
        final Class<?> gl3If = HostHandles.load(loader, GL3_INTERFACE);
        final Class<?> glCtxClass = HostHandles.load(loader, GL_CONTEXT_CLASS);

        drawableGetGl = HostHandles.virtual(
                drawableIf, "getGL", "()L" + GL_INTERFACE + ";", MethodType.methodType(Object.class, Object.class));
        glGetContext = HostHandles.virtual(
                glIf, "getContext", "()L" + GL_CONTEXT_CLASS + ";", MethodType.methodType(Object.class, Object.class));
        glGetRootGl = HostHandles.virtual(
                glIf, "getRootGL", "()L" + GL_INTERFACE + ";", MethodType.methodType(Object.class, Object.class));
        glIsGl3 = HostHandles.virtual(glIf, "isGL3", "()Z", MethodType.methodType(boolean.class, Object.class));
        glGetGl3 = HostHandles.virtual(
                glIf, "getGL3", "()L" + GL3_INTERFACE + ";", MethodType.methodType(Object.class, Object.class));
        contextGetCurrent = HostHandles.staticCall(
                glCtxClass, "getCurrent", "()L" + GL_CONTEXT_CLASS + ";", MethodType.methodType(Object.class));
        contextIsCreated = HostHandles.virtual(
                glCtxClass, "isCreated", "()Z", MethodType.methodType(boolean.class, Object.class));
        glDeleteBuffersArray = HostHandles.virtual(
                gl2es2If, "glDeleteBuffers", "(I[II)V", MethodType.methodType(void.class, Object.class, int.class, int[].class, int.class));
        glGenBuffersArray = HostHandles.virtual(
                gl2es2If, "glGenBuffers", "(I[II)V", MethodType.methodType(void.class, Object.class, int.class, int[].class, int.class));
        glDeleteTexturesArray = HostHandles.virtual(
                glIf, "glDeleteTextures", "(I[II)V", MethodType.methodType(void.class, Object.class, int.class, int[].class, int.class));
        glDeleteSamplersArray = HostHandles.virtual(
                gl3If, "glDeleteSamplers", "(I[II)V", MethodType.methodType(void.class, Object.class, int.class, int[].class, int.class));
    }

    /** Drains pending VBO, texture, and sampler deletes for {@code gl}'s context if current. */
    void drainForGl(final Object gl) {
        if (closed.get() || gl == null || pendingQueueCount.get() <= 0) {
            return;
        }
        try {
            final Object ctx = safeGetContext(gl);
            if (ctx == null || ctx != (Object) contextGetCurrent.invokeExact() || !(boolean) contextIsCreated.invokeExact(ctx)) {
                return;
            }
            final ContextDeletes queue = queues.get(ctx);
            if (queue == null) {
                return;
            }
            drainContextQueue(gl, queue);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Reuses a matching VBO handle or replaces it safely with context-scoped deletion. */
    boolean prepareVboHandle(final Object meshBuffer, final Object gl) {
        if (closed.get() || meshBuffer == null || gl == null) {
            return false;
        }
        try {
            drainForGl(gl);
            final IntBuffer nameBuf = (IntBuffer) bufGetIntBuffer.invokeExact(meshBuffer);
            if (nameBuf == null || nameBuf.capacity() < 1) {
                return false;
            }
            final Object currentCtx = safeGetContext(gl);
            final Object oldGl = (Object) bufGetGl.invokeExact(meshBuffer);
            final int currentId = nameBuf.get(0);
            final BufferSlot slot = getOrCreateBufferSlot(meshBuffer);

            if (oldGl == gl && currentId > INVALID_GL_NAME) {
                if (currentCtx != null) {
                    slot.ctxRef.set(new WeakReference<>(currentCtx));
                }
                slot.bufferId.set(currentId);
                recordVboReused();
                return true;
            }

            slot.bufferId.set(INVALID_GL_NAME);
            if (oldGl != null && currentId > INVALID_GL_NAME) {
                enqueueBufferDelete(oldGl, currentId);
            }
            nameBuf.put(0, INVALID_GL_NAME);

            final int[] generated = {INVALID_GL_NAME};
            glGenBuffersArray.invokeExact(gl, 1, generated, 0);
            nameBuf.put(0, generated[0]);
            if (generated[0] > INVALID_GL_NAME && currentCtx != null) {
                slot.ctxRef.set(new WeakReference<>(currentCtx));
                slot.bufferId.set(generated[0]);
            }
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /** Releases all GL buffer objects reachable from an entity being disposed. */
    void onEntityDisposing(final Object entity) {
        if (closed.get() || entity == null) {
            return;
        }
        final TraversalScratch scratch = acquireScratch();
        try {
            releaseEntityOwnBuffers(entity, 0, visitedSet(scratch));
        } finally {
            releaseScratch(scratch);
        }
    }

    /** Releases GL buffer objects owned by every child of a cleared {@code Entities} list. */
    void onEntitiesRemoveAll(final Object entitiesCollection) {
        if (closed.get() || entitiesCollection == null) {
            return;
        }
        final List<?> list = toRandomAccessList(entitiesCollection);
        if (list != null) {
            if (list.isEmpty()) {
                return;
            }
            final TraversalScratch scratch = acquireScratch();
            final Set<Object> visited = visitedSet(scratch);
            try {
                for (int i = 0, n = list.size(); i < n; i++) {
                    releaseSubtreeBuffers(list.get(i), 0, visited);
                }
            } finally {
                releaseScratch(scratch);
            }
            return;
        }
        if (entitiesCollection instanceof Iterable<?> iterable) {
            final Iterator<?> it = iterable.iterator();
            if (!it.hasNext()) {
                return;
            }
            final TraversalScratch scratch = acquireScratch();
            final Set<Object> visited = visitedSet(scratch);
            try {
                while (it.hasNext()) {
                    releaseSubtreeBuffers(it.next(), 0, visited);
                }
            } finally {
                releaseScratch(scratch);
            }
        }
    }

    /** Releases buffers on an editable mesh selection entity subtree and its map values. */
    void onSelectionEntityReset(final Object selectionEntity) {
        if (closed.get() || selectionEntity == null) {
            return;
        }
        final TraversalScratch scratch = acquireScratch();
        try {
            releaseSubtreeBuffers(selectionEntity, 0, visitedSet(scratch));
        } finally {
            releaseScratch(scratch);
        }
    }

    /** Releases buffers inside a Mesh Editor view context on deactivation. */
    void onMeshEditorDeactivate(final Object meshEditorViewContext) {
        if (closed.get() || meshEditorViewContext == null) {
            return;
        }
        final TraversalScratch scratch = acquireScratch();
        try {
            releaseObjectGraphBuffers(meshEditorViewContext, 0, visitedSet(scratch));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        } finally {
            releaseScratch(scratch);
        }
    }

    /** Observes active texture and sampler handles bound to {@code gl} after setup. */
    void observeTexture(final Object texture, final Object gl) {
        if (closed.get() || texture == null || gl == null) {
            return;
        }
        try {
            final Object ctx = safeGetContext(gl);
            final int texId = ((Number) texNameField.get(texture)).intValue();
            final int samplerId = ((Number) samplerNameField.get(texture)).intValue();
            final TextureSlot slot = getOrCreateTextureSlot(texture);
            slot.ctxRef.set(ctx == null ? null : new WeakReference<>(ctx));
            slot.texId.set(Math.max(0, texId));
            slot.samplerId.set(Math.max(0, samplerId));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Clears tracked GL names before explicit host GL resource release. */
    void clearTextureTracking(final Object texture) {
        if (texture == null) {
            return;
        }
        final TextureSlot slot = textureSlots.get(texture);
        if (slot != null) {
            slot.texId.set(INVALID_GL_NAME);
            slot.samplerId.set(INVALID_GL_NAME);
        }
    }

    /** Queues disposed texture and sampler handles for deletion and clears CPU pixel references. */
    void onTextureDisposing(final Object texture) {
        if (closed.get() || texture == null) {
            return;
        }
        try {
            final int texId = ((Number) texNameField.get(texture)).intValue();
            final int samplerId = ((Number) samplerNameField.get(texture)).intValue();
            Object ctx = null;
            Object ownerGl = null;
            if (initializedGlField != null && texture2dClass.isInstance(texture)) {
                ownerGl = initializedGlField.get(texture);
                if (ownerGl != null) {
                    ctx = safeGetContext(ownerGl);
                }
            }
            final TextureSlot removedSlot = textureSlots.remove(texture);
            if (removedSlot != null) {
                removedSlot.texId.set(INVALID_GL_NAME);
                removedSlot.samplerId.set(INVALID_GL_NAME);
                if (ctx == null) {
                    final WeakReference<Object> ref = removedSlot.ctxRef.get();
                    ctx = ref == null ? null : ref.get();
                }
                if (removedSlot.cleanable != null) {
                    removedSlot.cleanable.clean();
                }
            }
            if (ctx != null) {
                enqueueTextureDeletes(ownerGl, ctx, texId, samplerId);
            }
            texNameField.set(texture, UNINITIALIZED_TEXTURE_NAME);
            samplerNameField.set(texture, UNINITIALIZED_TEXTURE_NAME);
            reinitTexField.set(texture, Boolean.TRUE);
            reinitSamplerField.set(texture, Boolean.TRUE);
            if (texture2dClass.isInstance(texture)) {
                initializedGlField.set(texture, null);
                preBufferField.set(texture, null);
                srcImageResourceField.set(texture, null);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Drains deletes and prunes stale rendering cache entries when an offscreen GL context disposes. */
    void onGlDrawableDisposing(final Object glAutoDrawable) {
        if (closed.get() || glAutoDrawable == null) {
            return;
        }
        try {
            final Object gl = (Object) drawableGetGl.invokeExact(glAutoDrawable);
            if (gl == null) {
                return;
            }
            final Object rootGl = (Object) glGetRootGl.invokeExact(gl);
            final Object ctx = safeGetContext(gl);
            if (ctx != null) {
                final ContextDeletes removed = queues.remove(ctx);
                if (removed != null) {
                    drainContextQueue(gl, removed);
                }
            }
            pruneRenderingCache(gl, rootGl, ctx);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Clears callback list and offscreen writable image after {@code glWrapper.a.c()} destroys its drawable. */
    void onGlWrapperDestroyed(final Object wrapper) {
        if (closed.get() || wrapper == null) {
            return;
        }
        try {
            final Object callbacks = wrapperCallbacksField.get(wrapper);
            if (callbacks instanceof List<?> list) {
                list.clear();
            }
            wrapperImageField.set(wrapper, null);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Releases all tracking maps when the mitigation bridge closes. */
    void close() {
        closed.set(true);
        pendingQueueCount.set(0);
        queues.clear();
        bufferSlots.clear();
        textureSlots.clear();
        traversalScratch.remove();
        deleteBatchScratch.remove();
    }

    private void releaseSubtreeBuffers(final Object entity, final int depth, final Set<Object> visited) {
        if (entity == null || depth > 8 || !visited.add(entity)) {
            return;
        }
        releaseEntityOwnBuffers(entity, depth, visited);
        try {
            final Object children = (Object) entityGetChildren.invokeExact(entity);
            final List<?> childList = toRandomAccessList(children);
            if (childList != null) {
                for (int i = 0, n = childList.size(); i < n; i++) {
                    releaseSubtreeBuffers(childList.get(i), depth + 1, visited);
                }
                return;
            }
            if (children instanceof Iterable<?> iterable) {
                for (final Object child : iterable) {
                    releaseSubtreeBuffers(child, depth + 1, visited);
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void releaseEntityOwnBuffers(final Object entity, final int depth, final Set<Object> visited) {
        try {
            releaseObjectGraphBuffers(entity, depth, visited);
            final Object components = (Object) entityGetComponents.invokeExact(entity);
            final List<?> compList = toRandomAccessList(components);
            if (compList != null) {
                for (int i = 0, n = compList.size(); i < n; i++) {
                    releaseSingleComponentBuffers(compList.get(i), depth, visited);
                }
                return;
            }
            if (components instanceof Iterable<?> iterable) {
                for (final Object component : iterable) {
                    releaseSingleComponentBuffers(component, depth, visited);
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void releaseSingleComponentBuffers(final Object component, final int depth, final Set<Object> visited)
            throws Throwable {
        if (component == null) {
            return;
        }
        releaseObjectGraphBuffers(component, depth + 1, visited);
        if (isMeshRenderer(component)) {
            final Object container = (Object) meshRendererGetContainer.invokeExact(component);
            if (container != null) {
                final Object mesh = (Object) containerGetMesh.invokeExact(container);
                if (mesh != null) {
                    releaseObjectGraphBuffers(mesh, depth + 2, visited);
                }
            }
        }
    }

    private boolean isMeshRenderer(final Object component) {
        return component.getClass().getName().equals("com.live2d.graphics3d.component.GMeshRenderer");
    }

    private void releaseObjectGraphBuffers(final Object holder, final int depth, final Set<Object> visited)
            throws Throwable {
        if (holder == null || depth > 6) {
            return;
        }
        if (bufferBaseClass.isInstance(holder)) {
            releaseSingleBuffer(holder);
            return;
        }
        if (depth > 0 && entityClass.isInstance(holder)) {
            releaseSubtreeBuffers(holder, depth + 1, visited);
            return;
        }
        final HostHandles.HostField[] fields = bufferFieldsCache.get(holder.getClass());
        for (int i = 0, n = fields.length; i < n; i++) {
            final Object value = fields[i].get(holder);
            if (value == null) {
                continue;
            }
            if (bufferBaseClass.isInstance(value)) {
                releaseSingleBuffer(value);
            } else if (entityClass.isInstance(value)) {
                releaseSubtreeBuffers(value, depth + 1, visited);
            } else if (value instanceof List<?> list) {
                for (int idx = 0, count = list.size(); idx < count; idx++) {
                    releaseObjectGraphBuffers(list.get(idx), depth + 1, visited);
                }
            } else if (value instanceof Iterable<?> iterable) {
                for (final Object item : iterable) {
                    releaseObjectGraphBuffers(item, depth + 1, visited);
                }
            } else if (value instanceof Map<?, ?> map) {
                if (!map.isEmpty()) {
                    for (final Object item : map.values()) {
                        releaseObjectGraphBuffers(item, depth + 1, visited);
                    }
                }
            } else if (visited.add(value)) {
                releaseObjectGraphBuffers(value, depth + 1, visited);
            }
        }
    }

    private void releaseSingleBuffer(final Object meshBuffer) throws Throwable {
        final IntBuffer nameBuf = (IntBuffer) bufGetIntBuffer.invokeExact(meshBuffer);
        final int currentId = nameBuf == null || nameBuf.capacity() < 1 ? INVALID_GL_NAME : nameBuf.get(0);
        final Object oldGl = (Object) bufGetGl.invokeExact(meshBuffer);
        final BufferSlot removedSlot = bufferSlots.remove(meshBuffer);
        if (removedSlot != null) {
            removedSlot.bufferId.set(INVALID_GL_NAME);
            if (removedSlot.cleanable != null) {
                removedSlot.cleanable.clean();
            }
        }
        if (nameBuf != null && nameBuf.capacity() > 0) {
            nameBuf.put(0, INVALID_GL_NAME);
        }
        bufSetGl.invokeExact(meshBuffer, (Object) null);
        bufSetReinit.invokeExact(meshBuffer, true);
        bufSetDirty.invokeExact(meshBuffer, true);
        if (oldGl != null && currentId > INVALID_GL_NAME) {
            enqueueBufferDelete(oldGl, currentId);
        }
    }

    private HostHandles.HostField[] scanResourceFields(final Class<?> type) {
        final List<HostHandles.HostField> result = new ArrayList<>();
        for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (final Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                final Class<?> ft = f.getType();
                if (isInterestingResourceType(ft)) {
                    try {
                        result.add(HostHandles.field(cur, f.getName()));
                    } catch (final ReflectiveOperationException ignored) {
                        // Skip inaccessible field cleanly.
                    }
                }
            }
        }
        return result.toArray(HostHandles.HostField[]::new);
    }

    private boolean isInterestingResourceType(final Class<?> type) {
        if (bufferBaseClass.isAssignableFrom(type) || entityClass.isAssignableFrom(type)) {
            return true;
        }
        final String name = type.getName();
        return name.equals("com.live2d.graphics3d.mesh.GMesh2")
                || name.equals("com.live2d.graphics3d.component.GLineRenderer")
                || name.equals("com.live2d.graphics3d.component.GPointRenderer")
                || name.equals("com.live2d.graphics3d.component.GGroupRenderer")
                || name.equals("com.live2d.graphics3d.geometry.GSimpleLinesEntity")
                || name.equals("com.live2d.cubism.doc.model.drawable.artMesh.DrawEntity_EditableMesh")
                || name.equals("com.live2d.cubism.view.context.drawImpl.EditableMeshSelectionEntity")
                || name.equals("com.live2d.cubism.view.context.drawImpl.EditableMeshSelectionEntity$a")
                || name.equals("com.live2d.cubism.view.context.CEViewContext_MeshEditor$ImageAndMeshEntity")
                || (Map.class.isAssignableFrom(type) && name.equals("java.util.HashMap"))
                || (Iterable.class.isAssignableFrom(type) && name.equals("com.live2d.type.CArrayList"));
    }

    private void enqueueBufferDelete(final Object ownerGl, final int id) {
        if (id <= INVALID_GL_NAME || ownerGl == null || closed.get()) {
            return;
        }
        try {
            final Object ctx = safeGetContext(ownerGl);
            if (ctx == null) {
                return;
            }
            if (ctx == (Object) contextGetCurrent.invokeExact() && (boolean) contextIsCreated.invokeExact(ctx)) {
                glDeleteBuffersArray.invokeExact(ownerGl, 1, new int[] {id}, 0);
                recordVboDeleted(1);
                return;
            }
            pendingQueueCount.incrementAndGet();
            queueFor(ctx).buffers.add(id);
            recordVboQueued();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void enqueueTextureDeletes(final Object ownerGl, final Object ctx, final int texId, final int samplerId) {
        if (ctx == null || closed.get()) {
            return;
        }
        try {
            final boolean isCurrent =
                    ownerGl != null && ctx == (Object) contextGetCurrent.invokeExact() && (boolean) contextIsCreated.invokeExact(ctx);
            if (texId > INVALID_GL_NAME) {
                if (isCurrent) {
                    glDeleteTexturesArray.invokeExact(ownerGl, 1, new int[] {texId}, 0);
                } else {
                    pendingQueueCount.incrementAndGet();
                    queueFor(ctx).textures.add(texId);
                }
                recordTextureQueued();
            }
            if (samplerId > INVALID_GL_NAME) {
                if (isCurrent && (boolean) glIsGl3.invokeExact(ownerGl)) {
                    final Object gl3 = (Object) glGetGl3.invokeExact(ownerGl);
                    glDeleteSamplersArray.invokeExact(gl3, 1, new int[] {samplerId}, 0);
                } else {
                    pendingQueueCount.incrementAndGet();
                    queueFor(ctx).samplers.add(samplerId);
                }
                recordTextureQueued();
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void drainContextQueue(final Object gl, final ContextDeletes queue) throws Throwable {
        if (!queue.buffers.isEmpty()) {
            final int[] batch = deleteBatchScratch.get();
            int count = 0;
            int polled = 0;
            Integer id;
            while ((id = queue.buffers.poll()) != null) {
                polled++;
                if (id > INVALID_GL_NAME) {
                    batch[count++] = id;
                    if (count == DRAIN_BATCH_LIMIT) {
                        glDeleteBuffersArray.invokeExact(gl, count, batch, 0);
                        recordVboDeleted(count);
                        count = 0;
                    }
                }
            }
            if (count > 0) {
                glDeleteBuffersArray.invokeExact(gl, count, batch, 0);
                recordVboDeleted(count);
            }
            if (polled > 0) {
                decrementPendingCount(polled);
            }
        }
        if (!queue.textures.isEmpty()) {
            final int[] batch = deleteBatchScratch.get();
            int count = 0;
            int polled = 0;
            Integer id;
            while ((id = queue.textures.poll()) != null) {
                polled++;
                if (id > INVALID_GL_NAME) {
                    batch[count++] = id;
                    if (count == DRAIN_BATCH_LIMIT) {
                        glDeleteTexturesArray.invokeExact(gl, count, batch, 0);
                        count = 0;
                    }
                }
            }
            if (count > 0) {
                glDeleteTexturesArray.invokeExact(gl, count, batch, 0);
            }
            if (polled > 0) {
                decrementPendingCount(polled);
            }
        }
        if (!queue.samplers.isEmpty() && (boolean) glIsGl3.invokeExact(gl)) {
            final Object gl3 = (Object) glGetGl3.invokeExact(gl);
            final int[] batch = deleteBatchScratch.get();
            int count = 0;
            int polled = 0;
            Integer id;
            while ((id = queue.samplers.poll()) != null) {
                polled++;
                if (id > INVALID_GL_NAME) {
                    batch[count++] = id;
                    if (count == DRAIN_BATCH_LIMIT) {
                        glDeleteSamplersArray.invokeExact(gl3, count, batch, 0);
                        count = 0;
                    }
                }
            }
            if (count > 0) {
                glDeleteSamplersArray.invokeExact(gl3, count, batch, 0);
            }
            if (polled > 0) {
                decrementPendingCount(polled);
            }
        }
    }

    private void pruneRenderingCache(final Object gl, final Object rootGl, final Object disposingCtx) throws Throwable {
        if (renderingLock == null || renderingMap == null) {
            return;
        }
        synchronized (renderingLock) {
            final Iterator<? extends Map.Entry<?, ?>> it = renderingMap.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<?, ?> entry = it.next();
                final Object keyGl = entry.getKey();
                if (keyGl == null) {
                    it.remove();
                    recordContextPruned();
                    continue;
                }
                final Object keyCtx = safeGetContext(keyGl);
                final boolean dead = keyGl == gl
                        || keyGl == rootGl
                        || (disposingCtx != null && keyCtx == disposingCtx)
                        || keyCtx == null
                        || !(boolean) contextIsCreated.invokeExact(keyCtx);
                if (dead) {
                    it.remove();
                    recordContextPruned();
                }
            }
        }
    }

    private ContextDeletes queueFor(final Object ctx) {
        ContextDeletes queue = queues.get(ctx);
        if (queue == null) {
            synchronized (queues) {
                queue = queues.get(ctx);
                if (queue == null) {
                    queue = new ContextDeletes();
                    queues.put(ctx, queue);
                }
            }
        }
        return queue;
    }

    private BufferSlot getOrCreateBufferSlot(final Object meshBuffer) {
        BufferSlot slot = bufferSlots.get(meshBuffer);
        if (slot == null) {
            synchronized (bufferSlots) {
                slot = bufferSlots.get(meshBuffer);
                if (slot == null) {
                    slot = new BufferSlot(this);
                    bufferSlots.put(meshBuffer, slot);
                    slot.cleanable = CLEANER.register(meshBuffer, new BufferCleanupAction(slot));
                }
            }
        }
        return slot;
    }

    private TextureSlot getOrCreateTextureSlot(final Object texture) {
        TextureSlot slot = textureSlots.get(texture);
        if (slot == null) {
            synchronized (textureSlots) {
                slot = textureSlots.get(texture);
                if (slot == null) {
                    slot = new TextureSlot(this);
                    textureSlots.put(texture, slot);
                    slot.cleanable = CLEANER.register(texture, new TextureCleanupAction(slot));
                }
            }
        }
        return slot;
    }

    private Object safeGetContext(final Object gl) throws Throwable {
        return gl == null ? null : (Object) glGetContext.invokeExact(gl);
    }

    private void enqueueFromCleaner(final BufferSlot slot) {
        if (closed.get()) {
            return;
        }
        final int id = slot.bufferId.getAndSet(INVALID_GL_NAME);
        if (id <= INVALID_GL_NAME) {
            return;
        }
        final WeakReference<Object> ref = slot.ctxRef.get();
        final Object ctx = ref == null ? null : ref.get();
        if (ctx != null) {
            pendingQueueCount.incrementAndGet();
            queueFor(ctx).buffers.add(id);
            recordVboQueued();
        }
    }

    private void enqueueFromCleaner(final TextureSlot slot) {
        if (closed.get()) {
            return;
        }
        final int texId = slot.texId.getAndSet(INVALID_GL_NAME);
        final int samplerId = slot.samplerId.getAndSet(INVALID_GL_NAME);
        if (texId <= INVALID_GL_NAME && samplerId <= INVALID_GL_NAME) {
            return;
        }
        final WeakReference<Object> ref = slot.ctxRef.get();
        final Object ctx = ref == null ? null : ref.get();
        if (ctx != null) {
            final ContextDeletes queue = queueFor(ctx);
            if (texId > INVALID_GL_NAME) {
                pendingQueueCount.incrementAndGet();
                queue.textures.add(texId);
                recordTextureQueued();
            }
            if (samplerId > INVALID_GL_NAME) {
                pendingQueueCount.incrementAndGet();
                queue.samplers.add(samplerId);
                recordTextureQueued();
            }
        }
    }

    private static MethodHandle resolveOptionalListGetter(final Class<?> owner, final String methodName) {
        try {
            return HostHandles.virtual(
                    owner, methodName, "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));
        } catch (final ReflectiveOperationException ignored) {
            return null;
        }
    }

    private List<?> toRandomAccessList(final Object collection) {
        if (collection instanceof List<?> list) {
            return list;
        }
        try {
            if (entitiesGetList != null && entitiesClass.isInstance(collection)) {
                return (List<?>) entitiesGetList.invokeExact(collection);
            }
            if (componentsGetList != null && componentsClass.isInstance(collection)) {
                return (List<?>) componentsGetList.invokeExact(collection);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
        return null;
    }

    private TraversalScratch acquireScratch() {
        final TraversalScratch scratch = traversalScratch.get();
        if (scratch.inUse) {
            return null;
        }
        scratch.inUse = true;
        return scratch;
    }

    private Set<Object> visitedSet(final TraversalScratch scratch) {
        return scratch != null
                ? scratch.visited
                : Collections.newSetFromMap(new IdentityHashMap<>(SCRATCH_INITIAL_CAPACITY));
    }

    private void releaseScratch(final TraversalScratch scratch) {
        if (scratch == null) {
            return;
        }
        if (!scratch.visited.isEmpty()) {
            if (scratch.visited.size() > SCRATCH_RETAIN_CAP) {
                scratch.visited = Collections.newSetFromMap(new IdentityHashMap<>(SCRATCH_INITIAL_CAPACITY));
            } else {
                scratch.visited.clear();
            }
        }
        scratch.inUse = false;
    }

    private void decrementPendingCount(final int count) {
        pendingQueueCount.updateAndGet(current -> Math.max(0, current - count));
    }

    private void recordVboReused() {
        final StatsRecorder r = stats.get();
        if (r != null) r.onVboReused();
    }

    private void recordVboQueued() {
        final StatsRecorder r = stats.get();
        if (r != null) r.onVboQueued();
    }

    private void recordVboDeleted(final int count) {
        final StatsRecorder r = stats.get();
        if (r != null) r.onVboDeleted(count);
    }

    private void recordTextureQueued() {
        final StatsRecorder r = stats.get();
        if (r != null) r.onTextureQueued();
    }

    private void recordContextPruned() {
        final StatsRecorder r = stats.get();
        if (r != null) r.onContextPruned();
    }

    private static final class TraversalScratch {
        private Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>(SCRATCH_INITIAL_CAPACITY));
        private boolean inUse;
    }

    private static final class ContextDeletes {
        private final ConcurrentLinkedQueue<Integer> buffers = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Integer> textures = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Integer> samplers = new ConcurrentLinkedQueue<>();
    }

    private static final class BufferSlot {
        private final GlDeleteQueue owner;
        private final AtomicReference<WeakReference<Object>> ctxRef = new AtomicReference<>();
        private final AtomicInteger bufferId = new AtomicInteger(INVALID_GL_NAME);
        private Cleaner.Cleanable cleanable;

        private BufferSlot(final GlDeleteQueue owner) {
            this.owner = owner;
        }
    }

    private record BufferCleanupAction(BufferSlot slot) implements Runnable {
        @Override
        public void run() {
            slot.owner.enqueueFromCleaner(slot);
        }
    }

    private static final class TextureSlot {
        private final GlDeleteQueue owner;
        private final AtomicReference<WeakReference<Object>> ctxRef = new AtomicReference<>();
        private final AtomicInteger texId = new AtomicInteger(INVALID_GL_NAME);
        private final AtomicInteger samplerId = new AtomicInteger(INVALID_GL_NAME);
        private Cleaner.Cleanable cleanable;

        private TextureSlot(final GlDeleteQueue owner) {
            this.owner = owner;
        }
    }

    private record TextureCleanupAction(TextureSlot slot) implements Runnable {
        @Override
        public void run() {
            slot.owner.enqueueFromCleaner(slot);
        }
    }
}
