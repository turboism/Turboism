package dev.turboism.adapter.cubism.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationCallSiteTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exercises both admitted orderings of the two rewrites that own the single
 * {@code GShader.preDraw_exe} uniform query, on the real bytes of every adapted
 * host artifact:
 *
 * <ol>
 *   <li>official body only — the uniform-location cache owns the seam;</li>
 *   <li>shader-uniform patched body (the real retransform baseline, because that
 *       hook is registered retransformation-incapable) — the cache admits the
 *       exact composed shape and takes the seam over.</li>
 * </ol>
 */
class ShaderUniformCompositionArtifactTest {

    private static final List<String> VERSIONS =
            List.of("5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04");

    @Test
    void everyAdaptedVersionPatchesTheReviewedShaderDefinition() throws Exception {
        for (String version : VERSIONS) {
            Path artifact = artifact(version);
            byte[] bytes = shaderBytes(artifact);
            List<String> reports = new ArrayList<>();
            var transformer = new ShaderUniformTransformer(reports::add);
            try (URLClassLoader loader = loaderFor(artifact)) {
                byte[] patched =
                        transformer.transform(loader, ShaderUniformTransformer.OWNER, null, domain(artifact), bytes);
                assertNotNull(patched, version + " reports=" + reports);
                assertTrue(transformer.patched(), version);
                assertTrue(
                        ShaderUniformTransformer.isPatched(loader, ShaderUniformTransformer.OWNER),
                        version + ": a real patch must be recorded for the retransform chain");
                assertEquals(List.of("SHADER_UNIFORM_PATCHED_" + version.replace(".", "")), reports, version);
                assertEquals(1, bridgeCalls(patched, ShaderUniformTransformer.BRIDGE_LOOKUP), version);
                assertEquals(2, bridgeCalls(patched, "invalidate"), version);
                assertEquals(
                        0,
                        queries(patched, ShaderUniformTransformer.DRAW_METHOD),
                        version + ": the draw query must be fully delegated");
                assertEquals(
                        1,
                        queries(bytes, ShaderUniformTransformer.DRAW_METHOD),
                        version + ": the reviewed draw body must hold exactly one query");
                ShaderUniformTransformer.clearPatched(loader, ShaderUniformTransformer.OWNER);
            }
        }
    }

    @Test
    void officialAndComposedBodiesBothTransformWithOneNativeQuery() throws Exception {
        for (String version : VERSIONS) {
            Path artifact = artifact(version);
            byte[] bytes = shaderBytes(artifact);
            try (URLClassLoader loader = loaderFor(artifact)) {
                ProtectionDomain domain = domain(artifact);

                // (1) official baseline: the shader-uniform hook is absent or declined.
                var official = new UniformLocationCallSiteTransformer(loader, artifact, bytes);
                byte[] officialResult =
                        official.transform(null, loader, UniformLocationCallSiteTransformer.OWNER, null, domain, bytes);
                assertNotNull(officialResult, version + ": " + official.failure());
                assertEquals(1, official.matches(), version);
                assertEquals(1, queries(officialResult, UniformLocationCallSiteTransformer.METHOD), version);

                // (2) composed baseline: the premain rewrite already owns the seam.
                byte[] composedBytes = ShaderUniformTransformer.composedDefinition(bytes);
                assertNotNull(composedBytes, version);
                List<String> composedShape = ShaderUniformTransformer.composedShape(bytes);
                assertNotNull(composedShape, version);
                assertEquals(
                        ReviewedMethodShape.read(
                                composedBytes,
                                UniformLocationCallSiteTransformer.OWNER,
                                UniformLocationCallSiteTransformer.METHOD,
                                UniformLocationCallSiteTransformer.DESCRIPTOR),
                        composedShape,
                        version + ": the derived composed shape must equal the real patched body");

                var strict = new UniformLocationCallSiteTransformer(loader, artifact, bytes);
                assertNull(
                        strict.transform(
                                null, loader, UniformLocationCallSiteTransformer.OWNER, null, domain, composedBytes),
                        version + ": a composed body must fail closed without explicit admission");

                var composed = new UniformLocationCallSiteTransformer(loader, artifact, bytes);
                composed.acceptComposedShape(
                        composedShape,
                        ShaderUniformTransformer.BRIDGE_OWNER,
                        ShaderUniformTransformer.BRIDGE_LOOKUP,
                        ShaderUniformTransformer.BRIDGE_LOOKUP_DESCRIPTOR);
                byte[] composedResult = composed.transform(
                        null, loader, UniformLocationCallSiteTransformer.OWNER, null, domain, composedBytes);
                assertNotNull(composedResult, version + ": " + composed.failure());
                assertEquals(1, composed.matches(), version);
                assertEquals(
                        1,
                        queries(composedResult, UniformLocationCallSiteTransformer.METHOD),
                        version + ": the composed takeover must restore exactly one native query");
                assertEquals(
                        0,
                        bridgeCalls(composedResult, ShaderUniformTransformer.BRIDGE_LOOKUP),
                        version + ": only one cache may stay in front of the native query");
                assertEquals(
                        2,
                        bridgeCalls(composedResult, "invalidate"),
                        version + ": the upstream lifecycle hooks stay untouched");
            }
        }
    }

    private static Path artifact(String version) throws Exception {
        String configured = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (configured.isBlank()) {
            String ref = System.getProperty(
                    "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
            assumeTrue(!ref.isBlank(), "exact " + version + " artifact not configured");
            configured =
                    Path.of(ref).resolve(version).resolve("Live2D_Cubism.jar").toString();
        }
        Path artifact = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(artifact), "exact " + version + " artifact missing: " + artifact);
        assertEquals(
                ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.from(artifact))
                        .orElseThrow(),
                version,
                "the supplied artifact must be the reviewed " + version + " host");
        return artifact;
    }

    private static byte[] shaderBytes(Path artifact) throws Exception {
        try (var jar = new JarFile(artifact.toFile());
                var input = jar.getInputStream(jar.getJarEntry(ShaderUniformTransformer.OWNER + ".class"))) {
            return input.readAllBytes();
        }
    }

    private static URLClassLoader loaderFor(Path artifact) throws Exception {
        List<URL> urls = new ArrayList<>();
        try (var files = Files.walk(artifact.getParent(), 3)) {
            for (Path path :
                    files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                urls.add(path.toUri().toURL());
            }
        }
        return new URLClassLoader(
                urls.toArray(URL[]::new), ShaderUniformCompositionArtifactTest.class.getClassLoader());
    }

    private static ProtectionDomain domain(Path artifact) throws Exception {
        return new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
    }

    private static int queries(byte[] bytes, String method) {
        return count(bytes, method, (owner, name) -> name.equals("glGetUniformLocation"));
    }

    private static int bridgeCalls(byte[] bytes, String bridgeMethod) {
        return count(
                bytes,
                null,
                (owner, name) -> owner.equals(ShaderUniformTransformer.BRIDGE_OWNER) && name.equals(bridgeMethod));
    }

    private interface Match {
        boolean test(String owner, String name);
    }

    private static int count(byte[] bytes, String method, Match match) {
        int[] found = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (method != null && !method.equals(name)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String called, String desc, boolean itf) {
                                        if (match.test(owner, called)) found[0]++;
                                    }
                                };
                            }
                        },
                        0);
        return found[0];
    }
}
