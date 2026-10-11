package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.CubismEditor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ArtMeshMorphKeyformContractTest {
    @Test
    void additiveDrawableEntriesRemainDefaultUnsupportedForPreviousImplementations() throws Exception {
        final Drawable previous = (Drawable) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Drawable.class}, (proxy, method, args) -> {
                    if (!method.isDefault())
                        throw new AssertionError("unexpected older implementation call: " + method);
                    return InvocationHandler.invokeDefault(proxy, method, args);
                });
        assertThrows(UnsupportedOperationException.class, () -> previous.morphKeyform("m", .5f));
        assertThrows(UnsupportedOperationException.class, () -> previous.createMorphKeyform("m", 1f));
        for (String name : Set.of("morphKeyform", "createMorphKeyform")) {
            final var method = Drawable.class.getMethod(name, String.class, float.class);
            assertTrue(method.isDefault());
            assertEquals(ArtMeshMorphKeyform.class, method.getReturnType());
            assertArrayEquals(
                    new String[] {"5.3.02", "5.3.03"},
                    method.getAnnotation(CubismEditor.class).value());
        }
    }

    @Test
    void setDrawOrderRemainsDefaultUnsupportedForPreviousImplementations() throws Exception {
        final ArtMeshMorphKeyform previous = (ArtMeshMorphKeyform) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ArtMeshMorphKeyform.class}, (proxy, method, args) -> {
                    if (!method.isDefault())
                        throw new AssertionError("unexpected older implementation call: " + method);
                    return InvocationHandler.invokeDefault(proxy, method, args);
                });
        assertThrows(UnsupportedOperationException.class, () -> previous.setDrawOrder(700));
        final var method = ArtMeshMorphKeyform.class.getMethod("setDrawOrder", int.class);
        assertTrue(method.isDefault());
        assertArrayEquals(
                new String[] {"5.3.02", "5.3.03"},
                method.getAnnotation(CubismEditor.class).value());
    }

    @Test
    void memberSurfaceIsTypedAndDoesNotExposeNativeUndoOrWritableBase() throws Exception {
        assertArrayEquals(
                new String[] {"5.3.02", "5.3.03"},
                ArtMeshMorphKeyform.class.getAnnotation(CubismEditor.class).value());
        assertEquals(
                Set.of("parameterGuid", "keyValue", "snapshot", "baseSnapshot", "setPositions", "setDrawOrder"),
                Arrays.stream(ArtMeshMorphKeyform.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
        assertEquals(
                String.class,
                ArtMeshMorphKeyform.class.getMethod("parameterGuid").getReturnType());
        assertEquals(
                float.class, ArtMeshMorphKeyform.class.getMethod("keyValue").getReturnType());
        assertEquals(
                ArtMeshAuthoringSnapshot.Form.class,
                ArtMeshMorphKeyform.class.getMethod("baseSnapshot").getReturnType());
        assertEquals(
                void.class,
                ArtMeshMorphKeyform.class
                        .getMethod("setPositions", java.util.List.class)
                        .getReturnType());
        assertEquals(
                void.class,
                ArtMeshMorphKeyform.class.getMethod("setDrawOrder", int.class).getReturnType());
    }
}
