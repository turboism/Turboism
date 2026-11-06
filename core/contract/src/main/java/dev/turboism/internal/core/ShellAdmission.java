package dev.turboism.internal.core;

/**
 * Composition seam that supplies the framework shell to the runtime.
 *
 * <p>The runtime owns admission, lifecycle and the service handoff; it never names the shell
 * implementation class directly. Composition (the bootstrap agent or a test) injects the
 * factory, and a runtime started with a {@code null} admission simply runs headless —
 * external plugins still load and no shell surfaces are contributed.</p>
 */
@FunctionalInterface
public interface ShellAdmission {

    /**
     * Constructs the shell instance over the runtime-assembled service bundle.
     *
     * <p>Invoked by the runtime on the bounded lifecycle lane inside the admission deadline,
     * immediately before {@link ShellHandle#start}.
     *
     * @param services runtime-owned services; never {@code null}
     * @return the constructed shell; must not be {@code null}
     * @throws Exception when the shell cannot be constructed
     */
    ShellHandle create(ShellServices services) throws Exception;
}
