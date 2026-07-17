package dev.turboism.core.runtime;

public interface RuntimeTimerHandle extends AutoCloseable {

    boolean cancel();

    @Override
    default void close() {
        cancel();
    }
}
