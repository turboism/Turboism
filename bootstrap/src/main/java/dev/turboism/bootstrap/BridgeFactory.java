package dev.turboism.bootstrap;

/**
 * Builds a hook's own slot bridge from the contract the routine bound for this artifact.
 *
 * <p>It is a factory rather than a ready-made bridge because a bridge may need the reviewed target
 * (the handle owner, a size accessor) or a host class the hook only reaches through its own
 * admission. {@link VerifiedInstall} calls it after admission and before any transformer is
 * registered, so a woven site can never call an unpublished slot.</p>
 *
 * @param <T> the target/profile type the hook installs with
 */
@FunctionalInterface
interface BridgeFactory<T> {

    /**
     * @param <T> the target/profile type the hook installs with
     * @return the factory of a hook whose woven bytes call no published slot
     */
    static <T> BridgeFactory<T> none() {
        return (contract, context) -> BridgeLayer.NONE;
    }

    /**
     * @param contract the contract the routine bound for this artifact
     * @param context the launch facts, so the bridge can reach the host loader it binds against
     * @return the hook's bridge, installed before any transformer is registered
     * @throws Exception when the bridge cannot be built from the host classes
     */
    BridgeLayer create(T contract, InstallContext context) throws Exception;
}
