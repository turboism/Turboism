package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused regression for the bootstrap-loaded SDK parent selection and the internal
 * implementation boundary: when {@code TurboismPlugin} is loaded by the agent
 * Boot-Class-Path its class loader is null, and the plugin URLClassLoader parent must
 * then delegate to the JDK platform class loader so JDK platform modules stay visible.
 * The returned parent is the {@link PluginParentBoundary} wrapper — delegation behavior,
 * not identity, is asserted.
 */
class PreviewPluginLoaderParentTest {

    @Test
    void nullSdkLoaderDelegatesToPlatformClassLoader() {
        assertSame(
            ClassLoader.getPlatformClassLoader(),
            PreviewPluginLoader.resolvePluginParent(null).getParent()
        );
    }

    @Test
    void nonNullSdkLoaderRemainsTheDelegate() {
        final ClassLoader sdk = PreviewPluginLoaderParentTest.class.getClassLoader();
        assertNotNull(sdk);
        assertSame(sdk, PreviewPluginLoader.resolvePluginParent(sdk).getParent());
    }

    @Test
    void nullSelectedParentLoadsJdkPlatformClass() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(null)
        )) {
            // Real class resolution through the selected parent, not a mock
            final Class<?> server = Class.forName(
                "com.sun.net.httpserver.HttpServer",
                false,
                loader
            );
            assertNotNull(server);
            assertSame(server, loader.loadClass("com.sun.net.httpserver.HttpServer"));
        }
    }

    @Test
    void internalManagementContractsAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.internal.core.CorePluginManagement"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.internal.core.CorePluginServices"));
        }
    }

    @Test
    void coreUiAndShadedAgentNamespacesAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.plugin.core.MainToolbarPlugin"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.agent.shaded.jackson.databind.ObjectMapper"));
        }
    }

    @Test
    void sdkIdentityIsPreservedThroughTheBoundary() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            // A plugin linking the SDK type must get the exact same Class identity the
            // runtime uses — the boundary only denies implementation namespaces.
            assertSame(
                dev.turboism.sdk.plugin.TurboismPlugin.class,
                loader.loadClass("dev.turboism.sdk.plugin.TurboismPlugin")
            );
        }
    }
}
