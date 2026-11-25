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

    private static final List<String> VERSIONS = List.of(
            "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
            "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04",
            "5.3.05");

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
                assertEquals(
                        0,
                        countInDraw(
                                patched, (owner, name) -> owner.equals("java/util/HashMap") && name.equals("entrySet")),
                        version + ": entrySet() must be eliminated from preDraw_exe");
                assertEquals(
                        0,
                        countInDraw(
                                patched, (owner, name) -> owner.equals("java/util/HashMap") && name.equals("forEach")),
                        version + ": HashMap.forEach() must be eliminated from preDraw_exe");
                assertEquals(
                        2,
                        bridgeCalls(patched, "borrowMapEntries"),
                        version + ": both uniform and optional-texture maps must use pooled entries");
                assertEquals(
                        2,
                        bridgeCalls(patched, "shouldUploadCameraMatrix"),
                        version + ": projectionMatrix and worldToCameraMatrix must consult bridge cache");
                assertEquals(
                        2,
                        bridgeCalls(patched, "recordCameraMatrix"),
                        version + ": projectionMatrix and worldToCameraMatrix must record completed uploads");
                assertTrue(
                        normalMatrixAbsenceSkipsBindCheck(patched),
                        version + ": normalMatrix absence/negative-location checks must skip bind normalMatrix");
                assertEquals(
                        1,
                        bridgeCalls(patched, "useProgramLabel"),
                        version + ": preDraw_exe must route use-program debug string through lookup table");
                assertEquals(
                        0,
                        countUseProgramConcatInDraw(patched),
                        version + ": patched preDraw_exe must drop invokedynamic use-program concat");
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
                assertEquals(
                        1,
                        bridgeCalls(composedResult, "useProgramLabel"),
                        version + ": useProgramLabel lookup must survive UniformLocationCallSiteTransformer takeover");
            }
        }
    }

    @Test
    void strictVerifyAllLinksPatchedShaderAcrossAllAvailableVersions() throws Exception {
        final List<Path> artifacts = new ArrayList<>();
        for (final String version : VERSIONS) {
            final Path path = tryResolveArtifact(version);
            if (path != null && Files.isRegularFile(path)) {
                artifacts.add(path);
            }
        }
        assumeTrue(!artifacts.isEmpty(), "no Cubism artifacts configured for -Xverify:all check");
        final Path manifest = Files.createTempFile("shader-verify-all-", ".txt");
        try {
            Files.writeString(
                    manifest,
                    String.join(
                            "\n",
                            artifacts.stream()
                                    .map(p -> p.toAbsolutePath().toString())
                                    .toList()));
            final String javaExe =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            final Process process = new ProcessBuilder(
                            javaExe,
                            "-Xverify:all",
                            "-cp",
                            System.getProperty("java.class.path"),
                            StrictShaderVerifierMain.class.getName(),
                            manifest.toString())
                    .redirectErrorStream(true)
                    .start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(60L, java.util.concurrent.TimeUnit.SECONDS), "-Xverify:all timed out");
            assertEquals(0, process.exitValue(), "-Xverify:all failed:\n" + output);
            assertTrue(output.contains("VERIFIED_SHADERS=" + artifacts.size()), output);
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    /** Child entrypoint verifying patched {@code GShader} bytecode under {@code -Xverify:all}. */
    public static final class StrictShaderVerifierMain {
        public static void main(final String[] args) throws Exception {
            final List<String> lines = Files.readAllLines(Path.of(args[0]));
            int verified = 0;
            for (final String line : lines) {
                if (line.isBlank()) continue;
                final Path artifact = Path.of(line.trim());
                final byte[] bytes = shaderBytes(artifact);
                final var transformer = new ShaderUniformTransformer(ignored -> {});
                try (URLClassLoader baseLoader = loaderFor(artifact)) {
                    final ProtectionDomain artifactDomain = domain(artifact);
                    final byte[] patched = transformer.transform(
                            baseLoader, ShaderUniformTransformer.OWNER, null, artifactDomain, bytes);
                    if (patched == null) {
                        throw new AssertionError("patch failed for " + artifact);
                    }
                    final var pClassLoader =
                            new URLClassLoader(baseLoader.getURLs(), StrictShaderVerifierMain.class.getClassLoader()) {
                                @Override
                                protected Class<?> loadClass(final String name, final boolean resolve)
                                        throws ClassNotFoundException {
                                    if (name.equals(ShaderUniformTransformer.OWNER.replace('/', '.'))) {
                                        Class<?> loaded = findLoadedClass(name);
                                        if (loaded == null) {
                                            loaded = defineClass(name, patched, 0, patched.length, artifactDomain);
                                        }
                                        if (resolve) resolveClass(loaded);
                                        return loaded;
                                    }
                                    return super.loadClass(name, resolve);
                                }
                            };
                    try (pClassLoader) {
                        final Class<?> c =
                                Class.forName(ShaderUniformTransformer.OWNER.replace('/', '.'), true, pClassLoader);
                        if (c.getDeclaredMethods().length == 0) {
                            throw new AssertionError("no methods on verified " + c);
                        }
                    } finally {
                        ShaderUniformTransformer.clearPatched(baseLoader, ShaderUniformTransformer.OWNER);
                    }
                    verified++;
                }
            }
            System.out.println("VERIFIED_SHADERS=" + verified);
        }
    }

    private static Path tryResolveArtifact(final String version) {
        final String configured = System.getProperty("turboism.test.cubism" + version.replace(".", "") + "Jar", "");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        final String ref = System.getProperty(
                "turboism.test.cubismRefDir", System.getenv().getOrDefault("TURBOISM_CUBISM_REF", ""));
        return ref.isBlank()
                ? null
                : Path.of(ref)
                        .resolve(version)
                        .resolve("Live2D_Cubism.jar")
                        .toAbsolutePath()
                        .normalize();
    }

    private static int countUseProgramConcatInDraw(final byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!ShaderUniformTransformer.DRAW_METHOD.equals(name)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String indyName,
                                            String indyDesc,
                                            org.objectweb.asm.Handle h,
                                            Object... bsmArgs) {
                                        if ("makeConcatWithConstants".equals(indyName)
                                                && bsmArgs != null
                                                && bsmArgs.length == 1
                                                && ("use program " + '\001').equals(bsmArgs[0])) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }

    private static boolean normalMatrixAbsenceSkipsBindCheck(final byte[] bytes) {
        final int[] nmStart = {-1};
        final int[] bindLdc = {-1};
        final int[] bindCall = {-1};
        final var labels = new java.util.IdentityHashMap<org.objectweb.asm.Label, Integer>();
        record Jump(int opIdx, int opcode, org.objectweb.asm.Label target) {}
        final var jumps = new ArrayList<Jump>();
        final int[] op = {0};

        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String signature, String[] exceptions) {
                                if (!ShaderUniformTransformer.DRAW_METHOD.equals(name)) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitLabel(org.objectweb.asm.Label label) {
                                        labels.put(label, op[0]);
                                    }

                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        if ("normalMatrix".equals(value) && nmStart[0] < 0) nmStart[0] = op[0];
                                        if ("bind normalMatrix".equals(value) && bindLdc[0] < 0) bindLdc[0] = op[0];
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                                        jumps.add(new Jump(op[0]++, opcode, label));
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String owner, String called, String desc, boolean itf) {
                                        if (bindLdc[0] >= 0
                                                && bindCall[0] < 0
                                                && owner.startsWith("com/live2d/graphics3d/shader/")
                                                && "a".equals(called)) {
                                            bindCall[0] = op[0];
                                        }
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitInsn(int opcode) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitIntInsn(int opcode, int operand) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitVarInsn(int opcode, int varIndex) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitTypeInsn(int opcode, String type) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitFieldInsn(int o, String ow, String n, String d) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitIincInsn(int varIndex, int increment) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String n, String d, org.objectweb.asm.Handle h, Object... a) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitTableSwitchInsn(
                                            int min,
                                            int max,
                                            org.objectweb.asm.Label dflt,
                                            org.objectweb.asm.Label... l) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitLookupSwitchInsn(
                                            org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] l) {
                                        op[0]++;
                                    }

                                    @Override
                                    public void visitMultiANewArrayInsn(String d, int n) {
                                        op[0]++;
                                    }
                                };
                            }
                        },
                        0);
        if (nmStart[0] < 0 || bindLdc[0] <= nmStart[0] || bindCall[0] <= bindLdc[0]) return false;
        boolean sawIfNullSkip = false;
        boolean sawIfLtSkip = false;
        for (final Jump jump : jumps) {
            if (jump.opIdx() <= nmStart[0] || jump.opIdx() >= bindLdc[0]) continue;
            final Integer targetIdx = labels.get(jump.target());
            if (targetIdx == null) return false;
            if (jump.opcode() == Opcodes.IFNULL) {
                if (targetIdx <= bindCall[0]) return false;
                sawIfNullSkip = true;
            } else if (jump.opcode() == Opcodes.IFLT) {
                if (targetIdx <= bindCall[0]) return false;
                sawIfLtSkip = true;
            }
        }
        return sawIfNullSkip && sawIfLtSkip;
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

    private static int countInDraw(byte[] bytes, Match match) {
        return count(bytes, ShaderUniformTransformer.DRAW_METHOD, match);
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
