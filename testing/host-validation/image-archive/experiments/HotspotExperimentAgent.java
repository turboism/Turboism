package dev.turboism.validation.experiments;

import java.awt.image.BufferedImage;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import javax.swing.SwingUtilities;
import org.objectweb.asm.*;

/** Test-only exact-host transforms. Never installed in a real Turboism home or product JAR. */
public final class HotspotExperimentAgent {
    static final String EXT = "com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension";
    static final String IMAGE = "jp/noids/graphics/UtImage", ARCHIVE = "com/live2d/serialize/b", FLOAT = "com/live2d/serialize/impl/G";
    static final String OPS = "dev/turboism/validation/experiments/HotspotOps";
    static final String HOST_SHA = "c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d";
    private HotspotExperimentAgent() {}
    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        if ("selftest".equals(args)) { ExperimentLifecycleTest.instrumentation = instrumentation; return; }
        HotspotOps.mode = System.getProperty("turboism.validation.hotspotExperiment", "none");
        HotspotOps.shadow = Boolean.getBoolean("turboism.validation.hotspotShadow");
        if (!java.util.Set.of("none", "large", "png", "archive", "warp", "float").contains(HotspotOps.mode))
            throw new IllegalArgumentException("unknown experiment");
        Path jar = Path.of(System.getProperty("turboism.validation.hotspotHostJar")).toRealPath();
        if (!sha(Files.readAllBytes(jar)).equals(HOST_SHA)) throw new IllegalStateException("unreviewed host");
        Map<String, byte[]> reference = new HashMap<>();
        try (var zip = new JarFile(jar.toFile())) {
            for (String owner : java.util.List.of(IMAGE, ARCHIVE, EXT, FLOAT))
                try (var input = zip.getInputStream(zip.getJarEntry(owner + ".class"))) { reference.put(owner, input.readAllBytes()); }
        }
        var transformer = new CandidateTransformer(jar, reference);
        // Retransformable is essential: a non-retransformable transform's output
        // can remain cached by the JVM even after its transformer is removed.
        instrumentation.addTransformer(transformer, true);
        Path home = Path.of(System.getProperty("turboism.validation.textureUpload.home"));
        Thread worker = new Thread(() -> {
            Path state = home.resolve("state/texture-upload");
            try {
                await(state.resolve("load.properties"));
                var loading = new Properties();
                HotspotOps.snapshot().forEach((k, v) -> loading.setProperty(k, v.toString()));
                loading.setProperty("experiment", HotspotOps.mode);
                loading.setProperty("shadow", Boolean.toString(HotspotOps.shadow));
                store(state.resolve("experiment-load.properties"), loading);
                await(state.resolve("memory/end.properties"));
                if (transformer.error != null) throw new IllegalStateException(transformer.error);
                if (HotspotOps.snapshot().getOrDefault("transformClasses", 0L) != 4)
                    throw new IllegalStateException("not all exact classes transformed");
                var verify = new Properties();
                verify.setProperty("archiveSha256", HotspotOps.archiveHash());
                AtomicReference<String> thumb = new AtomicReference<>();
                AtomicReference<Throwable> failed = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    try { thumb.set(thumbnail()); } catch (Throwable error) { failed.set(error); }
                });
                if (failed.get() != null) throw new IllegalStateException("thumbnail witness failed", failed.get());
                verify.setProperty("thumbnailSha256", thumb.get());
                instrumentation.removeTransformer(transformer);
                Map<String, byte[]> captured = new HashMap<>();
                ClassFileTransformer observer = new ClassFileTransformer() {
                    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> type,
                            ProtectionDomain domain, byte[] bytes) {
                        if (reference.containsKey(name)) captured.put(name, bytes.clone());
                        return null;
                    }
                };
                instrumentation.addTransformer(observer, true);
                try {
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        String name = type.getName().replace('.', '/');
                        if (reference.containsKey(name)) instrumentation.retransformClasses(type);
                    }
                } finally { instrumentation.removeTransformer(observer); }
                for (String name : reference.keySet()) {
                    byte[] original = reference.get(name), restored = captured.get(name);
                    if (restored == null) throw new IllegalStateException("restored class not observed: " + name);
                    verifyMethodShapes(name, original, restored);
                    verify.setProperty("restoredRawEqual." + name, Boolean.toString(Arrays.equals(original, restored)));
                }
                verify.setProperty("restorationProof", "all-method instruction/branch/handler identity; CP independent");
                verify.setProperty("restoration", "PASS");
                verify.setProperty("status", "PASS");
                HotspotOps.snapshot().forEach((k, v) -> verify.setProperty("final." + k, v.toString()));
                store(state.resolve("experiment-verify.properties"), verify);
            } catch (Throwable error) {
                error.printStackTrace();
                var failed = new Properties(); failed.setProperty("status", "FAIL"); failed.setProperty("failure", error.toString());
                try { store(state.resolve("experiment-verify.properties"), failed); } catch (Exception ignored) {}
            }
        }, "texture-hotspot-experiment-verifier");
        worker.setDaemon(true); worker.start();
    }
    private static void verifyMethodShapes(String owner, byte[] original, byte[] restored) throws Exception {
        // The JVM may reconstitute constant pools/method order at retransformation.
        // Reuse the product's tested CP-independent instruction/branch/handler identity.
        var read = Class.forName("dev.turboism.adapter.cubism.optimization.ReviewedMethodShape")
                .getMethod("read", byte[].class, String.class, String.class, String.class);
        var signatures = new java.util.ArrayList<String[]>();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) signatures.add(new String[] {name, desc});
                return null;
            }
        }, ClassReader.SKIP_CODE);
        for (String[] method : signatures) {
            Object expected = read.invoke(null, original, owner, method[0], method[1]);
            Object actual = read.invoke(null, restored, owner, method[0], method[1]);
            if (expected == null || !expected.equals(actual))
                throw new IllegalStateException("restoration instruction mismatch: " + owner + "." + method[0]);
        }
    }

    private static String thumbnail() throws Exception {
        Class<?> app = Class.forName("com.live2d.cubism.CEAppCtrl");
        Object controller = app.getMethod("access$get_instance$cp").invoke(null);
        Object doc = app.getMethod("getCurrentDoc").invoke(controller);
        Object source = doc.getClass().getMethod("getModelSource").invoke(doc);
        Class<?> params = Class.forName("com.live2d.cubism.doc.model.param.CParameterSet");
        Object image = source.getClass().getMethod("getThumbnail", int.class, params).invoke(source, 256, null);
        int[] pixels = (int[]) image.getClass().getMethod("getIntBuffer").invoke(image);
        var digest = MessageDigest.getInstance("SHA-256");
        for (int pixel : pixels) {
            digest.update((byte) (pixel >>> 24)); digest.update((byte) (pixel >>> 16));
            digest.update((byte) (pixel >>> 8)); digest.update((byte) pixel);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void await(Path path) throws Exception {
        long deadline = System.nanoTime() + 900_000_000_000L;
        while (!Files.isRegularFile(path)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("experiment observer timeout");
            Thread.sleep(25);
        }
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void store(Path path, Properties value) throws Exception {
        Files.createDirectories(path.getParent()); Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(temp)) { value.store(out, "Private hotspot experiment"); }
        Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static final class CandidateTransformer implements ClassFileTransformer {
        final Path jar;
        final Map<String, byte[]> reference;
        volatile String error;
        CandidateTransformer(Path jar, Map<String, byte[]> reference) { this.jar = jar; this.reference = reference; }
        @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> type,
                ProtectionDomain domain, byte[] bytes) {
            if (!reference.containsKey(name)) return null;
            try {
                if (!jar.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toRealPath())
                        || !Arrays.equals(bytes, reference.get(name))) throw new IllegalStateException("target artifact/body differs");
                var reader = new ClassReader(bytes);
                int[] maxLocals = {0};
                if (name.equals(EXT)) reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                        if (!method.equals("updateInterpolatedForms_common")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMaxs(int stack, int locals) { maxLocals[0] = locals; }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                    @Override protected ClassLoader getClassLoader() { return loader; }
                };
                int[] sites = {0}, pointCalls = {0}, setups = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                        MethodVisitor original = super.visitMethod(access, method, desc, sig, ex);
                        boolean image = name.equals(IMAGE) && method.equals("getBufferedImage")
                                && desc.equals("(Ljava/io/InputStream;ILjp/noids/framework/e/c;)Ljava/awt/image/BufferedImage;");
                        boolean archive = name.equals(ARCHIVE) && method.equals("a") && desc.equals("(Ljava/io/InputStream;)V");
                        boolean warp = name.equals(EXT) && method.equals("updateInterpolatedForms_common");
                        boolean setup = name.equals(EXT) && method.equals("setupFormStructure") && desc.equals("(ZZ)V");
                        boolean floating = name.equals(FLOAT) && method.equals("a") && desc.equals("(ILjava/util/List;)Ljava/lang/Object;");
                        if (!image && !archive && !warp && !setup && !floating) return original;
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            final Label resume = new Label(); boolean injected, resumed;
                            @Override public void visitCode() {
                                super.visitCode();
                                if (setup) {
                                    super.visitVarInsn(Opcodes.ILOAD, 1); super.visitVarInsn(Opcodes.ILOAD, 2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "setupCalled", "(ZZ)V", false);
                                    setups[0]++;
                                }
                            }
                            @Override public void visitMethodInsn(int op, String owner, String called, String descriptor, boolean itf) {
                                if (floating && owner.equals("java/lang/Float") && called.equals("parseFloat")
                                        && descriptor.equals("(Ljava/lang/String;)F")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "parseFloat", descriptor, false); sites[0]++; return;
                                }
                                if (image && owner.equals("javax/imageio/ImageIO") && called.equals("read")
                                        && descriptor.equals("(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readImage", descriptor, false); sites[0]++; return;
                                }
                                if (archive && owner.equals("kotlin/io/ByteStreamsKt") && called.equals("readBytes")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "readArchive", descriptor, false); sites[0]++; return;
                                }
                                if (warp && owner.equals("com/live2d/cubism/doc/model/ACForm") && called.equals("getAllPointRef")) {
                                    pointCalls[0]++;
                                    if (!injected) {
                                        injected = true;
                                        int local = maxLocals[0];
                                        super.visitVarInsn(Opcodes.ASTORE, local);
                                        super.visitVarInsn(Opcodes.ALOAD, local);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, OPS, "project", "(Lcom/live2d/cubism/doc/model/ACForm;)Ljava/util/List;", false);
                                        super.visitInsn(Opcodes.DUP);
                                        Label fallback = new Label(); super.visitJumpInsn(Opcodes.IFNULL, fallback);
                                        super.visitJumpInsn(Opcodes.GOTO, resume);
                                        super.visitLabel(fallback); super.visitInsn(Opcodes.POP);
                                        super.visitVarInsn(Opcodes.ALOAD, local);
                                    }
                                }
                                super.visitMethodInsn(op, owner, called, descriptor, itf);
                            }
                            @Override public void visitTypeInsn(int op, String value) {
                                super.visitTypeInsn(op, value);
                                if (warp && injected && !resumed && op == Opcodes.CHECKCAST && value.equals("java/util/List")) {
                                    super.visitLabel(resume); resumed = true; sites[0]++;
                                }
                            }
                        };
                    }
                }, ClassReader.EXPAND_FRAMES);
                int expected = name.equals(IMAGE) ? 2 : 1;
                if (sites[0] != expected || (name.equals(EXT) && (pointCalls[0] != 2 || setups[0] != 1)))
                    throw new IllegalStateException("call-site mismatch: " + name + " sites=" + sites[0]);
                HotspotOps.add("transformClasses", 1);
                return writer.toByteArray();
            } catch (Throwable failed) { error = failed.toString(); HotspotOps.add("transformFailures", 1); return null; }
        }
    }
}
