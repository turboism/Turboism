package dev.turboism.sdk.cubism.psd;

import dev.turboism.sdk.cubism.id.RawImageId;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of exporting one model-bound raw image into a runtime-owned temporary PSD.
 *
 * <p>EXPORTED requires validated native export completeness, not merely a readable PSD or a normal
 * native void return. It describes Cubism's current resources, not lossless preservation of every
 * original PSD feature. Only EXPORTED carries a handle and baseline revision. Constructing this
 * value does not issue a runtime-authorized handle or establish host evidence.</p>
 *
 * @param diagnostic runtime-sanitized explanation, without granting access to a filesystem path
 * @param source requested raw-image identity within the originating model session
 * @param file authorized handle on success, empty on failure
 * @param initialRevision initial export baseline, not an external save notification
 */
public record PsdExportResult(
        Status status,
        String diagnostic,
        RawImageId source,
        Optional<PsdEditFile> file,
        Optional<PsdFileRevision> initialRevision) {
    public PsdExportResult {
        status = Objects.requireNonNull(status, "status");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        source = Objects.requireNonNull(source, "source");
        file = Objects.requireNonNull(file, "file");
        initialRevision = Objects.requireNonNull(initialRevision, "initialRevision");
        if (status == Status.EXPORTED) {
            if (file.isEmpty() || initialRevision.isEmpty()) {
                throw new IllegalArgumentException("exported result requires a file and baseline revision");
            }
        } else if (file.isPresent() || initialRevision.isPresent()) {
            throw new IllegalArgumentException("unsuccessful export cannot expose a usable file or revision");
        }
    }

    /** Outcome of one raw-image export request. */
    public enum Status {
        EXPORTED,
        UNAVAILABLE,
        STALE_TARGET,
        REJECTED,
        FAILED
    }
}
