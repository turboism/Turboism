package dev.turboism.update;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An authenticated, cryptographically verified update manifest.
 *
 * <p>The public planner accepts this result from the verifier. Package code is trusted and can
 * also construct it; this type is an API boundary, not a sandbox against package peers.
 */
public final class TrustedUpdateManifest {

    private final UpdateManifest manifest;
    private final String verifiedKeyId;
    private final Instant verifiedAt;

    private final Map<String, String> installedFrameworkHashes;

    /** Package-private construction within the trusted update package. */
    TrustedUpdateManifest(
            final UpdateManifest manifest,
            final String verifiedKeyId,
            final Instant verifiedAt,
            final Map<String, String> installedFrameworkHashes) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.verifiedKeyId = Objects.requireNonNull(verifiedKeyId, "verifiedKeyId");
        this.installedFrameworkHashes = Map.copyOf(installedFrameworkHashes);
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

    Map<String, String> installedFrameworkHashes() {
        return installedFrameworkHashes;
    }

    public UpdateManifest manifest() {
        return manifest;
    }

    public String verifiedKeyId() {
        return verifiedKeyId;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }

    public String version() {
        return manifest.version();
    }

    public String channel() {
        return manifest.channel();
    }

    public long buildNumber() {
        return manifest.buildNumber();
    }

    public String sourceRevision() {
        return manifest.sourceRevision();
    }

    public String targetPlatform() {
        return manifest.targetPlatform();
    }

    public List<UpdateManifestEntry> entries() {
        return manifest.entries();
    }
}
