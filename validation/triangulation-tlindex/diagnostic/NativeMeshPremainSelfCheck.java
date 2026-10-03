import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.NativeMeshEdgeLookup;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.agent.shaded.asm.*;
import dev.turboism.bootstrap.TurboismAgent;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Actual official native mesh dependency admission through canonical sole premain. */
public final class NativeMeshPremainSelfCheck {
    private static int checks, callbacks;
    private NativeMeshPremainSelfCheck() { }
    public static void callback() { callbacks++; }
    private static void require(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static TriangulationDefinitionLifecycle lifecycle() throws Exception {
        Class<?> shims = Class.forName("dev.turboism.bootstrap.JvmShims");
        for (String name : new String[] {"STARTUP_SUPPRESSION", "PIPE_IMPL_SHIM"}) {
            Object installed = ((AtomicReference<?>) field(shims, name).get(null)).get();
            if (installed == null) continue;
            Instrumentation gateway = (Instrumentation) field(installed.getClass(), "instrumentation").get(installed);
            if (gateway != null && TriangulationDefinitionLifecycle.ownedBy(gateway) != null)
                return TriangulationDefinitionLifecycle.ownedBy(gateway);
        }
        throw new AssertionError("actual owned gateway missing");
    }
    private static final class Mutator implements ClassFileTransformer {
        final String mode;
        Mutator(String mode) { this.mode = mode; }
        @Override public byte[] transform(ClassLoader loader, String name, Class<?> redef, ProtectionDomain domain, byte[] raw) {
            String target = mode.equals("callback") ? "com/live2d/type/CArrayList"
                    : "com/live2d/graphics3d/editableMesh/GEditableMesh2";
            if (!target.equals(name)) return null;
            ClassReader reader = new ClassReader(raw);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] errors) {
                    MethodVisitor delegate = super.visitMethod(access, method, desc, sig, errors);
                    if (!(mode.equals("callback") ? method.equals("get") && desc.equals("(I)Ljava/lang/Object;")
                            : method.equals("setEdgeUpdated"))) return delegate;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override public void visitCode() {
                            super.visitCode();
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "NativeMeshPremainSelfCheck", "callback", "()V", false);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        require(TurboismAgent.class.getClassLoader() == null, "canonical premain");
        String mode = args[0];
        TriangulationDefinitionLifecycle owner = lifecycle();
        if (mode.equals("callback") || mode.equals("mesh-mutation"))
            owner.instrumentation().addTransformer(new Mutator(mode), true);
        Class<?> host = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h");
        Class<?> listType = Class.forName("com.live2d.graphics3d.editableMesh.triangulation.TriangleList");
        Class<?> meshType = Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2");
        boolean admitted = !mode.equals("callback") && !mode.equals("mesh-mutation");
        try (AutoCloseable h = LazyTriangulationEdgeBridge.enter(host);
                AutoCloseable builder = LazyTriangulationEdgeBridge.enterBuilder(listType);
                AutoCloseable meshGate = LazyTriangulationEdgeBridge.enterMesh(meshType)) {
            require(h != null && builder != null, "original h and builder preserved");
            require((meshGate != null) == admitted, "actual extended SDK gate result");
        }
        Object mesh = meshType.getConstructor().newInstance();
        Class<?> edge = Class.forName("com.live2d.graphics3d.editableMesh.MEdge");
        Class<?> rank = Class.forName("com.live2d.graphics3d.editableMesh.MEdge$EdgeType");
        Object normal = rank.getField("NORMAL").get(null), auto = rank.getField("AUTO_TRIANGULATION").get(null);
        List<Object> edges = (List<Object>) field(meshType, "_edges").get(mesh);
        edges.add(edge.getConstructor(int.class, int.class, rank).newInstance(0, 1, auto));
        field(meshType, "cached_indices").set(mesh, new int[] {0, 1, 2});
        require(NativeMeshEdgeLookup.enter(mesh) == null, "external caller cannot retain a table across arbitrary mutations");
        var begin = NativeMeshEdgeLookup.class.getDeclaredMethod("begin", Object.class, AutoCloseable.class);
        begin.setAccessible(true);
        // Owned diagnostic seam consumes a genuine admitted SDK definition lease.
        // Complete system-loader geometry separately exercises the unchanged public ABI.
        try (AutoCloseable scope = admitted ? (AutoCloseable) begin.invoke(null, mesh, LazyTriangulationEdgeBridge.enterMesh(meshType)) : null) {
            require((scope != null) == admitted, "actual public runtime helper entry");
            if (admitted) require(NativeMeshEdgeLookup.find(mesh, 0, 1, false) == 0, "real admitted first-slot table");
            var add = meshType.getMethod("addEdge", int.class, int.class, rank, boolean.class, boolean.class);
            Object promoted = add.invoke(mesh, 0, 1, normal, false, false);
            Object appended = add.invoke(mesh, 0, 2, normal, false, false);
            require(promoted.equals(0) && appended.equals(1) && edges.size() == 2, "native promotion and append intact");
            if (admitted) require(NativeMeshEdgeLookup.find(mesh, 0, 2, false) == 1
                    && edge.getMethod("getType").invoke(edges.get(0)) == normal, "actual woven append/table promotion");
        }
        Class<?> table = Class.forName("dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTable");
        var reservation = table.getDeclaredMethod("reservedBytes"); reservation.setAccessible(true);
        require((int) reservation.invoke(null) == 0, "scope reservation fully released");
        if (!admitted) require(callbacks > 0, "modified SDK callback actually runs through native fallback");
        if (mode.equals("revocation")) {
            owner.instrumentation().addTransformer(new ClassFileTransformer() { }, false);
            require(LazyTriangulationEdgeBridge.enter(host) == null
                    && LazyTriangulationEdgeBridge.enterBuilder(listType) == null
                    && LazyTriangulationEdgeBridge.enterMesh(meshType) == null
                    && NativeMeshEdgeLookup.enter(mesh) == null, "owned mutation permanently revokes all entries");
        }
        require(java.awt.GraphicsEnvironment.isHeadless() && java.awt.Frame.getFrames().length == 0, "no Editor");
        System.out.printf("NATIVE_MESH_PREMAIN_PASS mode=%s admitted=%s checks=%d callbacks=%d%n", mode, admitted, checks, callbacks);
        System.exit(0);
    }
}
