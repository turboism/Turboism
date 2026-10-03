package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** The point operation joins the existing capture; it never creates an independent gate. */
final class PointTriangleReusePreparation {
    static final String OWNER = PointTriangleReusePatcher.OWNER.replace('/', '.');
    private static final Set<HostArtifactDigest> HOSTS = Set.of(
            ReviewedHostArtifacts.CUBISM_5_2_03,
            ReviewedHostArtifacts.CUBISM_5_3_02,
            ReviewedHostArtifacts.CUBISM_5_3_03);

    private PointTriangleReusePreparation() {}

    static byte[] prepare(
            byte[] raw, ProtectionDomain domain, ClassLoader loader, TriangulationDefinitionLifecycle lifecycle)
            throws Exception {
        require(
                loader != null && lifecycle != null && lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN"),
                "point ownership unavailable");
        require(domain != null && domain.getCodeSource() != null, "point origin unavailable");
        var origin = domain.getCodeSource().getLocation().toURI();
        require(origin.getScheme().equalsIgnoreCase("file"), "point origin rejected");
        Path jar = Path.of(origin);
        HostArtifactDigest reviewed = HostArtifactDigest.from(jar);
        require(HOSTS.contains(reviewed), "point archive rejected");
        require(
                Class.forName(LazyTriangulationEdgeBridge.class.getName(), false, loader)
                        == LazyTriangulationEdgeBridge.class,
                "point bridge identity rejected");
        byte[] output = PointTriangleReusePatcher.patch(raw);
        require(HostArtifactDigest.from(jar).equals(reviewed), "point archive changed");
        LazyTriangulationEdgeBridge.pointPrepared(loader, TriangulationDefinitionFingerprint.runtimeOf(output));
        return output;
    }

    static void extend(Map<String, String> dependencies, ZipFile host) throws Exception {
        dependencies.put(
                OWNER,
                TriangulationDefinitionFingerprint.runtimeOf(PointTriangleReusePatcher.patch(bytes(host, OWNER))));
        for (String name : new String[] {
            PointTriangleReusePatcher.RESULT, PointTriangleReusePatcher.VECTOR, PointTriangleReusePatcher.VECTOR + "$a"
        })
            dependencies.put(
                    name.replace('/', '.'),
                    TriangulationDefinitionFingerprint.runtimeOf(bytes(host, name.replace('/', '.'))));
    }

    private static byte[] bytes(ZipFile host, String name) throws Exception {
        var entry = host.getEntry(name.replace('.', '/') + ".class");
        require(entry != null, "point dependency missing");
        try (var stream = host.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
