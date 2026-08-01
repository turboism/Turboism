package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
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
        final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithmRegistry
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
            algorithmRegistry, java.util.Locale.getDefault()
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

    public static VerifiedTextureAtlasAutoLayoutHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader,
        final TextureAtlasNativeInvocationCoordinator nativeInvocations,
        final BooleanSupplier pluginCallback,
        final RuntimeTextureAtlasEditorUi editorUi,
        final RuntimeTextureAtlasLayoutAlgorithmRegistry algorithmRegistry
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
        } else if (verified.isExactCubismVersion("5.2.0")) {
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
            algorithmRegistry
        );
    }

    public RuntimeTextureAtlasEditorUi editorUi() {
        return editorUi;
    }

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
            System.err.println(
                "Turboism texture-atlas dialog contribution retransformation failed safely: " + failure
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
            System.err.println(
                "Turboism texture-atlas statistics contribution retransformation failed safely: " + failure
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
