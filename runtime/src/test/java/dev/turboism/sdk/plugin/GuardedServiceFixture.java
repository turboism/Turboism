package dev.turboism.sdk.plugin;

/**
 * Test fixture: an SDK-packaged interface so {@code PluginGenerationGuard} treats it as a
 * gateable handle. {@code close()} is the terminal operation; {@code mutate} is ordinary
 * admission-gated work; {@code child()} exercises recursive handle wrapping.
 */
public interface GuardedServiceFixture extends Registration {

    String mutate(String value);

    GuardedServiceFixture child();

    /** Same-named verb with an argument: never a terminal operation, stays fenced. */
    default void close(final String reason) {}
}
