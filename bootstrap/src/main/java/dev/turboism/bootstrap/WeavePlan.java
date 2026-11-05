package dev.turboism.bootstrap;

import java.util.List;

/**
 * The whole variation surface of a verified hook: how its targets are built and the assertions beyond
 * the per-target match count.
 *
 * @param targets target factory; called once per install attempt with the bound contract and the
 *     reference-byte source. Its order is the registration order and therefore load-bearing
 * @param postCheck the hook-specific assertion after retransformation, {@link PostCheck#NONE} when the
 *     per-target match count is the whole proof
 * @param <T> the target/profile type the hook installs with
 */
record WeavePlan<T>(Targets<T> targets, PostCheck postCheck) {

    /**
     * Builds the hook's targets, in registration order.
     *
     * @param <T> the target/profile type the hook installs with
     */
    @FunctionalInterface
    interface Targets<T> {

        /**
         * @param contract the contract the routine bound for this artifact
         * @param source reference bytes and attestation for this install attempt
         * @return the targets to register, in registration order
         * @throws Exception when a transformer cannot be built from the reference bytes
         */
        List<WeaveTarget> create(T contract, WeaveSource source) throws Exception;
    }

    /** A hook-specific assertion that runs after the retransformation. */
    @FunctionalInterface
    interface PostCheck {

        /** Satisfied: the per-target match and failure checks are the whole proof. */
        PostCheck NONE = targets -> null;

        /**
         * @param targets the targets in registration order
         * @return {@code null} when satisfied, otherwise the failure detail
         */
        String unsatisfied(List<WeaveTarget> targets);
    }
}
