package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget.Dep;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exact-artifact admission for the model-update skip: for each configured official jar,
 * the reviewed entry shape must read non-null, every dependency must be present with the
 * reviewed body (or the reviewed abstract declaration), and the transformer must admit the
 * real entry bytes while leaving every other method untouched. Versions without a
 * configured artifact are skipped, not failed.
 */
class ModelUpdateSkipArtifactTest {

    @Test void cubism5203EntryAndDependenciesAreAdmitted() throws Exception {
        exercise(ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow(),
            "turboism.test.cubism5203Jar");
    }

    @Test void cubism5302EntryAndDependenciesAreAdmitted() throws Exception {
        exercise(ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow(),
            "turboism.test.cubism5302Jar");
    }

    @Test void cubism5303EntryAndDependenciesAreAdmitted() throws Exception {
        exercise(ModelUpdateSkipTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow(),
            "turboism.test.cubism5303Jar");
    }

    private static void exercise(final ModelUpdateSkipTarget target, final String property)
            throws Exception {
        final String configured = System.getProperty(property, "");
        assumeTrue(!configured.isBlank(), "exact " + target.version() + " artifact not configured");
        final Path artifact = Path.of(configured);
        assertEquals(target.digest(), HostArtifactDigest.from(artifact), "artifact identity");
        final URL[] urls;
        try (var files = Files.walk(artifact.getParent())) {
            urls = files.filter(p -> p.toString().endsWith(".jar"))
                .map(p -> {
                    try { return p.toUri().toURL(); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).toArray(URL[]::new);
        }
        try (var loader = new URLClassLoader(urls, ModelUpdateSkipArtifactTest.class.getClassLoader());
             var jar = new JarFile(artifact.toFile())) {
            final byte[] entry = bytes(jar, target.owner());
            assertNotNull(ReviewedMethodShape.read(entry, target.owner(),
                ModelUpdateSkipTarget.METHOD, target.methodDescriptor()),
                "reviewed entry shape absent from official bytes");

            int concrete = 0, declaredAbstract = 0;
            for (final Dep dep : target.dependencies()) {
                final String owner = dep.owner().replace('.', '/');
                final byte[] ownerBytes = bytes(jar, owner);
                if (ReviewedMethodShape.read(ownerBytes, owner, dep.name(), dep.descriptor()) != null) {
                    concrete++;
                } else {
                    assertTrue(isAbstract(ownerBytes, owner, dep.name(), dep.descriptor()),
                        "dependency neither reviewed-concrete nor reviewed-abstract: "
                            + dep.owner() + "." + dep.name() + dep.descriptor());
                    declaredAbstract++;
                }
            }
            assertEquals(1, declaredAbstract,
                "exactly ACDrawable.getDeformedForm is an abstract declaration");
            assertTrue(concrete > 40, "dependency surface is concrete getters");

            var transformer = new ModelUpdateSkipTransformer(loader, artifact, entry, target);
            var domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null),
                null);
            final byte[] output = transformer.transform(null, loader, target.owner(), null,
                domain, entry);
            assertNotNull(output, transformer.failure());
            assertEquals(1, transformer.matches());
            final List<String[]> methods = new ArrayList<>();
            new ClassReader(entry).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(final int access, final String name,
                                                           final String desc, final String sig,
                                                           final String[] exceptions) {
                    if (!ModelUpdateSkipTarget.METHOD.equals(name)
                        || !target.methodDescriptor().equals(desc)) {
                        methods.add(new String[]{name, desc});
                    }
                    return null;
                }
            }, 0);
            for (final String[] method : methods) {
                assertEquals(
                    ReviewedMethodShape.read(entry, target.owner(), method[0], method[1]),
                    ReviewedMethodShape.read(output, target.owner(), method[0], method[1]),
                    method[0]);
            }
            assertTrue(methods.size() > 5, "entry owner has other methods to preserve");
        }
        assertEquals(target.digest(), HostArtifactDigest.from(artifact), "artifact unchanged");
    }

    private static byte[] bytes(final JarFile jar, final String owner) throws Exception {
        final var entry = jar.getJarEntry(owner + ".class");
        assertNotNull(entry, "class absent from official artifact: " + owner);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    /** True when the official class declares {@code name+descriptor} as an abstract method. */
    private static boolean isAbstract(final byte[] bytes, final String owner, final String name,
                                      final String descriptor) {
        final boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(final int access, final String method,
                                                       final String desc, final String sig,
                                                       final String[] exceptions) {
                if (name.equals(method) && descriptor.equals(desc)) {
                    found[0] = (access & Opcodes.ACC_ABSTRACT) != 0;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }
}
