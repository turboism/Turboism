package com.live2d.type;

import java.util.UUID;

/** Test-only stand-in for the host's {@code com.live2d.type.Guid}. */
public abstract class Guid {
    private final UUID uuid = UUID.randomUUID();

    public final String getUuidString() {
        return uuid.toString();
    }
}
