package dev.turboism.sdk.cubism.write;

/**
 * Base type for narrow, transaction-scoped Cubism write commands.
 *
 * <p>Commands are DTOs. They describe validated intent and never expose host
 * objects, hooks, UI handles, reflection handles, or broad executor callbacks.</p>
 */
public interface CubismWriteCommand {

    String commandId();
}
