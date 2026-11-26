package dev.turboism.adapter.cubism.optimization.renderpass;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Properties;
import java.util.function.BiFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Publishes pre-built debug draw labels for {@code rendering/e.b} and {@code rendering/d.b}, plus
 * a canonical uniform-key resolver eliminating repeated {@code kotlin.Pair} allocations in
 * {@code GMaterial} on Cubism 5.3.x.
 */
public final class RenderPassTraversalBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.renderPassTraversal";

    /** Property key storing the immutable pre-draw label array ({@code String[]}). */
    public static final String PRE_DRAW_LABELS_PROPERTY = "turboism.render-pass.pre-draw-labels";

    /** Property key storing the immutable post-draw label array ({@code String[]}). */
    public static final String POST_DRAW_LABELS_PROPERTY = "turboism.render-pass.post-draw-labels";

    /** Property key storing the canonical {@code BiFunction<Object, Object, Object>} uniform-key resolver. */
    public static final String UNIFORM_PAIR_PROPERTY = "turboism.render-pass.uniform-pair-resolver";

    /** Maximum pre-cached mesh draw index covered without heap concatenation. */
    public static final int LABEL_CACHE_SIZE = 4096;

    private static final String PRE_DRAW_PREFIX = "pre draw[";
    private static final String POST_DRAW_PREFIX = "post draw[";
    private static final String LABEL_SUFFIX = "]";
    private static final String BASE_COLOR_UNIFORM = "baseColor";
    private static final String MULTIPLY_COLOR_UNIFORM = "multiplyColor";
    private static final String SCREEN_COLOR_UNIFORM = "screenColor";

    private static final int UNIFORM_PAIR_CACHE_SIZE = 256;
    private static final int UNIFORM_PAIR_CACHE_MASK = UNIFORM_PAIR_CACHE_SIZE - 1;
    private static final int HASH_MULTIPLIER = 31;
    private static final int HASH_SHIFT = 16;

    private static final String OBJECT_OWNER = "java/lang/Object";
    private static final String OBJECTS_OWNER = "java/util/Objects";
    private static final String OBJECTS_EQUALS = "equals";
    private static final String OBJECTS_EQUALS_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String STRING_OWNER = "java/lang/String";
    private static final String STRING_EQUALS = "equals";
    private static final String STRING_EQUALS_DESC = "(Ljava/lang/Object;)Z";
    private static final String ATOMIC_REF_ARRAY_OWNER = "java/util/concurrent/atomic/AtomicReferenceArray";
    private static final String ATOMIC_REF_ARRAY_DESC = "L" + ATOMIC_REF_ARRAY_OWNER + ";";
    private static final String ATOMIC_REF_ARRAY_INIT_DESC = "(I)V";
    private static final String ATOMIC_REF_ARRAY_GET = "get";
    private static final String ATOMIC_REF_ARRAY_GET_DESC = "(I)Ljava/lang/Object;";
    private static final String ATOMIC_REF_ARRAY_LAZY_SET = "lazySet";
    private static final String ATOMIC_REF_ARRAY_LAZY_SET_DESC = "(ILjava/lang/Object;)V";
    private static final String BI_FUNCTION_OWNER = "java/util/function/BiFunction";
    private static final String BI_FUNCTION_APPLY = "apply";
    private static final String BI_FUNCTION_APPLY_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String PAIR_DOT = "kotlin.Pair";
    private static final String PAIR_OWNER = "kotlin/Pair";
    private static final String PAIR_DESC = "L" + PAIR_OWNER + ";";
    private static final String PAIR_INIT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String PAIR_GET_FIRST = "getFirst";
    private static final String PAIR_GET_SECOND = "getSecond";
    private static final String VOID_OBJECT_DESC = "()Ljava/lang/Object;";
    private static final String HASH_CODE_METHOD = "hashCode";
    private static final String HASH_CODE_DESC = "()I";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String VOID_DESC = "()V";
    private static final String ACCESSOR_BINARY =
            "dev/turboism/adapter/cubism/optimization/renderpass/UniformPairCacheAccessor";
    private static final String FIELD_BASE_COLOR = "baseColorPair";
    private static final String FIELD_MULTIPLY_COLOR = "multiplyColorPair";
    private static final String FIELD_SCREEN_COLOR = "screenColorPair";
    private static final String FIELD_CACHE = "cache";

    private static final String[] PRE_DRAW_LABELS = buildLabels(PRE_DRAW_PREFIX);
    private static final String[] POST_DRAW_LABELS = buildLabels(POST_DRAW_PREFIX);

    private final BiFunction<Object, Object, Object> uniformPairResolver;
    private Properties installedProperties;
    private Object previousPreLabels;
    private Object previousPostLabels;
    private Object previousPairResolver;
    private boolean installed;

    /** Creates a label-table bridge without a host loader. */
    public RenderPassTraversalBridge() {
        this(null);
    }

    /**
     * Creates a render-pass bridge with an optional host class loader for canonical uniform keys.
     *
     * @param hostLoader admitted host class loader, or {@code null} when uniform-key caching is unneeded
     */
    public RenderPassTraversalBridge(final ClassLoader hostLoader) {
        this.uniformPairResolver = compileUniformPairResolver(hostLoader);
    }

    /**
     * Installs pre-built label tables and optional uniform-key resolver into {@link System#getProperties()}.
     */
    public synchronized void install() {
        if (installed) {
            return;
        }
        final Properties properties = System.getProperties();
        previousPreLabels = properties.put(PRE_DRAW_LABELS_PROPERTY, PRE_DRAW_LABELS);
        previousPostLabels = properties.put(POST_DRAW_LABELS_PROPERTY, POST_DRAW_LABELS);
        if (uniformPairResolver != null) {
            previousPairResolver = properties.put(UNIFORM_PAIR_PROPERTY, uniformPairResolver);
        }
        installedProperties = properties;
        installed = true;
    }

    @Override
    public synchronized void close() {
        if (!installed) {
            return;
        }
        final Properties properties = installedProperties;
        if (properties != null) {
            restoreOrRemove(properties, PRE_DRAW_LABELS_PROPERTY, PRE_DRAW_LABELS, previousPreLabels);
            restoreOrRemove(properties, POST_DRAW_LABELS_PROPERTY, POST_DRAW_LABELS, previousPostLabels);
            if (uniformPairResolver != null) {
                restoreOrRemove(properties, UNIFORM_PAIR_PROPERTY, uniformPairResolver, previousPairResolver);
            }
        }
        installedProperties = null;
        previousPreLabels = null;
        previousPostLabels = null;
        previousPairResolver = null;
        installed = false;
    }

    /**
     * Returns whether a canonical uniform-key resolver compiled against the host loader.
     *
     * @return true when {@link #UNIFORM_PAIR_PROPERTY} publishes a compiled resolver
     */
    synchronized boolean hasUniformPairResolver() {
        return uniformPairResolver != null;
    }

    /**
     * Returns whether the pre-draw and post-draw label tables are currently published.
     *
     * @return true when installed and not yet closed
     */
    public synchronized boolean active() {
        return installed;
    }

    /**
     * Returns the cached pre-draw label for {@code index}, or formats it when outside the table.
     *
     * @param index draw call index
     * @return exact {@code "pre draw[<index>]"} label
     */
    public static String preDrawLabel(final int index) {
        return (index >= 0 && index < PRE_DRAW_LABELS.length)
                ? PRE_DRAW_LABELS[index]
                : PRE_DRAW_PREFIX + index + LABEL_SUFFIX;
    }

    /**
     * Returns the cached post-draw label for {@code index}, or formats it when outside the table.
     *
     * @param index draw call index
     * @return exact {@code "post draw[<index>]"} label
     */
    public static String postDrawLabel(final int index) {
        return (index >= 0 && index < POST_DRAW_LABELS.length)
                ? POST_DRAW_LABELS[index]
                : POST_DRAW_PREFIX + index + LABEL_SUFFIX;
    }

    private static String[] buildLabels(final String prefix) {
        final String[] labels = new String[LABEL_CACHE_SIZE];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = prefix + i + LABEL_SUFFIX;
        }
        return labels;
    }

    private static void restoreOrRemove(
            final Properties properties, final String key, final Object expectedValue, final Object previousValue) {
        final Object current = properties.get(key);
        if (current != expectedValue) {
            return;
        }
        if (previousValue == null) {
            properties.remove(key);
        } else {
            properties.put(key, Objects.requireNonNull(previousValue));
        }
    }

    @SuppressWarnings("unchecked")
    private static BiFunction<Object, Object, Object> compileUniformPairResolver(final ClassLoader hostLoader) {
        if (hostLoader == null) {
            return null;
        }
        try {
            final Class<?> pairClass = Class.forName(PAIR_DOT, false, hostLoader);
            if (!Modifier.isPublic(pairClass.getModifiers())) {
                return null;
            }
            final Constructor<?> ctor = pairClass.getConstructor(Object.class, Object.class);
            final Method getFirst = pairClass.getMethod(PAIR_GET_FIRST);
            final Method getSecond = pairClass.getMethod(PAIR_GET_SECOND);
            if (!Modifier.isPublic(ctor.getModifiers())
                    || !Modifier.isPublic(getFirst.getModifiers())
                    || !Modifier.isPublic(getSecond.getModifiers())
                    || getFirst.getReturnType() != Object.class
                    || getSecond.getReturnType() != Object.class) {
                return null;
            }
            final AccessorClassLoader accessorLoader = new AccessorClassLoader(hostLoader);
            final Class<?> accessorClass =
                    accessorLoader.define(ACCESSOR_BINARY.replace('/', '.'), buildAccessorClass(hostLoader));
            return (BiFunction<Object, Object, Object>)
                    accessorClass.getConstructor().newInstance();
        } catch (final Throwable unsupported) {
            FatalErrors.rethrowIfFatal(unsupported);
            return null;
        }
    }

    private static byte[] buildAccessorClass(final ClassLoader loader) {
        final ClassWriter writer = HostClassWriters.newFrameWriter(loader);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                ACCESSOR_BINARY,
                null,
                OBJECT_OWNER,
                new String[] {BI_FUNCTION_OWNER});
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, FIELD_BASE_COLOR, PAIR_DESC, null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, FIELD_MULTIPLY_COLOR, PAIR_DESC, null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, FIELD_SCREEN_COLOR, PAIR_DESC, null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, FIELD_CACHE, ATOMIC_REF_ARRAY_DESC, null, null)
                .visitEnd();

        emitInitMethod(writer);
        emitApplyMethod(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitInitMethod(final ClassWriter writer) {
        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESC, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT_OWNER, CONSTRUCTOR_NAME, VOID_DESC, false);

        emitStoreConstantNullKeyPair(init, BASE_COLOR_UNIFORM, FIELD_BASE_COLOR);
        emitStoreConstantNullKeyPair(init, MULTIPLY_COLOR_UNIFORM, FIELD_MULTIPLY_COLOR);
        emitStoreConstantNullKeyPair(init, SCREEN_COLOR_UNIFORM, FIELD_SCREEN_COLOR);

        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, ATOMIC_REF_ARRAY_OWNER);
        init.visitInsn(Opcodes.DUP);
        init.visitIntInsn(Opcodes.SIPUSH, UNIFORM_PAIR_CACHE_SIZE);
        init.visitMethodInsn(
                Opcodes.INVOKESPECIAL, ATOMIC_REF_ARRAY_OWNER, CONSTRUCTOR_NAME, ATOMIC_REF_ARRAY_INIT_DESC, false);
        init.visitFieldInsn(Opcodes.PUTFIELD, ACCESSOR_BINARY, FIELD_CACHE, ATOMIC_REF_ARRAY_DESC);

        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static void emitStoreConstantNullKeyPair(
            final MethodVisitor init, final String uniformName, final String fieldName) {
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, PAIR_OWNER);
        init.visitInsn(Opcodes.DUP);
        init.visitLdcInsn(uniformName);
        init.visitInsn(Opcodes.ACONST_NULL);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, PAIR_OWNER, CONSTRUCTOR_NAME, PAIR_INIT_DESC, false);
        init.visitFieldInsn(Opcodes.PUTFIELD, ACCESSOR_BINARY, fieldName, PAIR_DESC);
    }

    private static void emitApplyMethod(final ClassWriter writer) {
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, BI_FUNCTION_APPLY, BI_FUNCTION_APPLY_DESC, null, null);
        mv.visitCode();

        final Label generalLookup = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, generalLookup);

        emitNullIdFastMatch(mv, BASE_COLOR_UNIFORM, FIELD_BASE_COLOR);
        emitNullIdFastMatch(mv, MULTIPLY_COLOR_UNIFORM, FIELD_MULTIPLY_COLOR);
        emitNullIdFastMatch(mv, SCREEN_COLOR_UNIFORM, FIELD_SCREEN_COLOR);

        mv.visitLabel(generalLookup);
        emitHashComponent(mv, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, HASH_MULTIPLIER);
        mv.visitInsn(Opcodes.IMUL);
        emitHashComponent(mv, 2);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, HASH_SHIFT);
        mv.visitInsn(Opcodes.IUSHR);
        mv.visitInsn(Opcodes.IXOR);
        mv.visitIntInsn(Opcodes.SIPUSH, UNIFORM_PAIR_CACHE_MASK);
        mv.visitInsn(Opcodes.IAND);
        mv.visitVarInsn(Opcodes.ISTORE, 3);

        final Label cacheMiss = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ACCESSOR_BINARY, FIELD_CACHE, ATOMIC_REF_ARRAY_DESC);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, ATOMIC_REF_ARRAY_OWNER, ATOMIC_REF_ARRAY_GET, ATOMIC_REF_ARRAY_GET_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, cacheMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PAIR_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        emitPairComponentEquals(mv, PAIR_GET_FIRST, 1, cacheMiss);
        emitPairComponentEquals(mv, PAIR_GET_SECOND, 2, cacheMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(cacheMiss);
        mv.visitTypeInsn(Opcodes.NEW, PAIR_OWNER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PAIR_OWNER, CONSTRUCTOR_NAME, PAIR_INIT_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ACCESSOR_BINARY, FIELD_CACHE, ATOMIC_REF_ARRAY_DESC);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ATOMIC_REF_ARRAY_OWNER,
                ATOMIC_REF_ARRAY_LAZY_SET,
                ATOMIC_REF_ARRAY_LAZY_SET_DESC,
                false);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitNullIdFastMatch(final MethodVisitor mv, final String literal, final String fieldName) {
        final Label next = new Label();
        mv.visitLdcInsn(literal);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STRING_OWNER, STRING_EQUALS, STRING_EQUALS_DESC, false);
        mv.visitJumpInsn(Opcodes.IFEQ, next);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ACCESSOR_BINARY, fieldName, PAIR_DESC);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(next);
    }

    private static void emitHashComponent(final MethodVisitor mv, final int localIndex) {
        final Label isNull = new Label();
        final Label done = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, localIndex);
        mv.visitJumpInsn(Opcodes.IFNULL, isNull);
        mv.visitVarInsn(Opcodes.ALOAD, localIndex);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OBJECT_OWNER, HASH_CODE_METHOD, HASH_CODE_DESC, false);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(isNull);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(done);
    }

    private static void emitPairComponentEquals(
            final MethodVisitor mv, final String getter, final int keyLocal, final Label miss) {
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PAIR_OWNER, getter, VOID_OBJECT_DESC, false);
        mv.visitVarInsn(Opcodes.ALOAD, keyLocal);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OBJECTS_OWNER, OBJECTS_EQUALS, OBJECTS_EQUALS_DESC, false);
        mv.visitJumpInsn(Opcodes.IFEQ, miss);
    }

    private static final class AccessorClassLoader extends ClassLoader {
        AccessorClassLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String binaryName, final byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
