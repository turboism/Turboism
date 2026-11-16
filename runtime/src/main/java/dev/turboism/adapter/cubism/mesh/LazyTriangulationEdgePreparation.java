package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Complete-JAR admission and immutable dependency plans for the lazy-edge weave. */
final class LazyTriangulationEdgePreparation {
    private static final String P = "com.live2d.graphics3d.editableMesh.triangulation.";
    private static final String H = P + "h", J = P + "j", T = P + "TriPoint", L = P + "l", R = P + "r";
    private static final String K = P + "k", TL = P + "TriangleList";
    private static final String V = "com.live2d.graphics3d.type.GVector2";
    private static final String MATH_52 = "com.live2d.util.L";
    private static final String MATH_5051 = "com.live2d.util.K";
    private static final String ASSERTIONS = "kotlin._Assertions", INTRINSICS = "kotlin.jvm.internal.Intrinsics";
    private static final String KOTLIN_JAR = "kotlin-stdlib-1.7.21.jar";
    /** First reviewed minor release whose angle singleton moved from {@code K} to {@code L}. */
    private static final int MATH_SINGLETON_MOVED_MINOR = 2;

    private static final int VERSION_MINOR_INDEX = 1;
    private static final String VERSION_SEPARATOR = "\\.";
    /** Every class the lazy-edge weave links against, in a stable admission order. */
    private static final List<String> DEPENDENCIES = List.of(H, J, T, L, R, V, K, TL, ASSERTIONS, INTRINSICS);

    private LazyTriangulationEdgePreparation() {}

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    static byte[] prepare(
            byte[] baseline,
            ProtectionDomain domain,
            ClassLoader loader,
            TriangulationDefinitionLifecycle lifecycle,
            Consumer<String> receipt)
            throws Exception {
        require(
                lifecycle != null
                        && lifecycle.startupReason().equals(TriangulationDefinitionLifecycle.SUPPORTED_PREMAIN),
                "owned lifecycle unavailable");
        require(loader != null, "bootstrap host rejected");
        require(
                Class.forName(LazyTriangulationEdgeBridge.class.getName(), false, loader)
                        == LazyTriangulationEdgeBridge.class,
                "bridge identity rejected");
        require(
                Class.forName(TriangulationBuilderEdges.class.getName(), false, loader)
                        == TriangulationBuilderEdges.class,
                "builder helper identity rejected");
        require(
                Class.forName(TriangulationAngleGuard.class.getName(), false, loader) == TriangulationAngleGuard.class,
                "angle helper identity rejected");
        URI hostOrigin = origin(domain);
        require(hostOrigin != null && hostOrigin.getScheme().equalsIgnoreCase("file"), "host origin rejected");
        Path hostPath = Path.of(hostOrigin), kotlinPath = hostPath.getParent().resolve(KOTLIN_JAR);
        // Whole-JAR identity stays the outer trust boundary: an unreviewed build never reaches
        // the structural contract below. Everything inside the JAR is admitted by live shape.
        String cubismVersion = ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.cached(hostPath))
                .orElseThrow(() -> new IllegalArgumentException("unreviewed complete host JAR"));
        require(Files.isRegularFile(kotlinPath), "unreviewed complete Kotlin JAR");
        String mathName = mathSingleton(cubismVersion);
        byte[] output;
        try (ZipFile host = new ZipFile(hostPath.toFile());
                ZipFile kotlin = new ZipFile(kotlinPath.toFile())) {
            Map<String, byte[]> metadata = new HashMap<>();
            output = LazyTriangulationEdgePatcher.patch(
                    baseline, name -> metadata.computeIfAbsent(name, key -> definition(host, kotlin, key)));
            output = TriangulationAngleGuardPatcher.patch(output);
            byte[] list = definition(host, kotlin, TL.replace('.', '/'));
            byte[] collection = definition(host, kotlin, K.replace('.', '/'));
            require(list != null && collection != null, "builder dependencies missing");
            TriangleListEdgeBuilderPatcher.patch(new TriangulationEdgeIndexPatcher().patch(list));
            require(definition(host, kotlin, mathName.replace('.', '/')) != null, "angle threshold dependency missing");
        }
        Plan plan = new Plan(lifecycle, mathName, hostOrigin, kotlinPath.toUri().normalize(), receipt);
        require(LazyTriangulationEdgeBridge.register(loader, plan::capture), "loader already has a lazy plan");
        return output;
    }

    /**
     * Names the reviewed angle singleton for one admitted Editor version.
     *
     * <p>The 5.0/5.1 family declares the singleton as {@code com.live2d.util.K}; every later
     * family declares it as {@code com.live2d.util.L}. The admitted version selects the name,
     * and the live structural contract still rejects a host whose shape disagrees.</p>
     */
    static String mathSingleton(final String cubismVersion) {
        String[] parts = cubismVersion.split(VERSION_SEPARATOR);
        require(parts.length > VERSION_MINOR_INDEX, "unreviewed host version");
        int minor = Integer.parseInt(parts[VERSION_MINOR_INDEX]);
        return minor < MATH_SINGLETON_MOVED_MINOR ? MATH_5051 : MATH_52;
    }

    private record Plan(
            TriangulationDefinitionLifecycle lifecycle,
            String mathName,
            URI hostOrigin,
            URI kotlinOrigin,
            Consumer<String> receipt) {
        TriangulationDefinitionLifecycle.Gate capture(Class<?> owner) {
            try {
                require(owner.getName().equals(H) && !owner.getModule().isNamed(), "host identity/module rejected");
                ClassLoader loader = owner.getClassLoader();
                Map<String, Class<?>> actual = new LinkedHashMap<>();
                for (String name : DEPENDENCIES) {
                    Class<?> type = name.equals(H) ? owner : Class.forName(name, false, loader);
                    URI wantedOrigin = name.startsWith("kotlin.") ? kotlinOrigin : hostOrigin;
                    require(wantedOrigin.equals(origin(type.getProtectionDomain())), "live origin rejected: " + name);
                    if (!name.startsWith("kotlin."))
                        require(type.getClassLoader() == loader, "split host loader: " + name);
                    // Resolve metadata and verifier links without initializing official classes.
                    type.getDeclaredFields();
                    type.getDeclaredMethods();
                    type.getDeclaredConstructors();
                    actual.put(name, type);
                }
                TriangulationDefinitionLifecycle.Gate gate =
                        lifecycle.capture(actual.values().toArray(Class<?>[]::new), types -> {
                            try {
                                links(index(types), mathName);
                                return null;
                            } catch (IllegalArgumentException | ReflectiveOperationException rejected) {
                                return "LIVE_DEPENDENCY_REJECTED reason=" + rejected.getMessage();
                            }
                        });
                report(receipt, "TRIANGULATION_LAZY_EDGE_ADMISSION reason=" + gate.reason());
                return gate;
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                report(
                        receipt,
                        "TRIANGULATION_LAZY_EDGE_ADMISSION reason=LIVE_DEPENDENCY_REJECTED failure="
                                + failure.getClass().getSimpleName());
                return null;
            }
        }
    }

    private static Map<String, Class<?>> index(Class<?>[] types) {
        Map<String, Class<?>> byName = new LinkedHashMap<>();
        for (Class<?> type : types) byName.put(type.getName(), type);
        return byName;
    }

    private static void links(Map<String, Class<?>> types, String mathName) throws ReflectiveOperationException {
        Class<?> host = types.get(H), edge = types.get(J), point = types.get(T), face = types.get(L);
        Class<?> geometry = types.get(R), vector = types.get(V);
        require(Modifier.isFinal(edge.getModifiers()) && edge.getSuperclass() == Object.class, "edge hierarchy");
        require(Modifier.isFinal(point.getModifiers()) && point.getSuperclass() == vector, "point hierarchy");
        require(
                Modifier.isFinal(face.getModifiers()) && Modifier.isFinal(geometry.getModifiers()),
                "dependency finality");
        edge.getDeclaredConstructor(point, point);
        for (String name : new String[] {"a", "b"})
            require(edge.getDeclaredMethod(name).getReturnType() == point, "edge endpoint link");
        for (String name : new String[] {"a", "b", "c"})
            require(face.getDeclaredMethod(name).getReturnType() == point, "face endpoint link");
        require(point.getDeclaredMethod("getIndex").getReturnType() == int.class, "point index link");
        require(geometry.getDeclaredField("a").getType() == geometry, "geometry singleton link");
        require(geometry.getDeclaredMethod("a", edge, edge).getReturnType() == vector, "edge intersection link");
        require(
                geometry.getDeclaredMethod("a", vector, vector, vector, vector).getReturnType() == vector,
                "endpoint intersection link");
        var angle = geometry.getDeclaredMethod("a", vector, vector);
        require(angle.getReturnType() == float.class && Modifier.isFinal(angle.getModifiers()), "native angle link");
        for (String name : new String[] {"getX", "getY"}) {
            var getter = vector.getDeclaredMethod(name);
            require(
                    getter.getReturnType() == float.class && Modifier.isFinal(getter.getModifiers()),
                    "angle scalar getter link");
        }
        Class<?> math = types.get(mathName);
        require(
                Modifier.isFinal(math.getModifiers()) && math.getSuperclass() == Object.class,
                "angle threshold hierarchy");
        var threshold = math.getDeclaredMethod("f");
        require(
                threshold.getReturnType() == float.class && Modifier.isFinal(threshold.getModifiers()),
                "angle threshold method link");
        var epsilon = math.getDeclaredField("i");
        require(
                epsilon.getType() == float.class
                        && Modifier.isPrivate(epsilon.getModifiers())
                        && Modifier.isStatic(epsilon.getModifiers())
                        && Modifier.isFinal(epsilon.getModifiers()),
                "immutable angle threshold field");
        var mathSingleton = math.getDeclaredField("a");
        require(
                mathSingleton.getType() == math
                        && Modifier.isStatic(mathSingleton.getModifiers())
                        && Modifier.isFinal(mathSingleton.getModifiers()),
                "angle threshold singleton link");
        require(host.getDeclaredMethod("d").getReturnType() == void.class, "native angle operation link");
        require(host.getDeclaredMethod("c").getReturnType() == void.class, "host operation link");
        Class<?> collection = types.get(K), list = types.get(TL);
        require(
                Modifier.isFinal(collection.getModifiers()) && Modifier.isFinal(list.getModifiers()),
                "builder finality");
        require(list.getDeclaredMethod("b").getReturnType() == collection, "builder return link");
        require(
                collection.getDeclaredMethod("a", edge, boolean.class).getReturnType() == boolean.class,
                "builder membership link");
        require(collection.getDeclaredMethod("a", edge).getReturnType() == boolean.class, "builder append link");
        require(
                host.getDeclaredMethod("a", face, face, edge).getReturnType() == java.util.List.class,
                "host membership link");
        require(types.get(ASSERTIONS).getDeclaredField("ENABLED").getType() == boolean.class, "assertion link");
        require(
                types.get(INTRINSICS)
                                .getDeclaredMethod("checkNotNullParameter", Object.class, String.class)
                                .getReturnType()
                        == void.class,
                "intrinsics link");
    }

    private static URI origin(ProtectionDomain domain) {
        try {
            return domain == null
                            || domain.getCodeSource() == null
                            || domain.getCodeSource().getLocation() == null
                    ? null
                    : domain.getCodeSource().getLocation().toURI().normalize();
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static byte[] definition(ZipFile host, ZipFile kotlin, String name) {
        try {
            for (ZipFile jar : new ZipFile[] {host, kotlin}) {
                var entry = jar.getEntry(name + ".class");
                if (entry != null)
                    try (InputStream input = jar.getInputStream(entry)) {
                        return input.readAllBytes();
                    }
            }
            if (name.startsWith("java/")
                    || name.startsWith("javax/")
                    || name.startsWith("jdk/")
                    || name.startsWith("sun/")) {
                try (InputStream input = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                    return input == null ? null : input.readAllBytes();
                }
            }
            return null;
        } catch (Exception failure) {
            throw new IllegalArgumentException("hierarchy metadata rejected: " + name, failure);
        }
    }

    private static void report(Consumer<String> receipt, String value) {
        try {
            receipt.accept(value);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }
}
