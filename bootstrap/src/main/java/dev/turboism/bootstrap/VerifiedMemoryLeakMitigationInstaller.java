package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.memory.MemoryLeakMitigationBridge;
import dev.turboism.adapter.cubism.optimization.memory.MemoryLeakMitigationTarget;
import dev.turboism.adapter.cubism.optimization.memory.MemoryLeakMitigationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified memory, GL buffer/texture, undo, image resource, and UI
 * lifecycle leak mitigations across reviewed Cubism artifacts.
 */
final class VerifiedMemoryLeakMitigationInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.memory-leak-mitigation";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = 21;

    static final int EXPECTED_OWNERS = 21;

    private final InstallContext context;
    private VerifiedInstall<MemoryLeakMitigationTarget> hook;

    VerifiedMemoryLeakMitigationInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && Files.isRegularFile(artifact)
                && MemoryLeakMitigationTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private WeaveSpec<MemoryLeakMitigationTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                "memory leak mitigation",
                VerifiedMemoryLeakMitigationInstaller::resolve,
                new WeavePlan<>(
                        VerifiedMemoryLeakMitigationInstaller::targets,
                        VerifiedMemoryLeakMitigationInstaller::sitesProven),
                (target, launch) -> {
                    final MemoryLeakMitigationBridge bridge =
                            new MemoryLeakMitigationBridge(launch.hostLoader(), target);
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<MemoryLeakMitigationTarget> resolve(final Path artifact) throws Exception {
        return MemoryLeakMitigationTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final MemoryLeakMitigationTarget target, final WeaveSource source)
            throws Exception {
        for (final String dependency : target.targetClasses()) {
            source.load(dependency);
        }
        final MemoryLeakMitigationTransformer transformer =
                new MemoryLeakMitigationTransformer(source.loader(), source.artifact(), target);
        final List<String> owners = target.rewrittenOwners();
        final List<WeaveTarget> weaveTargets = new ArrayList<>(owners.size());
        for (final String owner : owners) {
            final Class<?> loaded = source.load(owner);
            weaveTargets.add(
                    new WeaveTarget(loaded, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
        }
        return weaveTargets;
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(MemoryLeakMitigationTransformer.class)
                .sites();
        return sites == MemoryLeakMitigationTransformer.EXPECTED_SITES ? null : "sites=" + sites;
    }

    @Override
    public boolean admits(final InstallContext context) {
        try {
            return admitted(context.artifact(), context.policy(), true, context.jvmFeature());
        } catch (final IOException unreadable) {
            return false;
        }
    }

    @Override
    public synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<MemoryLeakMitigationTarget> installed)) {
            throw new IllegalStateException("memory leak mitigation refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            hook.close();
        }
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
