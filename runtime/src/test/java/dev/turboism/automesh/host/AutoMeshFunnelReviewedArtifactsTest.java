package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.sdk.plugin.Registration;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Guard proof against every reviewed Cubism artifact.
 *
 * <p>The tests read the real host classes, transform them, and load the guarded class in a JVM
 * class loader, so the JVM verifier itself accepts the injected frames and the method links. Where
 * the funnel signature resolves without the Kotlin runtime the guarded funnel also runs: a handled
 * confirmation returns, and an unhandled confirmation reaches the native body.</p>
 *
 * <p>Without the artifact archive every case is skipped with an explicit assumption message, so a
 * CI host without licensed binaries still runs the whole suite.</p>
 */
class AutoMeshFunnelReviewedArtifactsTest {

    private static final int FIRST_INSTRUCTION_COUNT = 2;
    private static final String NO_ARCHIVE_MESSAGE = "reviewed Cubism artifact archive is not configured; set "
            + "TURBOISM_CUBISM_REF to a directory holding <version>/Live2D_Cubism.jar";
    private static final String MODELING = "updateMeshFromInspector_inModelingMode";
    private static final String MESH_EDITOR = "updateMeshFromInspector_inMeshEditorMode";
    private static final String AUTO_MESH_UTIL = "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil";
    private static final String AUTO_MESH_UTIL_IN_MESH_EDITOR =
            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil_InMeshEditor";
    private static final String SYSTEM_PROPERTIES =
            "INVOKESTATIC java/lang/System.getProperties()Ljava/util/Properties;";
    private static final String INSTANCE_FIELD = "INSTANCE";

    private Registration bridge;

    @AfterEach
    void closeBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Test
    void everyReviewedArtifactGuardsBothFunnelsAtOffsetZero() throws Exception {
        final Path root = archiveRoot();
        int guarded = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            final AutoMeshHostProfile profile = profileOf(jar, version);
            try (GuardedHostLoader loader = new GuardedHostLoader(jar)) {
                for (final String owner : List.of(AUTO_MESH_UTIL, AUTO_MESH_UTIL_IN_MESH_EDITOR)) {
                    final byte[] original = AutoMeshFunnelTestSupport.classBytes(jar, owner);
                    final AutoMeshFunnelTransformer transformer = transformer(profile, owner, loader);
                    final byte[] transformed = transformer.transform(null, loader, owner, null, null, original);
                    assertNotNull(transformed, "the reviewed funnel of " + owner + " must transform: " + version);
                    assertFalse(Arrays.equals(original, transformed), "the guard must change the sourced bytes");
                    assertGuardIsFirst(transformed, owner, version);
                    assertFalse(
                            new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1)
                                    .contains("dev/turboism"),
                            "transformed host bytecode must not name a Turboism type: " + version);
                    assertTrue(
                            Arrays.equals(
                                    transformed, transformer.transform(null, loader, owner, null, null, original)),
                            "the same host bytes must always produce the same guarded bytes: " + version);
                    assertNull(
                            transformer.transform(null, loader, owner, null, null, transformed),
                            "already guarded host bytes must stay unchanged: " + version);
                    assertNull(
                            transformer.transform(
                                    null,
                                    loader,
                                    owner,
                                    null,
                                    null,
                                    rewriteWithoutFunnel(original, funnelNameOf(owner))),
                            "a host whose funnel is missing must stay byte-identical: " + version);
                }
            }
            guarded++;
        }
        assumeTrue(guarded > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size(), guarded, "every reviewed release must be guarded");
    }

    @Test
    void everyReviewedArtifactLinksAndRunsTheGuardedFunnels() throws Exception {
        final Path root = archiveRoot();
        int verified = 0;
        int executedMeshEditor = 0;
        int executedModeling = 0;
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final Path jar = AutoMeshFunnelTestSupport.jar(root, version);
            if (jar == null) {
                continue;
            }
            final AutoMeshHostProfile profile = profileOf(jar, version);
            try (GuardedHostLoader loader = new GuardedHostLoader(jar)) {
                for (final String owner : List.of(AUTO_MESH_UTIL, AUTO_MESH_UTIL_IN_MESH_EDITOR)) {
                    final byte[] original = AutoMeshFunnelTestSupport.classBytes(jar, owner);
                    final byte[] transformed =
                            transformer(profile, owner, loader).transform(null, loader, owner, null, null, original);
                    assertNotNull(transformed, "the reviewed funnel must transform: " + version);
                    loader.register(owner.replace('/', '.'), transformed);
                }
                for (final String owner : List.of(AUTO_MESH_UTIL, AUTO_MESH_UTIL_IN_MESH_EDITOR)) {
                    final Class<?> type = Class.forName(owner.replace('/', '.'), true, loader);
                    verified++;
                    if (runGuard(type, profile, owner, version)) {
                        if (AUTO_MESH_UTIL.equals(owner)) {
                            executedModeling++;
                        } else {
                            executedMeshEditor++;
                        }
                    }
                }
            }
        }
        assumeTrue(verified > 0, NO_ARCHIVE_MESSAGE);
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size() * 2,
                verified,
                "both guarded classes of every reviewed release must link");
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size(),
                executedMeshEditor,
                "the mesh-editor funnel must execute behind the guard on every reviewed release");
        assertTrue(
                executedModeling > 0, "the modeling funnel of the early builds must execute behind the guard as well");
    }

    /**
     * Runs both confirmations of one guarded class.
     *
     * @return {@code true} when the funnel signature resolved and both confirmations ran
     */
    private boolean runGuard(
            final Class<?> type, final AutoMeshHostProfile profile, final String owner, final String version)
            throws Exception {
        final String funnelName = funnelNameOf(owner);
        final String descriptor = funnelDescriptorOf(profile, owner);
        final ClassLoader loader = type.getClassLoader();
        final MethodType methodType;
        try {
            methodType = MethodType.fromMethodDescriptorString(descriptor, loader);
        } catch (RuntimeException | LinkageError unavailable) {
            // The modeling funnel of 5.0.06 onward names Kotlin types the offline archive omits.
            return false;
        }
        final MethodHandle funnel = MethodHandles.publicLookup().findVirtual(type, funnelName, methodType);
        final Object instance = type.getField(INSTANCE_FIELD).get(null);
        final Object[] confirmation = new Object[methodType.parameterCount()];

        bridge = NativeAutoMeshBridge.install((guardedFunnel, arguments) -> Boolean.TRUE, message -> {});
        assertDoesNotThrow(
                () -> funnel.invokeWithArguments(prepend(instance, confirmation)),
                "a handled confirmation must return before the native body: " + version);
        bridge.close();
        bridge = null;

        org.junit.jupiter.api.Assertions.assertThrows(
                Throwable.class,
                () -> funnel.invokeWithArguments(prepend(instance, confirmation)),
                "an unhandled confirmation must reach the native body: " + version);
        return true;
    }

    private static Object[] prepend(final Object instance, final Object[] arguments) {
        final Object[] all = new Object[arguments.length + 1];
        all[0] = instance;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return all;
    }

    private static void assertGuardIsFirst(final byte[] transformed, final String owner, final String version) {
        assertEquals(
                List.of(SYSTEM_PROPERTIES, "LDC " + NativeAutoMeshBridge.callbackKey(funnelOf(owner))),
                AutoMeshFunnelTestSupport.firstInstructions(transformed, funnelNameOf(owner), FIRST_INSTRUCTION_COUNT),
                "the guard must be the first bytecode of " + owner + " on " + version);
    }

    private static AutoMeshHostProfile profileOf(final Path jar, final String version) throws Exception {
        final AutoMeshHostProfile profile = AutoMeshHostProfile.forArtifact(HostArtifactDigest.from(jar))
                .orElseThrow(() -> new AssertionError("the reviewed artifact of " + version + " has no profile"));
        assertEquals(version, profile.hostVersion(), "the profile must describe the loaded artifact");
        return profile;
    }

    private static AutoMeshFunnelTransformer transformer(
            final AutoMeshHostProfile profile, final String owner, final ClassLoader hostLoader) {
        final List<AutoMeshFunnelTransformer.Site> sites = new ArrayList<>();
        for (final AutoMeshFunnelTransformer.Site site : AutoMeshFunnelTransformer.sitesOf(profile)) {
            if (owner.equals(site.ownerInternalName())) {
                sites.add(site);
            }
        }
        final List<String> diagnostics = new ArrayList<>();
        return new AutoMeshFunnelTransformer(owner, sites, hostLoader, diagnostics::add);
    }

    private static AutoMeshFunnel funnelOf(final String owner) {
        return AUTO_MESH_UTIL.equals(owner) ? AutoMeshFunnel.MODELING_MODE : AutoMeshFunnel.MESH_EDITOR_MODE;
    }

    private static String funnelNameOf(final String owner) {
        return AUTO_MESH_UTIL.equals(owner) ? MODELING : MESH_EDITOR;
    }

    private static String funnelDescriptorOf(final AutoMeshHostProfile profile, final String owner) {
        return AUTO_MESH_UTIL.equals(owner)
                ? profile.modelingFunnel().descriptor()
                : profile.meshEditorFunnel().descriptor();
    }

    private static Path archiveRoot() {
        final Path root = AutoMeshFunnelTestSupport.refRoot();
        assumeTrue(root != null, NO_ARCHIVE_MESSAGE);
        return root;
    }

    private static byte[] rewriteWithoutFunnel(final byte[] original, final String funnelName) {
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (funnelName.equals(name)) {
                            return null;
                        }
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    /** Host loader that defines the guarded host classes from the transformer output. */
    private static final class GuardedHostLoader extends URLClassLoader {

        private final Map<String, byte[]> guarded = new HashMap<>();

        private GuardedHostLoader(final Path jar) throws Exception {
            super(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        }

        private void register(final String name, final byte[] bytes) {
            guarded.put(name, bytes);
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = guarded.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }
}
