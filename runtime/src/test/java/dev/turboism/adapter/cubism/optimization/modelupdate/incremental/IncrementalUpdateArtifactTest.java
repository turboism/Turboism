package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTarget.Dep;
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
 * Exact-artifact admission for the incremental update: for each configured official jar,
 * all six reviewed method shapes must read non-null, every bridge dependency must be
 * present with the reviewed body (or the reviewed abstract declaration), and the
 * transformer must admit the real bytes of all four touched classes while leaving every
 * other method untouched. Versions without a configured artifact are skipped, not failed.
 */
class IncrementalUpdateArtifactTest {

    @Test void cubism5203EntryAndDependenciesAreAdmitted() throws Exception {
        exercise(IncrementalUpdateTarget.of(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow(),
            "turboism.test.cubism5203Jar");
    }

    @Test void cubism5302EntryAndDependenciesAreAdmitted() throws Exception {
        exercise(IncrementalUpdateTarget.of(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow(),
            "turboism.test.cubism5302Jar");
    }

    @Test void cubism5303EntryAndDependenciesAreAdmitted() throws Exception {
        exercise(IncrementalUpdateTarget.of(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow(),
            "turboism.test.cubism5303Jar");
    }

    private static void exercise(final IncrementalUpdateTarget target, final String property)
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
        try (var loader = new URLClassLoader(urls, IncrementalUpdateArtifactTest.class.getClassLoader());
             var jar = new JarFile(artifact.toFile())) {
            final List<byte[]> references = new ArrayList<>(4);
            final List<String> siteOwners = List.of(
                target.updater(),
                IncrementalUpdateTarget.ROTATION_FORM.replace('.', '/'),
                IncrementalUpdateTarget.WARP_FORM.replace('.', '/'),
                IncrementalUpdateTarget.MESH_FORM.replace('.', '/'));
            for (final String owner : siteOwners) {
                references.add(bytes(jar, owner));
            }
            final List<String[]> sites = List.of(
                new String[]{target.updater(), "a", target.coreDescriptor()},
                new String[]{target.updater(), "a", target.deformerUpdateDescriptor()},
                new String[]{target.updater(), "a", target.artMeshUpdateDescriptor()},
                new String[]{IncrementalUpdateTarget.ROTATION_FORM.replace('.', '/'),
                    "interpolate__testImpl", target.deformerInterpolateDescriptor()},
                new String[]{IncrementalUpdateTarget.WARP_FORM.replace('.', '/'),
                    "interpolate__testImpl", target.deformerInterpolateDescriptor()},
                new String[]{IncrementalUpdateTarget.MESH_FORM.replace('.', '/'),
                    "interpolate__testImpl", target.meshInterpolateDescriptor()});
            for (final String[] site : sites) {
                final byte[] owner = references.get(siteOwners.indexOf(site[0]));
                assertNotNull(ReviewedMethodShape.read(owner, site[0], site[1], site[2]),
                    "reviewed site absent: " + site[0] + "." + site[1]);
            }

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
            assertTrue(concrete > 25, "dependency surface is mostly concrete getters");
            assertTrue(declaredAbstract > 0, "ACDeformer/ACDrawable abstract declarations exist");

            var transformer = new IncrementalUpdateTransformer(loader, artifact, references, target);
            var domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.cert.Certificate[]) null),
                null);
            for (int i = 0; i < siteOwners.size(); i++) {
                final byte[] output = transformer.transform(null, loader, siteOwners.get(i),
                    null, domain, references.get(i));
                assertNotNull(output, siteOwners.get(i) + " rejected: " + transformer.failure());
            }
            assertEquals(6, transformer.matches());
            assertEquals(4, transformer.touchedClasses().size());
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
