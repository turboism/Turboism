package dev.turboism.validation.tlindex.diagnostic;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Loads only own patchers from the pinned performance Agent; host bytes remain data. */
final class FrozenEdgeTransforms implements AutoCloseable {
    private static final String SHA = "ee244d0f0be9acc3ca9c812420ebea8bf034742fd75010c6f817987f8ddc1ad0";
    private final URLClassLoader loader;
    private final Method fresh, freshShape, membership;
    static String reviewedSha() { return SHA; }
    FrozenEdgeTransforms(Path agent) throws Exception {
        try (InputStream stream = Files.newInputStream(agent)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[65536]; int count;
            while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
            if (!SHA.equals(HexFormat.of().formatHex(digest.digest()))) throw new IllegalArgumentException("unreviewed frozen Agent");
        }
        loader = new URLClassLoader(new java.net.URL[] {agent.toUri().toURL()}, FrozenEdgeTransforms.class.getClassLoader());
        try {
            fresh = method("FreshTriangulationEdgePatcher", "patch");
            freshShape = method("FreshTriangulationEdgePatcher", "patchShape");
            membership = method("TriangulationMembershipPatcher", "patch");
        } catch (Exception | Error failure) { loader.close(); throw failure; }
    }
    private Method method(String type, String name) throws Exception {
        Class<?> owner = Class.forName("dev.turboism.adapter.cubism.mesh." + type, false, loader);
        if (owner.getClassLoader() != loader) throw new IllegalArgumentException("patcher was shadowed by parent loader");
        Method method = owner.getDeclaredMethod(name, byte[].class); method.setAccessible(true); return method;
    }
    private static byte[] invoke(Method method, byte[] bytes) throws Exception {
        try { return (byte[]) method.invoke(null, (Object) bytes); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    byte[] apply(byte[] bytes, boolean ownedFixture) throws Exception {
        return invoke(membership, invoke(ownedFixture ? freshShape : fresh, bytes));
    }
    ClassLoader bridgeLoader() { return loader; }
    @Override public void close() throws java.io.IOException { loader.close(); }
}
