package dev.turboism.mapping.verification;

public enum StaticVerificationStatus {
    VERIFIED_STATIC,
    ARTIFACT_MISMATCH,
    CLASS_MISSING,
    MEMBER_MISSING,
    DESCRIPTOR_MISMATCH,
    ACCESS_MISMATCH,
    DUPLICATE_ALIAS,
    INVALID_CLASS_FILE
}
