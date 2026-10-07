package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Focused regression for the bootstrap-loaded SDK parent selection and the internal
 * implementation boundary: when {@code TurboismPlugin} is loaded by the agent
 * Boot-Class-Path its class loader is null, and the plugin URLClassLoader parent must
 * then delegate to the JDK platform class loader so JDK platform modules stay visible.
 * The returned parent is the {@link PluginParentBoundary} wrapper — delegation behavior,
 * not identity, is asserted.
 *
 * <p>The boundary is an allow-list: {@code dev.turboism.sdk.*} and
 * {@code dev.turboism.protocol.*} resolve, every other {@code dev.turboism.*} name —
 * including every runtime implementation namespace the fat agent JAR exposes on
 * Boot-Class-Path — is refused, and agent-JAR resource lookups are filtered the same
 * way so bundled verification records stay unreadable to plugins.
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
                "dev.turboism.internal.core.ShellServices"));
        }
    }

    @Test
    void shellAndShadedAgentNamespacesAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            // The class genuinely exists on the test classpath — the boundary, not the
            // classpath, is what keeps it away from external plugins.
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.shell.CoreShell"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.plugin.core.MainToolbarPlugin"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.agent.shaded.jackson.databind.ObjectMapper"));
        }
    }

    @Test
    void runtimeImplementationNamespacesAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            // Public runtime classes that really exist on the test classpath: the deny
            // comes from the boundary, not from an absent type.
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.core.event.PluginEventBus"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.adapter.RuntimeHostAdapters"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.userfile.RuntimeUserFileAccessService"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "dev.turboism.preview.PreviewPluginLoader"));
        }
    }

    @Test
    void hostNamespacesAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "com.live2d.cubism.editor.CubismEditor"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "jp.noids.util.UtCache"));
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

    @Test
    void protocolIdentityIsPreservedThroughTheBoundary() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            assertSame(
                dev.turboism.protocol.json.StrictJson.class,
                loader.loadClass("dev.turboism.protocol.json.StrictJson")
            );
        }
    }

    @Test
    void pluginClassesUnderDeniedNamespacesStillLoadFromOwnJar() throws Exception {
        // A plugin may legitimately own classes under dev.turboism.plugin.*: the
        // boundary CNFE must fall through to the child loader's findClass, never
        // preempt it. This test class is itself a dev.turboism.* type, so loading it
        // through a child over the same classes root exercises that exact path.
        final URL classesRoot = PreviewPluginLoaderParentTest.class
            .getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{classesRoot},
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            final Class<?> pluginOwned = loader.loadClass(
                "dev.turboism.preview.PreviewPluginLoaderParentTest");
            assertEquals(
                PreviewPluginLoaderParentTest.class.getName(),
                pluginOwned.getName()
            );
            // Resolved by the child's own findClass, not shared through the parent.
            assertNotSame(PreviewPluginLoaderParentTest.class, pluginOwned);
        }
    }

    @Test
    void classpathClassesOutsideJdkModulesAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            // Resolves through the parent on the test classpath (unnamed module), so
            // the java.*/jdk.* named-module gate is what refuses it — exactly what
            // keeps host classes and unrelocated copies off a plugin's classpath.
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(
                "org.junit.jupiter.api.Test"));
        }
    }

    @Test
    void sdkResourcesResolveButInternalResourcesAreDenied() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[0],
            PreviewPluginLoader.resolvePluginParent(
                PreviewPluginLoaderParentTest.class.getClassLoader())
        )) {
            assertNotNull(loader.getResource(
                "dev/turboism/sdk/plugin/TurboismPlugin.class"));
            assertNotNull(loader.getResource(
                "dev/turboism/protocol/json/StrictJson.class"));

            assertNull(loader.getResource(
                "dev/turboism/core/event/PluginEventBus.class"));
            assertNull(loader.getResource(
                "dev/turboism/adapter/RuntimeHostAdapters.class"));
            assertNull(loader.getResource(
                "META-INF/turboism/verification/"));
            assertNull(loader.getResource(
                "META-INF/turboism/plugin.json"));

            assertEquals(
                List.of(),
                Collections.list(loader.getResources(
                    "dev/turboism/core/event/PluginEventBus.class"))
            );
        }
    }
}
