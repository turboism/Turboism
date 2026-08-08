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

    public VerifiedRuntimeHostAdaptersFactory() {
        this(
            new VerifiedProjectWorkspaceResolverFactory(),
            new VerifiedClipMaskResolverFactory(),
            new VerifiedStatusBarResolverFactory()
        );
    }

    VerifiedRuntimeHostAdaptersFactory(
        final VerifiedProjectWorkspaceResolverFactory projectResolverFactory,
        final VerifiedClipMaskResolverFactory clipMaskResolverFactory
    ) {
        this(
            projectResolverFactory,
            clipMaskResolverFactory,
            new VerifiedStatusBarResolverFactory()
        );
    }

    VerifiedRuntimeHostAdaptersFactory(
        final VerifiedProjectWorkspaceResolverFactory projectResolverFactory,
        final VerifiedClipMaskResolverFactory clipMaskResolverFactory,
        final VerifiedStatusBarResolverFactory statusBarResolverFactory
    ) {
        this.projectResolverFactory = Objects.requireNonNull(projectResolverFactory, "projectResolverFactory");
        this.clipMaskResolverFactory = Objects.requireNonNull(clipMaskResolverFactory, "clipMaskResolverFactory");
        this.statusBarResolverFactory = Objects.requireNonNull(statusBarResolverFactory, "statusBarResolverFactory");
    }

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
                    composed, projectResolver, panelResolver
                );
            }
            // unauthorized recent-preview slices fail closed: the base bundle is kept.
        }
        return composed;
    }

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
