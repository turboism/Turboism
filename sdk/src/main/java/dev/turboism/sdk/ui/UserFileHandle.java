package dev.turboism.sdk.ui;

public interface UserFileHandle extends AutoCloseable {

    String id();

    String displayName();

    UserFileMode mode();

    UserFileLifetime lifetime();

    UserFileHandleState state();

    void revoke();

    @Override
    void close();
}
