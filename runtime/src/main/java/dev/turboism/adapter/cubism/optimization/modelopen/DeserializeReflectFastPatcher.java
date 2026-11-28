package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.INSTANCEOF;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Rewrites Cubism's {@code PropertySet.readProperty} and {@code ClassSerializer.createInstance}
 * to route hot property assignment and object instantiation through
 * {@link DeserializeReflectFastBridge}.
 */
final class DeserializeReflectFastPatcher {

    static final String PROPERTY_SET_INTERNAL_NAME = "com/live2d/serialize/impl/PropertySet";
    static final String CLASS_SERIALIZER_INTERNAL_NAME = "com/live2d/serialize/impl/ClassSerializer";

    private static final String BRIDGE_OWNER =
            "dev/turboism/adapter/cubism/optimization/modelopen/DeserializeReflectFastBridge";
    private static final String TRY_SET_MUTABLE_NAME = "trySetMutableProperty";
    private static final String TRY_SET_MUTABLE_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String TRY_SET_FIELD_NAME = "trySetBackingField";
    private static final String TRY_SET_FIELD_DESC =
            "(Ljava/lang/Object;Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String NEW_INSTANCE_NAME = "newInstance";
    private static final String BRIDGE_NEW_INSTANCE_DESC = "(Ljava/lang/Class;)Ljava/lang/Object;";

    private static final String READ_PROPERTY_NAME = "readProperty";
    private static final String READ_PROPERTY_DESC =
            "(Lorg/jdom/Element;Ljava/lang/Object;Lcom/live2d/serialize/XmlReader;)V";
    private static final String CREATE_INSTANCE_NAME = "createInstance";
    private static final String CREATE_INSTANCE_DESC =
            "(Lorg/jdom/Element;Lcom/live2d/serialize/XmlReader;)Ljava/lang/Object;";

    private static final String FIELD_PROPERTY = "property";
    private static final String FIELD_PROPERTY_DESC = "Lkotlin/reflect/KProperty1;";
    private static final String FIELD_BACKING_FIELD = "backingField";
    private static final String FIELD_BACKING_FIELD_DESC = "Ljava/lang/reflect/Field;";

    private static final String INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics";
    private static final String CHECK_NOT_NULL_PARAM_NAME = "checkNotNullParameter";
    private static final String CHECK_NOT_NULL_PARAM_DESC = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String EMPTY_PARAM_LABEL = "";

    private static final String XML_READER_OWNER = "com/live2d/serialize/XmlReader";
    private static final String CREATE_OBJECT_FROM_ELEMENT_NAME = "createObjectFromElement";
    private static final String CREATE_OBJECT_FROM_ELEMENT_DESC = "(Lorg/jdom/Element;)Ljava/lang/Object;";

    private static final String KMUTABLE_PROPERTY1_OWNER = "kotlin/reflect/KMutableProperty1";
    private static final String KMUTABLE_SETTER_OWNER = "kotlin/reflect/KMutableProperty1$Setter";
    private static final String GET_SETTER_NAME = "getSetter";
    private static final String GET_SETTER_DESC = "()Lkotlin/reflect/KMutableProperty1$Setter;";
    private static final String CALL_NAME = "call";
    private static final String CALL_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    private static final String KCALLABLE_OWNER = "kotlin/reflect/KCallable";
    private static final String KCALLABLES_JVM_OWNER = "kotlin/reflect/jvm/KCallablesJvm";
    private static final String IS_ACCESSIBLE_NAME = "isAccessible";
    private static final String KCALLABLE_IS_ACCESSIBLE_DESC = "(Lkotlin/reflect/KCallable;)Z";
    private static final String SET_ACCESSIBLE_NAME = "setAccessible";
    private static final String KCALLABLE_SET_ACCESSIBLE_DESC = "(Lkotlin/reflect/KCallable;Z)V";

    private static final String FIELD_OWNER = "java/lang/reflect/Field";
    private static final String FIELD_IS_ACCESSIBLE_DESC = "()Z";
    private static final String FIELD_SET_ACCESSIBLE_DESC = "(Z)V";
    private static final String FIELD_SET_NAME = "set";
    private static final String FIELD_SET_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    private static final String CLASS_OWNER = "java/lang/Class";
    private static final String CLASS_NEW_INSTANCE_DESC = "()Ljava/lang/Object;";
    private static final String OBJECT_OWNER = "java/lang/Object";
    private static final String THROWABLE_OWNER = "java/lang/Throwable";

    private static final int LOCAL_THIS = 0;
    private static final int LOCAL_ELEMENT = 1;
    private static final int LOCAL_TARGET = 2;
    private static final int LOCAL_READER = 3;
    private static final int LOCAL_MEMBER = 4;
    private static final int LOCAL_VALUE = 5;

    private DeserializeReflectFastPatcher() {}

    /**
     * Returns {@code true} when {@code internalName} is a target handled by this patcher.
     *
     * @param internalName JVM internal class name
     * @return {@code true} for {@code PropertySet} or {@code ClassSerializer}
     */
    static boolean isSupportedTarget(final String internalName) {
        return PROPERTY_SET_INTERNAL_NAME.equals(internalName) || CLASS_SERIALIZER_INTERNAL_NAME.equals(internalName);
    }

    /**
     * Patches {@code PropertySet.readProperty} or {@code ClassSerializer.createInstance}.
     *
     * @param internalName JVM internal name of the class under transformation
     * @param loader class loader defining the target class
     * @param classfileBuffer original class bytecode
     * @return patched bytecode, or {@code null} when unsupported, already patched, or mismatched
     */
    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (classfileBuffer == null) {
            return null;
        }
        if (PROPERTY_SET_INTERNAL_NAME.equals(internalName)) {
            return patchPropertySet(loader, classfileBuffer);
        }
        if (CLASS_SERIALIZER_INTERNAL_NAME.equals(internalName)) {
            return patchClassSerializer(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchPropertySet(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        final PropertySetScan scan = new PropertySetScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (READ_PROPERTY_NAME.equals(name) && READ_PROPERTY_DESC.equals(desc)) {
                            patched[0] = true;
                            emitFastReadProperty(mv);
                            return null;
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchClassSerializer(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassSerializerScan scan = new ClassSerializerScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.hasClassNewInstance) {
            return null;
        }
        final ClassWriter writer = ModelOpenClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!CREATE_INSTANCE_NAME.equals(name) || !CREATE_INSTANCE_DESC.equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String methodName,
                                    final String methodDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKEVIRTUAL
                                        && CLASS_OWNER.equals(owner)
                                        && NEW_INSTANCE_NAME.equals(methodName)
                                        && CLASS_NEW_INSTANCE_DESC.equals(methodDesc)) {
                                    patched[0] = true;
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            BRIDGE_OWNER,
                                            NEW_INSTANCE_NAME,
                                            BRIDGE_NEW_INSTANCE_DESC,
                                            false);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static void emitFastReadProperty(final MethodVisitor mv) {
        final Label checkBackingField = new Label();
        final Label tryStartMutable = new Label();
        final Label tryEndMutable = new Label();
        final Label catchMutable = new Label();
        final Label invokeMutableSetter = new Label();
        final Label tryStartField = new Label();
        final Label tryEndField = new Label();
        final Label catchField = new Label();
        final Label invokeFieldSet = new Label();
        final Label done = new Label();

        mv.visitCode();
        mv.visitTryCatchBlock(tryStartMutable, tryEndMutable, catchMutable, THROWABLE_OWNER);
        mv.visitTryCatchBlock(tryStartField, tryEndField, catchField, THROWABLE_OWNER);

        emitCheckNotNullParam(mv, LOCAL_ELEMENT);
        emitCheckNotNullParam(mv, LOCAL_TARGET);
        emitCheckNotNullParam(mv, LOCAL_READER);

        mv.visitVarInsn(ALOAD, LOCAL_THIS);
        mv.visitFieldInsn(GETFIELD, PROPERTY_SET_INTERNAL_NAME, FIELD_PROPERTY, FIELD_PROPERTY_DESC);
        mv.visitVarInsn(ASTORE, LOCAL_MEMBER);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitTypeInsn(INSTANCEOF, KMUTABLE_PROPERTY1_OWNER);
        mv.visitJumpInsn(IFEQ, checkBackingField);

        mv.visitVarInsn(ALOAD, LOCAL_READER);
        mv.visitVarInsn(ALOAD, LOCAL_ELEMENT);
        mv.visitMethodInsn(
                INVOKEVIRTUAL,
                XML_READER_OWNER,
                CREATE_OBJECT_FROM_ELEMENT_NAME,
                CREATE_OBJECT_FROM_ELEMENT_DESC,
                false);
        mv.visitVarInsn(ASTORE, LOCAL_VALUE);

        mv.visitVarInsn(ALOAD, LOCAL_THIS);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitVarInsn(ALOAD, LOCAL_THIS);
        mv.visitFieldInsn(GETFIELD, PROPERTY_SET_INTERNAL_NAME, FIELD_BACKING_FIELD, FIELD_BACKING_FIELD_DESC);
        mv.visitVarInsn(ALOAD, LOCAL_TARGET);
        mv.visitVarInsn(ALOAD, LOCAL_VALUE);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, TRY_SET_MUTABLE_NAME, TRY_SET_MUTABLE_DESC, false);
        mv.visitJumpInsn(IFNE, done);

        mv.visitLabel(tryStartMutable);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitTypeInsn(CHECKCAST, KCALLABLE_OWNER);
        mv.visitMethodInsn(INVOKESTATIC, KCALLABLES_JVM_OWNER, IS_ACCESSIBLE_NAME, KCALLABLE_IS_ACCESSIBLE_DESC, false);
        mv.visitJumpInsn(IFNE, invokeMutableSetter);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitTypeInsn(CHECKCAST, KCALLABLE_OWNER);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(
                INVOKESTATIC, KCALLABLES_JVM_OWNER, SET_ACCESSIBLE_NAME, KCALLABLE_SET_ACCESSIBLE_DESC, false);
        mv.visitLabel(tryEndMutable);
        mv.visitJumpInsn(GOTO, invokeMutableSetter);

        mv.visitLabel(catchMutable);
        mv.visitInsn(POP);

        mv.visitLabel(invokeMutableSetter);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitTypeInsn(CHECKCAST, KMUTABLE_PROPERTY1_OWNER);
        mv.visitMethodInsn(INVOKEINTERFACE, KMUTABLE_PROPERTY1_OWNER, GET_SETTER_NAME, GET_SETTER_DESC, true);
        mv.visitInsn(ICONST_2);
        mv.visitTypeInsn(ANEWARRAY, OBJECT_OWNER);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, LOCAL_TARGET);
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, LOCAL_VALUE);
        mv.visitInsn(AASTORE);
        mv.visitMethodInsn(INVOKEINTERFACE, KMUTABLE_SETTER_OWNER, CALL_NAME, CALL_DESC, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);

        mv.visitLabel(checkBackingField);
        mv.visitVarInsn(ALOAD, LOCAL_THIS);
        mv.visitFieldInsn(GETFIELD, PROPERTY_SET_INTERNAL_NAME, FIELD_BACKING_FIELD, FIELD_BACKING_FIELD_DESC);
        mv.visitVarInsn(ASTORE, LOCAL_MEMBER);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitJumpInsn(IFNULL, done);

        mv.visitVarInsn(ALOAD, LOCAL_READER);
        mv.visitVarInsn(ALOAD, LOCAL_ELEMENT);
        mv.visitMethodInsn(
                INVOKEVIRTUAL,
                XML_READER_OWNER,
                CREATE_OBJECT_FROM_ELEMENT_NAME,
                CREATE_OBJECT_FROM_ELEMENT_DESC,
                false);
        mv.visitVarInsn(ASTORE, LOCAL_VALUE);

        mv.visitVarInsn(ALOAD, LOCAL_THIS);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitVarInsn(ALOAD, LOCAL_TARGET);
        mv.visitVarInsn(ALOAD, LOCAL_VALUE);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE_OWNER, TRY_SET_FIELD_NAME, TRY_SET_FIELD_DESC, false);
        mv.visitJumpInsn(IFNE, done);

        mv.visitLabel(tryStartField);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitMethodInsn(INVOKEVIRTUAL, FIELD_OWNER, IS_ACCESSIBLE_NAME, FIELD_IS_ACCESSIBLE_DESC, false);
        mv.visitJumpInsn(IFNE, invokeFieldSet);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEVIRTUAL, FIELD_OWNER, SET_ACCESSIBLE_NAME, FIELD_SET_ACCESSIBLE_DESC, false);
        mv.visitLabel(tryEndField);
        mv.visitJumpInsn(GOTO, invokeFieldSet);

        mv.visitLabel(catchField);
        mv.visitInsn(POP);

        mv.visitLabel(invokeFieldSet);
        mv.visitVarInsn(ALOAD, LOCAL_MEMBER);
        mv.visitVarInsn(ALOAD, LOCAL_TARGET);
        mv.visitVarInsn(ALOAD, LOCAL_VALUE);
        mv.visitMethodInsn(INVOKEVIRTUAL, FIELD_OWNER, FIELD_SET_NAME, FIELD_SET_DESC, false);

        mv.visitLabel(done);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitCheckNotNullParam(final MethodVisitor mv, final int localIndex) {
        mv.visitVarInsn(ALOAD, localIndex);
        mv.visitLdcInsn(EMPTY_PARAM_LABEL);
        mv.visitMethodInsn(INVOKESTATIC, INTRINSICS_OWNER, CHECK_NOT_NULL_PARAM_NAME, CHECK_NOT_NULL_PARAM_DESC, false);
    }

    private static final class PropertySetScan extends ClassVisitor {

        private boolean hasPropertyField;
        private boolean hasBackingField;
        private boolean hasReadProperty;
        private boolean callsCreateObjectFromElement;
        private boolean callsMutableSetterCall;
        private boolean callsFieldSet;
        private boolean alreadyPatched;

        PropertySetScan() {
            super(ASM9);
        }

        boolean matches() {
            return hasPropertyField
                    && hasBackingField
                    && hasReadProperty
                    && callsCreateObjectFromElement
                    && callsMutableSetterCall
                    && callsFieldSet;
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String signature, final Object value) {
            if (FIELD_PROPERTY.equals(name) && FIELD_PROPERTY_DESC.equals(desc)) {
                hasPropertyField = true;
            } else if (FIELD_BACKING_FIELD.equals(name) && FIELD_BACKING_FIELD_DESC.equals(desc)) {
                hasBackingField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            if (!READ_PROPERTY_NAME.equals(name) || !READ_PROPERTY_DESC.equals(desc)) {
                return null;
            }
            hasReadProperty = true;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDesc,
                        final boolean isInterface) {
                    if (BRIDGE_OWNER.equals(owner)) {
                        alreadyPatched = true;
                    } else if (opcode == INVOKEVIRTUAL
                            && XML_READER_OWNER.equals(owner)
                            && CREATE_OBJECT_FROM_ELEMENT_NAME.equals(methodName)
                            && CREATE_OBJECT_FROM_ELEMENT_DESC.equals(methodDesc)) {
                        callsCreateObjectFromElement = true;
                    } else if (opcode == INVOKEINTERFACE
                            && KMUTABLE_SETTER_OWNER.equals(owner)
                            && CALL_NAME.equals(methodName)
                            && CALL_DESC.equals(methodDesc)) {
                        callsMutableSetterCall = true;
                    } else if (opcode == INVOKEVIRTUAL
                            && FIELD_OWNER.equals(owner)
                            && FIELD_SET_NAME.equals(methodName)
                            && FIELD_SET_DESC.equals(methodDesc)) {
                        callsFieldSet = true;
                    }
                }
            };
        }
    }

    private static final class ClassSerializerScan extends ClassVisitor {

        private boolean hasClassNewInstance;
        private boolean alreadyPatched;

        ClassSerializerScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            if (!CREATE_INSTANCE_NAME.equals(name) || !CREATE_INSTANCE_DESC.equals(desc)) {
                return null;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String methodName,
                        final String methodDesc,
                        final boolean isInterface) {
                    if (BRIDGE_OWNER.equals(owner)) {
                        alreadyPatched = true;
                    } else if (opcode == INVOKEVIRTUAL
                            && CLASS_OWNER.equals(owner)
                            && NEW_INSTANCE_NAME.equals(methodName)
                            && CLASS_NEW_INSTANCE_DESC.equals(methodDesc)) {
                        hasClassNewInstance = true;
                    }
                }
            };
        }
    }
}
