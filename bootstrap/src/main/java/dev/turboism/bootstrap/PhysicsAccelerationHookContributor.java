package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.physics.PhysicsAccelerationBridge;

/**
 * Contributor for zero-allocation physics pendulum chain evaluation under the warp-deform edit
 * acceleration preference.
 */
final class PhysicsAccelerationHookContributor extends NativeOptimizationHookContributor {

    PhysicsAccelerationHookContributor() {
        super("TURBOISM_PHYSICS_ACCELERATION", VerifiedPhysicsAccelerationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean production = NativeOptimizationPolicy.warpDeformEditAccelerationEnabled(environment);
        final String override = System.getProperty(PhysicsAccelerationBridge.VALIDATION_PROPERTY);
        final boolean enabled = override == null ? production : Boolean.parseBoolean(override);
        if (!enabled) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedPhysicsAccelerationInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedPhysicsAccelerationInstaller installer =
                new VerifiedPhysicsAccelerationInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " physicsAcceleration=ACTIVE");
        return installer;
    }
}
