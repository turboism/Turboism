package dev.turboism.preview;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Narrow parent-boundary filter for external plugin classloaders. Plugin loaders delegate
 * parent-first and the whole agent fat JAR — runtime implementation, adapters, management
 * contracts, shell UI and relocated private libraries — rides one Boot-Class-Path entry,
 * so without this filter a plugin could link against or read any class/resource on it.
 * The boundary therefore mirrors {@code dev.turboism.core.event.SdkContractParent} and
 * works from an allow-list instead of a prefix deny-list:
 *
 * <ul>
 *   <li>{@code dev.turboism.sdk.*} and {@code dev.turboism.protocol.*} resolve normally —
 *       the plugin-facing framework surface. Every other {@code dev.turboism.*} name is
 *       implementation-internal and refused before delegation;</li>
 *   <li>every other name resolves through the parent <em>and</em> is accepted only when
 *       it belongs to a named {@code java.*}/{@code jdk.*} module. Classpath and
 *       boot-classpath classes — host {@code com.live2d.*}/{@code jp.noids.*} types in
 *       non-bootstrap layouts, test classpath, unrelocated library copies — never pass.</li>
 * </ul>
 *
 * <p>Resource lookups filter agent-JAR internals by name so bundled verification records
 * and internal metadata stay unreadable to plugins. {@code META-INF/services/} lookups
 * are hidden as well: the agent JAR carries classpath SPI registrations whose provider
 * classes are implementation-internal, so letting the parent enumerate them would both
 * leak the registrations and break {@link java.util.ServiceLoader} iteration inside
 * plugins. A plugin's own service files resolve through the child loader's
 * {@code findResources}, and named-module services resolve through the module system
 * rather than classpath resources. Plugin classes never resolve through this loader —
 * they come from the child loader's own JAR — and the runtime-owned shell is constructed
 * on the application classpath by the composition's shell admission, so its contract
 * access is unaffected.
 */
final class PluginParentBoundary extends ClassLoader {
    private static final String[] ALLOWED_FRAMEWORK_PREFIXES = {
        "dev.turboism.sdk.",
        "dev.turboism.protocol."
    };

    private PluginParentBoundary(final ClassLoader delegate) {
        super(delegate);
    }

    static ClassLoader denyImplementationNamespaces(final ClassLoader delegate) {
        return new PluginParentBoundary(delegate);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
        if (name.startsWith("dev.turboism.")) {
            if (allowedFrameworkName(name)) {
                return super.loadClass(name, resolve);
            }
            throw new ClassNotFoundException(
                name + " is implementation-internal, not a plugin-facing API"
            );
        }
        final Class<?> candidate = super.loadClass(name, resolve);
        final Module module = candidate.getModule();
        if (module == null || !isJdkModule(module)) {
            throw new ClassNotFoundException(
                name + " is outside the JDK platform modules and the plugin-facing SDK"
            );
        }
        return candidate;
    }

    @Override
    public URL getResource(final String name) {
        if (deniedResourceName(name)) {
            return null;
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(final String name) throws IOException {
        if (deniedResourceName(name)) {
            return Collections.emptyEnumeration();
        }
        return super.getResources(name);
    }

    private static boolean allowedFrameworkName(final String name) {
        for (final String allowed : ALLOWED_FRAMEWORK_PREFIXES) {
            if (name.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJdkModule(final Module module) {
        return module.isNamed()
            && (module.getName().startsWith("java.")
                || module.getName().startsWith("jdk."));
    }

    private static boolean deniedResourceName(final String name) {
        final String path = name.startsWith("/") ? name.substring(1) : name;
        if (path.startsWith("dev/turboism/")) {
            return !(path.startsWith("dev/turboism/sdk/")
                || path.startsWith("dev/turboism/protocol/"));
        }
        return path.startsWith("com/live2d/")
            || path.startsWith("jp/noids/")
            || path.startsWith("META-INF/turboism/")
            || path.startsWith("META-INF/services/");
    }
}
