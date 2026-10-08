package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Complete-JAR admission and immutable dependency plans for the lazy-edge weave. */
final class LazyTriangulationEdgePreparation {
    private static final String P = "com.live2d.graphics3d.editableMesh.triangulation.";
    private static final String H = P + "h", J = P + "j", T = P + "TriPoint", L = P + "l", R = P + "r";
    private static final String K = P + "k", TL = P + "TriangleList";
    private static final String V = "com.live2d.graphics3d.type.GVector2";
    private static final String MATH = "com.live2d.util.L";
    private static final String ASSERTIONS = "kotlin._Assertions", INTRINSICS = "kotlin.jvm.internal.Intrinsics";
    private static final String KOTLIN_JAR = "kotlin-stdlib-1.7.21.jar";
    private static final String KOTLIN_SHA = "d46a9d773ffb9dee4ff1a748ac845dc8e50005c589302951760a2b5187bddd19";
    private static final Map<HostArtifactDigest, Boolean> HOST_JARS = Map.of(
            ReviewedHostArtifacts.CUBISM_5_2_03, true,
            ReviewedHostArtifacts.CUBISM_5_3_02, false,
            ReviewedHostArtifacts.CUBISM_5_3_03, false);
    private static final String BASELINE_52 = "d0fac0cd2c2092db163db7b78bffd011713f2bef17279b08ab088af4e7d27d92";
    private static final String BASELINE_53 = "40d0754026a7a2fb7c491e95144b9d8a1a605d7aee44579cb20bb2363962f8e6";
    private static final String FACE_53 = "91963e179b498e470b9d474a27b876dd4a5a7ee714941cb530a7cd5202924a88";
    private static final String HASH_PATCHED_FACE_53 =
            "24a55d75f73956454aa5df6d7b9f8e15b30643bc41480632527655d11936d26a";

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
                lifecycle != null && lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN"),
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
        HostArtifactDigest hostArtifact = HostArtifactDigest.from(hostPath);
        Boolean family52 = HOST_JARS.get(hostArtifact);
        require(family52 != null, "unreviewed complete host JAR");
        require(sha(kotlinPath).equals(KOTLIN_SHA), "unreviewed complete Kotlin JAR");
        require(
                TriangulationEdgeIndexTransformer.sha256(baseline).equals(family52 ? BASELINE_52 : BASELINE_53),
                "unreviewed composed baseline");
        byte[] output;
        Map<String, String> expected = fingerprints(family52);
        try (ZipFile host = new ZipFile(hostPath.toFile());
                ZipFile kotlin = new ZipFile(kotlinPath.toFile())) {
            Map<String, byte[]> metadata = new HashMap<>();
            output = LazyTriangulationEdgePatcher.patch(
                    baseline, name -> metadata.computeIfAbsent(name, key -> definition(host, kotlin, key)));
            output = TriangulationAngleGuardPatcher.patch(output);
            byte[] list = definition(host, kotlin, TL.replace('.', '/'));
            byte[] collection = definition(host, kotlin, K.replace('.', '/'));
            require(list != null && collection != null, "builder dependencies missing");
            byte[] builtList = TriangleListEdgeBuilderPatcher.patch(new TriangulationEdgeIndexPatcher().patch(list));
            expected.put(TL, TriangleListEdgeBuilderPatcher.dependencyFingerprint(builtList));
            expected.put(K, TriangulationDefinitionFingerprint.runtimeOf(collection));
            byte[] math = definition(host, kotlin, MATH.replace('.', '/'));
            require(math != null, "angle threshold dependency missing");
            expected.put(MATH, TriangulationDefinitionFingerprint.runtimeOf(math));
        }
        // Managed host inventories are immutable; also refuse a changed input during preparation.
        require(
                HostArtifactDigest.from(hostPath).equals(hostArtifact)
                        && sha(kotlinPath).equals(KOTLIN_SHA),
                "input changed during preparation");
        expected.put(H, TriangulationDefinitionFingerprint.runtimeOf(output));
        Plan plan = new Plan(
                lifecycle, Map.copyOf(expected), hostOrigin, kotlinPath.toUri().normalize(), receipt);
        require(LazyTriangulationEdgeBridge.register(loader, plan::capture), "loader already has a lazy plan");
        return output;
    }

    private static Map<String, String> fingerprints(boolean family52) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(
                J,
                family52
                        ? "db8f5480f42194291b900032ab570b585abef98f6c21080d913609d7b94abbaa"
                        : "0df5034248eaf674cc1bce9e836c440d03c9a68fefdcf1330c8fc07f001136f9");
        values.put(T, "57f673e5133cbbc57f068f4822ec3c0df9924054aacbda27c38c85eead057299");
        values.put(L, family52 ? "2c91d3fe923c5e013df09973356487db7f25297dcc109cf942a8c859d4359d51" : FACE_53);
        values.put(
                R,
                family52
                        ? "10941aca78307d9b5725003bd59e7cea8550f755ce6902fcd7f3f27c36437419"
                        : "707c8d36739e7447b1ad904e87bcc0c523b4ad61f6beaa255aa653141052a72f");
        values.put(V, "69a3c0df346190b8b779ea6560dac5272a1ad8f42f0f38904247770400db223c");
        values.put(ASSERTIONS, "8472aaeca98624a13015e2074285960efc0e9c78abffaf32403ae85f039d77b6");
        values.put(INTRINSICS, "83fec7d361fa719a00bec37b424dad9a07e62e908d579347bac5b489fea6fdd4");
        return values;
    }

    static String dependencyFingerprint(byte[] definition) {
        if (new org.objectweb.asm.ClassReader(definition).getClassName().equals(TL.replace('.', '/'))) {
            // The builder reads only the reviewed class/field shape and b() body.
            // Other methods are outside this contract; return observation inside b() is rejected.
            return TriangleListEdgeBuilderPatcher.dependencyFingerprint(definition);
        }
        String observed = TriangulationDefinitionFingerprint.runtimeOf(definition);
        // Exact whole-definition alternative produced by the existing reviewed hash patch.
        // No member is omitted: further hash/getter/field changes still fail the comparison.
        return observed.equals(HASH_PATCHED_FACE_53) ? FACE_53 : observed;
    }

    private record Plan(
            TriangulationDefinitionLifecycle lifecycle,
            Map<String, String> expected,
            URI hostOrigin,
            URI kotlinOrigin,
            Consumer<String> receipt) {
        TriangulationDefinitionLifecycle.Gate capture(Class<?> owner) {
            try {
                require(owner.getName().equals(H) && !owner.getModule().isNamed(), "host identity/module rejected");
                ClassLoader loader = owner.getClassLoader();
                Map<String, Class<?>> actual = new LinkedHashMap<>();
                for (String name : expected.keySet()) {
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
                links(actual);
                TriangulationDefinitionLifecycle.Gate gate = lifecycle.capture(
                        actual.values().toArray(Class<?>[]::new),
                        expected,
                        LazyTriangulationEdgePreparation::dependencyFingerprint);
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

    private static void links(Map<String, Class<?>> types) throws Exception {
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
        Class<?> math = types.get(MATH);
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

    private static URI origin(ProtectionDomain domain) throws Exception {
        return domain == null
                        || domain.getCodeSource() == null
                        || domain.getCodeSource().getLocation() == null
                ? null
                : domain.getCodeSource().getLocation().toURI().normalize();
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

    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] block = new byte[65536];
            int read;
            while ((read = input.read(block)) != -1) digest.update(block, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void report(Consumer<String> receipt, String value) {
        try {
            receipt.accept(value);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }
}
