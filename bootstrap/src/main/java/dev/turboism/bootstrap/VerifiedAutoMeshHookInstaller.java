package dev.turboism.bootstrap;

import dev.turboism.automesh.host.AutoMeshContainer;
import dev.turboism.automesh.host.AutoMeshFunnelTransformer;
import dev.turboism.automesh.host.AutoMeshHostProfile;
import dev.turboism.automesh.host.AutoMeshPanelAttachTransformer;
import dev.turboism.automesh.host.NativeAutoMeshBridge;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.plugin.Registration;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thin, reversible installer for the exact Auto Mesh funnel guards and container attaches.
 *
 * <p>The installer consumes an already verified selector tuple. It does not create a verification
 * record, infer host readiness from static selectors, or wire itself into global startup
 * scheduling. The bridge is installed before the transformers, and dropped only after the host
 * classes are restored, so transformed bytecode never calls a removed callback.</p>
 */
final class VerifiedAutoMeshHookInstaller implements AutoCloseable {

    /** Exact Cubism releases whose reviewed artifacts carry an auto-mesh funnel profile. */
    static final Set<String> SUPPORTED_CUBISM_VERSIONS = Set.copyOf(AutoMeshHostProfile.supportedHostVersions());

    static final String MODELING_FUNNEL_OWNER_ALIAS = AutoMeshHostProfile.MODELING_FUNNEL_OWNER_ALIAS;
    static final String MODELING_FUNNEL_ALIAS = AutoMeshHostProfile.MODELING_FUNNEL_ALIAS;
    static final String MESH_EDITOR_FUNNEL_OWNER_ALIAS = AutoMeshHostProfile.MESH_EDITOR_FUNNEL_OWNER_ALIAS;
    static final String MESH_EDITOR_FUNNEL_ALIAS = AutoMeshHostProfile.MESH_EDITOR_FUNNEL_ALIAS;
    static final String MODELING_DIALOG_PANEL_CONSTRUCTOR_ALIAS =
            AutoMeshHostProfile.MODELING_DIALOG_PANEL_CONSTRUCTOR_ALIAS;
    static final String MODELING_POPUP_DIALOG_CONSTRUCTOR_ALIAS =
            AutoMeshHostProfile.MODELING_POPUP_DIALOG_CONSTRUCTOR_ALIAS;
    static final String MESH_EDITOR_POPUP_DIALOG_CONSTRUCTOR_ALIAS =
            AutoMeshHostProfile.MESH_EDITOR_POPUP_DIALOG_CONSTRUCTOR_ALIAS;

    private final Instrumentation instrumentation;
    private final List<java.lang.instrument.ClassFileTransformer> transformers;
    private final Set<String> targetClassNames;
    private final ClassLoader hostClassLoader;
    private final NativeAutoMeshBridge.Handler bridgeHandler;
    private final Consumer<String> diagnostics;
    private final AtomicBoolean installed = new AtomicBoolean();
    private Registration bridge;
    private boolean transformersRemoved;

    /**
     * Builds the production installer from a reviewed host profile.
     *
     * @param instrumentation JVM instrumentation of the running Editor
     * @param profile reviewed selectors for the loaded host artifact
     * @param handler runtime dispatch policy the guarded funnels report to
     * @param diagnostics receives every refusal and every swallowed policy failure
     * @param hostClassLoader loader that owns the host funnel classes
     * @throws IllegalArgumentException if the profile is not an exact supported release
     */
    VerifiedAutoMeshHookInstaller(
            final Instrumentation instrumentation,
            final AutoMeshHostProfile profile,
            final NativeAutoMeshBridge.Handler handler,
            final Consumer<String> diagnostics,
            final ClassLoader hostClassLoader) {
        final AutoMeshHostProfile requested = Objects.requireNonNull(profile, "profile");
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.bridgeHandler = Objects.requireNonNull(handler, "handler");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (!supportsExactCubismVersion(requested.hostVersion())) {
            throw new IllegalArgumentException("auto mesh guard requires exact Cubism " + SUPPORTED_CUBISM_VERSIONS);
        }
        final List<java.lang.instrument.ClassFileTransformer> registered = new java.util.ArrayList<>();
        registered.add(transformerFor(requested, requested.modelingFunnelOwner(), requested.modelingFunnel()));
        registered.add(transformerFor(requested, requested.meshEditorFunnelOwner(), requested.meshEditorFunnel()));
        for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(requested)) {
            registered.add(new AutoMeshPanelAttachTransformer(site, hostClassLoader, diagnostics));
        }
        this.transformers = List.copyOf(registered);
        final Set<String> owners = new TreeSet<>();
        for (final AutoMeshFunnelTransformer.Site site : AutoMeshFunnelTransformer.sitesOf(requested)) {
            owners.add(binaryNameOf(site.ownerInternalName()));
        }
        for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(requested)) {
            owners.add(binaryNameOf(site.ownerInternalName()));
        }
        this.targetClassNames = Set.copyOf(owners);
    }

    static boolean supportsExactCubismVersion(final String version) {
        return version != null && SUPPORTED_CUBISM_VERSIONS.contains(version);
    }

    /**
     * Installs the bridge and then both transformers.
     *
     * <p>Registration happens before the retransformation scan, so a funnel that loads during the
     * scan is guarded too. Any failure restores the host classes before it propagates.</p>
     *
     * @throws Exception when the guard cannot be proven installable
     */
    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        try {
            bridge = NativeAutoMeshBridge.install(bridgeHandler, diagnostics);
            for (final java.lang.instrument.ClassFileTransformer transformer : transformers) {
                instrumentation.addTransformer(transformer, true);
            }
            transformersRemoved = false;
            VerifiedHookInstallerSupport.retransformIfLoaded(instrumentation, targetClassNames, hostClassLoader);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            try {
                close();
            } catch (Throwable cleanupFailure) {
                FatalErrors.rethrowIfFatal(cleanupFailure);
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Verified auto mesh guard installation failed", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (!installed.get()) {
            return;
        }
        if (!transformersRemoved) {
            for (final java.lang.instrument.ClassFileTransformer transformer : transformers) {
                instrumentation.removeTransformer(transformer);
            }
            transformersRemoved = true;
        }
        try {
            VerifiedHookInstallerSupport.retransformIfLoaded(instrumentation, targetClassNames, hostClassLoader);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // Keep the installation live: host bytes must be restored before callbacks disappear.
            throw new IllegalStateException("Verified auto mesh guard restoration failed", failure);
        }
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        installed.set(false);
    }

    private AutoMeshFunnelTransformer transformerFor(
            final AutoMeshHostProfile profile, final StaticSelector owner, final StaticSelector funnel) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(funnel, "funnel");
        if (owner.kind() != StaticSelector.Kind.CLASS
                || funnel.kind() != StaticSelector.Kind.METHOD
                || !funnel.ownerInternalName().equals(owner.ownerInternalName())
                || (funnel.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC) == 0) {
            throw new IllegalArgumentException("Verified auto mesh selectors do not match the funnel shape.");
        }
        return new AutoMeshFunnelTransformer(
                owner.ownerInternalName(), List.of(siteOf(profile, funnel)), hostClassLoader, diagnostics);
    }

    private static AutoMeshFunnelTransformer.Site siteOf(
            final AutoMeshHostProfile profile, final StaticSelector funnel) {
        return AutoMeshFunnelTransformer.sitesOf(profile).stream()
                .filter(site -> site.methodName().equals(funnel.memberName()))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("no guard site for the verified funnel " + funnel.memberName()));
    }

    private static String binaryNameOf(final String internalName) {
        return internalName.replace('/', '.');
    }
}
