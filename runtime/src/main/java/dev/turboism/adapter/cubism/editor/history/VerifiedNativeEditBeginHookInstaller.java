package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistoryIngressSelectorContract;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs and owns the exact native {@code beginEdit} entry hooks.
 *
 * <p>Two exact methods are instrumented, because one is not enough and instrumenting more than two
 * would observe an edit twice:</p>
 *
 * <ul>
 *   <li>{@code CModelingEditMode_Main.beginEdit}, the entry the four HR-05 ingress families reach.
 *       Its form-animation branch returns through {@code CModelEditAnimationHandler} and never
 *       calls {@code super}.</li>
 *   <li>{@code ACEditMode.beginEdit}, the inherited implementation that the scene editor
 *       ({@code CSceneEditMode} declares no override) and the game-data editor reach. A plain
 *       modeling edit also reaches it, one frame deeper, which is why the bridge publishes only an
 *       outermost entry.</li>
 * </ul>
 *
 * <p>Nothing is instrumented until {@link #install()} runs, and both the capability and every
 * admitted alias are re-checked there. The hook adds a call and changes no native behaviour, so a
 * failed retransformation leaves the installer closed rather than half-installed.</p>
 */
public final class VerifiedNativeEditBeginHookInstaller implements AutoCloseable {

    /** System-property key holding the loader-neutral receiver the injected code calls. */
    public static final String CALLBACK_KEY = "dev.turboism.editor-history.begin-edit.ingress";

    private static final String MODELING_EDIT_ENTRY_OWNER =
        "com/live2d/cubism/doc/modeling/CModelingEditMode_Main";
    private static final String INHERITED_EDIT_ENTRY_OWNER = "com/live2d/cubism/doc/ACEditMode";
    private static final String EDIT_ENTRY_NAME = "beginEdit";
    private static final String EDIT_ENTRY_DESCRIPTOR =
        "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;";

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final List<StaticSelector> entries;
    private final List<NativeEditBeginTransformer> transformers;
    private final List<Class<?>> transformed = new ArrayList<>();
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private VerifiedNativeEditBeginHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final List<StaticSelector> entries
    ) {
        this.instrumentation = instrumentation;
        this.hostClassLoader = hostClassLoader;
        this.entries = List.copyOf(entries);
        final List<NativeEditBeginTransformer> created = new ArrayList<>();
        for (final StaticSelector entry : this.entries) {
            created.add(new NativeEditBeginTransformer(
                entry.ownerInternalName(),
                entry.memberName(),
                entry.descriptor(),
                hostClassLoader,
                CALLBACK_KEY
            ));
        }
        this.transformers = List.copyOf(created);
    }

    /**
     * Builds an installer for the exact Cubism version the resolver admits.
     *
     * <p>This is the admission gate: the version must be one whose records carry the reviewed
     * ingress family, the alias set must be authorized, and both hook targets must be public
     * instance methods with the exact {@code beginEdit} descriptor.</p>
     *
     * @param instrumentation the JVM instrumentation used to retransform the host classes
     * @param resolver        the verified member resolver for the running Cubism version
     * @param hostClassLoader the loader that owns the host classes to transform
     * @return a configured, not-yet-installed hook installer
     * @throws IllegalArgumentException if the host version is unsupported, the capability or
     *                                  aliases are not authorized, or a hook target is not the
     *                                  exact public instance method
     */
    public static VerifiedNativeEditBeginHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final ClassLoader hostClassLoader
    ) {
        final VerifiedMemberResolver verified = Objects.requireNonNull(resolver, "resolver");
        if (!verified.isExactCubismVersion("5.2.03") && !verified.isExactCubismVersion("5.3.02")) {
            throw new IllegalArgumentException("Native edit entry hook version is unsupported.");
        }
        final Set<String> aliases = Set.of(
            EditorHistoryIngressSelectorContract.MODELING_EDIT_ENTRY_ALIAS,
            EditorHistoryIngressSelectorContract.BASE_EDIT_ENTRY_ALIAS
        );
        if (!verified.authorizesFeature(
            EditorHistoryIngressSelectorContract.ADAPTER_SLICE_ID,
            EditorHistoryIngressSelectorContract.CAPABILITY_ID,
            aliases
        )) {
            throw new IllegalArgumentException("Native edit entry hook is not authorized.");
        }
        final List<StaticSelector> entries = new ArrayList<>();
        for (final String alias : aliases) {
            final StaticSelector selector = verified.verifiedSelector(alias);
            if (selector.kind() != StaticSelector.Kind.METHOD
                || !EDIT_ENTRY_NAME.equals(selector.memberName())
                || !EDIT_ENTRY_DESCRIPTOR.equals(selector.descriptor())
                || (selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
                throw new IllegalArgumentException(
                    "Verified native edit entry selector is invalid: " + alias
                );
            }
            entries.add(selector);
        }
        if (entries.size() != 2
            || count(entries, MODELING_EDIT_ENTRY_OWNER) != 1
            || count(entries, INHERITED_EDIT_ENTRY_OWNER) != 1) {
            throw new IllegalArgumentException(
                "Native edit entry hook needs the modeling entry plus the inherited entry,"
                    + " and nothing else."
            );
        }
        return new VerifiedNativeEditBeginHookInstaller(
            Objects.requireNonNull(instrumentation, "instrumentation"),
            Objects.requireNonNull(hostClassLoader, "hostClassLoader"),
            entries
        );
    }

    private static long count(final List<StaticSelector> entries, final String owner) {
        return entries.stream().filter(entry -> owner.equals(entry.ownerInternalName())).count();
    }

    /**
     * Publishes the receiver and retransforms the already-loaded hook targets.
     *
     * <p>Idempotent: a second call returns without doing anything, and a failure during
     * retransformation closes the installer so no property and no transformer is left behind.</p>
     *
     * @param receiver the loader-neutral receiver the injected code calls
     * @throws IllegalStateException if the JVM cannot retransform classes
     * @throws Exception if retransforming a hook target fails
     */
    public void install(final java.util.function.Consumer<String> receiver) throws Exception {
        Objects.requireNonNull(receiver, "receiver");
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        System.getProperties().put(CALLBACK_KEY, receiver);
        for (final NativeEditBeginTransformer transformer : transformers) {
            instrumentation.addTransformer(transformer, true);
        }
        try {
            for (final StaticSelector entry : entries) {
                retransform(entry.ownerInternalName().replace('/', '.'));
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
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
                    "Native edit entry hook retransformation failed: " + className,
                    failure
                );
            }
            synchronized (transformed) {
                transformed.add(loaded);
            }
            return;
        }
    }

    /** {@return whether the hooks are currently installed} */
    public boolean isInstalled() {
        return installed.get();
    }

    /**
     * {@return the binary names of the hook targets that were retransformed at install time}
     *
     * <p>An empty result is legitimate while a target has not been loaded yet, because the
     * registered transformers still apply to a later load. It is not legitimate once the host has
     * loaded the target, and reporting the names separately is what makes that difference
     * visible instead of a hook that silently observes nothing.</p>
     */
    public List<String> retransformedClassNames() {
        synchronized (transformed) {
            return transformed.stream().map(Class::getName).toList();
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        for (final NativeEditBeginTransformer transformer : transformers) {
            instrumentation.removeTransformer(transformer);
        }
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
                    "Native edit entry hook restoration failed: " + loaded.getName(),
                    failure
                );
            }
        }
    }
}
