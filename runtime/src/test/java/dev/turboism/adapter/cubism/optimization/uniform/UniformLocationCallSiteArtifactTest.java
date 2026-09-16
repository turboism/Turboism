package dev.turboism.adapter.cubism.optimization.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Read-only artifact verification. Does not initialize or launch the Editor. */
class UniformLocationCallSiteArtifactTest {
    @Test void transformsExact5303WithoutChangingOtherMethods() throws Exception {
        String supplied = System.getenv("TURBOISM_UNIFORM_HOST_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "explicit exact-host artifact not supplied");
        Path artifact = Path.of(supplied).toAbsolutePath();
        assertTrue(Files.isRegularFile(artifact), "explicit artifact must exist, not silently skip");
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_03, HostArtifactDigest.from(artifact),
            "unreviewed official artifact");
        List<URL> dependencies = new ArrayList<>();
        try (var files = Files.list(artifact.getParent())) {
            for (Path path : files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                dependencies.add(path.toUri().toURL());
            }
        }
        try (URLClassLoader loader = new URLClassLoader(dependencies.toArray(URL[]::new), getClass().getClassLoader());
             JarFile jar = new JarFile(artifact.toFile())) {
            String owner = UniformLocationCallSiteTransformer.OWNER;
            byte[] original;
            try (var input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { original = input.readAllBytes(); }
            UniformLocationCallSiteTransformer transformer = new UniformLocationCallSiteTransformer(loader, artifact, original);
            ProtectionDomain domain = new ProtectionDomain(new CodeSource(artifact.toUri().toURL(), (Certificate[]) null), null);
            byte[] changed = transformer.transform(null, loader, owner, null, domain, original);
            assertNotNull(changed, transformer.failure());
            assertEquals(1, transformer.matches());
            assertNotNull(transformer.beforeSha256());
            Map<String, String> methods = methods(original);
            assertEquals(methods, methods(changed), "no added, removed or renamed methods");
            int unchanged = 0;
            for (var method : methods.entrySet()) {
                if (method.getKey().equals(UniformLocationCallSiteTransformer.METHOD + UniformLocationCallSiteTransformer.DESCRIPTOR)) continue;
                String name = method.getKey().substring(0, method.getKey().indexOf('('));
                assertEquals(ReviewedMethodShape.read(original, owner, name, method.getValue()),
                    ReviewedMethodShape.read(changed, owner, name, method.getValue()), method.getKey());
                unchanged++;
            }
            assertTrue(unchanged > 50, "must inspect the actual shader, not a synthetic stand-in");
            assertEquals(calls(original), calls(changed), "all native GL query/draw/upload/state calls must remain in bytecode");
        }
    }
    private static Map<String, String> methods(byte[] bytes) {
        Map<String, String> result = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                result.put(name + descriptor, descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return result;
    }
    private static List<String> calls(byte[] bytes) {
        List<String> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (owner.startsWith("com/jogamp/opengl/") && method.startsWith("gl")) {
                            result.add(name + descriptor + ":" + opcode + ":" + owner + "." + method + desc);
                        }
                    }
                };
            }
        }, 0);
        return result;
    }
}
