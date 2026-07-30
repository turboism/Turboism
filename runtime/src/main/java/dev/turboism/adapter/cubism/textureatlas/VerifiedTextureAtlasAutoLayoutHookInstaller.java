package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Installs and owns the exact native texture-atlas automatic-layout entry transformer. */
public final class VerifiedTextureAtlasAutoLayoutHookInstaller implements AutoCloseable {

    public static final String CAPABILITY_ID = "cubism.texture-atlas.auto-layout-hook";
    public static final String CALLBACK_KEY = "dev.turboism.texture-atlas.auto-layout.callback";
    static final String AUTO_LAYOUT_ALIAS = "cubism.texture-atlas.auto-layout.invoke";

    private final Instrumentation instrumentation;
    private final String targetClassName;
    private final ClassLoader hostClassLoader;
    private final TextureAtlasAutoLayoutTransformer transformer;
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private volatile Class<?> transformedClass;

    private VerifiedTextureAtlasAutoLayoutHookInstaller(
        final Instrumentation instrumentation,
        final StaticSelector entry,
        final ClassLoader hostClassLoader
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.targetClassName = entry.ownerInternalName().replace('/', '.');
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.transformer = new TextureAtlasAutoLayoutTransformer(
            entry.ownerInternalName(),
            entry.memberName(),
            entry.descriptor(),
            hostClassLoader,
            CALLBACK_KEY
        );
    }

    public static VerifiedTextureAtlasAutoLayoutHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        final Set<String> aliases;
        final String adapterSliceId;
        if (verified.isExactCubismVersion("5.3.02")) {
            aliases = VerifiedCubism5302TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES;
            adapterSliceId = VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID;
        } else if (verified.isExactCubismVersion("5.2.0")) {
            aliases = VerifiedCubism520TextureAtlasSelectorContract.AUTO_LAYOUT_HOOK_ALIASES;
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
            hostClassLoader
        );
    }

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
                throw new IllegalStateException("Texture-atlas automatic-layout hook restoration failed.", failure);
            }
        }
    }
}
