import dev.turboism.agent.shaded.asm.ClassReader;
import dev.turboism.agent.shaded.asm.ClassVisitor;
import dev.turboism.agent.shaded.asm.ClassWriter;
import dev.turboism.agent.shaded.asm.FieldVisitor;
import dev.turboism.agent.shaded.asm.Label;
import dev.turboism.agent.shaded.asm.MethodVisitor;
import dev.turboism.agent.shaded.asm.Opcodes;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.jar.JarFile;

/** Execute own woven fixtures; official producer bytes are inspected/verified as data only. */
public final class MeshProducerWeaveSelfCheck {
    private static int checks;
    private MeshProducerWeaveSelfCheck() {}

    public static void main(String[] args) throws Exception {
        byte[] fixture;
        try (var stream = Producer.class.getResourceAsStream("/" + Producer.class.getName().replace('.', '/') + ".class")) {
            fixture = java.util.Objects.requireNonNull(stream).readAllBytes();
        }
        byte[] wovenFixture = MeshProducerWeave.instrument(fixture, "a",
            "(Ljava/lang/Object;Ljava/util/List;ZLjava/lang/Object;)V", false);
        Class<?> actual = new Loader().define(wovenFixture);
        Object producer = actual.getConstructor().newInstance();
        var invoke = actual.getMethod("a", Object.class, List.class, boolean.class, Object.class);
        MeshProducerRecorderSelfCheck.Mesh mesh = new MeshProducerRecorderSelfCheck.Mesh();
        IdentityHashMap<Object, String> bindings = new IdentityHashMap<>(); bindings.put(mesh, "source");
        // All normal paths and same-length in-place array writes are observed.
        try (var scope = MeshProducerRecorder.begin(1, List.of("source"), bindings)) {
            invoke.invoke(producer, mesh, List.of(), false, null);
            invoke.invoke(producer, mesh, List.of(1), false, null);
            List<MeshProducerRecorder.Event> events = scope.finish();
            require(events.size() == 2 && !events.get(0).result().indicesSha256().equals(events.get(1).result().indicesSha256()));
        }
        UnsupportedOperationException original = new UnsupportedOperationException("native fixture");
        try (var scope = MeshProducerRecorder.begin(2, List.of("source"), bindings)) {
            try { invoke.invoke(producer, mesh, List.of(), true, original); throw new AssertionError("missing exception"); }
            catch (InvocationTargetException wrapped) { require(wrapped.getCause() == original); }
            reject(scope::finish);
        }
        // Snapshot refusal stays diagnostic-only; the original method still returns normally.
        mesh.vertexCache = 0;
        try (var scope = MeshProducerRecorder.begin(3, List.of("source"), bindings)) {
            require(invoke.invoke(producer, mesh, List.of(), false, null) == null);
            reject(scope::finish);
        }
        for (int index = 0; index < args.length; index += 3) {
            String version = args[index]; Path jar = Path.of(args[index + 1]); Path output = Path.of(args[index + 2]);
            require(MeshProducerWeave.jarSha(version).equals(sha(Files.readAllBytes(jar))));
            byte[] input;
            try (JarFile archive = new JarFile(jar.toFile()); var stream = archive.getInputStream(archive.getJarEntry(MeshProducerWeave.TARGET + ".class"))) {
                input = stream.readAllBytes();
            }
            byte[] patched = MeshProducerWeave.patch(input, version);
            Files.write(output, patched, java.nio.file.StandardOpenOption.CREATE_NEW);
            require(members(input).equals(members(patched)));
            for (String[] method : methods(input)) {
                if (method[0].equals("a") && method[1].equals(MeshProducerWeave.descriptor(version))) {
                    int[] before = shape(input, method[0], method[1]), after = shape(patched, method[0], method[1]);
                    require(after[0] == 1 && after[1] == 1 && after[2] == 1);
                    require(after[3] == before[3] + 1 && after[4] == before[4] + 1);
                } else {
                    require(methodBytes(input, method).equals(methodBytes(patched, method)));
                }
            }
            byte[] changed = input.clone(); changed[changed.length - 1] ^= 1;
            rejectChecked(() -> MeshProducerWeave.patch(changed, version));
            rejectChecked(() -> MeshProducerWeave.patch(input, "unknown"));
            System.out.println("Producer official byte-data weave PASS " + version + " input=" + sha(input) + " output=" + sha(patched));
        }
        System.out.println("Mesh producer weave checks PASS: " + checks);
    }
    public static final class Producer {
        public Producer() {}
        public void a(Object value, List<?> options, boolean fail, Object original) {
            long wide = options.size(); // expanded frames must preserve wide local slots
            if (fail) throw (RuntimeException) original;
            int[] indices = ((MeshProducerRecorderSelfCheck.Mesh) value).getCached_indices$core();
            indices[1] = wide == 0 ? 1 : 2; indices[2] = wide == 0 ? 2 : 1;
            if (wide == 0) return;
        }
    }
    private static final class Loader extends ClassLoader {
        Loader() { super(MeshProducerWeaveSelfCheck.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
    private static List<String> members(byte[] bytes) {
        List<String> result = new java.util.ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                result.add("field:" + access + ":" + name + ":" + desc + ":" + signature + ":" + value); return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                result.add("method:" + access + ":" + name + ":" + desc + ":" + signature + ":" + java.util.Arrays.toString(exceptions)); return null;
            }
        }, 0);
        return result;
    }
    private static List<String[]> methods(byte[] bytes) {
        List<String[]> result = new java.util.ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                result.add(new String[] {name, desc}); return null;
            }
        }, 0);
        return result;
    }
    private static String methodBytes(byte[] bytes, String[] method) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) { return null; }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return name.equals(method[0]) && desc.equals(method[1]) ? super.visitMethod(access, name, desc, signature, exceptions) : null;
            }
        }, ClassReader.EXPAND_FRAMES);
        return sha(writer.toByteArray());
    }
    private static int[] shape(byte[] bytes, String name, String descriptor) {
        int[] result = new int[5];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String actual, String desc, String signature, String[] exceptions) {
                if (!actual.equals(name) || !desc.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean iface) {
                        if (!owner.equals(MeshProducerWeave.RECORDER)) return;
                        switch (method) { case "started" -> result[0]++; case "returned" -> result[1]++; case "failed" -> result[2]++; default -> throw new AssertionError(method); }
                    }
                    @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { result[3]++; }
                    @Override public void visitMaxs(int stack, int locals) { result[4] = locals; }
                };
            }
        }, 0);
        return result;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("expected refusal");
    }
    private interface Checked { void run() throws Exception; }
    private static void rejectChecked(Checked action) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("expected refusal");
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("check failed"); checks++;
    }
}
