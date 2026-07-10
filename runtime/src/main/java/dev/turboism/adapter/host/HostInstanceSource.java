package dev.turboism.adapter.host;

import java.util.Optional;

/** Supplies the currently available host instance descriptor without exposing host objects. */
@FunctionalInterface
public interface HostInstanceSource {
    Optional<HostInstanceDescriptor> current();
}
