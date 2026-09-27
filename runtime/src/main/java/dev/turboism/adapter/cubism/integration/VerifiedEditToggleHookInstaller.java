package dev.turboism.adapter.cubism.integration;

import dev.turboism.mapping.verification.selector.EditorIntegrationSettingsDialogSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the re-injection seam for the native 「编辑」 edit checkbox: an epilogue on the
 * host dialog's private build/reuse method
 * {@code com.live2d.cubism.doc.webSocket.y.b(owner)} (spec 051, Phase 2).
 *
 * <p>The target is the exact {@code private final void b(X|V)} evidenced for
 * 5.2.03/5.3.02/5.3.03 in {@code host-evidence/native-edit-toggle/
 * native-toggle-internals.md} §12.1: its return runs on the EDT after the row container is
 * (re)built and before the modal show call blocks, so the installed ingress —
 * {@link NativeEditToggleInjector#ensureInjectedOnEdt()} — can (re)install the checkbox in
 * time for every dialog open. The public {@code y.a(owner)} entry is deliberately not the
 * seam: its modal show blocks until the dialog closes.</p>
 *
 * <p>Admission is fail-closed: the resolver must carry the reviewed build-method selector for
 * the exact running version under the edit-toggle capability. Without it
 * {@link #fromVerifiedResolver} refuses and the feature stays inert — the native dialog is
 * byte-for-byte untouched, which is also the safe-mode behaviour for any install failure.</p>
 *
 * <p>Kill switch: {@value NativeEditToggleInjector#ENABLED_PROPERTY}{@code =false} skips
 * installation; the transformer is never registered and the host class keeps its original
 * bytes.</p>
 */
public final class VerifiedEditToggleHookInstaller implements AutoCloseable {

    /** System-property key holding the loader-neutral ingress the injected code calls. */
    public static final String INGRESS_KEY =
        "dev.turboism.integration.edit-toggle.show-ingress";

    private static final String ADAPTER_SLICE_ID =
        EditorIntegrationSettingsDialogSelectorContract.ADAPTER_SLICE_ID;
    private static final String CAPABILITY_ID =
        EditorIntegrationSettingsDialogSelectorContract.EDIT_TOGGLE_CAPABILITY_ID;
    private static final String BUILD_HOOK_ALIAS =
        EditorIntegrationSettingsDialogSelectorContract.BUILD_HOOK_ALIAS;

    private static final String BUILD_OWNER = "com/live2d/cubism/doc/webSocket/y";
    private static final String BUILD_NAME = "b";
    private static final String BUILD_DESCRIPTOR_5203 = "(Lcom/live2d/ui/window/X;)V";
    private static final String BUILD_DESCRIPTOR_530X = "(Lcom/live2d/ui/window/V;)V";
    private static final int BUILD_REQUIRED_ACCESS = 0x0012; // ACC_PRIVATE | ACC_FINAL

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final NativeEditToggleShowTransformer transformer;
    private final List<Class<?>> transformed = new ArrayList<>();
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private VerifiedEditToggleHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final StaticSelector build
    ) {
        this.instrumentation = instrumentation;
        this.hostClassLoader = hostClassLoader;
        this.transformer = new NativeEditToggleShowTransformer(
            build.ownerInternalName(),
            build.memberName(),
            build.descriptor(),
            hostClassLoader,
            INGRESS_KEY
        );
    }

    /**
     * Builds an installer for the exact Cubism version the resolver admits.
     *
     * @param instrumentation the JVM instrumentation used to transform the host class
     * @param resolver        the verified member resolver for the running Cubism version
     * @param hostClassLoader the loader that owns the host classes to transform
     * @return a configured, not-yet-installed installer
     * @throws IllegalArgumentException if the host version is unsupported, the capability or
     *                                  alias is not authorized, or the selector is not the
     *                                  exact reviewed build method
     */
    public static VerifiedEditToggleHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        final boolean is5203 = verified.isExactCubismVersion("5.2.03");
        if (!is5203
            && !verified.isExactCubismVersion("5.3.02")
            && !verified.isExactCubismVersion("5.3.03")) {
            throw new IllegalArgumentException(
                "Edit-toggle dialog hook version is unsupported.");
        }
        if (!verified.authorizesFeature(
            ADAPTER_SLICE_ID, CAPABILITY_ID, Set.of(BUILD_HOOK_ALIAS))) {
            throw new IllegalArgumentException(
                "Edit-toggle dialog hook is not authorized.");
        }
        final StaticSelector selector = verified.verifiedSelector(BUILD_HOOK_ALIAS);
        final String expectedDescriptor =
            is5203 ? BUILD_DESCRIPTOR_5203 : BUILD_DESCRIPTOR_530X;
        if (selector.kind() != StaticSelector.Kind.METHOD
            || !BUILD_OWNER.equals(selector.ownerInternalName())
            || !BUILD_NAME.equals(selector.memberName())
            || !expectedDescriptor.equals(selector.descriptor())
            || selector.requiredAccessFlags() != BUILD_REQUIRED_ACCESS
            || (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException(
                "Verified edit-toggle build selector is invalid: " + BUILD_HOOK_ALIAS);
        }
        return new VerifiedEditToggleHookInstaller(
            Objects.requireNonNull(instrumentation, "instrumentation"),
            Objects.requireNonNull(hostClassLoader, "hostClassLoader"),
            selector);
    }

    /**
     * Publishes the ingress and instruments the dialog build method.
     *
     * <p>Idempotent. When the kill switch is off the install is skipped and the transformer is
     * never registered, so the host class keeps its original bytes. The transformer is added
     * retransform-capable so it applies whether {@code y} is already loaded or loads later;
     * a failure during retransformation closes the installer so nothing is left
     * half-installed.</p>
     *
     * @param ingress the loader-neutral callback the injected epilogue runs; typically
     *                {@link NativeEditToggleInjector#ensureInjectedOnEdt} — it must never
     *                throw, though the injected call site also swallows any {@code Throwable}
     * @return {@code true} when the hook was installed, {@code false} when disabled
     * @throws Exception if transforming the host class fails
     */
    public boolean install(final Runnable ingress) throws Exception {
        Objects.requireNonNull(ingress, "ingress");
        if ("false".equalsIgnoreCase(
            System.getProperty(NativeEditToggleInjector.ENABLED_PROPERTY))) {
            return false;
        }
        if (!installed.compareAndSet(false, true)) return true;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        try {
            instrumentation.addTransformer(transformer, true);
            System.getProperties().put(INGRESS_KEY, ingress);
            retransform(BUILD_OWNER.replace('/', '.'));
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
                    "Edit-toggle dialog hook transformation failed: " + className,
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
    public NativeEditToggleShowTransformer.Outcome outcome() {
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
     * <p>Empty is legitimate while {@code y} has not been loaded; the registered transformer
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
        System.getProperties().remove(INGRESS_KEY);
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
                    "Edit-toggle dialog hook restoration failed: " + loaded.getName(),
                    failure
                );
            }
        }
    }
}
