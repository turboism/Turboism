package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Exact archive inputs for one extended h-owned definition capture. No independent gate. */
final class NativeMeshEdgePreparation {
    static final String MESH = NativeMeshEdgePatcher.MESH.replace('/', '.');
    static final String EDGE = "com.live2d.graphics3d.editableMesh.MEdge";
    static final String TYPE = EDGE + "$EdgeType";
    static final String COMPANION = TYPE + "$a";
    static final String LIST = "com.live2d.type.CArrayList";
    static final String PROGRESSION = "kotlin.internal.ProgressionUtilKt";
    private static final Set<HostArtifactDigest> HOSTS = Set.of(
            ReviewedHostArtifacts.CUBISM_5_2_03,
            ReviewedHostArtifacts.CUBISM_5_3_02,
            ReviewedHostArtifacts.CUBISM_5_3_03);

    private NativeMeshEdgePreparation() {}

    static byte[] prepare(
            byte[] raw, ProtectionDomain domain, ClassLoader loader, TriangulationDefinitionLifecycle lifecycle)
            throws Exception {
        if (loader == null
                || lifecycle == null
                || !lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN")
                || domain == null
                || domain.getCodeSource() == null)
            throw new IllegalArgumentException("native ownership/origin unavailable");
        var origin = domain.getCodeSource().getLocation().toURI();
        if (!"file".equalsIgnoreCase(origin.getScheme())) throw new IllegalArgumentException("native origin rejected");
        Path jar = Path.of(origin);
        HostArtifactDigest reviewed = HostArtifactDigest.from(jar);
        if (!HOSTS.contains(reviewed)) throw new IllegalArgumentException("unreviewed native archive");
        if (Class.forName(NativeMeshEdgeLookup.class.getName(), false, loader) != NativeMeshEdgeLookup.class)
            throw new IllegalArgumentException("native helper identity rejected");
        byte[] output = NativeMeshEdgePatcher.patch(raw);
        if (!HostArtifactDigest.from(jar).equals(reviewed))
            throw new IllegalArgumentException("native archive changed");
        LazyTriangulationEdgeBridge.meshPrepared(loader, TriangulationDefinitionFingerprint.runtimeOf(output));
        return output;
    }

    static void extend(Map<String, String> expected, ZipFile host, ZipFile kotlin) throws Exception {
        byte[] mesh = bytes(host, MESH);
        expected.put(MESH, TriangulationDefinitionFingerprint.runtimeOf(NativeMeshEdgePatcher.patch(mesh)));
        for (String name : new String[] {EDGE, TYPE, COMPANION, LIST})
            expected.put(name, TriangulationDefinitionFingerprint.runtimeOf(bytes(host, name)));
        expected.put(PROGRESSION, TriangulationDefinitionFingerprint.runtimeOf(bytes(kotlin, PROGRESSION)));
    }

    private static byte[] bytes(ZipFile archive, String name) throws Exception {
        var entry = archive.getEntry(name.replace('.', '/') + ".class");
        if (entry == null) throw new IllegalArgumentException("native dependency missing: " + name);
        try (var stream = archive.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }
}
