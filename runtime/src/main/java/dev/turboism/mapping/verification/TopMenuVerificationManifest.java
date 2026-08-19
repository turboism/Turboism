package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime trust root for exact-version Cubism top-menu providers. */
public final class TopMenuVerificationManifest {

    public static final String VERIFICATION_ID = "cubism-5.3.02.ui-top-menu.static";
    public static final String RECORD_SHA256 =
        "fa98853b6b834a6937f27c8b48119d3e56cfabbf96305aa57ad3427c7393850e";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = ReviewedHostArtifacts.CUBISM_5_3_02.size();
    public static final String ARTIFACT_SHA256 = ReviewedHostArtifacts.CUBISM_5_3_02.sha256();
    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.top-menu";
    public static final String CAPABILITY_ID = "cubism.editor-ui.top-menu";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-top-menu.app-controller.instance",
        "cubism.ui-top-menu.app-controller.main-frame",
        "cubism.ui-top-menu.main-frame.window",
        "cubism.ui-top-menu.menu.add",
        "cubism.ui-top-menu.menu-bar.add",
        "cubism.ui-top-menu.menu-bar.menus",
        "cubism.ui-top-menu.menu-bar.swing",
        "cubism.ui-top-menu.menu.create",
        "cubism.ui-top-menu.menu-item.create",
        "cubism.ui-top-menu.menu.swing",
        "cubism.editor-command.canvas.begin-edit",
        "cubism.editor-command.canvas.canvas",
        "cubism.editor-command.canvas.companion",
        "cubism.editor-command.canvas.complete-pack",
        "cubism.editor-command.canvas.current-view-context",
        "cubism.editor-command.canvas.doc-size",
        "cubism.editor-command.canvas.edit-mode",
        "cubism.editor-command.canvas.edit-mode-main",
        "cubism.editor-command.canvas.end-edit-default",
        "cubism.editor-command.canvas.group-add",
        "cubism.editor-command.canvas.handler",
        "cubism.editor-command.canvas.is-editing",
        "cubism.editor-command.canvas.mark-dirty",
        "cubism.editor-command.canvas.model",
        "cubism.editor-command.canvas.model-source",
        "cubism.editor-command.canvas.modeling-doc",
        "cubism.editor-command.canvas.modeling-view",
        "cubism.editor-command.canvas.notify-size",
        "cubism.editor-command.canvas.pixel-height",
        "cubism.editor-command.canvas.pixel-width",
        "cubism.editor-command.canvas.scale-with-anchor",
        "cubism.editor-command.canvas.set-pixel-height",
        "cubism.editor-command.canvas.set-pixel-width",
        "cubism.editor-command.canvas.simple-undo",
        "cubism.editor-command.canvas.size-height",
        "cubism.editor-command.canvas.size-width",
        "cubism.editor-command.canvas.undo",
        "cubism.editor-command.canvas.undo-manager",
        "cubism.editor-command.canvas.undo-pos",
        "cubism.editor-command.canvas.vector2",
        "cubism.editor-command.canvas.vector2-zero",
        "cubism.editor-command.config.instance",
        "cubism.editor-command.config.read",
        "cubism.editor-command.config.write",
        "cubism.editor-command.external-app.companion",
        "cubism.editor-command.external-app.connected",
        "cubism.editor-command.external-app.get-port",
        "cubism.editor-command.external-app.get-remote",
        "cubism.editor-command.external-app.instance",
        "cubism.editor-command.external-app.manager",
        "cubism.editor-command.external-app.set-port",
        "cubism.editor-command.external-app.set-remote",
        "cubism.editor-command.external-app.start",
        "cubism.editor-command.external-app.stop",
        "cubism.editor-command.file.save-model",
        "cubism.editor-command.file.save-scene",
        "cubism.editor-command.file.scene-content",
        "cubism.editor-command.file.scene-document",
        "cubism.editor-command.grid.all-view-contexts",
        "cubism.editor-command.grid.color-create",
        "cubism.editor-command.grid.developer-setting",
        "cubism.editor-command.grid.entity",
        "cubism.editor-command.grid.entity-from-draw",
        "cubism.editor-command.grid.get-bold",
        "cubism.editor-command.grid.get-color",
        "cubism.editor-command.grid.get-jcolor",
        "cubism.editor-command.grid.get-spacing",
        "cubism.editor-command.grid.modeling-draw",
        "cubism.editor-command.grid.repaint-default",
        "cubism.editor-command.grid.set-color",
        "cubism.editor-command.grid.set-reset",
        "cubism.editor-command.grid.set-spacing",
        "cubism.editor-command.grid.update-manager",
        "cubism.editor-command.resize.guard",
        "cubism.editor-command.resize.guard-active",
        "cubism.editor-command.resize.guard-current",
        "cubism.editor-command.resize.scale-model",
        "cubism.ui-top-menu.widget.name",
        "cubism.ui-top-menu.widget.repaint",
        "cubism.ui-top-menu.widget.revalidate",
        "cubism.ui-top-menu.widget.set-name",
        "cubism.ui-top-menu.window.menu-bar"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        if (artifact.size() == TopMenuVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(TopMenuVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                TopMenuVerificationManifest52.VERIFICATION_ID,
                TopMenuVerificationManifest52.RECORD_SHA256,
                TopMenuVerificationManifest52.CUBISM_VERSION,
                TopMenuVerificationManifest52.PROFILE_ID,
                TopMenuVerificationManifest52.ARTIFACT_SIZE,
                TopMenuVerificationManifest52.ARTIFACT_SHA256
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
        throw new IllegalArgumentException("host artifact is not a reviewed Cubism top-menu artifact");
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

    private TopMenuVerificationManifest() {
    }
}
