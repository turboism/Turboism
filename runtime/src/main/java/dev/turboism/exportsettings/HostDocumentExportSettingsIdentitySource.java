package dev.turboism.exportsettings;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.id.ModelId;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads the export-settings dialog identity from the live host document.
 *
 * <p>A native export always exports the active MODEL document, so the identity is the pair of the
 * active document id and the id of the model that document owns. Anything else — no host, a
 * non-MODEL document, or a MODEL document without a model — yields empty, and the authority treats
 * an absent identity as a failed-closed selection.</p>
 *
 * <p>Reads project the host's mutable object graph into fresh immutable snapshots, so a returned
 * identity is only valid for the moment it was read; the authority compares it against the identity
 * captured when the dialog was attached.</p>
 */
public final class HostDocumentExportSettingsIdentitySource
    implements Supplier<Optional<ExportSettingsIdentity>> {

    private final HostSnapshotSource snapshots;

    public HostDocumentExportSettingsIdentitySource(final HostSnapshotSource snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public Optional<ExportSettingsIdentity> get() {
        return snapshots.activeDocument()
            .filter(document -> document.kind() == DocumentKind.MODEL)
            .flatMap(document -> document.model()
                .map(model -> new ExportSettingsIdentity(
                    document.documentId(), new ModelId(model.modelId())
                )));
    }
}
