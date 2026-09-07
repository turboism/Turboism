package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs and retracts the exact verified bounding-box update transformer.
 *
 * <p>Close restoration fresh-discovers the exact owner by binary name and verified
 * host-loader identity after the transformer is removed, so an owner that loaded after
 * installation is retransformed back to its exact original bytes. A loaded but
 * unmodifiable exact owner, a retransform failure, a wrong-loader duplicate, or an
 * inability to prove restoration is a structured cleanup failure; callbacks are made
 * inert before any of that runs.</p>
 */
public final class BoundingBoxOverlayButtonHookInstaller {

    private final Instrumentation instrumentation;

    public BoundingBoxOverlayButtonHookInstaller(final Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
    }

    /**
     * Installs the exact verified update transformer and returns reversible cleanup.
     *
     * @param resolver exact-artifact resolver bound to the active host loader
     * @return registration that deactivates callbacks, removes the transformer, and restores the owner
     */
    public Registration install(final VerifiedMemberResolver resolver) {
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("class retransformation is unavailable");
        }
        final StaticSelector updateSelector = resolver.verifiedSelector(
            "cubism.ui-bounding-box-overlay.bounding-box.update"
        );
        final StaticSelector setupButtonSelector = resolver.verifiedSelector(
            "cubism.ui-bounding-box-overlay.bounding-box.setup-button"
        );
        final StaticSelector vectorTimesSelector = resolver.verifiedSelector(
            "cubism.ui-bounding-box-overlay.vector.times"
        );
        final StaticSelector vectorPlusSelector = resolver.verifiedSelector(
            "cubism.ui-bounding-box-overlay.vector.plus"
        );
        final AtomicBoolean emittedForOwner = new AtomicBoolean();
        final ClassFileTransformer transformer = new BoundingBoxOverlayButtonUpdateTransformer(
            resolver.hostClassLoader(),
            updateSelector,
            setupButtonSelector,
            vectorTimesSelector,
            vectorPlusSelector
        ) {
            @Override
            public byte[] transform(
                final Module module,
                final ClassLoader loader,
                final String className,
                final Class<?> classBeingRedefined,
                final ProtectionDomain protectionDomain,
                final byte[] classfileBuffer
            ) {
                final byte[] candidate = super.transform(
                    module,
                    loader,
                    className,
                    classBeingRedefined,
                    protectionDomain,
                    classfileBuffer
                );
                if (candidate != null && updateSelector.ownerInternalName().equals(className)) {
                    emittedForOwner.set(true);
                }
                return candidate;
            }
        };
        final Class<?> target = exactClass(resolver, updateSelector.ownerInternalName());
        instrumentation.addTransformer(transformer, true);
        try {
            if (target != null) {
                instrumentation.retransformClasses(target);
            }
        } catch (Exception failure) {
            instrumentation.removeTransformer(transformer);
            throw new IllegalStateException("bounding-box overlay hook installation failed", failure);
        }
        return () -> {
            // Make callbacks inert before restoring the exact original owner bytes.
            NativeBoundingBoxOverlayButtonBridge.deactivateCallbacks();
            if (!instrumentation.removeTransformer(transformer)) {
                throw new IllegalStateException("bounding-box overlay hook was not installed");
            }
            restoreOwner(resolver, updateSelector.ownerInternalName(), emittedForOwner);
        };
    }

    /**
     * Retransforms the exact owner to its original bytes when transformed bytecode was
     * emitted for it. The owner is fresh-discovered by binary name through the verified
     * host loader after transformer removal, covering owners that loaded after
     * installation; a discovery, loader-identity, modifiability or retransform failure is
     * a structured cleanup failure.
     */
    private void restoreOwner(
        final VerifiedMemberResolver resolver,
        final String ownerInternalName,
        final AtomicBoolean emittedForOwner
    ) {
        if (!emittedForOwner.get()) {
            return;
        }
        final Class<?> owner = exactClass(resolver, ownerInternalName);
        if (owner == null) {
            throw new IllegalStateException(
                "bounding-box overlay owner was transformed but can no longer be discovered; "
                    + "exact original bytes cannot be proven restored"
            );
        }
        if (!instrumentation.isModifiableClass(owner)) {
            throw new IllegalStateException(
                "bounding-box overlay owner is not modifiable; transformed bytes cannot be "
                    + "restored to the exact original"
            );
        }
        try {
            instrumentation.retransformClasses(owner);
        } catch (Exception failure) {
            throw new IllegalStateException(
                "bounding-box overlay hook cleanup failed to restore exact original bytes",
                failure
            );
        }
    }

    private static Class<?> exactClass(
        final VerifiedMemberResolver resolver,
        final String ownerInternalName
    ) {
        try {
            final Class<?> target = Class.forName(
                ownerInternalName.replace('/', '.'),
                false,
                resolver.hostClassLoader()
            );
            if (target.getClassLoader() != resolver.hostClassLoader()) {
                throw new IllegalStateException("bounding-box classloader identity is stale");
            }
            return target;
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
