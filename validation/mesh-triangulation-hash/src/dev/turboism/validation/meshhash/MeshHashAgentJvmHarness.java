package dev.turboism.validation.meshhash;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Runs inside a fresh JVM started with {@code -javaagent:mesh-hash-agent.jar} against owner-built
 * fixture classes that carry the host's exact names. It proves the packaged agent — including its
 * private, relocated bytecode library — really installs the fix and the probe in a live JVM,
 * without defining or executing any official class.
 *
 * <p>Usage: {@code MeshHashAgentJvmHarness <mode> <evidence.properties>}</p>
 */
public final class MeshHashAgentJvmHarness {
    private static final String TRIPLE = "com.live2d.graphics3d.editableMesh.triangulation.l";
    private static final String POINT = "com.live2d.graphics3d.editableMesh.triangulation.TriPoint";
    private static final String MESH = "com.live2d.graphics3d.editableMesh.GEditableMesh2";
    private static final String INPUT = "com.live2d.util.j.a";

    private MeshHashAgentJvmHarness() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalStateException(
                "usage: MeshHashAgentJvmHarness <mode> <evidence.properties>");
        }
        final String mode = args[0];
        final Path evidence = Path.of(args[1]);

        final Class<?> point = Class.forName(POINT);
        final Class<?> triple = Class.forName(TRIPLE);
        final Class<?> mesh = Class.forName(MESH);
        final Class<?> input = Class.forName(INPUT);

        final Object p1 = newPoint(point, 1.0f, 2.0f, 1);
        final Object p2 = newPoint(point, 3.0f, 4.0f, 2);
        final Object p3 = newPoint(point, 5.0f, 6.0f, 3);
        final Set<Object> distinct = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            final Object instance = newTriple(triple, new Object[] {
                newPoint(point, i, i * 2.0f, i),
                newPoint(point, i + 0.5f, i * 2.0f + 0.5f, i + 1000),
                newPoint(point, i + 0.25f, i * 2.0f + 0.25f, i + 2000),
            });
            distinct.add(instance.getClass().getMethod("hashCode").invoke(instance));
        }
        final int constant = distinct.size();

        // Permutation consistency must hold in both modes: equality already grouped these.
        final Object base = newTriple(triple, new Object[] {p1, p2, p3});
        final Object permuted = newTriple(triple, new Object[] {p3, p1, p2});
        final boolean sameHash = (int) triple.getMethod("hashCode").invoke(base)
            == (int) triple.getMethod("hashCode").invoke(permuted);

        // Drive the instrumented mesh method so the probe records at least one capture.
        final Object meshInstance = mesh.getConstructor().newInstance();
        mesh.getMethod("runUpdate", input, boolean.class).invoke(meshInstance, null, true);
        mesh.getMethod("runUpdate", input, boolean.class).invoke(meshInstance, null, false);

        final Properties recorded = load(evidence);
        final String tripleState = recorded.getProperty("tripleState", "MISSING");
        final String meshHook = recorded.getProperty("meshHook", "MISSING");
        final String captures = recorded.getProperty("captures", "0");
        final String digest = recorded.getProperty("digest", "NONE");
        final String blocked = recorded.getProperty("blocked", "NONE");

        System.out.println("AGENT_HARNESS mode=" + mode
            + " distinctHashes=" + constant
            + " permutationHashEqual=" + sameHash
            + " tripleState=" + tripleState
            + " meshHook=" + meshHook
            + " captures=" + captures
            + " digest=" + digest
            + " blocked=" + blocked);

        final boolean ok;
        if ("fix".equals(mode)) {
            ok = constant > 1 && sameHash
                && "PATCHED".equals(tripleState)
                && "HOOKED".equals(meshHook)
                && Integer.parseInt(captures) >= 2
                && "NONE".equals(blocked);
        } else {
            ok = constant == 1 && sameHash
                && "SEEN_UNPATCHED".equals(tripleState)
                && "HOOKED".equals(meshHook)
                && Integer.parseInt(captures) >= 2
                && "NONE".equals(blocked);
        }
        System.out.println(ok
            ? "MESH_HASH_AGENT_HARNESS PASS mode=" + mode
            : "MESH_HASH_AGENT_HARNESS FAIL mode=" + mode);
        if (!ok) System.exit(1);
    }

    private static Object newPoint(final Class<?> point, final float x, final float y,
                                   final int index) throws Exception {
        return point.getConstructor(float.class, float.class, int.class).newInstance(x, y, index);
    }

    private static Object newTriple(final Class<?> triple, final Object[] points) throws Exception {
        final Class<?> point = Class.forName(POINT, false, triple.getClassLoader());
        return triple.getConstructor(point, point, point)
            .newInstance(points[0], points[1], points[2]);
    }

    private static Properties load(final Path path) throws Exception {
        final Properties properties = new Properties();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

}
