package dev.turboism.adapter.host;

import java.util.Optional;

/** Supplies the currently available host instance descriptor without exposing host objects. */
@FunctionalInterface
public interface HostInstanceSource {
    /**
     * @return the descriptor of the host instance currently available; empty when no host
     *         is attached
     */
    Optional<HostInstanceDescriptor> current();
}
