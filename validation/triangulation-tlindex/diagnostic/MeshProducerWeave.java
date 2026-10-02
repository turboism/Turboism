import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.agent.shaded.asm.ClassReader;
import dev.turboism.agent.shaded.asm.ClassVisitor;
import dev.turboism.agent.shaded.asm.ClassWriter;
import dev.turboism.agent.shaded.asm.Label;
import dev.turboism.agent.shaded.asm.MethodVisitor;
import dev.turboism.agent.shaded.asm.Opcodes;
import dev.turboism.agent.shaded.asm.Type;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Exact initial-definition, owned-Instrumentation validation hook. Never installed in release packaging. */
final class MeshProducerWeave implements ClassFileTransformer, AutoCloseable {
    static final String TARGET = "com/live2d/graphics3d/editableMesh/b";
    static final String RECORDER = MeshProducerRecorder.class.getName().replace('.', '/');
    private final Instrumentation instrumentation;
    private final String version;
    private final java.net.URI expectedOrigin;
    private volatile String status = "REGISTERED_NOT_OBSERVED";
    private boolean closed;

    private MeshProducerWeave(Instrumentation instrumentation, String version, java.net.URI expectedOrigin) {
        this.instrumentation = instrumentation;
        this.version = version;
        this.expectedOrigin = expectedOrigin;
    }

    static MeshProducerWeave install(Instrumentation instrumentation, String version) {
        var lifecycle = TriangulationDefinitionLifecycle.ownedBy(instrumentation);
        require(lifecycle != null && lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN"),
            "producer recorder requires owned Instrumentation");
        classSha(version);
        java.net.URI origin = java.net.URI.create(System.getProperty("turboism.validation.tlWeave.expectCodeSource", "")).normalize();
        require(origin.isAbsolute() && "file".equals(origin.getScheme()), "producer expected origin absent");
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            require(!type.getName().equals(TARGET.replace('/', '.')), "producer already loaded");
        }
        MeshProducerRecorder.requireIdle();
        MeshProducerWeave result = new MeshProducerWeave(instrumentation, version, origin);
        instrumentation.addTransformer(result, false);
        return result;
    }

    String status() { return status; }
    void requireInstalled() { require(status.equals("INITIAL_DEFINITION_WOVEN"), "producer hook: " + status); }

    @Override public synchronized byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            require(!closed && status.equals("REGISTERED_NOT_OBSERVED"), "producer definition replay");
            require(redefined == null && loader == ClassLoader.getSystemClassLoader(), "producer loader/initial definition");
            require(domain != null && domain.getCodeSource() != null, "producer origin absent");
            require(expectedOrigin.equals(domain.getCodeSource().getLocation().toURI().normalize()), "producer origin mismatch");
            Path origin = Path.of(domain.getCodeSource().getLocation().toURI());
            require(origin.getFileName().toString().equals("Live2D_Cubism.jar") && Files.isRegularFile(origin),
                "producer origin is not official JAR");
            // Initial load only, before measurement. No filesystem work occurs in per-producer callbacks.
            try (var stream = Files.newInputStream(origin)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] block = new byte[65536]; int count;
                while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
                require(jarSha(version).equals(HexFormat.of().formatHex(digest.digest())), "producer official JAR mismatch");
            }
            byte[] output = patch(bytes, version);
            status = "INITIAL_DEFINITION_WOVEN";
            return output;
        } catch (Throwable rejected) {
            status = "PRODUCER_DEFINITION_REJECTED";
            return null;
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        if (!instrumentation.removeTransformer(this)) throw new IllegalStateException("producer hook removal failed");
        status = "REMOVED_AFTER_COMMANDS";
    }

    static String descriptor(String version) {
        classSha(version);
        return "(Lcom/live2d/graphics3d/editableMesh/GEditableMesh2;Ljava/util/List;ZLcom/live2d/util/"
            + (version.equals("5203") ? "i" : "j") + "/a;)V";
    }
    static String classSha(String version) {
        return switch (version) {
            case "5203" -> "02cbb1ddc8fc51c18276161a4379a4ef476265ea1963f848dc8ecbee1a64500c";
            case "5302", "5303" -> "671e8cdd67f6f78ff756edf0e910f478f901b58c19fab90ad4a0b6d367ee1aca";
            default -> throw new IllegalStateException("unreviewed producer version");
        };
    }
    static String jarSha(String version) {
        return switch (version) {
            case "5203" -> "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd";
            case "5302" -> "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
            case "5303" -> "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166";
            default -> throw new IllegalStateException("unreviewed producer version");
        };
    }

    static byte[] patch(byte[] bytes, String version) throws Exception {
        require(classSha(version).equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
            "producer class SHA mismatch");
        ClassReader reader = new ClassReader(bytes);
        require(reader.getClassName().equals(TARGET), "producer class name mismatch");
        return instrument(bytes, "a", descriptor(version), true);
    }

    /** Shared algorithm exercised on own executable fixtures. No host class loading/frame recomputation. */
    static byte[] instrument(byte[] bytes, String targetName, String descriptor, boolean singleReturn) {
        ClassReader reader = new ClassReader(bytes);
        int[] shape = new int[3]; // matches, returns, maxLocals
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(targetName) || !desc.equals(descriptor)) return null;
                require((access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0,
                    "unsupported producer method shape");
                require(Type.getReturnType(desc).equals(Type.VOID_TYPE), "producer must return void");
                shape[0]++;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitInsn(int opcode) { if (opcode == Opcodes.RETURN) shape[1]++; }
                    @Override public void visitMaxs(int stack, int locals) { shape[2] = locals; }
                };
            }
        }, 0);
        require(shape[0] == 1 && shape[1] >= 1 && (!singleReturn || shape[1] == 1), "producer method/return shape mismatch");
        int ticketSlot = shape[2];
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor nativeMethod = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals(targetName) || !desc.equals(descriptor)) return nativeMethod;
                return new MethodVisitor(Opcodes.ASM9, nativeMethod) {
                    private final Label start = new Label(), end = new Label(), handler = new Label();
                    @Override public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "started", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        super.visitVarInsn(Opcodes.ASTORE, ticketSlot);
                        super.visitLabel(start);
                    }
                    @Override public void visitFrame(int kind, int count, Object[] values, int stackCount, Object[] stack) {
                        require(kind == Opcodes.F_NEW, "producer frames must be expanded");
                        List<Object> locals = new ArrayList<>(List.of(java.util.Arrays.copyOf(values, count)));
                        pad(locals, ticketSlot); locals.add("java/lang/Object");
                        super.visitFrame(Opcodes.F_NEW, locals.size(), locals.toArray(), stackCount, stack);
                    }
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, ticketSlot);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "returned", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitMaxs(int stack, int localCount) {
                        super.visitLabel(end); super.visitLabel(handler);
                        List<Object> locals = new ArrayList<>(); locals.add(reader.getClassName());
                        for (Type argument : Type.getArgumentTypes(desc)) {
                            locals.add(switch (argument.getSort()) {
                                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                                case Type.FLOAT -> Opcodes.FLOAT;
                                case Type.LONG -> Opcodes.LONG;
                                case Type.DOUBLE -> Opcodes.DOUBLE;
                                case Type.ARRAY -> argument.getDescriptor();
                                default -> argument.getInternalName();
                            });
                        }
                        pad(locals, ticketSlot); locals.add("java/lang/Object");
                        super.visitFrame(Opcodes.F_NEW, locals.size(), locals.toArray(), 1, new Object[] {"java/lang/Throwable"});
                        super.visitInsn(Opcodes.DUP);
                        super.visitVarInsn(Opcodes.ALOAD, ticketSlot);
                        super.visitInsn(Opcodes.SWAP);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "failed", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
                        super.visitInsn(Opcodes.ATHROW);
                        super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                        super.visitMaxs(stack, ticketSlot + 1);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
    private static void pad(List<Object> locals, int slot) {
        int slots = 0;
        for (Object local : locals) slots += local.equals(Opcodes.LONG) || local.equals(Opcodes.DOUBLE) ? 2 : 1;
        require(slots <= slot, "local frame exceeds token slot");
        while (slots++ < slot) locals.add(Opcodes.TOP);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
