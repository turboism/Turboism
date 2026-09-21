package dev.turboism.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarFile;

/**
 * Child-process check for the distributed agent's private dependency isolation.
 *
 * <p>Runs with {@code -javaagent:turboism-agent.jar}; the host classpath carries a fixture
 * {@code com.fasterxml.jackson.databind.ObjectMapper} (marker "host") and a sibling plugin
 * JAR carries another (marker "plugin"). Before relocation both resolved to the agent's
 * copy loaded by the bootstrap class loader.</p>
 */
public final class BootstrapDependencyIsolationMain {

    private static final String[] FORBIDDEN_ENTRY_PREFIXES = {
        "com/fasterxml/jackson/",
        "org/objectweb/asm/",
        "org/slf4j/",
        "io/github/resilience4j/",
        "io/vavr/"
    };

    private static final String[] REQUIRED_SHADED_ENTRIES = {
        "dev/turboism/agent/shaded/jackson/databind/ObjectMapper.class",
        "dev/turboism/agent/shaded/asm/ClassReader.class",
        "dev/turboism/agent/shaded/slf4j/Logger.class",
        "dev/turboism/agent/shaded/resilience4j/circuitbreaker/CircuitBreaker.class",
        "META-INF/services/dev.turboism.agent.shaded.jackson.core.JsonFactory"
    };

    private BootstrapDependencyIsolationMain() { }

    public static void main(final String[] args) throws Exception {
        final Path agentJar = Path.of(args[0]);
        final Path pluginJar = Path.of(args[1]);
        final Path home = Path.of(args[2]);
        final Path probeAgentJar = Path.of(args[3]);
        checkJarNamespaces(agentJar);
        checkJarNamespaces(probeAgentJar);
        checkHostFixture();
        checkPluginFixture(pluginJar);
        checkSharedSdkIdentity();
        checkBootstrapBridge();
        checkRelocatedRuntimeJson(home);
        checkRelocatedServiceDiscovery();
        System.out.println("dependency isolation probe passed");
    }

    private static void checkJarNamespaces(final Path agentJar) throws Exception {
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            final java.util.List<String> leaked = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .map(java.util.zip.ZipEntry::getName)
                .filter(BootstrapDependencyIsolationMain::isForbiddenEntry)
                .toList();
            if (!leaked.isEmpty()) {
                throw new IllegalStateException(
                    "Agent JAR still exposes private library namespaces: " + leaked
                );
            }
            for (final String entry : REQUIRED_SHADED_ENTRIES) {
                if (jar.getJarEntry(entry) == null) {
                    throw new IllegalStateException(
                        "Agent JAR is missing relocated entry " + entry
                    );
                }
            }
            final java.util.List<String> names = jar.stream()
                .map(java.util.zip.ZipEntry::getName)
                .toList();
            if (names.size() != new java.util.HashSet<>(names).size()) {
                throw new IllegalStateException("Agent JAR contains duplicate entries");
            }
            if (jar.getJarEntry("module-info.class") != null) {
                throw new IllegalStateException("Agent JAR carries a root module-info.class");
            }
        }
    }

    private static boolean isForbiddenEntry(final String name) {
        for (final String prefix : FORBIDDEN_ENTRY_PREFIXES) {
            if (name.startsWith(prefix)
                || name.startsWith("META-INF/versions/") && name.contains("/" + prefix)) {
                return true;
            }
        }
        return name.startsWith("META-INF/services/com.fasterxml.jackson.")
            || name.startsWith("META-INF/services/org.slf4j.");
    }

    private static void checkHostFixture() throws Exception {
        final Class<?> mapper = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
        final String marker = String.valueOf(mapper.getMethod("marker").invoke(null));
        if (!"host".equals(marker)) {
            throw new IllegalStateException(
                "Host fixture ObjectMapper overridden by agent copy (marker=" + marker + ")"
            );
        }
        if (mapper.getClassLoader() == null) {
            throw new IllegalStateException("Host fixture ObjectMapper resolved via bootstrap loader");
        }
    }

    private static void checkPluginFixture(final Path pluginJar) throws Exception {
        try (URLClassLoader plugin = new URLClassLoader(
            new URL[] { pluginJar.toUri().toURL() },
            ClassLoader.getPlatformClassLoader()
        )) {
            final Class<?> mapper = Class.forName(
                "com.fasterxml.jackson.databind.ObjectMapper", true, plugin
            );
            final String marker = String.valueOf(mapper.getMethod("marker").invoke(null));
            if (!"plugin".equals(marker)) {
                throw new IllegalStateException(
                    "Plugin fixture ObjectMapper overridden by agent copy (marker=" + marker + ")"
                );
            }
        }
    }

    private static void checkSharedSdkIdentity() throws Exception {
        final Class<?> sdk = Class.forName("dev.turboism.sdk.plugin.TurboismPlugin");
        if (sdk.getClassLoader() != null) {
            throw new IllegalStateException("SDK type identity is not bootstrap-shared");
        }
    }

    private static void checkBootstrapBridge() throws Exception {
        final Class<?> bridge = Class.forName(
            "dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge",
            false,
            null
        );
        if (bridge.getClassLoader() != null) {
            throw new IllegalStateException("Mesh mirror bridge is not bootstrap-visible");
        }
    }

    private static void checkRelocatedRuntimeJson(final Path home) throws Exception {
        final Class<?> config = Class.forName("dev.turboism.config.RuntimeStartupConfig");
        final Object loaded = config.getMethod("load", Path.class).invoke(null, home);
        final Object safeMode = config.getMethod("safeMode").invoke(loaded);
        if (!Boolean.TRUE.equals(safeMode)) {
            throw new IllegalStateException(
                "Runtime JSON config parsing did not observe safeMode=true "
                    + "(relocated Jackson is not executing correctly)"
            );
        }
        final Class<?> mapperType = Class.forName(
            "dev.turboism.agent.shaded.jackson.databind.ObjectMapper"
        );
        final Object mapper = mapperType.getDeclaredConstructor().newInstance();
        final String json = String.valueOf(
            mapperType.getMethod("writeValueAsString", Object.class)
                .invoke(mapper, Map.of("marker", 42))
        );
        if (!json.contains("\"marker\":42")) {
            throw new IllegalStateException(
                "Relocated ObjectMapper produced unexpected output: " + json
            );
        }
    }

    private static void checkRelocatedServiceDiscovery() throws Exception {
        final Class<?> factory = Class.forName(
            "dev.turboism.agent.shaded.jackson.core.JsonFactory"
        );
        final ServiceLoader<?> services = ServiceLoader.load(factory);
        if (!services.iterator().hasNext()) {
            throw new IllegalStateException(
                "Relocated META-INF/services descriptor for JsonFactory was not discovered"
            );
        }
    }
}
