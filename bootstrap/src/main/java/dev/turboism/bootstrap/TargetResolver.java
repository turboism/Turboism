package dev.turboism.bootstrap;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Reviewed-target admission for one verified hook: the hook's own admission source, kept as the one
 * expression that names the table it reads.
 *
 * <p>An empty result is a refusal and never an error; a resolver that cannot read the artifact may
 * throw, which surfaces as an installation failure instead of a refusal, because the host was admitted
 * and the hook could not prove otherwise.</p>
 *
 * @param <T> the target/profile type the hook installs with
 */
@FunctionalInterface
interface TargetResolver<T> {

    /**
     * @param artifact the located host artifact
     * @return the reviewed target for this artifact, or empty when the artifact is not admitted
     * @throws Exception when the artifact cannot be read or inspected
     */
    Optional<T> resolve(Path artifact) throws Exception;
}
