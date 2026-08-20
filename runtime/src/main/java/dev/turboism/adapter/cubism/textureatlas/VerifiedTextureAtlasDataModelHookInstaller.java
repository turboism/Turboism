package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs and owns the exact native texture-atlas editor data-model capture transformer. */
public final class VerifiedTextureAtlasDataModelHookInstaller implements AutoCloseable {

    public static final String CAPABILITY_ID = "cubism.texture-atlas.data-model-hook";
    static final String INIT_ALIAS = "cubism.texture-atlas.model-image-list.init";
    static final String DATA_MODEL_ALIAS = "cubism.texture-atlas.model-image-list.data-model";

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final TextureAtlasDataModelTransformer transformer;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private volatile Class<?> transformedClass;

    private VerifiedTextureAtlasDataModelHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector init,
        final StaticSelector dataModel,
        final ClassLoader hostClassLoader,
        final TextureAtlasDataModelCapture capture
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = init.ownerInternalName().replace('/', '.');
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (!init.ownerInternalName().equals(dataModel.ownerInternalName())) {
            throw new IllegalArgumentException("Texture-atlas hook selectors must share one owner.");
        }
        this.transformer = new TextureAtlasDataModelTransformer(
            init.ownerInternalName(),
            init.memberName(),
            init.descriptor(),
            hostClassLoader,
            dataModel.memberName(),
            dataModel.descriptor(),
            capture.key()
        );
    }

    /**
     * Builds an installer after verifying that this host is one the data-model hook is authorized
     * for.
     *
     * <p>Admits exactly Cubism 5.3.02 and 5.2.0, checks the version's hook aliases against the
     * authorization contract, and requires both verified selectors to be instance methods sharing
     * a single owning class. Nothing is instrumented until {@link #install()} is called.
     *
     * @param instrumentation the JVM instrumentation used to retransform the host class
     * @param resolver the verified member resolver for the running Cubism version; must not be null
     * @param hostClassLoader the loader that owns the host class to transform
     * @param capture the destination the injected bytecode publishes the data model into; must
     *                not be null
     * @return a configured, not-yet-installed hook installer
     * @throws IllegalArgumentException if the host version is unsupported, the capability is not
     *                                  authorized, the selectors are not instance methods, or
     *                                  they do not share one owner
     * @throws NullPointerException if {@code resolver} or {@code capture} is null
     */
    public static VerifiedTextureAtlasDataModelHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader,
        final TextureAtlasDataModelCapture capture
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        final Set<String> hookAliases;
        final String adapterSliceId;
        if (verified.isExactCubismVersion("5.3.02")) {
            hookAliases = VerifiedCubism5302TextureAtlasSelectorContract.HOOK_ALIASES;
            adapterSliceId = VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID;
        } else if (verified.isExactCubismVersion("5.2.0")) {
            hookAliases = VerifiedCubism520TextureAtlasSelectorContract.HOOK_ALIASES;
            adapterSliceId = VerifiedCubism520TextureAtlasSelectorContract.ADAPTER_SLICE_ID;
        } else {
            throw new IllegalArgumentException("Texture-atlas data-model hook version is unsupported.");
        }
        if (!verified.authorizesFeature(adapterSliceId, CAPABILITY_ID, hookAliases)) {
            throw new IllegalArgumentException("Texture-atlas data-model hook is not authorized.");
        }
        final StaticSelector init = verified.verifiedSelector(INIT_ALIAS);
        final StaticSelector dataModel = verified.verifiedSelector(DATA_MODEL_ALIAS);
        if (init.kind() != StaticSelector.Kind.METHOD
            || dataModel.kind() != StaticSelector.Kind.METHOD
            || (init.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0
            || (dataModel.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Verified texture-atlas hook selectors must be instance methods.");
        }
        return new VerifiedTextureAtlasDataModelHookInstaller(
            instrumentation,
            init,
            dataModel,
            hostClassLoader,
            Objects.requireNonNull(capture, "capture")
        );
    }

    /**
     * Installs the transformer and retransforms the already-loaded target class, after which the
     * capture starts receiving data models.
     *
     * <p>Idempotent: a second call while installed returns without doing anything. If
     * retransformation fails the installer closes itself - removing the transformer and undoing
     * any transformation - before rethrowing, so a failed install leaves no partial hook.
     *
     * @throws IllegalStateException if the JVM does not support class retransformation, in which
     *                               case the installer stays uninstalled
     * @throws Exception if retransforming the target class fails
     */
    public void install() throws Exception {
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        instrumentation.addTransformer(transformer, true);
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
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        instrumentation.removeTransformer(transformer);
        final Class<?> loaded = transformedClass;
        transformedClass = null;
        if (loaded != null && instrumentation.isModifiableClass(loaded)) {
            try {
                instrumentation.retransformClasses(loaded);
            } catch (Throwable failure) {
                throw new IllegalStateException("Texture-atlas hook restoration failed.", failure);
            }
        }
    }
}
