package dev.turboism.bootstrap;

/**
 * Everything one verified hook declares. Immutable, and free of any protocol statement: the routine
 * owns admission ordering, registration, retransformation and the restoration proof.
 *
 * @param admission how the hook is admitted (policy id, kill-switch semantics, JVM predicate)
 * @param feature the human feature name used in every failure message
 * @param resolver the hook's reviewed-target expression
 * @param plan target factory and post-check
 * @param bridge the hook's own slot bridge, or {@link BridgeLayer#NONE}
 * @param effects install/close side effects, or {@link WeaveEffects#none()}
 * @param <T> the target/profile type the hook installs with
 */
record WeaveSpec<T>(
        Admission admission,
        String feature,
        TargetResolver<T> resolver,
        WeavePlan<T> plan,
        BridgeLayer bridge,
        WeaveEffects<T> effects) {}
