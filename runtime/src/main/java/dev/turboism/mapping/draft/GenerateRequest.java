package dev.turboism.mapping.draft;

import java.nio.file.Path;
import java.util.Objects;

/** Explicit, single-edge recipe for producing one class-runtime update candidate. */
public record GenerateRequest(
    Path artifact,
    String targetPack,
    String semanticName,
    String expectedOldRuntime,
    String callerOwner,
    String callerName,
    String callerDescriptor,
    String targetMethodName,
    String targetMethodDescriptor,
    InvocationConstraint invocationConstraint,
    Path outputDirectory,
    String worktreeId
) {
    public GenerateRequest {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(targetPack, "targetPack");
        Objects.requireNonNull(semanticName, "semanticName");
        Objects.requireNonNull(expectedOldRuntime, "expectedOldRuntime");
        Objects.requireNonNull(callerOwner, "callerOwner");
        Objects.requireNonNull(callerName, "callerName");
        Objects.requireNonNull(callerDescriptor, "callerDescriptor");
        Objects.requireNonNull(targetMethodName, "targetMethodName");
        Objects.requireNonNull(targetMethodDescriptor, "targetMethodDescriptor");
        Objects.requireNonNull(invocationConstraint, "invocationConstraint");
        if (worktreeId == null) worktreeId = "mapping-review-local";
    }

    /**
     * Retains the original API's default output location: {@code build/mapping-review}.
     */
    public GenerateRequest(
        final Path artifact,
        final String targetPack,
        final String semanticName,
        final String expectedOldRuntime,
        final String callerOwner,
        final String callerName,
        final String callerDescriptor,
        final String targetMethodName,
        final String targetMethodDescriptor,
        final InvocationConstraint invocationConstraint
    ) {
        this(
            artifact,
            targetPack,
            semanticName,
            expectedOldRuntime,
            callerOwner,
            callerName,
            callerDescriptor,
            targetMethodName,
            targetMethodDescriptor,
            invocationConstraint,
            null,
            "mapping-review-local"
        );
    }

    public GenerateRequest(
        final Path artifact,
        final String targetPack,
        final String semanticName,
        final String expectedOldRuntime,
        final String callerOwner,
        final String callerName,
        final String callerDescriptor,
        final String targetMethodName,
        final String targetMethodDescriptor,
        final InvocationConstraint invocationConstraint,
        final Path outputDirectory
    ) {
        this(
            artifact,
            targetPack,
            semanticName,
            expectedOldRuntime,
            callerOwner,
            callerName,
            callerDescriptor,
            targetMethodName,
            targetMethodDescriptor,
            invocationConstraint,
            outputDirectory,
            "mapping-review-local"
        );
    }
}
