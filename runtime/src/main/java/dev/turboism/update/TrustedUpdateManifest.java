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

    /**
     * Returns the authenticated manifest exactly as it was verified.
     *
     * @return the immutable manifest document behind this trusted view
     */
    public UpdateManifest manifest() {
        return manifest;
    }

    /**
     * Returns the key id whose Ed25519 signature authenticated the manifest.
     *
     * @return the trusted key id recorded at verification time
     */
    public String verifiedKeyId() {
        return verifiedKeyId;
    }

    /**
     * Returns when the signature was verified.
     *
     * @return verification timestamp from the injected clock
     */
    public Instant verifiedAt() {
        return verifiedAt;
    }

    /**
     * Target framework version of the authenticated manifest.
     *
     * @return the manifest version string
     */
    public String version() {
        return manifest.version();
    }

    /**
     * Release channel of the authenticated manifest.
     *
     * @return the manifest channel, for example {@code stable} or {@code beta}
     */
    public String channel() {
        return manifest.channel();
    }

    /**
     * Monotonic release build number of the authenticated manifest.
     *
     * @return the build number, used to reject equal or older builds
     */
    public long buildNumber() {
        return manifest.buildNumber();
    }

    /**
     * Source revision the authenticated manifest was signed for.
     *
     * @return the 40-character source commit
     */
    public String sourceRevision() {
        return manifest.sourceRevision();
    }

    /**
     * Platform the authenticated manifest targets.
     *
     * @return the manifest target platform, for example {@code windows-x64}
     */
    public String targetPlatform() {
        return manifest.targetPlatform();
    }

    /**
     * Framework-owned entries covered by the signature, in manifest order.
     *
     * @return immutable entry list used by delta planning and the helper
     */
    public List<UpdateManifestEntry> entries() {
        return manifest.entries();
    }
}
