package dev.turboism.permissions;

import java.util.Objects;

/**
 * One row of a {@link ServiceGateInstaller} table: which SDK service method is guarded, by
 * which permission, and how the gate treats the call.
 *
 * <p>The row is pure data so the complete authorization surface of a plugin service can be
 * read in one place instead of being spread across one decorator class per service. Grants are
 * matched by exact permission id, the same string a plugin declares in its manifest.</p>
 *
 * @param api the SDK interface the row belongs to; must not be null
 * @param method the method name to guard; overloads of one name share the row, so a row can
 *     never disagree with itself about a single call
 * @param permissionId the permission demanded before the delegate is reached, or {@code null}
 *     when the operation is admitted by the service's own construction (availability probes and
 *     state observations that expose nothing outside the caller's own participation)
 * @param operationId the operation identity recorded in the denial message; must not be blank
 * @param access how the row is fenced and checked
 */
public record ServiceGate(Class<?> api, String method, String permissionId, String operationId, Access access) {

    /** How the gate treats one method of a service. */
    public enum Access {
        /**
         * Observational call: a declared permission is still enforced, but the scope fence is
         * not applied, so reads of the caller's own state keep working while a plugin winds
         * down.
         */
        READ,

        /**
         * Mutating or registering call: refused with {@link IllegalStateException} while the
         * owning scope reports inactive, then permission-checked, and only then forwarded.
         */
        WRITE,

        /**
         * Cleanup verb: never permission-checked and never fenced. Teardown must be able to
         * release what was acquired before the generation was fenced, mirroring
         * {@code PluginGenerationGuard}'s terminal-operation set.
         */
        TERMINAL
    }

    /**
     * @param api the SDK interface the row belongs to; must not be null
     * @param method the guarded method name; must not be blank
     * @param permissionId the required permission id, or {@code null} for an operation admitted
     *     without one; must not be blank when present
     * @param operationId the operation identity used in diagnostics; must not be blank
     * @param access the access class of the row; must not be null
     * @throws NullPointerException if {@code api} or {@code access} is null
     * @throws IllegalArgumentException if a name is blank, or if a {@link Access#TERMINAL} row
     *     declares a permission it would have to skip
     */
    public ServiceGate {
        Objects.requireNonNull(api, "api");
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (permissionId != null && permissionId.isBlank()) {
            throw new IllegalArgumentException("permissionId must not be blank");
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        access = Objects.requireNonNull(access, "access");
        if (access == Access.TERMINAL && permissionId != null) {
            throw new IllegalArgumentException("terminal gate row " + api.getName() + "." + method
                    + " must not declare the permission " + permissionId + " it would skip");
        }
    }
}
