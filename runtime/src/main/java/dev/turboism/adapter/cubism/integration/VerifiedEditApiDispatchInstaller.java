package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.selector.EditorIntegrationWebSocketSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * Installs the single interception point for the 5.4-style editing protocol on low-version
 * hosts.
 *
 * <p>The target is the exact private dispatcher entry
 * {@code com.live2d.cubism.doc.webSocket.l.a(String, org.java_websocket.WebSocket)}
 * (flags {@code 0x0012}), evidenced for 5.2.03/5.3.02/5.3.03 in
 * {@code host-evidence/integration-54compat/dispatcher-internals.md}. Nothing else in the
 * host's webSocket package is touched: not {@code U.onMessage}, not the JSONIC decode sites,
 * not the version tables.</p>
 *
 * <p>Admission is fail-closed: the resolver must carry the reviewed dispatch-entry selector for
 * the exact running version. Until the mapping candidate lands in a verified record,
 * {@link #fromVerifiedResolver} refuses and the feature stays inert — every message then flows
 * through the native path, which is also the safe-mode behaviour for any install failure.</p>
 *
 * <p>Kill switch: {@value #ENABLED_PROPERTY}{@code =false} skips installation and, at runtime,
 * makes the published bridge answer {@code false} for every message. The transformer's injected
 * prologue additionally degrades to native behaviour whenever the receiver is absent or
 * throws.</p>
 */
public final class VerifiedEditApiDispatchInstaller implements AutoCloseable {

    /** System-property key holding the loader-neutral receiver the injected code calls. */
    public static final String CALLBACK_KEY = EditProtocolBridge.RECEIVER_PROPERTY;

    /** Kill switch honored by both {@link #install} and {@link EditProtocolBridge}. */
    public static final String ENABLED_PROPERTY = EditProtocolBridge.ENABLED_PROPERTY;

    private static final String ADAPTER_SLICE_ID =
        EditorIntegrationWebSocketSelectorContract.ADAPTER_SLICE_ID;
    private static final String CAPABILITY_ID =
        EditorIntegrationWebSocketSelectorContract.DISPATCH_CAPABILITY_ID;
    private static final String DISPATCH_ENTRY_ALIAS =
        "cubism.integration.websocket.dispatch.on-message";

    private static final String DISPATCH_OWNER = "com/live2d/cubism/doc/webSocket/l";
    private static final String DISPATCH_NAME = "a";
    private static final String DISPATCH_DESCRIPTOR =
        "(Ljava/lang/String;Lorg/java_websocket/WebSocket;)V";
    private static final int DISPATCH_REQUIRED_ACCESS = 0x0012; // ACC_PRIVATE | ACC_FINAL

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final StaticSelector entry;
    private final EditApiDispatchTransformer transformer;
    private final List<Class<?>> transformed = new ArrayList<>();
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private VerifiedEditApiDispatchInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final StaticSelector entry
    ) {
        this.instrumentation = instrumentation;
        this.hostClassLoader = hostClassLoader;
        this.entry = entry;
        this.transformer = new EditApiDispatchTransformer(
            entry.ownerInternalName(),
            entry.memberName(),
            entry.descriptor(),
            hostClassLoader,
            CALLBACK_KEY
        );
    }

    /**
     * Builds an installer for the exact Cubism version the resolver admits.
     *
     * <p>The version must be one whose artifacts carry the reviewed dispatcher evidence
     * (5.2.03/5.3.02/5.3.03 — 5.4 hosts serve the editing API natively and are deliberately
     * excluded), the bridge capability and the dispatch-entry alias must be authorized, and the
     * selector must resolve to the exact private {@code a(String, WebSocket)} member.</p>
     *
     * @param instrumentation the JVM instrumentation used to transform the host class
     * @param resolver        the verified member resolver for the running Cubism version
     * @param hostClassLoader the loader that owns the host classes to transform
     * @return a configured, not-yet-installed installer
     * @throws IllegalArgumentException if the host version is unsupported, the capability or
     *                                  alias is not authorized, or the selector is not the
     *                                  exact reviewed member
     */
    public static VerifiedEditApiDispatchInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        if (!verified.isExactCubismVersion("5.2.03")
            && !verified.isExactCubismVersion("5.3.02")
            && !verified.isExactCubismVersion("5.3.03")) {
            throw new IllegalArgumentException(
                "Edit-protocol dispatch hook version is unsupported.");
        }
        if (!verified.authorizesFeature(
            ADAPTER_SLICE_ID, CAPABILITY_ID, Set.of(DISPATCH_ENTRY_ALIAS))) {
            throw new IllegalArgumentException(
                "Edit-protocol dispatch hook is not authorized.");
        }
        final StaticSelector selector = verified.verifiedSelector(DISPATCH_ENTRY_ALIAS);
        if (selector.kind() != StaticSelector.Kind.METHOD
            || !DISPATCH_OWNER.equals(selector.ownerInternalName())
            || !DISPATCH_NAME.equals(selector.memberName())
            || !DISPATCH_DESCRIPTOR.equals(selector.descriptor())
            || selector.requiredAccessFlags() != DISPATCH_REQUIRED_ACCESS
            || (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException(
                "Verified edit-protocol dispatch selector is invalid: " + DISPATCH_ENTRY_ALIAS);
        }
        return new VerifiedEditApiDispatchInstaller(
            Objects.requireNonNull(instrumentation, "instrumentation"),
            Objects.requireNonNull(hostClassLoader, "hostClassLoader"),
            selector);
    }

    /**
     * Publishes the receiver and instruments the dispatcher entry.
     *
     * <p>Idempotent. When the kill switch is off the install is skipped and the transformer is
     * never registered, so the host class keeps its original bytes. The transformer is added
     * retransform-capable so it applies whether {@code l} is already loaded or loads later;
     * a failure during retransformation closes the installer so nothing is left half-installed.</p>
     *
     * @param receiver the loader-neutral receiver the injected code calls; typically
     *                 {@link EditProtocolBridge#receiver()}
     * @return {@code true} when the hook was installed, {@code false} when disabled
     * @throws Exception if transforming the host class fails
     */
    public boolean install(final BiFunction<Object, Object, Object> receiver) throws Exception {
        Objects.requireNonNull(receiver, "receiver");
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) return false;
        if (!installed.compareAndSet(false, true)) return true;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        try {
            instrumentation.addTransformer(transformer, true);
            System.getProperties().put(CALLBACK_KEY, receiver);
            retransform(DISPATCH_OWNER.replace('/', '.'));
        } catch (Throwable failure) {
            close();
            throw failure;
        }
        return true;
    }

    private void retransform(final String className) {
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!loaded.getName().equals(className)
                || loaded.getClassLoader() != hostClassLoader
                || !instrumentation.isModifiableClass(loaded)) {
                continue;
            }
            try {
                instrumentation.retransformClasses(loaded);
            } catch (Exception failure) {
                throw new IllegalStateException(
                    "Edit-protocol dispatch hook transformation failed: " + className,
                    failure
                );
            }
            synchronized (transformed) {
                transformed.add(loaded);
            }
            return;
        }
        // Not loaded yet: the registered transformer still applies at first definition.
    }

    /** {@return the transformer's latest outcome; diagnostics and tests} */
    public EditApiDispatchTransformer.Outcome outcome() {
        return transformer.outcome();
    }

    /** {@return transformer detail for a non-clean outcome, or the empty string} */
    public String diagnostic() {
        return transformer.diagnostic();
    }

    /** {@return whether the hook is currently installed} */
    public boolean isInstalled() {
        return installed.get();
    }

    /**
     * {@return the binary names of classes retransformed at install time}
     *
     * <p>Empty is legitimate while {@code l} has not been loaded; the registered transformer
     * still applies to the later definition.</p>
     */
    public List<String> transformedClassNames() {
        synchronized (transformed) {
            return transformed.stream().map(Class::getName).toList();
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        instrumentation.removeTransformer(transformer);
        System.getProperties().remove(CALLBACK_KEY);
        final List<Class<?>> restore;
        synchronized (transformed) {
            restore = List.copyOf(transformed);
            transformed.clear();
        }
        for (final Class<?> loaded : restore) {
            if (!instrumentation.isModifiableClass(loaded)) continue;
            try {
                instrumentation.retransformClasses(loaded);
            } catch (Exception failure) {
                throw new IllegalStateException(
                    "Edit-protocol dispatch hook restoration failed: " + loaded.getName(),
                    failure
                );
            }
        }
    }
}
