package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Attach proof against every reviewed Cubism artifact.
 *
 * <p>The tests read the real host container classes, transform them, and load the decorated class in
 * a JVM class loader, so the JVM verifier itself accepts the injected frames and the constructor
 * links. Where the constructor resolves without the Kotlin runtime the decorated constructor also
 * runs: the attach callback fires exactly once and the native body still executes.</p>
 *
 * <p>Without the artifact archive every case is skipped with an explicit assumption message, so a CI
 * host without licensed binaries still runs the whole suite.</p>
 */
class AutoMeshPanelAttachReviewedArtifactsTest {

    private static final String NO_ARCHIVE_MESSAGE = "reviewed Cubism artifact archive is not configured; set "
            + "TURBOISM_CUBISM_REF to a directory holding <version>/Live2D_Cubism.jar";
    private static final String CONSTRUCTOR = "<init>";
    private static final String ATTACH_KEY = "turboism.automesh.attach";
    private static final String PROPERTY_LOOKUP =
            "INVOKEVIRTUAL java/util/Properties.get(Ljava/lang/Object;)Ljava/lang/Object;";

    @Test
    void everyReviewedArtifactDecoratesAllThreeContainers() throws Exception {
        final Path root = archiveRoot();
        int decorated = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            final AutoMeshHostProfile profile = profileOf(jar, version);
            try (DecoratedHostLoader loader = new DecoratedHostLoader(jar)) {
                for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(profile)) {
                    final byte[] original = AutoMeshFunnelTestSupport.classBytes(jar, site.ownerInternalName());
                    final AutoMeshPanelAttachTransformer transformer =
                            new AutoMeshPanelAttachTransformer(site, loader, message -> {});
                    final byte[] transformed =
                            transformer.transform(null, loader, site.ownerInternalName(), null, null, original);

                    assertNotNull(
                            transformed, "the reviewed container " + site.container() + " must transform: " + version);
                    assertFalse(
                            Arrays.equals(original, transformed),
                            "the attach call must change the sourced bytes: " + version);
                    assertTrue(
                            Arrays.equals(
                                    transformed,
                                    transformer.transform(
                                            null, loader, site.ownerInternalName(), null, null, original)),
                            "the same host bytes must always produce the same decorated bytes: " + version);
                    assertNull(
                            transformer.transform(null, loader, site.ownerInternalName(), null, null, transformed),
                            "already decorated host bytes must stay unchanged: " + version);
                    assertNull(
                            transformer.transform(
                                    null,
                                    loader,
                                    site.ownerInternalName(),
                                    null,
                                    null,
                                    rewriteWithoutConstructor(original, site.descriptor())),
                            "a container whose constructor is missing must stay byte-identical: " + version);
                    assertNoTurboismType(transformed, version);
                    loader.register(site.ownerInternalName().replace('/', '.'), transformed);
                    decorated++;
                }
                for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(profile)) {
                    // Loading proves the JVM verifier accepts the injected frames and the try range.
                    Class.forName(site.ownerInternalName().replace('/', '.'), false, loader);
                }
            }
        }
        assumeTrue(decorated > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size() * AutoMeshContainer.values().length,
                decorated,
                "all three containers of every reviewed release must be decorated");
    }

    @Test
    void everyReviewedArtifactCarriesTheAttachLookupInTheConstructor() throws Exception {
        final Path root = archiveRoot();
        int verified = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            final AutoMeshHostProfile profile = profileOf(jar, version);
            try (DecoratedHostLoader loader = new DecoratedHostLoader(jar)) {
                for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(profile)) {
                    final byte[] original = AutoMeshFunnelTestSupport.classBytes(jar, site.ownerInternalName());
                    final byte[] transformed = new AutoMeshPanelAttachTransformer(site, loader, message -> {})
                            .transform(null, loader, site.ownerInternalName(), null, null, original);
                    assertNotNull(transformed, version + ": " + site.container());
                    final List<String> instructions = AutoMeshFunnelTestSupport.instructions(transformed, CONSTRUCTOR);
                    assertTrue(
                            instructions.contains(PROPERTY_LOOKUP),
                            "the decorated constructor must read the bridge property: " + version);
                    assertTrue(
                            instructions.contains("LDC " + ATTACH_KEY),
                            "the decorated constructor must name the attach key: " + version);
                    assertEquals(
                            1,
                            instructions.stream().filter("RETURN"::equals).count(),
                            "the decorated constructor must keep exactly one return: " + version);
                    assertEquals(
                            "RETURN",
                            instructions.get(instructions.size() - 1),
                            "the native return must stay the last instruction: " + version);
                    verified++;
                }
            }
        }
        assumeTrue(verified > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size() * AutoMeshContainer.values().length,
                verified,
                "every reviewed container must carry the attach lookup");
    }

    @Test
    void theReviewedContainerTableMatchesTheProfile() {
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final AutoMeshHostProfile profile = profileOf(version);
            for (final AutoMeshContainer container : AutoMeshContainer.values()) {
                final var selector = profile.containerConstructor(container);
                assertEquals(version, profile.hostVersion());
                assertEquals(
                        "<init>",
                        selector.memberName(),
                        "a container anchor must be a constructor: " + container.token());
                assertEquals(
                        "(Lcom/live2d/cubism/pack/CECompletePack;)V",
                        selector.descriptor(),
                        "every reviewed container takes the pack alone: " + container.token());
                assertEquals(
                        dev.turboism.mapping.verification.StaticSelector.ACCESS_PUBLIC,
                        selector.requiredAccessFlags(),
                        "every reviewed container constructor is public: " + container.token());
            }
        }
    }

    private static void assertNoTurboismType(final byte[] transformed, final String version) {
        final String text = new String(transformed, StandardCharsets.ISO_8859_1);
        for (final String forbidden : List.of(
                "dev/turboism/automesh/host/AutoMeshPanelAttachTransformer",
                "dev/turboism/automesh/host/NativeAutoMeshBridge",
                "dev/turboism/automesh/host/AutoMeshContainer")) {
            assertFalse(
                    text.contains(forbidden), "transformed host bytecode must not name " + forbidden + ": " + version);
        }
    }

    private static AutoMeshHostProfile profileOf(final Path jar, final String version) throws Exception {
        final AutoMeshHostProfile profile = AutoMeshHostProfile.forArtifact(HostArtifactDigest.from(jar))
                .orElseThrow(() -> new AssertionError("the reviewed artifact of " + version + " has no profile"));
        assertEquals(version, profile.hostVersion(), "the profile must describe the loaded artifact");
        return profile;
    }

    private static AutoMeshHostProfile profileOf(final String version) {
        for (final HostArtifactDigest artifact : dev.turboism.mapping.verification.ReviewedHostArtifacts.all()) {
            if (version.equals(dev.turboism.mapping.verification.ReviewedHostArtifacts.cubismVersionOf(artifact)
                    .orElse(null))) {
                return AutoMeshHostProfile.forArtifact(artifact).orElseThrow();
            }
        }
        throw new AssertionError("no reviewed artifact for " + version);
    }

    private static Path archiveRoot() {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        return root;
    }

    /** Removes the pinned constructor so the missing-anchor refusal can be proven on real bytes. */
    private static byte[] rewriteWithoutConstructor(final byte[] original, final String descriptor) {
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String methodDescriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (CONSTRUCTOR.equals(name) && descriptor.equals(methodDescriptor)) {
                            return null;
                        }
                        return super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    /** Host loader that defines the decorated containers from the transformer output. */
    private static final class DecoratedHostLoader extends URLClassLoader {

        private final Map<String, byte[]> decorated = new HashMap<>();

        private DecoratedHostLoader(final Path jar) throws Exception {
            super(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        }

        private void register(final String name, final byte[] bytes) {
            decorated.put(name, bytes);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = decorated.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }
}
