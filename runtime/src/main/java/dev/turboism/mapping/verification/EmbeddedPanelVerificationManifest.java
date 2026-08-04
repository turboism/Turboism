package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for exact-version Cubism embedded-panel providers. */
public final class EmbeddedPanelVerificationManifest {

    public static final String VERIFICATION_ID =
        "cubism-5.3.02.ui-embedded-panel.static";
    public static final String RECORD_SHA256 =
        "c153981a7b236e7d027dabebd649d8c8bbb6b82f27cf50c5ebb4561afa690b72";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41_922_739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.embedded-panel";
    public static final String CAPABILITY_ID = "cubism.editor-ui.embedded-panel";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-panel.app-controller.class",
        "cubism.ui-panel.app-controller.instance",
        "cubism.ui-panel.app-controller.main-frame",
        "cubism.ui-panel.app-controller.repaint",
        "cubism.ui-panel.main-frame.class",
        "cubism.ui-panel.main-frame.dock-manager",
        "cubism.ui-panel.dock.class",
        "cubism.ui-panel.dock.palette-manager",
        "cubism.ui-panel.dock.set-palette-visible",
        "cubism.ui-panel.dock.update-window-menu",
        "cubism.ui-panel.palette-manager.class",
        "cubism.ui-panel.palette-manager.get",
        "cubism.ui-panel.palette-manager.add",
        "cubism.ui-panel.palette-manager.close",
        "cubism.ui-panel.palette-manager.current-workspace",
        "cubism.ui-panel.workspace.class",
        "cubism.ui-panel.workspace.activate",
        "cubism.ui-panel.workspace.palette-box-for",
        "cubism.ui-panel.palette-box.class",
        "cubism.ui-panel.palette-box.remove-tab",
        "cubism.ui-panel.palette-manager.remove-update",
        "cubism.ui-panel.palette-manager.main-frame-window",
        "cubism.ui-panel.palette-manager.verify-cleanup",
        "cubism.ui-panel.palette-manager.fire-state",
        "cubism.ui-panel.workspace.add-palette-frame",
        "cubism.ui-panel.workspace.remove-palette-frame",
        "cubism.ui-panel.workspace.first-palette-box",
        "cubism.ui-panel.palette-box.create",
        "cubism.ui-panel.palette-box.add-tab",
        "cubism.ui-panel.palette-box.set-selected",
        "cubism.ui-panel.palette-box.palettes",
        "cubism.ui-panel.palette-box.tab-panel",
        "cubism.ui-panel.tab-panel.entries",
        "cubism.ui-panel.tab-entry.palette",
        "cubism.ui-panel.tab-entry.button",
        "cubism.ui-panel.widget.jcomponent",
        "cubism.ui-panel.palette-frame.create",
        "cubism.ui-panel.palette-frame.root",
        "cubism.ui-panel.palette-frame.window",
        "cubism.ui-panel.palette-frame.dispose",
        "cubism.ui-panel.palette-frame.raw-disposed",
        "cubism.ui-panel.floating-tab-close.operation",
        "cubism.ui-panel.floating-tab-close.palette-field",
        "cubism.ui-panel.workspace.root-container",
        "cubism.ui-panel.root.component",
        "cubism.ui-panel.split.contents",
        "cubism.ui-panel.split.remove",
        "cubism.ui-panel.component.palette-count",
        "cubism.ui-panel.root.set-component",
        "cubism.ui-panel.window.set-visible",
        "cubism.ui-panel.palette-id.class",
        "cubism.ui-panel.palette-id.create",
        "cubism.ui-panel.palette.class",
        "cubism.ui-panel.palette.create",
        "cubism.ui-panel.palette.id",
        "cubism.ui-panel.palette.set-panel",
        "cubism.ui-panel.dock-tab-popup.operation",
        "cubism.ui-panel.dock-tab-popup.palette-field",
        "cubism.ui-panel.dock-tab-popup.menu-append",
        "cubism.ui-panel.swing-container.class",
        "cubism.ui-panel.swing-container.create",
        "cubism.ui-panel.main-frame.window",
        "cubism.ui-panel.window.menu-bar",
        "cubism.ui-panel.menu-bar.menus",
        "cubism.ui-panel.widget.name",
        "cubism.ui-panel.widget.set-name",
        "cubism.ui-panel.widget.revalidate",
        "cubism.ui-panel.widget.repaint",
        "cubism.ui-panel.menu.items",
        "cubism.ui-panel.menu.add",
        "cubism.ui-panel.menu.swing",
        "cubism.ui-panel.menu-item.create",
        "cubism.ui-panel.menu-item.swing"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        if (artifact.size() == EmbeddedPanelVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(EmbeddedPanelVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                EmbeddedPanelVerificationManifest52.VERIFICATION_ID,
                EmbeddedPanelVerificationManifest52.RECORD_SHA256,
                EmbeddedPanelVerificationManifest52.CUBISM_VERSION,
                EmbeddedPanelVerificationManifest52.PROFILE_ID,
                EmbeddedPanelVerificationManifest52.ARTIFACT_SIZE,
                EmbeddedPanelVerificationManifest52.ARTIFACT_SHA256
            );
        }
        if (artifact.size() == ARTIFACT_SIZE && artifact.sha256().equals(ARTIFACT_SHA256)) {
            return manifest(
                VERIFICATION_ID,
                RECORD_SHA256,
                CUBISM_VERSION,
                PROFILE_ID,
                ARTIFACT_SIZE,
                ARTIFACT_SHA256
            );
        }
        throw new IllegalArgumentException(
            "host artifact is not a reviewed Cubism embedded-panel artifact"
        );
    }

    public static AdmissionEvidence admissionForArtifact(final HostArtifactDigest artifact) {
        final PinnedVerifiedResolverWorkflow.Manifest manifest = forArtifact(artifact);
        return new AdmissionEvidence(
            manifest.cubismVersion(),
            manifest.artifactSize(),
            manifest.artifactSha256(),
            manifest.adapterSliceId(),
            manifest.recordSha256()
        );
    }

    public record AdmissionEvidence(
        String cubismVersion,
        long artifactSize,
        String artifactSha256,
        String adapterSliceId,
        String recordSha256
    ) {
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest(
        final String verificationId,
        final String recordSha256,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256,
            cubismVersion,
            profileId,
            artifactSize,
            artifactSha256,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private EmbeddedPanelVerificationManifest() {
    }
}
