package dev.turboism.ui.contribution;

import dev.turboism.ui.host.EditorUiFamily;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Exact, generation-bound admission state for one Editor UI provider family. */
public record EditorUiProviderAdmission(
    EditorUiFamily family,
    Status status,
    long hostGeneration,
    Optional<VerificationEvidence> verificationEvidence,
    Optional<EditorUiContributionFailure.Code> failureCode,
    Optional<String> diagnosticId
) {
    private static final int DIAGNOSTIC_ID_MAX_LENGTH = 128;

    public EditorUiProviderAdmission {
        family = Objects.requireNonNull(family, "family");
        status = Objects.requireNonNull(status, "status");
        verificationEvidence = Objects.requireNonNull(verificationEvidence, "verificationEvidence");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        diagnosticId = normalizedDiagnosticId(diagnosticId);
        if (status == Status.ADMITTED) {
            if (hostGeneration <= 0 || verificationEvidence.isEmpty()
                || failureCode.isPresent() || diagnosticId.isPresent()) {
                throw new IllegalArgumentException(
                    "admitted UI provider requires one positive host generation and exact evidence"
                );
            }
        } else if (hostGeneration != 0 || verificationEvidence.isPresent()
            || failureCode.isEmpty() || diagnosticId.isEmpty()) {
            throw new IllegalArgumentException(
                "safe-mode UI provider requires one bounded diagnostic and no host evidence"
            );
        }
    }

    public static EditorUiProviderAdmission safeMode(
        final EditorUiFamily family,
        final EditorUiContributionFailure.Code failureCode,
        final String diagnosticId
    ) {
        return new EditorUiProviderAdmission(
            family,
            Status.SAFE_MODE,
            0,
            Optional.empty(),
            Optional.of(Objects.requireNonNull(failureCode, "failureCode")),
            Optional.of(requireDiagnosticId(diagnosticId))
        );
    }

    public static EditorUiProviderAdmission safeMode(
        final EditorUiFamily family,
        final String diagnosticId
    ) {
        return safeMode(
            family,
            EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
            diagnosticId
        );
    }

    public static EditorUiProviderAdmission admitted(
        final EditorUiFamily family,
        final long hostGeneration,
        final VerificationEvidence verificationEvidence
    ) {
        return new EditorUiProviderAdmission(
            family,
            Status.ADMITTED,
            hostGeneration,
            Optional.of(Objects.requireNonNull(verificationEvidence, "verificationEvidence")),
            Optional.empty(),
            Optional.empty()
        );
    }

    public boolean isAdmitted() {
        return status == Status.ADMITTED;
    }

    public boolean isAdmittedTo(final long generation) {
        return isAdmitted() && hostGeneration == generation;
    }

    private static Optional<String> normalizedDiagnosticId(final Optional<String> value) {
        Objects.requireNonNull(value, "diagnosticId");
        return value.map(EditorUiProviderAdmission::requireDiagnosticId);
    }

    private static String requireDiagnosticId(final String value) {
        final String normalized = requireText(value, "diagnosticId").toLowerCase(Locale.ROOT);
        if (normalized.length() > DIAGNOSTIC_ID_MAX_LENGTH
            || !normalized.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("diagnosticId must be a bounded stable identifier");
        }
        return normalized;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Status {
        SAFE_MODE,
        ADMITTED
    }

    /** Hash-anchored reviewed verification material for one exact provider implementation. */
    public record VerificationEvidence(
        String hostVersion,
        long artifactSize,
        String artifactSha256,
        String verificationSlice,
        String verificationRecordSha256
    ) {
        public VerificationEvidence {
            hostVersion = requireText(hostVersion, "hostVersion");
            if (artifactSize < 0) {
                throw new IllegalArgumentException("artifactSize must not be negative");
            }
            artifactSha256 = requireSha256(artifactSha256, "artifactSha256");
            verificationSlice = requireText(verificationSlice, "verificationSlice");
            verificationRecordSha256 = requireSha256(
                verificationRecordSha256,
                "verificationRecordSha256"
            );
        }

        private static String requireSha256(final String value, final String name) {
            final String normalized = requireText(value, name).toLowerCase(Locale.ROOT);
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    name + " must be 64 lowercase hexadecimal characters"
                );
            }
            return normalized;
        }
    }
}
