package dev.turboism.bootstrap;

import dev.turboism.automesh.host.AutoMeshFunnelTransformer;
import dev.turboism.automesh.host.AutoMeshHostProfile;
import dev.turboism.automesh.host.AutoMeshPanelAttachTransformer;
import dev.turboism.automesh.host.NativeAutoMeshBridge;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.sdk.plugin.Registration;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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

    private final ClassLoader hostClassLoader;
    private final Consumer<String> diagnostics;
    private final HookInstallSession session;
    private Registration bridge;

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
        Objects.requireNonNull(instrumentation, "instrumentation");
        final NativeAutoMeshBridge.Handler bridgeHandler = Objects.requireNonNull(handler, "handler");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        if (!supportsExactCubismVersion(requested.hostVersion())) {
            throw new IllegalArgumentException("auto mesh guard requires exact Cubism " + SUPPORTED_CUBISM_VERSIONS);
        }
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        transformers.add(transformerFor(requested, requested.modelingFunnelOwner(), requested.modelingFunnel()));
        transformers.add(transformerFor(requested, requested.meshEditorFunnelOwner(), requested.meshEditorFunnel()));
        for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(requested)) {
            transformers.add(new AutoMeshPanelAttachTransformer(site, hostClassLoader, diagnostics));
        }
        final Set<String> owners = new TreeSet<>();
        for (final AutoMeshFunnelTransformer.Site site : AutoMeshFunnelTransformer.sitesOf(requested)) {
            owners.add(binaryNameOf(site.ownerInternalName()));
        }
        for (final AutoMeshPanelAttachTransformer.Site site : AutoMeshPanelAttachTransformer.sitesOf(requested)) {
            owners.add(binaryNameOf(site.ownerInternalName()));
        }
        this.session = HookInstallSession.builder(instrumentation, hostClassLoader, owners)
                .transformers(transformers)
                .bridge(() -> bridge = NativeAutoMeshBridge.install(bridgeHandler, diagnostics), () -> {
                    if (bridge != null) {
                        bridge.close();
                        bridge = null;
                    }
                })
                .restorePolicy(HookInstallSession.RestorePolicy.RETRYABLE_BEFORE_BRIDGE_CLOSE)
                .installFailureMessage("Verified auto mesh guard installation failed")
                .restoreFailureMessage("Verified auto mesh guard restoration failed")
                .build();
    }

    static boolean supportsExactCubismVersion(final String version) {
        return version != null && SUPPORTED_CUBISM_VERSIONS.contains(version);
    }

    /**
     * Installs the bridge and transformers, then retransforms already-loaded funnel and panel owners.
     *
     * @throws Exception when the guard cannot be proven installable
     */
    void install() throws Exception {
        session.install();
    }

    @Override
    public void close() {
        session.close();
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
