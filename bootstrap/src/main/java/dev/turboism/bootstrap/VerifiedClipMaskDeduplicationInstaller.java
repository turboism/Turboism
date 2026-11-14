package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationBridge;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationTarget;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationTransformer;
import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskScissorBridge;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact admission, weaving and restoration for the zero-quality-loss clipping-mask
 * deduplication optimization.
 *
 * <p>One reviewed render-system class is rewritten at the three mask-pass call sites, and closing
 * re-captures that class and proves it hashes back to the bytes the transformer observed. The
 * scissor-limited clear sub-mode rides the dedupe weave, so it is reported from {@link WeaveEffects}:
 * only once the weave is proven, and withdrawn before the restoration capture.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's own two bridges
 * and the sub-mode decision it owns.</p>
 */
final class VerifiedClipMaskDeduplicationInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.clip-mask-dedupe";

    /** The admission row this hook froze: the contributor's preference is the only switch. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.ANY);

    /** Mask-pass call sites the one transformer rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 3;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private final InstallContext context;
    private ClipMaskDeduplicationBridge bridge;
    private ClipMaskScissorBridge scissorBridge;
    private String scissorFailure;
    /** True while the scissor sub-mode is reported as installed, so closing reports itself. */
    private boolean scissorReported;

    private VerifiedInstall<String> hook;

    VerifiedClipMaskDeduplicationInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        context = InstallContext.of(instrumentation, artifact, loader);
    }

    /**
     * @return the hook's spec, whose bridge publishes the dedupe slots and then the scissor sub-mode's,
     *     and whose side effects report the sub-mode from the point where this installer decides it
     */
    private WeaveSpec<String> spec() {
        return new WeaveSpec<String>(
                ADMISSION,
                "clip mask dedupe",
                VerifiedClipMaskDeduplicationInstaller::resolve,
                new WeavePlan<>(VerifiedClipMaskDeduplicationInstaller::targets, WeavePlan.PostCheck.NONE),
                (version, launch) -> {
                    bridge = new ClipMaskDeduplicationBridge(launch.hostLoader(), version);
                    try {
                        scissorBridge = new ClipMaskScissorBridge(launch.hostLoader(), version);
                    } catch (Exception | LinkageError refused) {
                        scissorFailure = refused.toString();
                    }
                    bridge.attachScissorBridge(scissorBridge);
                    return BridgeLayer.of(this::publish, this::withdraw);
                },
                new WeaveEffects<String>() {
                    @Override
                    public void confirmed(final VerifiedInstall<String> installed) {
                        reportScissor();
                    }

                    @Override
                    public void withdrawn(final VerifiedInstall<String> closing) {
                        reportScissorClosed();
                    }
                });
    }

    private static Optional<String> resolve(final Path artifact) throws Exception {
        return ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final String cubismVersion, final WeaveSource source) throws Exception {
        final String renderSystemOwner = ClipMaskDeduplicationTarget.renderSystemOwner(cubismVersion);
        final String renderMethodDescriptor = ClipMaskDeduplicationTarget.renderMethodDescriptor(cubismVersion);
        final Class<?> target = source.load(renderSystemOwner);
        final Map<String, byte[]> references = new LinkedHashMap<>();
        references.put(renderSystemOwner, source.referenceBytes(target));
        final ClipMaskDeduplicationTransformer transformer = new ClipMaskDeduplicationTransformer(
                source.loader(), source.artifact(), renderSystemOwner, renderMethodDescriptor, references);
        return List.of(
                WeaveTarget.single(target, transformer, EXPECTED_MATCHES, transformer::sites, transformer::failure));
    }

    /** Publishes the dedupe slots and then the scissor sub-mode's, in that order. */
    private void publish() throws Exception {
        bridge.install();
        if (scissorBridge == null) return;
        try {
            scissorBridge.install();
        } catch (Exception | LinkageError refused) {
            scissorFailure = refused.toString();
            bridge.attachScissorBridge(null);
            try {
                scissorBridge.close();
            } catch (Exception | LinkageError ignored) {
                // The bridge refused installation before owning any slot.
            }
            scissorBridge = null;
        }
    }

    /** Withdraws the sub-mode first and the dedupe slots last, as the hand-rolled close did. */
    private void withdraw() throws Exception {
        try {
            if (scissorBridge != null) scissorBridge.close();
        } finally {
            bridge.close();
        }
    }

    /**
     * Reports the scissor-limited clear sub-mode from the point where this installer decides
     * it: the sub-mode rides the dedupe weave, so only the bridge's own switch and whether it
     * claimed its slots can still refuse it.
     */
    private void reportScissor() {
        if (scissorBridge == null) {
            NativeOptimizationDiagnostics.installed(
                    OptimizationCatalog.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR,
                    "not-installed",
                    scissorFailure == null ? "not-attempted" : "refused");
        } else if (!ClipMaskScissorBridge.enabledByPreference()) {
            NativeOptimizationDiagnostics.installed(
                    OptimizationCatalog.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR, "not-installed", "disabled-by-property");
        } else {
            scissorReported = true;
            NativeOptimizationDiagnostics.installed(
                    OptimizationCatalog.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR, "installed", "verified");
        }
    }

    /** The sub-mode cannot survive the weave it rides, so a closed installer proves nothing. */
    private void reportScissorClosed() {
        if (!scissorReported) return;
        scissorReported = false;
        NativeOptimizationDiagnostics.installed(
                OptimizationCatalog.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR, "not-installed", "closed");
    }

    int sites() {
        return transformer().sites();
    }

    String cubismVersion() {
        return hook.contract();
    }

    String failure() {
        return transformer().failure();
    }

    String scissorFailure() {
        return scissorFailure;
    }

    Map<String, Long> snapshot() {
        final Map<String, Long> merged = new LinkedHashMap<>(bridge.snapshot());
        if (scissorBridge != null) {
            scissorBridge.snapshot().forEach((key, value) -> merged.put("scissor." + key, value));
        } else {
            merged.put("scissor.active", 0L);
        }
        return Map.copyOf(merged);
    }

    private ClipMaskDeduplicationTransformer transformer() {
        return hook.targets().get(0).transformer(ClipMaskDeduplicationTransformer.class);
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not a reviewed clip-mask-dedupe host
     * @throws Exception when registration, retransformation or the site proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<String> installed)) {
            throw new IllegalStateException("clip mask dedupe refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
