package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.animation.AnimationAccelerationBridge;

/**
 * Contributor for animation evaluation pooling, timeline formatting caches, and Graph Editor
 * viewport culling under the warp-deform edit acceleration preference.
 */
final class AnimationAccelerationHookContributor extends NativeOptimizationHookContributor {

    AnimationAccelerationHookContributor() {
        super("TURBOISM_ANIMATION_ACCELERATION", VerifiedAnimationAccelerationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean production = NativeOptimizationPolicy.warpDeformEditAccelerationEnabled(environment);
        final String override = System.getProperty(AnimationAccelerationBridge.VALIDATION_PROPERTY);
        final boolean enabled = override == null ? production : Boolean.parseBoolean(override);
        if (!enabled) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedAnimationAccelerationInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedAnimationAccelerationInstaller installer =
                new VerifiedAnimationAccelerationInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " animationAcceleration=ACTIVE");
        return installer;
    }
}
