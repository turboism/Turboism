package dev.turboism.update;

import java.util.Map;
import java.util.Objects;

/** Immutable offline inputs. Product requests are built from the receipt loader and a live service snapshot. */
record UpdateVerificationContext(
        String expectedVersion,
        String expectedChannel,
        long expectedBuildNumber,
        String expectedSourceRevision,
        String currentUpdaterVersion,
        String supportedPlatform,
        InstalledBuild installedBuild,
        Map<String, String> installedFrameworkHashes,
        String receiptBinding,
        Validation validation) {

    @FunctionalInterface
    interface Validation {
        void validate() throws java.io.IOException, InterruptedException;

        default void claimCommit(final Runnable claim) throws java.io.IOException, InterruptedException {
            validate();
            claim.run();
        }
    }

    /** Package-only offline model/test seam, not a product construction API. */
    UpdateVerificationContext(
            String expectedVersion,
            String expectedChannel,
            long expectedBuildNumber,
            String expectedSourceRevision,
            String currentUpdaterVersion,
            String supportedPlatform,
            InstalledBuild installedBuild,
            Map<String, String> installedFrameworkHashes) {
        this(
                expectedVersion,
                expectedChannel,
                expectedBuildNumber,
                expectedSourceRevision,
                currentUpdaterVersion,
                supportedPlatform,
                installedBuild,
                installedFrameworkHashes,
                "",
                () -> {});
    }

    static UpdateVerificationContext fromReceipt(
            UpdateDiscovery.Candidate candidate,
            FrameworkInstallationReceiptLoader loader,
            FrameworkInstallationReceiptLoader.Evidence evidence,
            Validation freshDiscovery) {
        final UpdateIncrementalDiscovery target = candidate.incremental().orElseThrow();
        return new UpdateVerificationContext(
                target.version(),
                target.channel(),
                target.buildNumber(),
                target.sourceRevision(),
                evidence.loaded().version(),
                "windows-x64",
                InstalledBuild.from(evidence.loaded()),
                evidence.hashes(),
                evidence.binding(),
                new Validation() {
                    @Override
                    public void validate() throws java.io.IOException, InterruptedException {
                        freshDiscovery.validate();
                        final FrameworkInstallationReceiptLoader.Evidence current;
                        try {
                            current = loader.load();
                        } catch (UpdateManifestVerifier.UpdateManifestVerificationException failure) {
                            throw new java.io.IOException("Installation receipt no longer authenticates", failure);
                        }
                        if (!evidence.binding().equals(current.binding())
                                || !evidence.loaded().equals(current.loaded())
                                || !evidence.root().equals(current.root())
                                || !evidence.hashes().equals(current.hashes())) {
                            throw new java.io.IOException("Installation receipt binding changed");
                        }
                        freshDiscovery.validate();
                    }

                    @Override
                    public void claimCommit(final Runnable claim) throws java.io.IOException, InterruptedException {
                        freshDiscovery.claimCommit(claim);
                    }
                });
    }

    void revalidate() throws java.io.IOException, InterruptedException {
        validation.validate();
    }

    void claimCommit(final Runnable claim) throws java.io.IOException, InterruptedException {
        validation.claimCommit(claim);
    }

    UpdateVerificationContext {
        Objects.requireNonNull(receiptBinding, "receiptBinding");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        Objects.requireNonNull(expectedSourceRevision, "expectedSourceRevision");
        Objects.requireNonNull(currentUpdaterVersion, "currentUpdaterVersion");
        Objects.requireNonNull(supportedPlatform, "supportedPlatform");
        Objects.requireNonNull(installedBuild, "installedBuild");
        installedFrameworkHashes = Map.copyOf(installedFrameworkHashes);
        if (installedFrameworkHashes.size() > UpdateManifest.MAX_ENTRIES) {
            throw new IllegalArgumentException("too many installation receipts");
        }
        for (final var receipt : installedFrameworkHashes.entrySet()) {
            // Reuse exactly the manifest path/digest boundary, without implying name-based ownership.
            new UpdateManifestEntry(receipt.getKey(), 0, receipt.getValue(), "sha256:" + receipt.getValue());
        }
        if (expectedVersion.length() > 128
                || expectedSourceRevision.length() > 40
                || currentUpdaterVersion.length() > 128
                || supportedPlatform.length() > 32) {
            throw new IllegalArgumentException("context field too long");
        }
        if (expectedBuildNumber <= 0 || expectedBuildNumber > UpdateDiscovery.MAX_BUILD_NUMBER) {
            throw new IllegalArgumentException("expectedBuildNumber must be positive");
        }
    }
}
