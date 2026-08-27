package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;
import dev.turboism.ui.appearance.control.DeformerControlRowAppearanceProvider;
import dev.turboism.ui.appearance.control.DeformerControlRowRendererMethodTransformer;
import dev.turboism.ui.appearance.control.DeformerTreeControlAppearanceProvider;
import dev.turboism.ui.appearance.control.DeformerTreeRendererMethodTransformer;
import dev.turboism.ui.appearance.control.NativeDeformerControlRowAppearanceBridge;
import dev.turboism.ui.appearance.control.NativeDeformerTreeAppearanceBridge;
import dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge;
import dev.turboism.ui.appearance.control.NativePartTreeAppearanceBridge;
import dev.turboism.ui.appearance.control.ParameterControlAppearanceProvider;
import dev.turboism.ui.appearance.control.ParameterRowMethodTransformer;
import dev.turboism.ui.appearance.control.PartTreeControlAppearanceProvider;
import dev.turboism.ui.appearance.control.PartTreeRendererMethodTransformer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the exact-version control-appearance transformer bundle and bridge cleanup. */
final class VerifiedControlAppearanceHookInstaller implements AutoCloseable {
    private static final String PREFIX = "cubism.ui-control-appearance.";

    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final long hostGeneration;
    private final List<ClassFileTransformer> transformers;
    private final Set<String> targetClassNames;
    private final NativeDeformerTreeAppearanceBridge.Selectors deformerTreeSelectors;
    private final NativeDeformerControlRowAppearanceBridge.Selectors deformerControlSelectors;
    private final NativeParameterAppearanceBridge.Selectors parameterSelectors;
    private final NativePartTreeAppearanceBridge.Selectors partSelectors;
    private final PaletteAppearanceCoordinator coordinator;
    private final AtomicBoolean installed = new AtomicBoolean();

    private VerifiedControlAppearanceHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final long hostGeneration,
        final List<ClassFileTransformer> transformers,
        final Set<String> targetClassNames,
        final NativeDeformerTreeAppearanceBridge.Selectors deformerTreeSelectors,
        final NativeDeformerControlRowAppearanceBridge.Selectors deformerControlSelectors,
        final NativeParameterAppearanceBridge.Selectors parameterSelectors,
        final NativePartTreeAppearanceBridge.Selectors partSelectors,
        final PaletteAppearanceCoordinator coordinator
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.hostGeneration = hostGeneration;
        this.transformers = List.copyOf(transformers);
        this.targetClassNames = Set.copyOf(targetClassNames);
        this.deformerTreeSelectors = Objects.requireNonNull(deformerTreeSelectors, "deformerTreeSelectors");
        this.deformerControlSelectors = Objects.requireNonNull(deformerControlSelectors, "deformerControlSelectors");
        this.parameterSelectors = Objects.requireNonNull(parameterSelectors, "parameterSelectors");
        this.partSelectors = Objects.requireNonNull(partSelectors, "partSelectors");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    static VerifiedControlAppearanceHookInstaller fromVerifiedResolver(
        final Instrumentation instrumentation,
        final VerifiedMemberResolver resolver,
        final long hostGeneration,
        final PaletteAppearanceCoordinator coordinator
    ) {
        Objects.requireNonNull(resolver, "resolver");
        if (!supportsStaticProfile(resolver.cubismVersion()) || hostGeneration <= 0) {
            throw new IllegalArgumentException("control appearance requires an exact supported active generation");
        }
        final ClassLoader loader = resolver.hostClassLoader();
        final StaticSelector deformerRenderer = instanceMethod(resolver, "deformer-label.renderer");
        final StaticSelector deformerRowSource = instanceMethod(resolver, "deformer-row.source");
        final StaticSelector deformerSource = classSelector(resolver, "deformer-source.class");
        final StaticSelector deformerId = instanceMethod(resolver, "deformer-source.id");
        final StaticSelector artMeshSource = classSelector(resolver, "art-mesh.source-class");
        final StaticSelector artMeshId = instanceMethod(resolver, "art-mesh.source-id");
        final StaticSelector deformerControlRenderer = instanceMethod(resolver, "deformer-control.renderer");
        final StaticSelector deformerControlOuter = instanceField(resolver, "deformer-control.outer");
        final StaticSelector deformerControlOuterClass = classSelector(resolver, "deformer-control.outer-class");
        final StaticSelector deformerControlTree = staticMethod(resolver, "deformer-control.tree");
        final StaticSelector deformerControlTreeClass = classSelector(resolver, "deformer-control.tree-class");
        final StaticSelector singleClass = classSelector(resolver, "parameter.single-class");
        final StaticSelector doubleClass = classSelector(resolver, "parameter.double-class");
        final StaticSelector folderClass = classSelector(resolver, "parameter.folder-class");
        final StaticSelector singleCreate = constructor(resolver, "parameter.single-create");
        final StaticSelector singleSelection = instanceMethod(resolver, "parameter.single-selection");
        final StaticSelector doubleCreate = constructor(resolver, "parameter.double-create");
        final StaticSelector doubleSelection = instanceMethod(resolver, "parameter.double-selection");
        final StaticSelector folderCreatePrimary = constructor(resolver, "parameter.folder-create-primary");
        final StaticSelector folderCreateSecondary = constructor(resolver, "parameter.folder-create-secondary");
        final StaticSelector folderSelection = instanceMethod(resolver, "parameter.folder-selection");
        final StaticSelector parameterSource = instanceMethod(resolver, "parameter.source");
        final StaticSelector secondaryParameterSource = instanceMethod(resolver, "parameter.secondary-source");
        final StaticSelector parameterLabel = instanceField(resolver, "parameter.label");
        final StaticSelector secondaryParameterLabel = instanceField(resolver, "parameter.secondary-label");
        final StaticSelector folderSource = instanceMethod(resolver, "parameter.folder-source");
        final StaticSelector folderLabel = instanceMethod(resolver, "parameter.folder-label");
        final StaticSelector parameterSourceClass = classSelector(resolver, "parameter.source-class");
        final StaticSelector folderSourceClass = classSelector(resolver, "parameter.folder-source-class");
        final StaticSelector parameterSourceId = instanceMethod(resolver, "parameter.source-id");
        final StaticSelector folderSourceId = instanceMethod(resolver, "parameter.folder-source-id");
        final StaticSelector labelClass = classSelector(resolver, "parameter.label-class");
        final StaticSelector labelSwing = instanceMethod(resolver, "parameter.label-swing");
        final StaticSelector partRenderer = instanceMethod(resolver, "part.renderer");
        final StaticSelector partNode = classSelector(resolver, "part.node-class");
        final StaticSelector partNodeSource = instanceMethod(resolver, "part.node-source");
        final StaticSelector partSource = classSelector(resolver, "part.source-class");
        final StaticSelector partSourceId = instanceMethod(resolver, "part.source-id");
        final StaticSelector partChildren = instanceMethod(resolver, "part.source-children");
        final StaticSelector idValue = instanceMethod(resolver, "id.value");

        final List<ClassFileTransformer> transformers = List.of(
            new DeformerTreeRendererMethodTransformer(
                deformerRenderer.ownerInternalName(), deformerRenderer.memberName(), deformerRenderer.descriptor(), loader
            ),
            new DeformerControlRowRendererMethodTransformer(
                deformerControlRenderer.ownerInternalName(), deformerControlRenderer.memberName(),
                deformerControlRenderer.descriptor(), loader
            ),
            new ParameterRowMethodTransformer(
                singleClass.ownerInternalName(),
                Set.of(method(singleCreate), method(singleSelection)),
                false,
                loader
            ),
            new ParameterRowMethodTransformer(
                doubleClass.ownerInternalName(),
                Set.of(method(doubleCreate), method(doubleSelection)),
                false,
                loader
            ),
            new ParameterRowMethodTransformer(
                folderClass.ownerInternalName(),
                Set.of(method(folderCreatePrimary), method(folderCreateSecondary), method(folderSelection)),
                true,
                loader
            ),
            new PartTreeRendererMethodTransformer(
                partRenderer.ownerInternalName(), partRenderer.memberName(), partRenderer.descriptor(), loader
            )
        );
        final Set<String> targetClassNames = new LinkedHashSet<>();
        for (StaticSelector selector : List.of(
            deformerRenderer, deformerControlRenderer, singleClass, doubleClass, folderClass, partRenderer
        )) {
            targetClassNames.add(selector.ownerInternalName().replace('/', '.'));
        }

        return new VerifiedControlAppearanceHookInstaller(
            instrumentation,
            loader,
            hostGeneration,
            transformers,
            targetClassNames,
            new NativeDeformerTreeAppearanceBridge.Selectors(
                deformerRowSource.ownerInternalName(), deformerRowSource.memberName(),
                deformerSource.ownerInternalName(), deformerId.memberName(),
                artMeshSource.ownerInternalName(), artMeshId.memberName(), idValue.memberName(), loader
            ),
            new NativeDeformerControlRowAppearanceBridge.Selectors(
                deformerControlRenderer.ownerInternalName(), deformerControlOuter.memberName(),
                deformerControlOuterClass.ownerInternalName(), deformerControlTree.memberName(),
                deformerControlTreeClass.ownerInternalName(), "getPathForRow",
                deformerRowSource.ownerInternalName(), deformerRowSource.memberName(),
                deformerSource.ownerInternalName(), deformerId.memberName(), idValue.memberName(), loader
            ),
            new NativeParameterAppearanceBridge.Selectors(
                singleClass.ownerInternalName(), doubleClass.ownerInternalName(), folderClass.ownerInternalName(),
                parameterSource.memberName(), secondaryParameterSource.memberName(), folderSource.memberName(),
                parameterLabel.memberName(), secondaryParameterLabel.memberName(), folderLabel.memberName(),
                parameterSourceClass.ownerInternalName(), folderSourceClass.ownerInternalName(),
                parameterSourceId.memberName(), folderSourceId.memberName(), idValue.memberName(),
                labelClass.ownerInternalName(), labelSwing.memberName(), loader
            ),
            new NativePartTreeAppearanceBridge.Selectors(
                partNode.ownerInternalName(), partNodeSource.memberName(), partSource.ownerInternalName(),
                deformerSource.ownerInternalName(), artMeshSource.ownerInternalName(),
                partSourceId.memberName(), idValue.memberName(), partChildren.memberName(), loader
            ),
            coordinator
        );
    }

    static boolean supportsStaticProfile(final String cubismVersion) {
        return Set.of("5.2.03", "5.3.02", "5.3.03").contains(cubismVersion);
    }

    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        try {
            NativeDeformerTreeAppearanceBridge.install(
                hostGeneration, deformerTreeSelectors, new DeformerTreeControlAppearanceProvider(coordinator)
            );
            NativeDeformerControlRowAppearanceBridge.install(
                hostGeneration, deformerControlSelectors, new DeformerControlRowAppearanceProvider(coordinator)
            );
            NativeParameterAppearanceBridge.install(
                parameterSelectors, new ParameterControlAppearanceProvider(hostGeneration, coordinator)
            );
            NativePartTreeAppearanceBridge.install(
                hostGeneration, partSelectors, new PartTreeControlAppearanceProvider(coordinator)
            );
            for (ClassFileTransformer transformer : transformers) instrumentation.addTransformer(transformer, true);
            final List<Class<?>> loadedTargets = loadedTargets();
            if (!loadedTargets.isEmpty()) instrumentation.retransformClasses(loadedTargets.toArray(Class<?>[]::new));
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        for (ClassFileTransformer transformer : transformers) instrumentation.removeTransformer(transformer);
        NativeParameterAppearanceBridge.uninstall();
        NativePartTreeAppearanceBridge.uninstall();
        NativeDeformerControlRowAppearanceBridge.uninstall();
        NativeDeformerTreeAppearanceBridge.uninstall();
        try {
            final List<Class<?>> loadedTargets = loadedTargets();
            if (!loadedTargets.isEmpty()) instrumentation.retransformClasses(loadedTargets.toArray(Class<?>[]::new));
        } catch (Throwable ignored) {
            // Styles and callbacks are already revoked; bytecode restoration remains best-effort on shutdown.
        }
    }

    private List<Class<?>> loadedTargets() {
        final List<Class<?>> targets = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (targetClassNames.contains(loaded.getName())
                && loaded.getClassLoader() == hostClassLoader
                && instrumentation.isModifiableClass(loaded)) {
                targets.add(loaded);
            }
        }
        return targets;
    }

    private static ParameterRowMethodTransformer.MethodSelector method(final StaticSelector selector) {
        return new ParameterRowMethodTransformer.MethodSelector(selector.memberName(), selector.descriptor());
    }

    private static StaticSelector selector(
        final VerifiedMemberResolver resolver,
        final String suffix,
        final StaticSelector.Kind kind
    ) {
        final StaticSelector selector = resolver.verifiedSelector(PREFIX + suffix);
        if (selector.kind() != kind) throw new IllegalArgumentException("verified control-appearance selector kind mismatch");
        return selector;
    }

    private static StaticSelector classSelector(final VerifiedMemberResolver resolver, final String suffix) {
        return selector(resolver, suffix, StaticSelector.Kind.CLASS);
    }

    private static StaticSelector constructor(final VerifiedMemberResolver resolver, final String suffix) {
        return selector(resolver, suffix, StaticSelector.Kind.CONSTRUCTOR);
    }

    private static StaticSelector instanceMethod(final VerifiedMemberResolver resolver, final String suffix) {
        final StaticSelector selector = selector(resolver, suffix, StaticSelector.Kind.METHOD);
        if ((selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("verified control-appearance selector must be an instance method");
        }
        return selector;
    }

    private static StaticSelector staticMethod(final VerifiedMemberResolver resolver, final String suffix) {
        final StaticSelector selector = selector(resolver, suffix, StaticSelector.Kind.METHOD);
        if ((selector.requiredAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("verified control-appearance selector must be a static method");
        }
        return selector;
    }

    private static StaticSelector instanceField(final VerifiedMemberResolver resolver, final String suffix) {
        final StaticSelector selector = selector(resolver, suffix, StaticSelector.Kind.FIELD);
        if ((selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("verified control-appearance selector must be an instance field");
        }
        return selector;
    }
}
