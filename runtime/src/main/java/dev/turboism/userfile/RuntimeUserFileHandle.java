package dev.turboism.userfile;

import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-owned opaque capability. The selected path never enters the SDK surface. */
final class RuntimeUserFileHandle implements UserFileHandle {

    private final Object ownerToken;
    private final String id;
    private final String displayName;
    private final UserFileMode mode;
    private final UserFileLifetime lifetime;
    private final Path target;
    private final AtomicReference<UserFileHandleState> state =
        new AtomicReference<>(UserFileHandleState.ACTIVE);

    RuntimeUserFileHandle(
        final Object ownerToken,
        final String displayName,
        final UserFileMode mode,
        final UserFileLifetime lifetime,
        final Path target
    ) {
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.id = UUID.randomUUID().toString();
        this.displayName = requireDisplayName(displayName);
        this.mode = Objects.requireNonNull(mode, "mode");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public UserFileMode mode() {
        return mode;
    }

    @Override
    public UserFileLifetime lifetime() {
        return lifetime;
    }

    @Override
    public UserFileHandleState state() {
        return state.get();
    }

    @Override
    public void revoke() {
        state.compareAndSet(UserFileHandleState.ACTIVE, UserFileHandleState.REVOKED);
    }

    @Override
    public void close() {
        state.compareAndSet(UserFileHandleState.ACTIVE, UserFileHandleState.CLOSED);
    }

    boolean ownedBy(final Object token) {
        return ownerToken == token;
    }

    Path target() {
        return target;
    }

    UserFileHandleState beginAttempt() {
        while (true) {
            final UserFileHandleState current = state.get();
            if (current != UserFileHandleState.ACTIVE) {
                return current;
            }
            if (lifetime == UserFileLifetime.UNTIL_DISABLE) {
                return UserFileHandleState.ACTIVE;
            }
            if (state.compareAndSet(UserFileHandleState.ACTIVE, UserFileHandleState.CLOSED)) {
                return UserFileHandleState.ACTIVE;
            }
        }
    }

    private static String requireDisplayName(final String value) {
        Objects.requireNonNull(value, "displayName");
        if (value.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return value;
    }
}
