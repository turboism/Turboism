package dev.turboism.adapter.cubism.edit;

import java.util.Set;

/**
 * Session-scoped verified member surface for the editing operation families (spec 046, T3).
 *
 * <p>One accessor is bound to one admitted session's native binding: {@link #document()},
 * {@link #modelSource()}, and {@link #model()} expose the raw host handles the operation
 * orchestrations navigate from, while the {@code invoke*}/{@code construct}/{@code isInstance}
 * members reach host objects exclusively through verified aliases — the production
 * implementation resolves every call through the {@code VerifiedMemberResolver}, so no
 * operation can touch an unverified member. {@link #authorizesFeature} is the per-operation
 * capability gate every route must run before touching a member.</p>
 *
 * <p>All members run on the host UI thread: callers reach them through
 * {@link EditorEditSessionHost#dispatch}.</p>
 */
public interface EditSessionOpsAccess {

    /**
     * Returns the bound modeling document handle.
     *
     * @throws IllegalStateException when the session binding went stale
     */
    Object document();

    /**
     * Returns the bound model-source handle.
     *
     * @throws IllegalStateException when the session binding went stale
     */
    Object modelSource();

    /**
     * Returns the bound model handle.
     *
     * @throws IllegalStateException when the session binding went stale
     */
    Object model();

    /**
     * Returns whether the bound verification record admits {@code capabilityId} with every
     * member in {@code aliases}. A missing row or member yields {@code false}; the operation
     * must then fail closed with a typed unavailable error.
     */
    boolean authorizesFeature(String capabilityId, Set<String> aliases);

    /**
     * Invokes the verified instance method bound to {@code alias} on {@code target}.
     *
     * @throws RuntimeException when the alias is unverified or the member fails
     */
    Object invoke(String alias, Object target, Object... arguments);

    /** Invokes the verified static method bound to {@code alias}. */
    Object invokeStatic(String alias, Object... arguments);

    /** Invokes the verified constructor bound to {@code alias}. */
    Object construct(String alias, Object... arguments);

    /** Reads the verified static field bound to {@code alias}. */
    Object readStaticField(String alias);

    /** Returns whether {@code value} is an instance of the verified class bound to {@code alias}. */
    boolean isInstance(String alias, Object value);
}
