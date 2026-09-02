package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Installs and owns the exact native texture-atlas automatic-layout entry transformer. */
public final class VerifiedTextureAtlasAutoLayoutHookInstaller implements AutoCloseable {

    public static final String CAPABILITY_ID = "cubism.texture-atlas.auto-layout-hook";
    public static final String CALLBACK_KEY = "dev.turboism.texture-atlas.auto-layout.runtime-ingress";
    public static final String PLUGIN_CALLBACK_KEY = "dev.turboism.texture-atlas.auto-layout.callback";
    static final String AUTO_LAYOUT_ALIAS = "cubism.texture-atlas.auto-layout.invoke";
    static final String DIALOG_INIT_ALIAS = "cubism.texture-atlas.dialog.init";
    static final String DIALOG_INGRESS_KEY = "dev.turboism.texture-atlas.auto-layout.dialog-ingress";
    static final String STATISTICS_VIEW_INIT_ALIAS = "cubism.texture-atlas.statistics.view-init";
    static final String STATISTICS_INGRESS_KEY = "dev.turboism.texture-atlas.statistics.ingress";

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final VerifiedMemberResolver resolver;
    private final TextureAtlasAutoLayoutTransformer transformer;
    private final TextureAtlasAutoLayoutDialogTransformer dialogTransformer;
    private final TextureAtlasAutoLayoutDialogTransformer statisticsTransformer;
    private final java.util.function.Consumer<Object> dialogIngress;
    private final TextureAtlasAutoLayoutDialogContributor dialogContributor;
    private final java.util.function.Consumer<Object> statisticsIngress;
    private final RuntimeTextureAtlasEditorUi editorUi;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final TextureAtlasNativeInvocationCoordinator nativeInvocations;
    private final BooleanSupplier pluginCallback;
    private final java.util.function.Predicate<Object> ingress;
    private volatile Class<?> transformedClass;
    private volatile Class<?> transformedDialogClass;
    private volatile Class<?> transformedStatisticsClass;
    private final String dialogOwnerName;

    private VerifiedTextureAtlasAutoLayoutHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector entry,
        final ClassLoader hostClassLoader,
        final VerifiedMemberResolver resolverForConstructor,
        final TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final BooleanSupplier pluginCallback,
        final RuntimeTextureAtlasEditorUi editorUi,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithmRegistry,
        final Locale effectiveLocale
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = entry.ownerInternalName().replace('/', '.');
        final StaticSelector dialog = resolverForConstructor.verifiedSelector(DIALOG_INIT_ALIAS);
        this.dialogOwnerName = dialog.ownerInternalName().replace('/', '.');
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.resolver = Objects.requireNonNull(resolverForConstructor, "resolver");
        this.nativeInvocations = Objects.requireNonNull(nativeInvocations, "nativeInvocations");
        this.pluginCallback = Objects.requireNonNull(pluginCallback, "pluginCallback");
        this.editorUi = Objects.requireNonNull(editorUi, "editorUi");
        this.ingress = nativeInvocations.ingress(pluginCallback);
        this.transformer = new TextureAtlasAutoLayoutTransformer(
            entry.ownerInternalName(),
            entry.memberName(),
            entry.descriptor(),
            hostClassLoader,
            CALLBACK_KEY
        );
        final StaticSelector dialogEntry = resolverForConstructor.verifiedSelector(DIALOG_INIT_ALIAS);
        this.dialogTransformer = new TextureAtlasAutoLayoutDialogTransformer(
            dialogEntry.ownerInternalName(),
            dialogEntry.descriptor(),
            hostClassLoader,
            DIALOG_INGRESS_KEY
        );
        this.dialogContributor = new TextureAtlasAutoLayoutDialogContributor(
            algorithmRegistry, Objects.requireNonNull(effectiveLocale, "effectiveLocale")
        );
        this.dialogIngress = dialogContributor.ingress();
        if (resolverForConstructor.isExactCubismVersion("5.3.02")) {
            final StaticSelector statisticsEntry =
                resolverForConstructor.verifiedSelector(STATISTICS_VIEW_INIT_ALIAS);
            this.statisticsIngress = editorUi.ingress();
            this.statisticsTransformer = new TextureAtlasAutoLayoutDialogTransformer(
                statisticsEntry.ownerInternalName(),
                statisticsEntry.descriptor(),
                hostClassLoader,
                STATISTICS_INGRESS_KEY
            );
        } else {
            this.statisticsIngress = editorUi.ingress();
            this.statisticsTransformer = null;
        }
    }

    /**
     * Builds an installer with runtime-default collaborators, taking the plugin callback from the
     * {@link #PLUGIN_CALLBACK_KEY} system property.
     *
     * <p>If nothing usable is registered under that key the callback defaults to one that always
     * declines, so the hook installs but never diverts the host's automatic layout.
     *
     * @param instrumentation the JVM instrumentation used to retransform the host class
     * @param resolver the verified member resolver for the running Cubism version
     * @param hostClassLoader the loader that owns the host classes to transform
     * @return a configured, not-yet-installed hook installer
     * @throws IllegalArgumentException if the host is not Cubism 5.3.02 or 5.2.0, if the
     *                                  capability is not authorized, or if the verified
     *                                  automatic-layout selector is not a boolean-returning
     *                                  instance method
     */
    public static VerifiedTextureAtlasAutoLayoutHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader
    ) {
        final Object registered = System.getProperties().get(PLUGIN_CALLBACK_KEY);
        final BooleanSupplier callback = registered instanceof BooleanSupplier supplier
            ? supplier
            : () -> false;
        return fromVerifiedResolver(
            instrumentation, resolver, hostClassLoader,
            new TextureAtlasNativeInvocationCoordinator(), callback,
            new RuntimeTextureAtlasEditorUi(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry()
        );
    }

    /**
     * Builds an installer from explicit collaborators, resolving the dialog locale from the
     * Cubism host.
     *
     * @param instrumentation the JVM instrumentation used to retransform the host class
     * @param resolver the verified member resolver for the running Cubism version
     * @param hostClassLoader the loader that owns the host classes to transform
     * @param nativeInvocations the coordinator scoping each native packing invocation
     * @param pluginCallback invoked inside that scope; returning false lets the host's own
     *                       layout proceed
     * @param editorUi receives the statistics-view ingress and exposes the editor session
     * @param algorithmRegistry supplies the layout algorithms offered in the injected dialog
     * @return a configured, not-yet-installed hook installer
     * @throws IllegalArgumentException if the host version is unsupported, the capability is not
     *                                  authorized, or the verified selector is invalid
     */
    public static VerifiedTextureAtlasAutoLayoutHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader,
        final TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final BooleanSupplier pluginCallback,
        final RuntimeTextureAtlasEditorUi editorUi,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithmRegistry
    ) {
        return fromVerifiedResolver(
            instrumentation, resolver, hostClassLoader, nativeInvocations, pluginCallback,
            editorUi, algorithmRegistry, dev.turboism.i18n.CubismHostLocale.resolve()
        );
    }

    /**
     * Builds an installer from explicit collaborators and an explicit dialog locale.
     *
     * <p>This is the verification gate for the whole feature: it admits exactly Cubism 5.3.02 and
     * 5.2.0, checks the alias set for that version against the authorization contract, and
     * rejects a selector that is not a boolean-returning instance method. Nothing is instrumented
     * until {@link #install()} is called. The statistics transformer exists only on 5.3.02; on
     * 5.2.0 the editor UI ingress is still wired but no statistics class is transformed.
     *
     * @param instrumentation the JVM instrumentation used to retransform the host class
     * @param resolver the verified member resolver for the running Cubism version; must not be null
     * @param hostClassLoader the loader that owns the host classes to transform
     * @param nativeInvocations the coordinator scoping each native packing invocation
     * @param pluginCallback invoked inside that scope; returning false lets the host's own
     *                       layout proceed
     * @param editorUi receives the statistics-view ingress and exposes the editor session
     * @param algorithmRegistry supplies the layout algorithms offered in the injected dialog
     * @param effectiveLocale the locale the injected dialog labels use; must not be null
     * @return a configured, not-yet-installed hook installer
     * @throws IllegalArgumentException if the host is neither Cubism 5.3.02 nor 5.2.0, if the
     *                                  capability is not authorized for that adapter slice, or if
     *                                  the verified automatic-layout selector is not a
     *                                  boolean-returning instance method
     * @throws NullPointerException if {@code resolver} or {@code effectiveLocale} is null
     */
    public static VerifiedTextureAtlasAutoLayoutHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader,
        final TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final BooleanSupplier pluginCallback,
        final RuntimeTextureAtlasEditorUi editorUi,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithmRegistry,
        final Locale effectiveLocale
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        final Set<String> aliases;
        final String adapterSliceId;
        if (verified.isExactCubismVersion("5.3.02")) {
            aliases = union(
                union(
                    union(
                        VerifiedCubism5302TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES,
                        VerifiedCubism5302TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES
                    ),
                    VerifiedCubism5302TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES
                ),
                VerifiedCubism5302TextureAtlasSelectorContract.STATISTICS_ALIASES
            );
            adapterSliceId = VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID;
        } else if (verified.isExactCubismVersion("5.2.03")) {
            aliases = union(
                union(
                    VerifiedCubism520TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES,
                    VerifiedCubism520TextureAtlasSelectorContract.NATIVE_INVOCATION_ALIASES
                ),
                VerifiedCubism520TextureAtlasSelectorContract.DIALOG_INJECTION_ALIASES
            );
            adapterSliceId = VerifiedCubism520TextureAtlasSelectorContract.ADAPTER_SLICE_ID;
        } else {
            throw new IllegalArgumentException("Texture-atlas automatic-layout hook version is unsupported.");
        }
        if (!verified.authorizesFeature(adapterSliceId, CAPABILITY_ID, aliases)) {
            throw new IllegalArgumentException("Texture-atlas automatic-layout hook is not authorized.");
        }
        final StaticSelector entry = verified.verifiedSelector(AUTO_LAYOUT_ALIAS);
        if (entry.kind() != StaticSelector.Kind.METHOD
            || !entry.descriptor().endsWith(")Z")
            || (entry.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Verified texture-atlas automatic-layout selector is invalid.");
        }
        return new VerifiedTextureAtlasAutoLayoutHookInstaller(
            instrumentation,
            entry,
            hostClassLoader,
            verified,
            nativeInvocations,
            pluginCallback,
            editorUi,
            algorithmRegistry,
            effectiveLocale
        );
    }

    /**
     * @return the editor UI this installer wires the native statistics-view ingress into; the
     *         same instance whether or not {@link #install()} has run
     */
    public RuntimeTextureAtlasEditorUi editorUi() {
        return editorUi;
    }

    /**
     * Installs the transformers and retransforms the already-loaded host classes.
     *
     * <p>Idempotent: a second call while installed returns without doing anything. The core
     * automatic-layout retransformation is all-or-nothing - if it fails the installer closes
     * itself and rethrows. The dialog and statistics contributions are deliberately fail-open:
     * their retransformation failures are reported to {@code System.err} and swallowed so a
     * cosmetic augmentation cannot disable the core hook. Ingress callbacks are published as
     * system properties before transformation, since the injected bytecode reads them from there.
     *
     * @throws IllegalStateException if the JVM does not support class retransformation, in which
     *                               case the installer stays uninstalled
     * @throws Exception if retransforming the automatic-layout class fails; the installer is
     *                   closed first
     */
    public void install() throws Exception {
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        nativeInvocations.connect(resolver);
        System.getProperties().put(CALLBACK_KEY, ingress);
        System.getProperties().put(DIALOG_INGRESS_KEY, dialogIngress);
        System.getProperties().put(STATISTICS_INGRESS_KEY, statisticsIngress);
        instrumentation.addTransformer(transformer, true);
        instrumentation.addTransformer(dialogTransformer, true);
        if (statisticsTransformer != null) {
            instrumentation.addTransformer(statisticsTransformer, true);
        }
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(targetClassName)
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                    transformedClass = loaded;
                    break;
                }
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
        // Dialog contribution is a best-effort augmentation: a retransformation failure
        // must not tear down the core automatic-layout hook (fail-open isolation).
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(dialogOwnerName)
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                    transformedDialogClass = loaded;
                    break;
                }
            }
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "texture-atlas",
                "Texture-atlas dialog contribution retransformation failed safely",
                failure
            );
        }
        try {
            if (statisticsTransformer == null) {
                return;
            }
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(statisticsTransformerOwnerName())
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                    transformedStatisticsClass = loaded;
                    break;
                }
            }
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "texture-atlas",
                "Texture-atlas statistics contribution retransformation failed safely",
                failure
            );
        }
    }

    private String statisticsTransformerOwnerName() {
        if (statisticsTransformer == null) return "";
        return resolver.verifiedSelector(STATISTICS_VIEW_INIT_ALIAS).ownerInternalName().replace('/', '.');
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        instrumentation.removeTransformer(transformer);
        instrumentation.removeTransformer(dialogTransformer);
        if (statisticsTransformer != null) {
            instrumentation.removeTransformer(statisticsTransformer);
        }
        System.getProperties().remove(CALLBACK_KEY, ingress);
        System.getProperties().remove(DIALOG_INGRESS_KEY, dialogIngress);
        System.getProperties().remove(STATISTICS_INGRESS_KEY, statisticsIngress);
        nativeInvocations.deactivate();
        final Class<?> loaded = transformedClass;
        transformedClass = null;
        if (loaded != null && instrumentation.isModifiableClass(loaded)) {
            try {
                instrumentation.retransformClasses(loaded);
            } catch (Throwable failure) {
                throw new IllegalStateException("Texture-atlas automatic-layout hook restoration failed.", failure);
            }
        }
        final Class<?> dialogLoaded = transformedDialogClass;
        transformedDialogClass = null;
        if (dialogLoaded != null && instrumentation.isModifiableClass(dialogLoaded)) {
            try {
                instrumentation.retransformClasses(dialogLoaded);
            } catch (Throwable failure) {
                throw new IllegalStateException("Texture-atlas automatic-layout dialog restoration failed.", failure);
            }
        }
        final Class<?> statisticsLoaded = transformedStatisticsClass;
        transformedStatisticsClass = null;
        if (statisticsTransformer != null && statisticsLoaded != null && instrumentation.isModifiableClass(statisticsLoaded)) {
            try {
                instrumentation.retransformClasses(statisticsLoaded);
            } catch (Throwable failure) {
                throw new IllegalStateException("Texture-atlas statistics contribution restoration failed.", failure);
            }
        }
    }


    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final java.util.HashSet<String> values = new java.util.HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }
}
