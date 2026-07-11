package dev.turboism.mapping.draft;

/** Result of validating or writing an exact candidate. */
public record ApplyResult(boolean written, String resultPackSha256) { }
