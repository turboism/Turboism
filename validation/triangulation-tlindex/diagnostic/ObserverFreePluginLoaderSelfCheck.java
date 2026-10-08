package dev.turboism.preview;

import dev.turboism.core.event.PublicEventContractCatalog;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;

/** Uses the exact production JAR's inspector and plugin loader; no Cubism startup. */
public final class ObserverFreePluginLoaderSelfCheck {
    private static int checks;
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Class<?> inspector = Class.forName("dev.turboism.distribution.PluginJarInspector");
        var limits = inspector.getDeclaredField("LIMITS"); limits.setAccessible(true);
        var policy = Class.forName("dev.turboism.distribution.PluginPathPolicy").getDeclaredField("ARCHIVE");
        policy.setAccessible(true);
        try (var archive = dev.turboism.core.archive.StrictZipArchive.open(jar,
                (dev.turboism.core.archive.StrictZipArchive.Limits) limits.get(null),
                (dev.turboism.core.archive.ArchivePathPolicy) policy.get(null))) {
            require(!archive.entries().isEmpty(), "exact production strict ZIP parser");
        } catch (dev.turboism.core.archive.ArchiveStructureException failure) {
            System.err.println("STRICT_ARCHIVE_REFUSED code=" + failure.code() + " path=" + failure.problemPath());
            throw failure;
        }
        var constructor = inspector.getDeclaredConstructor(); constructor.setAccessible(true);
        var inspect = inspector.getDeclaredMethod("inspect", Path.class, String.class); inspect.setAccessible(true);
        Object inspected = inspect.invoke(constructor.newInstance(), jar, "observer-free-mesh-probe.jar");
        var descriptorMethod = inspected.getClass().getDeclaredMethod("descriptor");
        descriptorMethod.setAccessible(true);
        Object descriptor = descriptorMethod.invoke(inspected);
        require(descriptor.getClass().getMethod("id").invoke(descriptor)
                .equals("dev.turboism.validation.observerfreemesh"), "real inspector metadata");
        try (var loader = new PluginContractClassLoader(new URL[] {jar.toUri().toURL()},
                PreviewPluginLoader.resolvePluginParent(TurboismPlugin.class.getClassLoader()),
                PublicEventContractCatalog.ContractLease.empty())) {
            Class<?> plugin = Class.forName("dev.turboism.validation.atlasimage.shadow.ObserverFreeMeshProbePlugin",
                    true, loader);
            require(plugin.getClassLoader() == loader && TurboismPlugin.class.isAssignableFrom(plugin),
                    "actual plugin/SDK loader identity");
            require(plugin.getDeclaredConstructor().newInstance() instanceof TurboismPlugin,
                    "plugin entrypoint instantiation");
            for (String denied : new String[] {"dev.turboism.agent.shaded.jackson.databind.ObjectMapper",
                    "dev.turboism.validation.atlasimage.shadow.MeshProducerRecorder",
                    "dev.turboism.validation.atlasimage.shadow.MeshProducerWeave"}) {
                try { loader.loadClass(denied); throw new AssertionError("forbidden dependency loaded"); }
                catch (ClassNotFoundException expected) { checks++; }
            }
            Class<?> driver = loader.loadClass("dev.turboism.validation.atlasimage.shadow.T040ShadowSceneDriverAgent");
            for (var method : driver.getDeclaredMethods())
                require(!method.getName().equals("premain") && !method.getName().equals("agentmain"),
                        "no agent entrypoint");
            System.clearProperty("turboism.validation.observerFree.optIn");
            try {
                driver.getMethod("startFromPlugin").invoke(null);
                throw new AssertionError("unapproved plugin startup accepted");
            } catch (InvocationTargetException expected) {
                require(expected.getCause() instanceof IllegalStateException
                        && expected.getCause().getCause() instanceof IllegalArgumentException,
                        "missing opt-in refuses before native access/thread startup");
            }
            System.setProperty("turboism.validation.observerFree.optIn", "T076_OBSERVER_FREE_COMMAND_BOUNDARY_V1");
            try {
                driver.getMethod("startFromPlugin").invoke(null);
                throw new AssertionError("second plugin startup accepted");
            } catch (InvocationTargetException expected) {
                require(expected.getCause() instanceof IllegalStateException
                        && expected.getCause().getMessage().equals("observer-free plugin already started"),
                        "one-shot plugin startup");
            } finally {
                System.clearProperty("turboism.validation.observerFree.optIn");
            }
        }
        System.out.println("ObserverFreePluginLoaderSelfCheck PASS checks=" + checks);
    }
}
