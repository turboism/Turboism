package dev.turboism.adapter.cubism.optimization.extinterpsibling;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code extendedInterpolation.l.a(ACParameterControllableSource)} to resolve parent
 * parts and siblings in constant time and elide forced rebuilds on unmodified siblings.
 */
public final class ExtendedInterpolationSiblingTransformer implements ClassFileTransformer {

    /** Number of rewritten target methods expected on {@code extendedInterpolation.l}. */
    public static final int EXPECTED_SITES = 1;

    private static final int LOCAL_TARGET = 1;
    private static final int LOCAL_MODEL_SOURCE = 2;
    private static final int LOCAL_PARENT_GUID = 3;
    private static final int LOCAL_PART_SOURCE = 4;
    private static final int LOCAL_CHILD_GUIDS = 5;
    private static final int LOCAL_CHILD_COUNT = 6;
    private static final int LOCAL_CHILD_INDEX = 7;
    private static final int LOCAL_CHILD_GUID = 8;
    private static final int LOCAL_SIBLING = 9;
    private static final int LOCAL_EXTENSIONS = 10;
    private static final int LOCAL_EXT_COUNT = 11;
    private static final int LOCAL_EXT_INDEX = 12;
    private static final int LOCAL_EXT_OBJECT = 13;
    private static final int LOCAL_EXTENSION = 14;
    private static final int LOCAL_THROWABLE = 15;

    private static final int EXPECTED_FORCED_CALLS = 1;

    private static final String LIST_OWNER = "java/util/List";
    private static final String LIST_SIZE_METHOD = "size";
    private static final String LIST_SIZE_DESCRIPTOR = "()I";
    private static final String LIST_GET_METHOD = "get";
    private static final String LIST_GET_DESCRIPTOR = "(I)Ljava/lang/Object;";
    private static final String THROWABLE_OWNER = "java/lang/Throwable";
    private static final String PRINT_STACK_TRACE_METHOD = "printStackTrace";
    private static final String VOID_DESCRIPTOR = "()V";

    private static final String ERROR_LOADER_OR_SOURCE = "unexpected loader or code source for ";
    private static final String ERROR_METHOD_SHAPE = "sibling extended-interpolation method shape mismatch";
    private static final String ERROR_ABSENT_REFERENCE = "sibling extended-interpolation entry absent from reference";
    private static final String ERROR_ANCHOR_PREFIX = "sibling extended-interpolation anchors absent: ";
    private static final String ERROR_SITE_COUNT = "sibling extended-interpolation site count mismatch: ";
    private static final String ANCHOR_STATIC = "target method is static";
    private static final String ANCHOR_PART_SET = "missing getPartSourceSet anchor";
    private static final String ANCHOR_ALL_OBJECTS = "missing getAllObjects anchor";
    private static final String ANCHOR_FORCED_OUTSIDE = "updateInterpolatedForms called outside target method";
    private static final String ANCHOR_FORCED_ARG = "updateInterpolatedForms not preceded by iconst_1";
    private static final String ANCHOR_FORCED_COUNT = "unexpected updateInterpolatedForms call count: ";
    private static final String ANCHOR_ALREADY_WOVEN = "target method already rewritten";

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> referenceShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures and verifies reviewed anchors for {@code extendedInterpolation.l}.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param referenceBytes reference bytes of {@code extendedInterpolation/l.class}
     * @param target reviewed target metadata
     */
    public ExtendedInterpolationSiblingTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] referenceBytes,
            final ExtendedInterpolationSiblingTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(referenceBytes, "referenceBytes");
        Objects.requireNonNull(target, "target");

        referenceShape = ReviewedMethodShape.read(
                referenceBytes,
                ExtendedInterpolationSiblingTarget.OWNER,
                ExtendedInterpolationSiblingTarget.TARGET_METHOD,
                ExtendedInterpolationSiblingTarget.TARGET_DESCRIPTOR);
        if (referenceShape == null) {
            throw new IllegalArgumentException(ERROR_ABSENT_REFERENCE);
        }
        final List<String> problems = verify(referenceBytes);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(ERROR_ANCHOR_PREFIX + problems);
        }
    }

    /**
     * Verifies native bytecode anchors on one {@code extendedInterpolation/l.class}.
     *
     * @param classFile class file bytes
     * @return anchor violations in discovery order; empty when patchable
     */
    public static List<String> verify(final byte[] classFile) {
        Objects.requireNonNull(classFile, "classFile");
        final List<String> problems = new ArrayList<>();
        final boolean[] sawPartSet = {false};
        final boolean[] sawAllObjects = {false};
        final boolean[] sawRewrittenLookup = {false};
        final int[] totalForcedCalls = {0};
        final int[] anchoredForcedCalls = {0};

        new ClassReader(classFile)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                final boolean targetMethod = ExtendedInterpolationSiblingTarget.TARGET_METHOD.equals(
                                                name)
                                        && ExtendedInterpolationSiblingTarget.TARGET_DESCRIPTOR.equals(descriptor);
                                if (targetMethod && (access & Opcodes.ACC_STATIC) != 0) {
                                    problems.add(ANCHOR_STATIC);
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private int lastOpcode = -1;

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        lastOpcode = opcode;
                                    }

                                    @Override
                                    public void visitIntInsn(final int opcode, final int operand) {
                                        lastOpcode = opcode;
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int var) {
                                        lastOpcode = opcode;
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String owner,
                                            final String fName,
                                            final String fDesc) {
                                        lastOpcode = opcode;
                                    }

                                    @Override
                                    public void visitJumpInsn(final int opcode, final Label label) {
                                        lastOpcode = opcode;
                                    }

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        lastOpcode = Opcodes.LDC;
                                    }

                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        lastOpcode = opcode;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String methodName,
                                            final String desc,
                                            final boolean isInterface) {
                                        if (targetMethod
                                                && ExtendedInterpolationSiblingTarget.MODEL_SOURCE_OWNER.equals(
                                                        owner)) {
                                            if (ExtendedInterpolationSiblingTarget.GET_PART_SOURCE_SET_METHOD.equals(
                                                            methodName)
                                                    && ExtendedInterpolationSiblingTarget.GET_PART_SOURCE_SET_DESCRIPTOR
                                                            .equals(desc)) {
                                                sawPartSet[0] = true;
                                            } else if (ExtendedInterpolationSiblingTarget.GET_ALL_OBJECTS_METHOD.equals(
                                                            methodName)
                                                    && ExtendedInterpolationSiblingTarget.LIST_GETTER_DESCRIPTOR.equals(
                                                            desc)) {
                                                sawAllObjects[0] = true;
                                            } else if (ExtendedInterpolationSiblingTarget.GET_PART_SOURCE_METHOD.equals(
                                                            methodName)
                                                    || ExtendedInterpolationSiblingTarget.GET_OBJECT_METHOD.equals(
                                                            methodName)) {
                                                sawRewrittenLookup[0] = true;
                                            }
                                        }
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && ExtendedInterpolationSiblingTarget.EXTENSION_OWNER.equals(owner)
                                                && ExtendedInterpolationSiblingTarget.UPDATE_FORMS_METHOD.equals(
                                                        methodName)
                                                && ExtendedInterpolationSiblingTarget.UPDATE_FORMS_DESCRIPTOR.equals(
                                                        desc)) {
                                            totalForcedCalls[0]++;
                                            if (!targetMethod) {
                                                problems.add(ANCHOR_FORCED_OUTSIDE);
                                            } else if (lastOpcode != Opcodes.ICONST_1) {
                                                problems.add(ANCHOR_FORCED_ARG);
                                            } else {
                                                anchoredForcedCalls[0]++;
                                            }
                                        }
                                        lastOpcode = opcode;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (sawRewrittenLookup[0]) {
            problems.add(ANCHOR_ALREADY_WOVEN);
        }
        if (!sawPartSet[0]) {
            problems.add(ANCHOR_PART_SET);
        }
        if (!sawAllObjects[0]) {
            problems.add(ANCHOR_ALL_OBJECTS);
        }
        if (totalForcedCalls[0] != EXPECTED_FORCED_CALLS || anchoredForcedCalls[0] != EXPECTED_FORCED_CALLS) {
            problems.add(ANCHOR_FORCED_COUNT + anchoredForcedCalls[0] + "/" + totalForcedCalls[0]);
        }
        return List.copyOf(problems);
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the number of rewritten target methods. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || !ExtendedInterpolationSiblingTarget.OWNER.equals(name)) {
            return null;
        }
        if (actualLoader != loader || !matchesArtifactSource(domain)) {
            failure = ERROR_LOADER_OR_SOURCE + name;
            return null;
        }
        if (!referenceShape.equals(ReviewedMethodShape.read(
                bytes,
                ExtendedInterpolationSiblingTarget.OWNER,
                ExtendedInterpolationSiblingTarget.TARGET_METHOD,
                ExtendedInterpolationSiblingTarget.TARGET_DESCRIPTOR))) {
            failure = ERROR_METHOD_SHAPE;
            return null;
        }
        final List<String> problems = verify(bytes);
        if (!problems.isEmpty()) {
            failure = ERROR_ANCHOR_PREFIX + problems;
            return null;
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override
                protected ClassLoader getClassLoader() {
                    return loader == null ? super.getClassLoader() : loader;
                }

                @Override
                protected String getCommonSuperClass(final String left, final String right) {
                    return HostClassWriters.commonSuperClass(loader, left, right);
                }
            };
            final int[] localSites = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor delegate =
                                    super.visitMethod(access, methodName, descriptor, signature, exceptions);
                            if (!ExtendedInterpolationSiblingTarget.TARGET_METHOD.equals(methodName)
                                    || !ExtendedInterpolationSiblingTarget.TARGET_DESCRIPTOR.equals(descriptor)) {
                                return delegate;
                            }
                            emitFastSiblingUpdate(delegate);
                            localSites[0]++;
                            return null;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (localSites[0] != EXPECTED_SITES) {
                failure = ERROR_SITE_COUNT + localSites[0];
                return null;
            }
            final byte[] output = writer.toByteArray();
            matches++;
            sites += localSites[0];
            return output;
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private boolean matchesArtifactSource(final ProtectionDomain domain) {
        if (domain == null
                || domain.getCodeSource() == null
                || domain.getCodeSource().getLocation() == null) {
            return false;
        }
        try {
            return Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize()
                    .equals(artifact);
        } catch (final Exception ignored) {
            return false;
        }
    }

    private static void emitFastSiblingUpdate(final MethodVisitor mv) {
        final Label tryStart = new Label();
        final Label tryEnd = new Label();
        final Label catchHandler = new Label();
        final Label returnLabel = new Label();
        final Label loopChildren = new Label();
        final Label nextChild = new Label();
        final Label loopExtensions = new Label();
        final Label nextExtension = new Label();
        final Label pushFalse = new Label();
        final Label callUpdate = new Label();

        mv.visitCode();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, THROWABLE_OWNER);
        mv.visitLabel(tryStart);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_TARGET);
        mv.visitJumpInsn(Opcodes.IFNULL, returnLabel);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_TARGET);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.CONTROLLABLE_SOURCE_OWNER,
                ExtendedInterpolationSiblingTarget.GET_MODEL_SOURCE_METHOD,
                ExtendedInterpolationSiblingTarget.GET_MODEL_SOURCE_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_MODEL_SOURCE);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_MODEL_SOURCE);
        mv.visitJumpInsn(Opcodes.IFNULL, returnLabel);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_TARGET);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.CONTROLLABLE_SOURCE_OWNER,
                ExtendedInterpolationSiblingTarget.GET_PARENT_GUID_METHOD,
                ExtendedInterpolationSiblingTarget.GET_PARENT_GUID_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_PARENT_GUID);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_PARENT_GUID);
        mv.visitJumpInsn(Opcodes.IFNULL, returnLabel);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_MODEL_SOURCE);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_PARENT_GUID);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.MODEL_SOURCE_OWNER,
                ExtendedInterpolationSiblingTarget.GET_PART_SOURCE_METHOD,
                ExtendedInterpolationSiblingTarget.GET_PART_SOURCE_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_PART_SOURCE);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_PART_SOURCE);
        mv.visitJumpInsn(Opcodes.IFNULL, returnLabel);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_PART_SOURCE);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.PART_SOURCE_OWNER,
                ExtendedInterpolationSiblingTarget.GET_CHILD_GUIDS_METHOD,
                ExtendedInterpolationSiblingTarget.LIST_GETTER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_CHILD_GUIDS);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CHILD_GUIDS);
        mv.visitJumpInsn(Opcodes.IFNULL, returnLabel);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CHILD_GUIDS);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_CHILD_COUNT);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_CHILD_INDEX);

        mv.visitLabel(loopChildren);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CHILD_INDEX);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CHILD_COUNT);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, tryEnd);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CHILD_GUIDS);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_CHILD_INDEX);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_CHILD_GUID);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CHILD_GUID);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, ExtendedInterpolationSiblingTarget.GUID_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, nextChild);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_MODEL_SOURCE);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_CHILD_GUID);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ExtendedInterpolationSiblingTarget.GUID_OWNER);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.MODEL_SOURCE_OWNER,
                ExtendedInterpolationSiblingTarget.GET_OBJECT_METHOD,
                ExtendedInterpolationSiblingTarget.GET_OBJECT_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_SIBLING);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_SIBLING);
        mv.visitJumpInsn(Opcodes.IFNULL, nextChild);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_SIBLING);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.CONTROLLABLE_SOURCE_OWNER,
                ExtendedInterpolationSiblingTarget.GET_EXTENSIONS_METHOD,
                ExtendedInterpolationSiblingTarget.LIST_GETTER_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_EXTENSIONS);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXTENSIONS);
        mv.visitJumpInsn(Opcodes.IFNULL, nextChild);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXTENSIONS);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_EXT_COUNT);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, LOCAL_EXT_INDEX);

        mv.visitLabel(loopExtensions);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_EXT_INDEX);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_EXT_COUNT);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, nextChild);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXTENSIONS);
        mv.visitVarInsn(Opcodes.ILOAD, LOCAL_EXT_INDEX);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_EXT_OBJECT);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXT_OBJECT);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, ExtendedInterpolationSiblingTarget.EXTENSION_OWNER);
        mv.visitJumpInsn(Opcodes.IFEQ, nextExtension);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXT_OBJECT);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ExtendedInterpolationSiblingTarget.EXTENSION_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_EXTENSION);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXTENSION);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.EXTENSION_OWNER,
                ExtendedInterpolationSiblingTarget.IS_ENABLED_METHOD,
                ExtendedInterpolationSiblingTarget.BOOLEAN_GETTER_DESCRIPTOR,
                false);
        // CExtendedInterpolationExtension is single-per-object; skip remaining entries once found.
        mv.visitJumpInsn(Opcodes.IFEQ, nextChild);

        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_EXTENSION);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_SIBLING);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_TARGET);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, pushFalse);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, callUpdate);

        mv.visitLabel(pushFalse);
        mv.visitInsn(Opcodes.ICONST_0);

        mv.visitLabel(callUpdate);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                ExtendedInterpolationSiblingTarget.EXTENSION_OWNER,
                ExtendedInterpolationSiblingTarget.UPDATE_FORMS_METHOD,
                ExtendedInterpolationSiblingTarget.UPDATE_FORMS_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, nextChild);

        mv.visitLabel(nextExtension);
        mv.visitIincInsn(LOCAL_EXT_INDEX, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopExtensions);

        mv.visitLabel(nextChild);
        mv.visitIincInsn(LOCAL_CHILD_INDEX, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopChildren);

        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, returnLabel);

        mv.visitLabel(catchHandler);
        mv.visitVarInsn(Opcodes.ASTORE, LOCAL_THROWABLE);
        mv.visitVarInsn(Opcodes.ALOAD, LOCAL_THROWABLE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THROWABLE_OWNER, PRINT_STACK_TRACE_METHOD, VOID_DESCRIPTOR, false);

        mv.visitLabel(returnLabel);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
