package dev.turboism.ui.overlay;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Objects;

/** Installs and retracts the exact verified bounding-box update transformer. */
public final class BoundingBoxOverlayButtonHookInstaller {

    private final Instrumentation instrumentation;

    public BoundingBoxOverlayButtonHookInstaller(final Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
    }

    public Registration install(final VerifiedMemberResolver resolver) {
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("class retransformation is unavailable");
        }
        final StaticSelector selector = resolver.verifiedSelector(
            "cubism.ui-bounding-box-overlay.bounding-box.update"
        );
        final ClassFileTransformer transformer = new BoundingBoxOverlayButtonUpdateTransformer(
            resolver.hostClassLoader(),
            selector
        );
        final Class<?> target = exactClass(resolver, selector.ownerInternalName());
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
            if (!instrumentation.removeTransformer(transformer)) {
                throw new IllegalStateException("bounding-box overlay hook was not installed");
            }
            if (target != null && instrumentation.isModifiableClass(target)) {
                try {
                    instrumentation.retransformClasses(target);
                } catch (Exception failure) {
                    throw new IllegalStateException("bounding-box overlay hook cleanup failed", failure);
                }
            }
        };
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
