package dev.turboism.mapping.verification;

/**
 * Verdict for one selector. {@code VERIFIED_STATIC} is the only passing
 * value; the rest distinguish a wrong artifact, an absent class or member,
 * a member whose descriptor or access flags differ from the reviewed one, a
 * duplicated alias in the request, and an unparsable class file.
 */
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
