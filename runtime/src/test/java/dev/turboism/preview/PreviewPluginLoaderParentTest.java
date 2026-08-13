package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Focused regression for the bootstrap-loaded SDK parent selection: when
 * {@code TurboismPlugin} is loaded by the agent Boot-Class-Path its class
 * loader is null, and the plugin URLClassLoader parent must then be the JDK
 * platform class loader so JDK platform modules stay visible.
 */
class PreviewPluginLoaderParentTest {

    @Test
    void nullSdkLoaderSelectsPlatformClassLoader() {
        assertSame(
            ClassLoader.getPlatformClassLoader(),
            PreviewPluginLoader.resolvePluginParent(null)
        );
    }

    @Test
    void nonNullSdkLoaderIsReturnedUnchanged() {
        final ClassLoader sdk = PreviewPluginLoaderParentTest.class.getClassLoader();
        assertNotNull(sdk);
        assertSame(sdk, PreviewPluginLoader.resolvePluginParent(sdk));
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
}
