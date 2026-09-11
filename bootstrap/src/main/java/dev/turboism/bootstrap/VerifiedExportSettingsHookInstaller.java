package dev.turboism.bootstrap;

import dev.turboism.exportsettings.ExportSettingsHostProfile;
import dev.turboism.exportsettings.ExportSettingsNativeMethodTransformer;
import dev.turboism.exportsettings.NativeExportSettingsDialogBridge;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;

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
    static final String SUPPORTED_CUBISM_VERSION = ExportSettingsHostProfile.CUBISM_5_3_02.hostVersion();
    static final String DIALOG_OWNER_ALIAS = ExportSettingsHostProfile.DIALOG_OWNER_ALIAS;
    static final String DIALOG_CONSTRUCTOR_ALIAS = ExportSettingsHostProfile.DIALOG_CONSTRUCTOR_ALIAS;
    static final String DIALOG_SHOW_ALIAS = ExportSettingsHostProfile.DIALOG_SHOW_ALIAS;
    static final String DIALOG_CONTENT_BUILDER_ALIAS =
        ExportSettingsHostProfile.DIALOG_CONTENT_BUILDER_ALIAS;
    static final String DIALOG_WINDOW_FIELD_ALIAS = ExportSettingsHostProfile.DIALOG_WINDOW_FIELD_ALIAS;
    static final String WINDOW_CLASS_ALIAS = ExportSettingsHostProfile.WINDOW_CLASS_ALIAS;
    static final String WINDOW_JDIALOG_ALIAS = ExportSettingsHostProfile.WINDOW_JDIALOG_ALIAS;

    static boolean supportsExactCubismVersion(final String version) {
        return SUPPORTED_CUBISM_VERSION.equals(version);
    }

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final ExportSettingsNativeMethodTransformer transformer;
    /**
     * Runtime policy handler installed as the export-settings bridge, or {@code null} when this
     * installer owns only the transformer (focused shape tests and the resolver-based seam).
     */
    private final NativeExportSettingsDialogBridge.Handler bridgeHandler;
    private final AtomicBoolean installed = new AtomicBoolean();
    private Registration bridge;
    private boolean transformerRemoved;

    /**
     * Transformer-only installer: it owns no runtime bridge, so the resolver-based seam and the
     * focused selector-shape tests exercise bytecode transformation in isolation.
     */
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
        this(
            instrumentation, owner, constructor, show, contentBuilder, windowField, windowClass,
            jdialog, hostClassLoader, null
        );
    }

    /**
     * Installer that additionally owns the export-settings bridge.
     *
     * <p>The bridge publishes loader-neutral JDK callbacks that transformed host bytecode calls. It
     * is installed before the transformer is registered and dropped only after {@link #close()} has
     * restored the original host bytes, so transformed code can never call a removed callback.</p>
     *
     * @param bridgeHandler runtime policy handler, or {@code null} for a transformer-only install
     */
    VerifiedExportSettingsHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector owner,
        final StaticSelector constructor,
        final StaticSelector show,
        final StaticSelector contentBuilder,
        final StaticSelector windowField,
        final StaticSelector windowClass,
        final StaticSelector jdialog,
        final ClassLoader hostClassLoader,
        final NativeExportSettingsDialogBridge.Handler bridgeHandler
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = requireExactDialogShape(
            owner, constructor, show, contentBuilder, windowField, windowClass, jdialog
        );
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.bridgeHandler = bridgeHandler;
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

    /**
     * Builds the production installer from a reviewed host profile.
     *
     * <p>The profile carries only exact reviewed selectors, and the version gate below is the same
     * exact-release admission the resolver seam enforces, so an unreviewed build cannot be
     * transformed through this path. The handler is the runtime policy the transformed dialog calls
     * back into; it is required here, because a transformer without its bridge would leave the host
     * calling a callback that does not exist.</p>
     *
     * @param instrumentation JVM instrumentation of the running Editor
     * @param profile reviewed selectors for the loaded host artifact
     * @param handler runtime export-settings policy
     * @param hostClassLoader loader that owns the host dialog classes
     * @return an uninstalled installer; callers must call {@link #install()}
     * @throws IllegalArgumentException if the profile is not the exact supported release
     */
    static VerifiedExportSettingsHookInstaller fromHostProfile(
        final Instrumentation instrumentation,
        final ExportSettingsHostProfile profile,
        final NativeExportSettingsDialogBridge.Handler handler,
        final ClassLoader hostClassLoader
    ) {
        final ExportSettingsHostProfile requested = Objects.requireNonNull(profile, "profile");
        if (!supportsExactCubismVersion(requested.hostVersion())) {
            throw new IllegalArgumentException(
                "export settings hook requires exact Cubism " + SUPPORTED_CUBISM_VERSION
            );
        }
        return new VerifiedExportSettingsHookInstaller(
            instrumentation,
            requested.dialogOwner(),
            requested.dialogConstructor(),
            requested.dialogShow(),
            requested.dialogContentBuilder(),
            requested.dialogWindowField(),
            requested.windowClass(),
            requested.windowJDialog(),
            Objects.requireNonNull(hostClassLoader, "hostClassLoader"),
            Objects.requireNonNull(handler, "handler")
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
            // Bridge first: the transformer must never publish a call to a missing callback.
            if (bridgeHandler != null) {
                bridge = NativeExportSettingsDialogBridge.install(bridgeHandler);
            }
            // Register next: an owner that loads during the scan must be transformed too.
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
        // Host bytes are native again, so the runtime callback surface can be dropped safely.
        if (bridge != null) {
            bridge.close();
            bridge = null;
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
