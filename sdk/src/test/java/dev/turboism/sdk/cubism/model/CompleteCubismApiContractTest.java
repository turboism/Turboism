package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocInfo;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.permission.PermissionIds;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompleteCubismApiContractTest {

    @Test
    void approvedInterfaceAdditionsRemainDefaultAndSourceCompatible() throws Exception {
        assertDefault(CubismFacade.class, "coreRuntime");
        for (String method : List.of("name", "setName", "mocInfo", "parameterDefinitions", "statistics")) {
            assertDefault(CubismModel.class, method);
        }
        for (String method : List.of("index", "keyValues")) assertDefault(Parameter.class, method);
        for (String method : List.of(
            "index", "shortName", "setShortName", "parentId", "childIds", "visible",
            "setVisible", "visibleInHierarchy", "locked", "setLocked", "lockedInHierarchy",
            "editColor", "setEditColor", "sketch", "setSketch", "defaultOrder", "setDefaultOrder"
        )) assertDefault(Part.class, method);
        for (String method : List.of(
            "index", "doubleSided", "evaluationState", "parentPartId",
            "parentDeformerId", "parameterIds", "maskIds"
        )) assertDefault(Drawable.class, method);
        for (String method : List.of("index", "parentPartId", "parentDeformerId", "parameterIds")) {
            assertDefault(Deformer.class, method);
        }
        for (String method : List.of("index", "drawableAId", "drawableBId", "parameterIds")) {
            assertDefault(Glue.class, method);
        }
    }

    @Test
    void coreValuesAreNormalizedAndDefensivelyCopied() {
        assertEquals("5.3.2", new CoreVersion(5, 3, 2).toString());
        assertThrows(IllegalArgumentException.class, () -> new CoreVersion(-1, 0, 0));
        assertEquals(new CoreCapabilities(true, true, true), new CoreCapabilities(true, true, true));
        assertEquals(MocVersion.V5_3, new MocInfo(MocVersion.V5_3, MocConsistency.CONSISTENT).version());
        assertThrows(NullPointerException.class, () -> new MocInfo(null, MocConsistency.UNKNOWN));

        final byte[] source = {1, 2, 3};
        final MocData data = MocData.copyOf(source);
        source[0] = 9;
        final byte[] first = data.toByteArray();
        first[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, data.toByteArray());
        assertEquals(3, data.size());
        assertThrows(IllegalArgumentException.class, () -> MocData.copyOf(new byte[0]));
    }

    @Test
    void servicesAndPermissionsUseTheApprovedShape() throws Exception {
        assertTrue(CoreRuntimeInfo.class.isInterface());
        assertTrue(MocInspector.class.isInterface());
        assertTrue(ParameterDefinitions.class.isInterface());
        assertEquals(CoreVersion.class, CoreRuntimeInfo.class.getMethod("version").getReturnType());
        assertEquals(MocInfo.class, MocInspector.class.getMethod("inspect", MocData.class).getReturnType());
        assertEquals("turboism.cubism.model.read", PermissionIds.TURBOISM_CUBISM_MODEL_READ);
        assertEquals("turboism.cubism.model.write", PermissionIds.TURBOISM_CUBISM_MODEL_WRITE);
    }

    private static void assertDefault(final Class<?> owner, final String name) {
        final Method method = java.util.Arrays.stream(owner.getMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow();
        assertTrue(method.isDefault(), () -> owner.getName() + "#" + name + " must be default");
    }
}
