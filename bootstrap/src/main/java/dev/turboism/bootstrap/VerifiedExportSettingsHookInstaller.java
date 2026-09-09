package dev.turboism.bootstrap;

import dev.turboism.exportsettings.ExportSettingsNativeMethodTransformer;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin, reversible installer for the exact 5.3.02 Export Settings transformer.
 *
 * <p>This class consumes an already verified resolver or exact selector tuple. It does
 * not create a verification record, infer host readiness from static selectors, or wire
 * itself into global startup scheduling.</p>
 */
final class VerifiedExportSettingsHookInstaller implements AutoCloseable {
    static final String SUPPORTED_CUBISM_VERSION = "5.3.02";
    static final String DIALOG_OWNER_ALIAS = "cubism.export-settings.dialog.owner";
    static final String DIALOG_CONSTRUCTOR_ALIAS = "cubism.export-settings.dialog.constructor";
    static final String DIALOG_SHOW_ALIAS = "cubism.export-settings.dialog.show";
    static final String DIALOG_CONTENT_BUILDER_ALIAS = "cubism.export-settings.dialog.content-builder";
    static final String DIALOG_WINDOW_FIELD_ALIAS = "cubism.export-settings.dialog.window-field";
    static final String WINDOW_CLASS_ALIAS = "cubism.export-settings.window.class";
    static final String WINDOW_JDIALOG_ALIAS = "cubism.export-settings.window.jdialog";

    static boolean supportsExactCubismVersion(final String version) {
        return SUPPORTED_CUBISM_VERSION.equals(version);
    }

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final ExportSettingsNativeMethodTransformer transformer;
    private final AtomicBoolean installed = new AtomicBoolean();
    private boolean transformerRemoved;

    VerifiedExportSettingsHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector owner,
        final StaticSelector constructor,
        final StaticSelector show,
        final StaticSelector contentBuilder,
        final StaticSelector windowField,
        final StaticSelector windowClass,
        final StaticSelector jdialog,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = requireExactDialogShape(
            owner, constructor, show, contentBuilder, windowField, windowClass, jdialog
        );
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.transformer = new ExportSettingsNativeMethodTransformer(
            owner.ownerInternalName(),
            contentBuilder.memberName(),
            contentBuilder.descriptor(),
            show.memberName(),
            show.descriptor(),
            windowField.memberName(),
            windowField.descriptor(),
            windowClass.ownerInternalName(),
            jdialog.memberName(),
            jdialog.descriptor(),
            hostClassLoader
        );
    }

    static VerifiedExportSettingsHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader
    ) {
        final VerifiedMemberResolver requested = Objects.requireNonNull(resolver, "resolver");
        final ClassLoader requestedLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (!supportsExactCubismVersion(requested.cubismVersion())) {
            throw new IllegalArgumentException(
                "export settings hook requires exact Cubism " + SUPPORTED_CUBISM_VERSION
            );
        }
        if (requested.hostClassLoader() != requestedLoader) {
            throw new IllegalArgumentException("verified export settings host loader does not match");
        }
        return new VerifiedExportSettingsHookInstaller(
            instrumentation,
            requireSelector(requested, DIALOG_OWNER_ALIAS),
            requireSelector(requested, DIALOG_CONSTRUCTOR_ALIAS),
            requireSelector(requested, DIALOG_SHOW_ALIAS),
            requireSelector(requested, DIALOG_CONTENT_BUILDER_ALIAS),
            requireSelector(requested, DIALOG_WINDOW_FIELD_ALIAS),
            requireSelector(requested, WINDOW_CLASS_ALIAS),
            requireSelector(requested, WINDOW_JDIALOG_ALIAS),
            requestedLoader
        );
    }

    private static String requireExactDialogShape(
        final StaticSelector owner,
        final StaticSelector constructor,
        final StaticSelector show,
        final StaticSelector contentBuilder,
        final StaticSelector windowField,
        final StaticSelector windowClass,
        final StaticSelector jdialog
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(constructor, "constructor");
        Objects.requireNonNull(show, "show");
        Objects.requireNonNull(contentBuilder, "contentBuilder");
        Objects.requireNonNull(windowField, "windowField");
        Objects.requireNonNull(windowClass, "windowClass");
        Objects.requireNonNull(jdialog, "jdialog");
        final String expectedWindowDescriptor = "L" + windowClass.ownerInternalName() + ";";
        if (owner.kind() != StaticSelector.Kind.CLASS
            || constructor.kind() != StaticSelector.Kind.CONSTRUCTOR
            || !constructor.ownerInternalName().equals(owner.ownerInternalName())
            || show.kind() != StaticSelector.Kind.METHOD
            || (show.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || contentBuilder.kind() != StaticSelector.Kind.METHOD
            || (contentBuilder.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || windowField.kind() != StaticSelector.Kind.FIELD
            || (windowField.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || !windowField.ownerInternalName().equals(owner.ownerInternalName())
            || !windowField.descriptor().equals(expectedWindowDescriptor)
            || windowClass.kind() != StaticSelector.Kind.CLASS
            || jdialog.kind() != StaticSelector.Kind.METHOD
            || (jdialog.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || !jdialog.ownerInternalName().equals(windowClass.ownerInternalName())) {
            throw new IllegalArgumentException(
                "Verified export settings selectors do not match the exact dialog shape."
            );
        }
        return owner.ownerInternalName().replace('/', '.');
    }

    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        try {
            // Register first: an owner that loads during the scan must be transformed too.
            instrumentation.addTransformer(transformer, true);
            for (Class<?> loaded : loadedTargets()) {
                instrumentation.retransformClasses(loaded);
                break;
            }
        } catch (Throwable failure) {
            try {
                close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Verified export settings hook installation failed", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (!installed.get()) {
            return;
        }
        if (!transformerRemoved) {
            instrumentation.removeTransformer(transformer);
            transformerRemoved = true;
        }
        try {
            for (Class<?> loaded : loadedTargets()) {
                instrumentation.retransformClasses(loaded);
                break;
            }
        } catch (Throwable failure) {
            // Keep the installation live: callers must restore host bytes before removing bridge callbacks.
            throw new IllegalStateException(
                "Verified export settings hook restoration failed", failure
            );
        }
        installed.set(false);
    }

    private List<Class<?>> loadedTargets() {
        final List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded.getName().equals(targetClassName)
                && loaded.getClassLoader() == hostClassLoader
                && instrumentation.isModifiableClass(loaded)) {
                targets.add(loaded);
            }
        }
        return targets;
    }

    private static StaticSelector requireSelector(
        final VerifiedMemberResolver resolver,
        final String alias
    ) {
        return Objects.requireNonNull(resolver.verifiedSelector(alias), alias);
    }
}
