package dev.turboism.validation.tlindex.diagnostic;

import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndexTransformer;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/**
 * Real production weave/admission with official definitions; invokes no official code.
 *
 * <p>Admission is structural: the weave admits the live dependency shapes it links against
 * and stores no byte preimage. The observer therefore records definitions for evidence only,
 * and the tampered control changes a reviewed shape instead of a reviewed digest.</p>
 */
public final class LazyEdgeProductionIntegrationSelfCheck implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String B = P.replace('/', '.');
    private static final String H = P + "h", J = P + "j";
    /** Binary names the lazy-edge weave links against, plus both reviewed angle singletons. */
    private static final List<String> DEPENDENCIES = List.of(
            B + "h",
            B + "j",
            B + "TriPoint",
            B + "l",
            B + "r",
            "com.live2d.graphics3d.type.GVector2",
            B + "k",
            B + "TriangleList",
            "com.live2d.util.K",
            "com.live2d.util.L",
            "kotlin._Assertions",
            "kotlin.jvm.internal.Intrinsics");
    /** The angle singleton moved from {@code K} to {@code L}; one family is defined per host. */
    private static final List<String> ANGLE_SINGLETONS = List.of("com.live2d.util.K", "com.live2d.util.L");
    /** Independent cold callers that must share exactly one published capture. */
    private static final int COLD_WORKERS = 8;
    /** Each stage reports one receipt; the weave returns a single composed result. */
    private static final List<String> PHASE_RECEIPTS = List.of(
            "TRIANGULATION_FRESH_EDGE_PATCHED",
            "TRIANGULATION_MEMBERSHIP_PATCHED",
            "TRIANGULATION_LAZY_EDGE_PATCHED",
            "TRIANGULATION_ANGLE_GUARD_PATCHED");
    private static final String ADMISSION_RECEIPT = "TRIANGULATION_LAZY_EDGE_ADMISSION reason=";
    private static final String CONTRACT_MATCH = "OWNED_DEFINITION_CONTRACT_MATCH";
    private static final String SHAPE_REFUSAL = "LIVE_DEPENDENCY_REJECTED";
    private static final String LAZY_EDGE = "TRIANGULATION_LAZY_EDGE_ADMISSION ";
    private static final long TIMEOUT_SECONDS = 5;
    private static final long JOIN_MILLIS = 10_000;
    private static int checks;

    private LazyEdgeProductionIntegrationSelfCheck() {}

    private static void require(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason + " check=" + checks);
    }

    private static TriangulationEdgeIndexTransformer transformer(
            Consumer<String> receipt, TriangulationDefinitionLifecycle owner) throws Exception {
        Constructor<TriangulationEdgeIndexTransformer> constructor = TriangulationEdgeIndexTransformer.class
                .getDeclaredConstructor(Consumer.class, TriangulationDefinitionLifecycle.class);
        constructor.setAccessible(true);
        return constructor.newInstance(receipt, owner);
    }

    /**
     * Removes the reviewed finality of the edge class.
     *
     * <p>The link contract requires a final edge class, so this shape change must refuse
     * admission. The class stays loadable, which keeps the control focused on admission.</p>
     */
    private static byte[] changed(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        super.visit(version, access & ~ACC_FINAL, name, signature, superName, interfaces);
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static void coldConcurrent(Class<?> host) throws Exception {
        CountDownLatch ready = new CountDownLatch(COLD_WORKERS), start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int n = 0; n < COLD_WORKERS; n++) {
            Thread thread = new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                throw new AssertionError("cold start timed out");
                            try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
                                if (lease != null) admitted.incrementAndGet();
                            }
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        }
                    },
                    "lazy-edge-cold-" + n);
            threads.add(thread);
            thread.start();
        }
        require(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "cold workers did not start");
        start.countDown();
        for (Thread thread : threads) {
            thread.join(JOIN_MILLIS);
            require(!thread.isAlive(), "cold worker stuck");
        }
        require(failure.get() == null, "cold worker failed: " + failure.get());
        require(admitted.get() == COLD_WORKERS, "competing cold holders revoked the published gate");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "official JAR, new output directory and reviewed|tampered required");
        Path official = Path.of(args[0]).toRealPath(), out = Path.of(args[1]);
        boolean tampered = args[2].equals("tampered");
        require(tampered || args[2].equals("reviewed"), "unknown mode");
        require(LazyTriangulationEdgeBridge.class.getClassLoader() == null, "canonical bridge is not bootstrap loaded");
        require(
                TriangulationDefinitionLifecycle.class.getClassLoader() == null,
                "canonical lifecycle is not bootstrap loaded");
        Set<String> expected = Set.copyOf(DEPENDENCIES);
        List<Path> libraries;
        try (var stream = Files.list(official.getParent())) {
            libraries = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        }
        URL[] urls = new URL[libraries.size()];
        for (int n = 0; n < urls.length; n++) urls[n] = libraries.get(n).toUri().toURL();
        Files.createDirectory(out);
        List<String> receipts = new CopyOnWriteArrayList<>();
        Map<String, byte[]> observed = new ConcurrentHashMap<>();
        AtomicReference<byte[]> initialHost = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String name,
                    Class<?> type,
                    ProtectionDomain domain,
                    byte[] bytes) {
                if (name == null) return null;
                if (type == null && name.equals(H)) initialHost.set(bytes.clone());
                if (tampered && name.equals(J)) bytes = changed(bytes);
                if (expected.contains(name.replace('/', '.'))) observed.put(name.replace('/', '.'), bytes.clone());
                return tampered && name.equals(J) ? bytes : null;
            }
        };
        try (var owner = TriangulationDefinitionLifecycle.forPremain(
                        DefinitionAdmissionSelfCheckAgent.instrumentation(),
                        DefinitionAdmissionSelfCheckAgent.class.getName());
                var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            require(owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN"), owner.startupReason());
            require(
                    Class.forName(LazyTriangulationEdgeBridge.class.getName(), false, loader)
                            == LazyTriangulationEdgeBridge.class,
                    "official loader resolves a different bridge");
            TriangulationEdgeIndexTransformer weave = transformer(receipts::add, owner);
            owner.instrumentation().addTransformer(weave, false);
            owner.instrumentation().addTransformer(observer, true);
            try {
                Class<?> host = Class.forName(H.replace('/', '.'), false, loader);
                host.getDeclaredMethods();
                host.getDeclaredFields();
                host.getDeclaredConstructors();
                require(
                        weave.lazyEdgeOutcome() == TriangulationEdgeIndexTransformer.Outcome.PATCHED,
                        "production lazy weave declined: " + weave.lazyEdgeDiagnostic());
                require(host.getClassLoader() == loader, "official host loader changed");
                if (tampered) {
                    require(LazyTriangulationEdgeBridge.enter(host) == null, "changed live shape admitted");
                    require(
                            receipts.stream().anyMatch(s -> s.contains(SHAPE_REFUSAL)),
                            "changed live shape has no rejection receipt: " + receipts);
                } else {
                    coldConcurrent(host);
                    require(
                            receipts.stream()
                                            .filter(s -> s.startsWith(LAZY_EDGE))
                                            .count()
                                    == 1,
                            "cold ClassValue attempted multiple captures: " + receipts);
                    require(
                            receipts.stream().anyMatch(s -> s.startsWith(ADMISSION_RECEIPT + CONTRACT_MATCH)),
                            "missing contract-match admission receipt: " + receipts);
                    for (String name : DEPENDENCIES) {
                        if (name.equals(host.getName()) || ANGLE_SINGLETONS.contains(name)) continue;
                        require(observed.containsKey(name), "uncaptured dependency " + name);
                    }
                    require(
                            ANGLE_SINGLETONS.stream().anyMatch(observed::containsKey),
                            "uncaptured angle singleton");
                    byte[] output = initialHost.get();
                    require(output != null, "initial host output missing");
                    for (String phase : PHASE_RECEIPTS) {
                        require(
                                receipts.stream().filter(s -> s.startsWith(phase)).count() == 1,
                                "phase receipt missing or duplicated: " + phase);
                    }
                    Files.write(out.resolve("h.initial-output.class"), output);
                    ClassFileTransformer mutation = new ClassFileTransformer() {};
                    owner.instrumentation().addTransformer(mutation, false);
                    require(LazyTriangulationEdgeBridge.enter(host) == null, "mutation failed to retire old gate");
                    owner.instrumentation().removeTransformer(mutation);
                    require(
                            LazyTriangulationEdgeBridge.enter(host) == null,
                            "retired gate revived after mutation removal");
                    require(
                            LazyTriangulationEdgeBridge.enter(null) == null
                                    && LazyTriangulationEdgeBridge.enter(Object.class) == null,
                            "wrong owner admitted");
                }
                for (var entry : observed.entrySet()) {
                    Path file = out.resolve("captured").resolve(entry.getKey().replace('.', '/') + ".class");
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                }
                Files.writeString(out.resolve("receipts.txt"), String.join("\n", receipts) + "\n");
                System.out.println("LAZY_EDGE_PRODUCTION_INTEGRATION PASS mode=" + args[2] + " checks=" + checks
                        + " bootBridge=true officialDefinitions=true officialCodeInvoked=false"
                        + " nativeGeometryExecuted=false storedPreimage=false");
            } finally {
                owner.instrumentation().removeTransformer(observer);
                owner.instrumentation().removeTransformer(weave);
            }
        }
    }
}
