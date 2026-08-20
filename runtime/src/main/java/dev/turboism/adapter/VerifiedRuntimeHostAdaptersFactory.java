package dev.turboism.adapter;

import dev.turboism.adapter.host.HostVerificationEvidence;
import dev.turboism.mapping.verification.RecentPreviewVerificationManifest;
import dev.turboism.mapping.verification.VerifiedClipMaskResolverFactory;
import dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedProjectWorkspaceResolverFactory;
import dev.turboism.mapping.verification.VerifiedStatusBarResolverFactory;
import dev.turboism.mapping.verification.VerifiedAutoBackupResolverFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Production composition helper for pinned, reviewed host-adapter verification evidence.
 *
 * <p>The production entrypoint is {@link #create(HostVerificationEvidence)}. Its typed evidence
 * validates that every present slice attests the same artifact and defining classloader before
 * the independently pinned resolver factories are invoked.</p>
 */
public final class VerifiedRuntimeHostAdaptersFactory {

    private final VerifiedProjectWorkspaceResolverFactory projectResolverFactory;
    private final VerifiedClipMaskResolverFactory clipMaskResolverFactory;
    private final VerifiedStatusBarResolverFactory statusBarResolverFactory;
    private final java.util.Locale locale;

    public VerifiedRuntimeHostAdaptersFactory() {
        this(
            new VerifiedProjectWorkspaceResolverFactory(),
            new VerifiedClipMaskResolverFactory(),
            new VerifiedStatusBarResolverFactory(),
            java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY)
        );
    }

    /** Production factory: receives the one startup-resolved effective locale. */
    public VerifiedRuntimeHostAdaptersFactory(final java.util.Locale effectiveLocale) {
        this(
            new VerifiedProjectWorkspaceResolverFactory(),
            new VerifiedClipMaskResolverFactory(),
            new VerifiedStatusBarResolverFactory(),
            effectiveLocale
        );
    }

    VerifiedRuntimeHostAdaptersFactory(
        final VerifiedProjectWorkspaceResolverFactory projectResolverFactory,
        final VerifiedClipMaskResolverFactory clipMaskResolverFactory
    ) {
        this(
            projectResolverFactory,
            clipMaskResolverFactory,
            new VerifiedStatusBarResolverFactory(),
            java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY)
        );
    }

    VerifiedRuntimeHostAdaptersFactory(
        final VerifiedProjectWorkspaceResolverFactory projectResolverFactory,
        final VerifiedClipMaskResolverFactory clipMaskResolverFactory,
        final VerifiedStatusBarResolverFactory statusBarResolverFactory
    ) {
        this(
            projectResolverFactory,
            clipMaskResolverFactory,
            statusBarResolverFactory,
            java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY)
        );
    }

    VerifiedRuntimeHostAdaptersFactory(
        final VerifiedProjectWorkspaceResolverFactory projectResolverFactory,
        final VerifiedClipMaskResolverFactory clipMaskResolverFactory,
        final VerifiedStatusBarResolverFactory statusBarResolverFactory,
        final java.util.Locale locale
    ) {
        this.projectResolverFactory = Objects.requireNonNull(projectResolverFactory, "projectResolverFactory");
        this.clipMaskResolverFactory = Objects.requireNonNull(clipMaskResolverFactory, "clipMaskResolverFactory");
        this.statusBarResolverFactory = Objects.requireNonNull(statusBarResolverFactory, "statusBarResolverFactory");
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    /**
     * Builds the host adapter bundle from verification evidence, connecting only the slices that
     * actually carry evidence and leaving every other adapter in safe mode. The project/workspace slice
     * is always required; clip-mask, auto-backup, status-bar, and embedded-panel slices are optional and
     * layered on when present. The recent-preview surface additionally requires the project and panel
     * resolvers to satisfy the recent-preview verification manifest, and fails closed to the base bundle
     * when they do not.
     *
     * @param evidence reviewed records, verified artifacts, and host class loaders per slice
     * @return the composed adapter bundle
     * @throws NullPointerException when {@code evidence} is null
     * @throws IOException when a reviewed record or verified artifact cannot be read
     */
    public RuntimeHostAdapters create(final HostVerificationEvidence evidence) throws IOException {
        Objects.requireNonNull(evidence, "evidence");
        final HostVerificationEvidence.Slice project = evidence.projectWorkspace();
        final RuntimeHostAdapters base;
        if (evidence.clipMask().isEmpty()) {
            base = projectWorkspace(
                project.reviewedRecord(),
                project.verifiedArtifact(),
                project.hostClassLoader()
            );
        } else {
            final HostVerificationEvidence.Slice clipMask = evidence.clipMask().orElseThrow();
            final VerifiedMemberResolver projectResolver = projectResolverFactory.create(
                project.reviewedRecord(),
                project.verifiedArtifact(),
                project.hostClassLoader()
            );
            final VerifiedMemberResolver clipMaskResolver = clipMaskResolverFactory.create(
                clipMask.reviewedRecord(),
                clipMask.verifiedArtifact(),
                clipMask.hostClassLoader()
            );
            base = RuntimeHostAdapters.withVerifiedProjectWorkspaceAndClipMask(
                projectResolver,
                clipMaskResolver
            );
        }
        RuntimeHostAdapters composed = base;
        if (evidence.autoBackup().isPresent()) {
            final HostVerificationEvidence.Slice autoBackup = evidence.autoBackup().orElseThrow();
            final VerifiedMemberResolver autoBackupResolver = new VerifiedAutoBackupResolverFactory().create(
                autoBackup.reviewedRecord(),
                autoBackup.verifiedArtifact(),
                autoBackup.hostClassLoader()
            );
            composed = RuntimeHostAdapters.withVerifiedAutoBackup(composed, autoBackupResolver);
        }
        if (evidence.statusBar().isPresent()) {
            final HostVerificationEvidence.Slice statusBar = evidence.statusBar().orElseThrow();
            final VerifiedMemberResolver statusBarResolver = statusBarResolverFactory.create(
                statusBar.reviewedRecord(),
                statusBar.verifiedArtifact(),
                statusBar.hostClassLoader()
            );
            composed = RuntimeHostAdapters.withVerifiedStatusBar(composed, statusBarResolver);
        }
        if (evidence.embeddedPanel().isPresent()) {
            final HostVerificationEvidence.Slice panel = evidence.embeddedPanel().orElseThrow();
            final VerifiedMemberResolver projectResolver = projectResolverFactory.create(
                project.reviewedRecord(),
                project.verifiedArtifact(),
                project.hostClassLoader()
            );
            final VerifiedMemberResolver panelResolver = new VerifiedEmbeddedPanelResolverFactory().create(
                panel.reviewedRecord(),
                panel.verifiedArtifact(),
                panel.hostClassLoader()
            );
            if (RecentPreviewVerificationManifest.authorizes(projectResolver, panelResolver)) {
                composed = RuntimeHostAdapters.withVerifiedRecentPreview(
                    composed, projectResolver, panelResolver, locale
                );
            }
            // unauthorized recent-preview slices fail closed: the base bundle is kept.
        }
        return composed;
    }

    /**
     * Builds a bundle with only the project/workspace slice connected; every other adapter stays in
     * safe mode until it receives its own evidence.
     *
     * @param reviewedRecord path to the reviewed member record for this slice
     * @param hostArtifact path to the host artifact whose digest was verified
     * @param hostClassLoader loader through which the verified host members are resolved
     * @return a bundle with the verified project/workspace adapter installed
     * @throws IOException when the reviewed record or host artifact cannot be read
     */
    public RuntimeHostAdapters projectWorkspace(
        final Path reviewedRecord,
        final Path hostArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        final VerifiedMemberResolver resolver = projectResolverFactory.create(
            reviewedRecord,
            hostArtifact,
            hostClassLoader
        );
        return RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);
    }

}
