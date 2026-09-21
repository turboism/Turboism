package dev.turboism.bootstrap;

import java.lang.reflect.Constructor;

/**
 * Child-process regression for the restricted public event contract parent loader.
 * Runs under {@code -javaagent:turboism-agent.jar}, so the whole agent (runtime,
 * core plugin, shaded dependencies) sits on the boot classpath in the unnamed
 * module — exactly the escape a bare platform-classloader delegation would allow.
 *
 * <p>Asserts that {@code dev.turboism.core.event.SdkContractParent}:</p>
 * <ul>
 *   <li>blocks agent/runtime/core-plugin classes that ARE bootstrap-visible;</li>
 *   <li>preserves the shared SDK {@link Class} identity for {@code dev.turboism.sdk.*};</li>
 *   <li>still resolves genuine JDK platform-module classes ({@code jdk.httpserver},
 *       {@code java.xml}).</li>
 * </ul>
 */
public final class ContractParentVisibilityMain {

    private ContractParentVisibilityMain() { }

    public static void main(final String[] args) throws Exception {
        final Class<?> sdk = Class.forName("dev.turboism.sdk.event.EventBus");
        final Class<?> filter =
            Class.forName("dev.turboism.core.event.SdkContractParent");
        final Constructor<?> constructor =
            filter.getDeclaredConstructor(ClassLoader.class);
        constructor.setAccessible(true);
        final ClassLoader parent =
            (ClassLoader) constructor.newInstance(sdk.getClassLoader());

        for (final String name : new String[] {
            "dev.turboism.core.event.RuntimeEventBroker",
            "dev.turboism.internal.core.CorePluginManagement",
            "dev.turboism.plugin.core.MainToolbarPlugin",
            "dev.turboism.bootstrap.TurboismAgent",
            "dev.turboism.agent.shaded.jackson.databind.ObjectMapper"
        }) {
            Class.forName(name, false, null); // prove the class exists on bootstrap
            try {
                parent.loadClass(name);
                throw new AssertionError("contract parent exposed " + name);
            } catch (ClassNotFoundException expected) {
                System.out.println(name + " => BLOCKED");
            }
        }

        if (parent.loadClass(sdk.getName()) != sdk) {
            throw new AssertionError("SDK identity split through the contract parent");
        }
        System.out.println(sdk.getName() + " => IDENTITY");

        for (final String name : new String[] {
            "com.sun.net.httpserver.HttpServer",
            "org.w3c.dom.Node"
        }) {
            final Class<?> type = parent.loadClass(name);
            final Module module = type.getModule();
            if (!module.isNamed()
                || !(module.getName().startsWith("java.")
                    || module.getName().startsWith("jdk."))) {
                throw new AssertionError(name + " resolved outside JDK platform modules");
            }
            System.out.println(name + " => VISIBLE module=" + module.getName());
        }
        System.out.println("PASS: contract parent is closed under the real agent");
    }
}
