package dev.turboism.plugin.mcp;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact reviewed Editor-version and per-operation availability metadata for one MCP tool. */
record McpVersionSupport(
    String providerCapabilityId,
    List<String> supportedVersions,
    List<OperationSupport> operations
) {

    private static final Pattern EXACT_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    private static final McpVersionSupport UNSCOPED = new McpVersionSupport(
        "",
        List.of(),
        List.of()
    );

    McpVersionSupport {
        providerCapabilityId = Objects.requireNonNull(
            providerCapabilityId,
            "providerCapabilityId"
        ).strip();
        supportedVersions = exactVersions(supportedVersions, "supportedVersions");
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        if (providerCapabilityId.isEmpty()) {
            if (!supportedVersions.isEmpty() || !operations.isEmpty()) {
                throw new IllegalArgumentException(
                    "unscoped MCP version support cannot declare versions or operations"
                );
            }
        } else if (supportedVersions.isEmpty()) {
            throw new IllegalArgumentException(
                "scoped MCP version support requires exact versions"
            );
        }
        final HashSet<String> operationNames = new HashSet<>();
        for (OperationSupport operation : operations) {
            final OperationSupport checked = Objects.requireNonNull(operation, "operation");
            if (!operationNames.add(checked.operation())) {
                throw new IllegalArgumentException(
                    "duplicate MCP operation availability: " + checked.operation()
                );
            }
            if (!supportedVersions.containsAll(checked.supportedVersions())) {
                throw new IllegalArgumentException(
                    "operation versions must be a subset of the tool provider versions: "
                        + checked.operation()
                );
            }
        }
    }

    static McpVersionSupport unscoped() {
        return UNSCOPED;
    }

    static McpVersionSupport exact(
        final String providerCapabilityId,
        final List<String> supportedVersions
    ) {
        return exact(providerCapabilityId, supportedVersions, List.of());
    }

    static McpVersionSupport exact(
        final String providerCapabilityId,
        final List<String> supportedVersions,
        final List<OperationSupport> operations
    ) {
        return new McpVersionSupport(providerCapabilityId, supportedVersions, operations);
    }

    boolean scoped() {
        return !providerCapabilityId.isEmpty();
    }

    enum Availability {
        AVAILABLE,
        RUNTIME_UNAVAILABLE,
        EXCLUDED
    }

    enum UndoVerification {
        NOT_APPLICABLE,
        RUNTIME_VERIFIED,
        EXACT_HOST_VERIFIED,
        UNVERIFIED
    }

    record OperationSupport(
        String operation,
        Availability availability,
        McpOperationEffect effect,
        boolean transactionEligible,
        UndoVerification undoVerification,
        List<String> supportedVersions,
        String reason
    ) {
        OperationSupport {
            operation = requireText(operation, "operation");
            availability = Objects.requireNonNull(availability, "availability");
            effect = Objects.requireNonNull(effect, "effect");
            undoVerification = Objects.requireNonNull(undoVerification, "undoVerification");
            supportedVersions = exactVersions(supportedVersions, "supportedVersions");
            reason = requireText(reason, "reason");
            if (availability == Availability.AVAILABLE && supportedVersions.isEmpty()) {
                throw new IllegalArgumentException(
                    "available MCP operation requires exact supported versions: " + operation
                );
            }
            if (availability != Availability.AVAILABLE && !supportedVersions.isEmpty()) {
                throw new IllegalArgumentException(
                    "unavailable or excluded MCP operation cannot advertise supported versions: "
                        + operation
                );
            }
            if (transactionEligible
                && (availability != Availability.AVAILABLE
                    || (effect != McpOperationEffect.READ
                        && effect != McpOperationEffect.UNDOABLE_WRITE))) {
                throw new IllegalArgumentException(
                    "transaction-eligible operation must be an available read or undoable write: "
                        + operation
                );
            }
            if (availability == Availability.AVAILABLE
                && effect == McpOperationEffect.UNDOABLE_WRITE
                && undoVerification != UndoVerification.RUNTIME_VERIFIED
                && undoVerification != UndoVerification.EXACT_HOST_VERIFIED) {
                throw new IllegalArgumentException(
                    "available undoable write requires runtime or exact-host verification: "
                        + operation
                );
            }
        }

        static OperationSupport available(
            final String operation,
            final McpOperationEffect effect,
            final boolean transactionEligible,
            final UndoVerification undoVerification,
            final List<String> supportedVersions,
            final String reason
        ) {
            return new OperationSupport(
                operation,
                Availability.AVAILABLE,
                effect,
                transactionEligible,
                undoVerification,
                supportedVersions,
                reason
            );
        }

        static OperationSupport unavailable(
            final String operation,
            final McpOperationEffect effect,
            final String reason
        ) {
            return new OperationSupport(
                operation,
                Availability.RUNTIME_UNAVAILABLE,
                effect,
                false,
                UndoVerification.UNVERIFIED,
                List.of(),
                reason
            );
        }
    }

    private static List<String> exactVersions(
        final List<String> values,
        final String field
    ) {
        final List<String> versions = List.copyOf(Objects.requireNonNull(values, field));
        final Set<String> seen = new HashSet<>();
        for (String version : versions) {
            final String checked = Objects.requireNonNull(version, "supportedVersion");
            if (!EXACT_VERSION.matcher(checked).matches()) {
                throw new IllegalArgumentException(
                    "MCP provider version must be exact: " + checked
                );
            }
            if (!seen.add(checked)) {
                throw new IllegalArgumentException(
                    "duplicate MCP provider version: " + checked
                );
            }
        }
        return versions;
    }

    private static String requireText(final String value, final String field) {
        final String checked = Objects.requireNonNull(value, field).strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return checked;
    }
}
