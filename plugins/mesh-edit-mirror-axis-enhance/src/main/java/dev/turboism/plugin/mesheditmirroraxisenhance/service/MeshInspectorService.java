package dev.turboism.plugin.mesheditmirroraxisenhance.service;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * SDK-only fake-ready read-only mesh/deformer inspector.
 */
public final class MeshInspectorService {

    public static final String INSPECT_ACTION_ID = "mesh.inspector.inspect";
    public static final String REFRESHED = "mesh.inspector.refreshed";
    public static final String UNAVAILABLE = "mesh.inspector.unavailable";

    private final CubismReadCapabilityService cubismRead;
    private final UiHostCapabilityService uiHost;

    public MeshInspectorService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    /**
     * Reads the host's meshes, deformers, and context source and reports the
     * counts as a status notification. Read-only: nothing in the model is
     * modified.
     *
     * <p>When the host exposes neither meshes nor deformers, a WARNING status
     * is posted instead and no counts are reported; the absence is not treated
     * as an error.</p>
     */
    public void inspect() {
        final List<ArtMeshSnapshot> meshes = cubismRead.meshes();
        final List<DeformerSnapshot> deformers = cubismRead.deformers();
        final ContextSourceSnapshot context = uiHost.contextSource();

        if (meshes.isEmpty() && deformers.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                UNAVAILABLE,
                "WARNING",
                "No meshes or deformers are available in this host."
            ));
            return;
        }

        uiHost.notifyStatus(new StatusNotification(
            REFRESHED,
            "INFO",
            "Meshes: " + meshes.size()
                + ", deformers: " + deformers.size()
                + ", context: " + context.contextKind()
        ));
    }
}
