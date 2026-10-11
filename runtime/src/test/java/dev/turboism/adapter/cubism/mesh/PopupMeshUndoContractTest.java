package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PopupMeshUndoContractTest {
    @Test
    void popupSnapshotRestoresRealNativeMeshAndInvalidatesPositionsOnUndoRedo() throws Exception {
        final Path jars = evidence().resolve("Cubism-5.3.02/jars");
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(jars.resolve("Live2D_Cubism.jar")));
        try (var paths = Files.list(jars);
                var loader = new URLClassLoader(
                        paths.filter(p -> p.toString().endsWith(".jar"))
                                .map(p -> {
                                    try {
                                        return p.toUri().toURL();
                                    } catch (Exception failure) {
                                        throw new IllegalStateException(failure);
                                    }
                                })
                                .toArray(URL[]::new),
                        ClassLoader.getPlatformClassLoader())) {
            final Class<?> meshType = loader.loadClass("com.live2d.graphics3d.editableMesh.GEditableMesh2");
            final Object mesh = meshType.getConstructor().newInstance();
            final Class<?> pointType = loader.loadClass("com.live2d.graphics3d.editableMesh.MPoint2$PointType");
            final Object normal = pointType.getField("NORMAL").get(null);
            meshType.getMethod("addPoint", float.class, float.class, pointType, long.class)
                    .invoke(mesh, 1f, 2f, normal, -1L);
            final Class<?> groupType = loader.loadClass("com.live2d.undo.GroupUndo");
            final Object group = groupType.getConstructor(String.class).newInstance("Move Points");
            groupType.getMethod("setPreparing$core", boolean.class).invoke(group, true);
            PopupMeshUndoContract.snapshotMeshes(loader, "Move Points", group, List.of(mesh));
            assertEquals(1, groupType.getMethod("getEditCount").invoke(group));
            final Object point = meshType.getMethod("getPointRef", int.class).invoke(mesh, 0);
            NativeMeshMirrorBridge.movePoint(point, 7f, 8f);
            groupType.getMethod("setPreparing$core", boolean.class).invoke(group, false);
            assertPosition(mesh, 7f, 8f);
            final int movedVersion =
                    (Integer) meshType.getMethod("get_postion_edit_version").invoke(mesh);
            groupType.getMethod("undo").invoke(group);
            assertPosition(mesh, 1f, 2f);
            assertTrue((Integer) meshType.getMethod("get_postion_edit_version").invoke(mesh) > movedVersion);
            groupType.getMethod("redo").invoke(group);
            assertPosition(mesh, 7f, 8f);
        }
    }

    @Test
    void unrelatedPackWithPopupShapedMethodsIsNeverAdmitted() {
        assertFalse(PopupMeshUndoContract.admitted(new UnreviewedPack()));
        assertFalse(NativeMeshMirrorBridge.actionPackOwnsEditMode(new UnreviewedPack(), new Object()));
    }

    public static final class UnreviewedPack {
        public Object c() {
            return new Object();
        }

        public List<Object> a() {
            return List.of();
        }
    }

    private static void assertPosition(final Object mesh, final float x, final float y) throws Exception {
        final List<?> points = (List<?>) mesh.getClass().getMethod("getPoints").invoke(mesh);
        assertEquals(1, points.size());
        final Object position = points.get(0);
        assertEquals(x, ((Number) position.getClass().getMethod("getX").invoke(position)).floatValue());
        assertEquals(y, ((Number) position.getClass().getMethod("getY").invoke(position)).floatValue());
    }

    private static Path evidence() {
        final String configured = System.getenv("TURBOISM_LEGACY_CUBISM_REF");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            final Path candidate = current.resolveSibling("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("reviewed Cubism evidence is unavailable");
    }
}
