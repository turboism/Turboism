package dev.turboism.adapter.cubism.optimization.camera;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Caches {@code GCamera.getWorldToCameraMatrix()} results across bounded ancestor hierarchies whose
 * position, Euler angles, and scale bits remain identical.
 */
public final class CameraMatrixCacheBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.cameraMatrixCache";

    /** Property key for the {@code Function<Object, Object>} cache lookup callback. */
    public static final String GET_PROPERTY = "turboism.camera-matrix.get";

    /** Property key for the {@code BiFunction<Object, Object, Object>} cache store callback. */
    public static final String PUT_PROPERTY = "turboism.camera-matrix.put";

    /** Property key exposing diagnostic counters. */
    public static final String STATS_PROPERTY = "turboism.camera-matrix.stats";

    private static final int CACHE_CAPACITY = 8;
    private static final int MAX_ANCESTOR_DEPTH = 4;
    private static final int LEVEL_STRIDE = 10;
    private static final int STATE_LENGTH = MAX_ANCESTOR_DEPTH * LEVEL_STRIDE;
    private static final AtomicInteger COMPILED_CLASS_SEQUENCE = new AtomicInteger();
    private static final String COMPILED_READER_PREFIX =
            "dev/turboism/adapter/cubism/optimization/camera/CompiledCameraHierarchyReader$";
    private static final String JAVA_OBJECT_OWNER = "java/lang/Object";
    private static final String BI_PREDICATE_OWNER = "java/util/function/BiPredicate";
    private static final String VAR_HANDLE_OWNER = "java/lang/invoke/VarHandle";
    private static final String VAR_HANDLE_DESCRIPTOR = "Ljava/lang/invoke/VarHandle;";
    private static final String STATIC_MATRIX_FIELD_NAME = "staticMatrix";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String VOID_OBJECT_CTOR = "()V";
    private static final String ACCESSOR_CTOR_DESCRIPTOR = "(Ljava/lang/invoke/VarHandle;)V";
    private static final String TEST_METHOD = "test";
    private static final String TEST_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String ARRAYS_OWNER = "java/util/Arrays";
    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String FLOAT_OWNER = "java/lang/Float";
    private static final int VAR_SELF = 0;
    private static final int VAR_CAMERA = 1;
    private static final int VAR_OUT_BITS = 2;
    private static final int VAR_TRANSFORM = 3;
    private static final int VAR_ENTITY = 4;
    private static final int VAR_DEPTH = 5;
    private static final int VAR_BASE = 6;
    private static final int VAR_POS = 7;
    private static final int VAR_EULER = 8;
    private static final int VAR_SCALE = 9;
    private static final int POS_X_OFFSET = 1;
    private static final int POS_Y_OFFSET = 2;
    private static final int POS_Z_OFFSET = 3;
    private static final int EULER_X_OFFSET = 4;
    private static final int EULER_Y_OFFSET = 5;
    private static final int EULER_Z_OFFSET = 6;
    private static final int SCALE_X_OFFSET = 7;
    private static final int SCALE_Y_OFFSET = 8;
    private static final int SCALE_Z_OFFSET = 9;

    private final MethodHandle cameraTransformHandle;
    private final MethodHandle transformEntityHandle;
    private final MethodHandle entityParentHandle;
    private final MethodHandle entityTransformHandle;
    private final MethodHandle staticMatrixHandle;
    private final MethodHandle positionHandle;
    private final MethodHandle eulerAnglesHandle;
    private final MethodHandle scaleHandle;
    private final MethodHandle vecXHandle;
    private final MethodHandle vecYHandle;
    private final MethodHandle vecZHandle;
    private final BiPredicate<Object, int[]> compiledHierarchyReader;

    private final AtomicBoolean active = new AtomicBoolean();
    private final WeakReference<?>[] cameraRefs = new WeakReference<?>[CACHE_CAPACITY];
    private final int[] stateBits = new int[CACHE_CAPACITY * STATE_LENGTH];
    private final Object[] cachedMatrices = new Object[CACHE_CAPACITY];
    private final ThreadLocal<int[]> scratchBits = ThreadLocal.withInitial(() -> new int[STATE_LENGTH]);

    private final Function<Object, Object> getCallback = this::lookup;
    private final BiFunction<Object, Object, Object> putCallback = this::store;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private Properties installedProperties;
    private long lookups;
    private long hits;
    private long misses;
    private long observerFailures;

    /**
     * Resolves exact-typed method handles on the admitted host class loader.
     *
     * @param loader admitted host class loader
     * @throws ReflectiveOperationException when any reviewed member is unavailable
     */
    public CameraMatrixCacheBridge(final ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> cameraType = HostHandles.load(loader, CameraMatrixCacheTarget.CAMERA_OWNER);
        final Class<?> transformType = HostHandles.load(loader, CameraMatrixCacheTarget.TRANSFORM_OWNER);
        final Class<?> entityType = HostHandles.load(loader, CameraMatrixCacheTarget.ENTITY_OWNER);
        final Class<?> vectorType = HostHandles.load(loader, CameraMatrixCacheTarget.VECTOR3_OWNER);
        final MethodType objectUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);

        cameraTransformHandle = HostHandles.virtual(
                cameraType,
                CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR,
                objectUnary);
        transformEntityHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_ENTITY_METHOD,
                CameraMatrixCacheTarget.GET_ENTITY_DESCRIPTOR,
                objectUnary);
        entityParentHandle = HostHandles.virtual(
                entityType,
                CameraMatrixCacheTarget.GET_PARENT_ENTITY_METHOD,
                CameraMatrixCacheTarget.GET_PARENT_ENTITY_DESCRIPTOR,
                objectUnary);
        entityTransformHandle = HostHandles.virtual(
                entityType,
                CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR,
                objectUnary);
        staticMatrixHandle = HostHandles.getter(
                transformType,
                CameraMatrixCacheTarget.STATIC_MATRIX_FIELD,
                CameraMatrixCacheTarget.MATRIX_FIELD_DESCRIPTOR,
                objectUnary);
        positionHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_POSITION_METHOD,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                objectUnary);
        eulerAnglesHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_EULER_ANGLES_METHOD,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                objectUnary);
        scaleHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_SCALE_METHOD,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                objectUnary);
        vecXHandle = HostHandles.virtual(
                vectorType,
                CameraMatrixCacheTarget.GET_X_METHOD,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                floatUnary);
        vecYHandle = HostHandles.virtual(
                vectorType,
                CameraMatrixCacheTarget.GET_Y_METHOD,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                floatUnary);
        vecZHandle = HostHandles.virtual(
                vectorType,
                CameraMatrixCacheTarget.GET_Z_METHOD,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                floatUnary);
        compiledHierarchyReader = compileHierarchyReader(loader, cameraType, transformType, entityType, vectorType);
    }

    boolean usesCompiledReader() {
        return compiledHierarchyReader != null;
    }

    /** Publishes the camera-matrix lookup and store callbacks into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException("camera matrix cache already installed");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(GET_PROPERTY)
                    || properties.containsKey(PUT_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("camera matrix cache slots occupied");
            }
            properties.put(GET_PROPERTY, getCallback);
            properties.put(PUT_PROPERTY, putCallback);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
            clearEntries();
            active.set(true);
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        clearEntries();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(GET_PROPERTY, getCallback);
                properties.remove(PUT_PROPERTY, putCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Returns an immutable snapshot of cache counters. */
    public Map<String, Long> snapshot() {
        return Map.of(
                "lookups", lookups,
                "hits", hits,
                "misses", misses,
                "observerFailures", observerFailures);
    }

    private void clearEntries() {
        Arrays.fill(cameraRefs, null);
        Arrays.fill(cachedMatrices, null);
        Arrays.fill(stateBits, 0);
    }

    private Object lookup(final Object camera) {
        lookups++;
        if (!active.get() || camera == null) {
            misses++;
            return null;
        }
        try {
            final int[] bits = scratchBits.get();
            if (!readCameraHierarchyState(camera, bits)) {
                misses++;
                return null;
            }
            synchronized (this) {
                for (int slot = 0; slot < CACHE_CAPACITY; slot++) {
                    final WeakReference<?> ref = cameraRefs[slot];
                    if (ref != null && ref.get() == camera && matchesBits(slot * STATE_LENGTH, bits)) {
                        final Object matrix = cachedMatrices[slot];
                        if (matrix != null) {
                            hits++;
                            return matrix;
                        }
                    }
                }
            }
            misses++;
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            misses++;
            return null;
        }
    }

    private Object store(final Object camera, final Object matrix) {
        if (!active.get() || camera == null || matrix == null) {
            return matrix;
        }
        try {
            final int[] bits = scratchBits.get();
            if (!readCameraHierarchyState(camera, bits)) {
                return matrix;
            }
            synchronized (this) {
                int targetSlot = 0;
                for (int slot = 0; slot < CACHE_CAPACITY; slot++) {
                    final WeakReference<?> ref = cameraRefs[slot];
                    if (ref == null || ref.get() == null || ref.get() == camera) {
                        targetSlot = slot;
                        break;
                    }
                }
                System.arraycopy(bits, 0, stateBits, targetSlot * STATE_LENGTH, STATE_LENGTH);
                cachedMatrices[targetSlot] = matrix;
                cameraRefs[targetSlot] = new WeakReference<>(camera);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
        return matrix;
    }

    private boolean matchesBits(final int offset, final int[] candidate) {
        for (int i = 0; i < STATE_LENGTH; i++) {
            if (stateBits[offset + i] != candidate[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean readCameraHierarchyState(final Object camera, final int[] outBits) throws Throwable {
        if (compiledHierarchyReader != null) {
            return compiledHierarchyReader.test(camera, outBits);
        }
        return readCameraHierarchyStateHandles(camera, outBits);
    }

    private boolean readCameraHierarchyStateHandles(final Object camera, final int[] outBits) throws Throwable {
        Object transform = (Object) cameraTransformHandle.invokeExact(camera);
        if (transform == null) {
            return false;
        }
        Object entity = (Object) transformEntityHandle.invokeExact(transform);
        if (entity == null) {
            return false;
        }
        Arrays.fill(outBits, 0);
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH; depth++) {
            if ((Object) staticMatrixHandle.invokeExact(transform) != null) {
                return false;
            }
            if (!writeTransformState(entity, transform, outBits, depth * LEVEL_STRIDE)) {
                return false;
            }
            entity = (Object) entityParentHandle.invokeExact(entity);
            if (entity == null) {
                return true;
            }
            transform = (Object) entityTransformHandle.invokeExact(entity);
            if (transform == null) {
                return false;
            }
        }
        return false;
    }

    private static BiPredicate<Object, int[]> compileHierarchyReader(
            final ClassLoader loader,
            final Class<?> cameraType,
            final Class<?> transformType,
            final Class<?> entityType,
            final Class<?> vectorType) {
        if (loader == null
                || !isPublicHierarchy(cameraType, transformType, entityType, vectorType)
                || !hasPublicAccessors(cameraType, transformType, entityType, vectorType)) {
            return null;
        }
        try {
            final Field staticField = findDeclaredField(transformType, CameraMatrixCacheTarget.STATIC_MATRIX_FIELD);
            if (staticField == null) {
                return null;
            }
            final Class<?> fieldOwner = staticField.getDeclaringClass();
            final VarHandle staticMatrixVarHandle = MethodHandles.privateLookupIn(fieldOwner, MethodHandles.lookup())
                    .findVarHandle(fieldOwner, staticField.getName(), staticField.getType());
            final String internalName = COMPILED_READER_PREFIX + COMPILED_CLASS_SEQUENCE.incrementAndGet();
            final ClassWriter writer = HostClassWriters.newFrameWriter(loader);
            writer.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                    internalName,
                    null,
                    JAVA_OBJECT_OWNER,
                    new String[] {BI_PREDICATE_OWNER});
            writer.visitField(
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                            STATIC_MATRIX_FIELD_NAME,
                            VAR_HANDLE_DESCRIPTOR,
                            null,
                            null)
                    .visitEnd();
            emitAccessorConstructor(writer, internalName);
            emitTestBridge(writer, internalName);
            emitTypedTest(writer, internalName, cameraType, transformType, entityType, vectorType, fieldOwner);
            writer.visitEnd();
            final AccessorClassLoader accessorLoader = new AccessorClassLoader(loader);
            final Class<?> compiled = accessorLoader.define(internalName.replace('/', '.'), writer.toByteArray());
            @SuppressWarnings("unchecked")
            final BiPredicate<Object, int[]> instance = (BiPredicate<Object, int[]>)
                    compiled.getConstructor(VarHandle.class).newInstance(staticMatrixVarHandle);
            return instance;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static boolean isPublicHierarchy(
            final Class<?> cameraType,
            final Class<?> transformType,
            final Class<?> entityType,
            final Class<?> vectorType) {
        return isAccessiblePublic(cameraType)
                && isAccessiblePublic(transformType)
                && isAccessiblePublic(entityType)
                && isAccessiblePublic(vectorType);
    }

    private static boolean isAccessiblePublic(final Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPublicAccessors(
            final Class<?> cameraType,
            final Class<?> transformType,
            final Class<?> entityType,
            final Class<?> vectorType) {
        return hasPublicMethod(
                        cameraType,
                        CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                        CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR)
                && hasPublicMethod(
                        transformType,
                        CameraMatrixCacheTarget.GET_ENTITY_METHOD,
                        CameraMatrixCacheTarget.GET_ENTITY_DESCRIPTOR)
                && hasPublicMethod(
                        entityType,
                        CameraMatrixCacheTarget.GET_PARENT_ENTITY_METHOD,
                        CameraMatrixCacheTarget.GET_PARENT_ENTITY_DESCRIPTOR)
                && hasPublicMethod(
                        entityType,
                        CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                        CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR)
                && hasPublicMethod(
                        transformType,
                        CameraMatrixCacheTarget.GET_POSITION_METHOD,
                        CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR)
                && hasPublicMethod(
                        transformType,
                        CameraMatrixCacheTarget.GET_EULER_ANGLES_METHOD,
                        CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR)
                && hasPublicMethod(
                        transformType,
                        CameraMatrixCacheTarget.GET_SCALE_METHOD,
                        CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR)
                && hasPublicMethod(
                        vectorType,
                        CameraMatrixCacheTarget.GET_X_METHOD,
                        CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR)
                && hasPublicMethod(
                        vectorType,
                        CameraMatrixCacheTarget.GET_Y_METHOD,
                        CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR)
                && hasPublicMethod(
                        vectorType,
                        CameraMatrixCacheTarget.GET_Z_METHOD,
                        CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR);
    }

    private static boolean hasPublicMethod(final Class<?> owner, final String name, final String descriptor) {
        for (final Method candidate : owner.getMethods()) {
            if (candidate.getName().equals(name)
                    && Modifier.isPublic(candidate.getModifiers())
                    && isAccessiblePublic(candidate.getDeclaringClass())
                    && Type.getMethodDescriptor(candidate).equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static Field findDeclaredField(final Class<?> owner, final String name) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (final NoSuchFieldException ignored) {
                // Continue walking superclasses for inherited static-matrix field.
            }
        }
        return null;
    }

    private static void emitAccessorConstructor(final ClassWriter writer, final String owner) {
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, ACCESSOR_CTOR_DESCRIPTOR, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, VAR_SELF);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, JAVA_OBJECT_OWNER, CONSTRUCTOR_NAME, VOID_OBJECT_CTOR, false);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_SELF);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, owner, STATIC_MATRIX_FIELD_NAME, VAR_HANDLE_DESCRIPTOR);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitTestBridge(final ClassWriter writer, final String owner) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC,
                TEST_METHOD,
                TEST_DESCRIPTOR,
                null,
                null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, VAR_SELF);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_CAMERA);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_OUT_BITS);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[I");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, TEST_METHOD, "(Ljava/lang/Object;[I)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitTypedTest(
            final ClassWriter writer,
            final String internalName,
            final Class<?> cameraType,
            final Class<?> transformType,
            final Class<?> entityType,
            final Class<?> vectorType,
            final Class<?> staticFieldOwner) {
        final String cameraOwner = Type.getInternalName(cameraType);
        final String transformOwner = Type.getInternalName(transformType);
        final String entityOwner = Type.getInternalName(entityType);
        final String vectorOwner = Type.getInternalName(vectorType);
        final String fieldOwner = Type.getInternalName(staticFieldOwner);
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, TEST_METHOD, "(Ljava/lang/Object;[I)Z", null, null);
        mv.visitCode();
        final Label loopStart = new Label(), returnTrue = new Label(), returnFalse = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, VAR_CAMERA);
        mv.visitTypeInsn(Opcodes.CHECKCAST, cameraOwner);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                cameraOwner,
                CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, VAR_TRANSFORM);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_TRANSFORM);
        mv.visitJumpInsn(Opcodes.IFNULL, returnFalse);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_TRANSFORM);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                transformOwner,
                CameraMatrixCacheTarget.GET_ENTITY_METHOD,
                CameraMatrixCacheTarget.GET_ENTITY_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, VAR_ENTITY);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_ENTITY);
        mv.visitJumpInsn(Opcodes.IFNULL, returnFalse);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_OUT_BITS);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARRAYS_OWNER, "fill", "([II)V", false);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, VAR_DEPTH);
        mv.visitLabel(loopStart);
        mv.visitVarInsn(Opcodes.ILOAD, VAR_DEPTH);
        mv.visitIntInsn(Opcodes.BIPUSH, MAX_ANCESTOR_DEPTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, returnFalse);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_SELF);
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName, STATIC_MATRIX_FIELD_NAME, VAR_HANDLE_DESCRIPTOR);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_TRANSFORM);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, VAR_HANDLE_OWNER, "get", "(L" + fieldOwner + ";)Ljava/lang/Object;", false);
        mv.visitJumpInsn(Opcodes.IFNONNULL, returnFalse);
        emitVectorRead(mv, transformOwner, CameraMatrixCacheTarget.GET_POSITION_METHOD, VAR_POS, returnFalse);
        emitVectorRead(mv, transformOwner, CameraMatrixCacheTarget.GET_EULER_ANGLES_METHOD, VAR_EULER, returnFalse);
        emitVectorRead(mv, transformOwner, CameraMatrixCacheTarget.GET_SCALE_METHOD, VAR_SCALE, returnFalse);
        mv.visitVarInsn(Opcodes.ILOAD, VAR_DEPTH);
        mv.visitIntInsn(Opcodes.BIPUSH, LEVEL_STRIDE);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ISTORE, VAR_BASE);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_OUT_BITS);
        mv.visitVarInsn(Opcodes.ILOAD, VAR_BASE);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_ENTITY);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SYSTEM_OWNER, "identityHashCode", "(Ljava/lang/Object;)I", false);
        mv.visitInsn(Opcodes.IASTORE);
        emitVectorBits(mv, vectorOwner, VAR_POS, POS_X_OFFSET, POS_Y_OFFSET, POS_Z_OFFSET);
        emitVectorBits(mv, vectorOwner, VAR_EULER, EULER_X_OFFSET, EULER_Y_OFFSET, EULER_Z_OFFSET);
        emitVectorBits(mv, vectorOwner, VAR_SCALE, SCALE_X_OFFSET, SCALE_Y_OFFSET, SCALE_Z_OFFSET);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_ENTITY);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                entityOwner,
                CameraMatrixCacheTarget.GET_PARENT_ENTITY_METHOD,
                CameraMatrixCacheTarget.GET_PARENT_ENTITY_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, VAR_ENTITY);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_ENTITY);
        mv.visitJumpInsn(Opcodes.IFNULL, returnTrue);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_ENTITY);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                entityOwner,
                CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, VAR_TRANSFORM);
        mv.visitVarInsn(Opcodes.ALOAD, VAR_TRANSFORM);
        mv.visitJumpInsn(Opcodes.IFNULL, returnFalse);
        mv.visitIincInsn(VAR_DEPTH, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopStart);
        mv.visitLabel(returnTrue);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(returnFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitVectorRead(
            final MethodVisitor mv,
            final String transformOwner,
            final String getter,
            final int localSlot,
            final Label onNull) {
        mv.visitVarInsn(Opcodes.ALOAD, VAR_TRANSFORM);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                transformOwner,
                getter,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, localSlot);
        mv.visitVarInsn(Opcodes.ALOAD, localSlot);
        mv.visitJumpInsn(Opcodes.IFNULL, onNull);
    }

    private static void emitVectorBits(
            final MethodVisitor mv,
            final String vectorOwner,
            final int vectorLocal,
            final int xOffset,
            final int yOffset,
            final int zOffset) {
        emitComponentBits(mv, vectorOwner, vectorLocal, CameraMatrixCacheTarget.GET_X_METHOD, xOffset);
        emitComponentBits(mv, vectorOwner, vectorLocal, CameraMatrixCacheTarget.GET_Y_METHOD, yOffset);
        emitComponentBits(mv, vectorOwner, vectorLocal, CameraMatrixCacheTarget.GET_Z_METHOD, zOffset);
    }

    private static void emitComponentBits(
            final MethodVisitor mv,
            final String vectorOwner,
            final int vectorLocal,
            final String componentGetter,
            final int offset) {
        mv.visitVarInsn(Opcodes.ALOAD, VAR_OUT_BITS);
        mv.visitVarInsn(Opcodes.ILOAD, VAR_BASE);
        mv.visitIntInsn(Opcodes.BIPUSH, offset);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ALOAD, vectorLocal);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                vectorOwner,
                componentGetter,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT_OWNER, "floatToRawIntBits", "(F)I", false);
        mv.visitInsn(Opcodes.IASTORE);
    }

    private static final class AccessorClassLoader extends ClassLoader {

        AccessorClassLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String binaryName, final byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private boolean writeTransformState(
            final Object entity, final Object transform, final int[] outBits, final int base) throws Throwable {
        final Object position = (Object) positionHandle.invokeExact(transform);
        final Object euler = (Object) eulerAnglesHandle.invokeExact(transform);
        final Object scale = (Object) scaleHandle.invokeExact(transform);
        if (position == null || euler == null || scale == null) {
            return false;
        }
        outBits[base] = System.identityHashCode(entity);
        outBits[base + 1] = Float.floatToRawIntBits((float) vecXHandle.invokeExact(position));
        outBits[base + 2] = Float.floatToRawIntBits((float) vecYHandle.invokeExact(position));
        outBits[base + 3] = Float.floatToRawIntBits((float) vecZHandle.invokeExact(position));
        outBits[base + 4] = Float.floatToRawIntBits((float) vecXHandle.invokeExact(euler));
        outBits[base + 5] = Float.floatToRawIntBits((float) vecYHandle.invokeExact(euler));
        outBits[base + 6] = Float.floatToRawIntBits((float) vecZHandle.invokeExact(euler));
        outBits[base + 7] = Float.floatToRawIntBits((float) vecXHandle.invokeExact(scale));
        outBits[base + 8] = Float.floatToRawIntBits((float) vecYHandle.invokeExact(scale));
        outBits[base + 9] = Float.floatToRawIntBits((float) vecZHandle.invokeExact(scale));
        return true;
    }
}
