package dev.turboism.validation.tlindex.diagnostic;

import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndexTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/** Real production weave/admission with official definitions; invokes no official code. */
public final class LazyEdgeProductionIntegrationSelfCheck implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String H = P + "h", J = P + "j";
    private static int checks;
    private LazyEdgeProductionIntegrationSelfCheck() {}
    private static void require(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason + " check=" + checks);
    }
    private static String fingerprint(byte[] bytes) throws Exception {
        Class<?> type = Class.forName("dev.turboism.adapter.cubism.mesh.TriangulationDefinitionFingerprint");
        Method method = type.getDeclaredMethod("runtimeOf", byte[].class); method.setAccessible(true);
        return (String) method.invoke(null, (Object) bytes);
    }
    private static TriangulationEdgeIndexTransformer transformer(Consumer<String> receipt,
            TriangulationDefinitionLifecycle owner) throws Exception {
        Constructor<TriangulationEdgeIndexTransformer> constructor = TriangulationEdgeIndexTransformer.class
                .getDeclaredConstructor(Consumer.class, TriangulationDefinitionLifecycle.class);
        constructor.setAccessible(true); return constructor.newInstance(receipt, owner);
    }
    private static byte[] changed(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes); ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("a")) return output;
                return new MethodVisitor(ASM9, output) {
                    @Override public void visitCode() { super.visitCode(); super.visitInsn(NOP); }
                };
            }
        }, 0); return writer.toByteArray();
    }
    private static void coldConcurrent(Class<?> host) throws Exception {
        int workers = 8; CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int n = 0; n < workers; n++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("cold start timed out");
                    try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
                        if (lease != null) admitted.incrementAndGet();
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "lazy-edge-cold-" + n);
            threads.add(thread); thread.start();
        }
        require(ready.await(5, TimeUnit.SECONDS), "cold workers did not start"); start.countDown();
        for (Thread thread : threads) { thread.join(10000); require(!thread.isAlive(), "cold worker stuck"); }
        require(failure.get() == null, "cold worker failed: " + failure.get());
        require(admitted.get() == workers, "competing cold holders revoked the published gate");
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 4, "official JAR, old fingerprints, new output directory and reviewed|tampered required");
        Path official = Path.of(args[0]).toRealPath(), prior = Path.of(args[1]), out = Path.of(args[2]);
        boolean tampered = args[3].equals("tampered"); require(tampered || args[3].equals("reviewed"), "unknown mode");
        require(LazyTriangulationEdgeBridge.class.getClassLoader() == null, "canonical bridge is not bootstrap loaded");
        require(TriangulationDefinitionLifecycle.class.getClassLoader() == null, "canonical lifecycle is not bootstrap loaded");
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(prior)) { String[] fields = line.split("\t"); expected.put(fields[0], fields[1]); }
        require(expected.size() == 8, "incomplete reviewed dependency inventory");
        List<Path> libraries;
        try (var stream = Files.list(official.getParent())) {
            libraries = stream.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        URL[] urls = new URL[libraries.size()];
        for (int n = 0; n < urls.length; n++) urls[n] = libraries.get(n).toUri().toURL();
        Files.createDirectory(out);
        List<String> receipts = new CopyOnWriteArrayList<>(); Map<String, byte[]> observed = new ConcurrentHashMap<>();
        AtomicReference<byte[]> initialHost = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null) return null;
                if (type == null && name.equals(H)) initialHost.set(bytes.clone());
                if (tampered && name.equals(J)) bytes = changed(bytes);
                if (expected.containsKey(name.replace('/', '.'))) observed.put(name.replace('/', '.'), bytes.clone());
                return tampered && name.equals(J) ? bytes : null;
            }
        };
        try (var owner = TriangulationDefinitionLifecycle.forPremain(DefinitionAdmissionSelfCheckAgent.instrumentation(),
                DefinitionAdmissionSelfCheckAgent.class.getName());
                var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            require(owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN"), owner.startupReason());
            require(Class.forName(LazyTriangulationEdgeBridge.class.getName(), false, loader) == LazyTriangulationEdgeBridge.class,
                    "official loader resolves a different bridge");
            // Preload the fingerprint implementation before callbacks. The observer
            // records copies only and cannot mutate a reviewed JVM input.
            ClassWriter preload = new ClassWriter(0);
            preload.visit(V17, ACC_PUBLIC, "owned/Preload", null, "java/lang/Object", null); preload.visitEnd();
            fingerprint(preload.toByteArray());
            TriangulationEdgeIndexTransformer weave = transformer(receipts::add, owner);
            owner.instrumentation().addTransformer(weave, false); owner.instrumentation().addTransformer(observer, true);
            try {
                Class<?> host = Class.forName(H.replace('/', '.'), false, loader);
                host.getDeclaredMethods(); host.getDeclaredFields(); host.getDeclaredConstructors();
                require(weave.lazyEdgeOutcome() == TriangulationEdgeIndexTransformer.Outcome.PATCHED,
                        "production lazy weave declined: " + weave.lazyEdgeDiagnostic());
                require(host.getClassLoader() == loader, "official host loader changed");
                if (tampered) {
                    require(LazyTriangulationEdgeBridge.enter(host) == null, "modified actual dependency admitted");
                    require(receipts.stream().anyMatch(s -> s.contains("DEFINITION_MISMATCH_OR_INCOMPLETE")),
                            "modified dependency has no rejection receipt: " + receipts);
                } else {
                    coldConcurrent(host);
                    require(receipts.stream().filter(s -> s.startsWith("TRIANGULATION_LAZY_EDGE_ADMISSION ")).count() == 1,
                            "cold ClassValue attempted multiple captures: " + receipts);
                    require(receipts.stream().anyMatch(s -> s.contains("OWNED_FINAL_DEFINITION_MATCH")), "missing final-definition admission receipt");
                    for (var entry : expected.entrySet()) {
                        byte[] actual = observed.get(entry.getKey()); require(actual != null, "uncaptured dependency " + entry.getKey());
                        String production = fingerprint(actual);
                        require(production.equals(DefinitionFingerprint.runtimeOf(actual)), "migrated projection drift: " + entry.getKey());
                        if (!entry.getKey().equals(host.getName())) require(production.equals(entry.getValue()), "reviewed dependency drift " + entry.getKey());
                    }
                    byte[] output = initialHost.get(); require(output != null, "initial host output missing");
                    String outputSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output));
                    for (String phase : List.of("FRESH_EDGE", "MEMBERSHIP", "LAZY_EDGE")) {
                        require(receipts.stream().anyMatch(s -> s.startsWith("TRIANGULATION_" + phase + "_PATCHED ")
                                && s.endsWith("outputSha256=" + outputSha)), "phase receipt does not bind final output: " + phase);
                    }
                    Files.write(out.resolve("h.initial-output.class"), output);
                    ClassFileTransformer mutation = new ClassFileTransformer() {};
                    owner.instrumentation().addTransformer(mutation, false);
                    require(LazyTriangulationEdgeBridge.enter(host) == null, "mutation failed to retire old gate");
                    owner.instrumentation().removeTransformer(mutation);
                    require(LazyTriangulationEdgeBridge.enter(host) == null, "retired gate revived after mutation removal");
                    require(LazyTriangulationEdgeBridge.enter(null) == null && LazyTriangulationEdgeBridge.enter(Object.class) == null,
                            "wrong owner admitted");
                }
                for (var entry : observed.entrySet()) {
                    Path file = out.resolve("captured").resolve(entry.getKey().replace('.', '/') + ".class");
                    Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
                }
                Files.writeString(out.resolve("receipts.txt"), String.join("\n", receipts) + "\n");
                System.out.println("LAZY_EDGE_PRODUCTION_INTEGRATION PASS mode=" + args[3] + " checks=" + checks
                        + " bootBridge=true officialDefinitions=true officialCodeInvoked=false nativeGeometryExecuted=false");
            } finally { owner.instrumentation().removeTransformer(observer); owner.instrumentation().removeTransformer(weave); }
        }
    }
}
