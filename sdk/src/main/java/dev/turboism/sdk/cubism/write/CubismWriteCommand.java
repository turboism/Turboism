package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.PreviewApi;

/**
 * Base type for narrow, transaction-scoped Cubism write commands.
 *
 * <p>Commands are DTOs. They describe validated intent and never expose host
 * objects, hooks, UI handles, reflection handles, or broad executor callbacks.</p>
 */
@PreviewApi
public interface CubismWriteCommand {

    String commandId();
}
