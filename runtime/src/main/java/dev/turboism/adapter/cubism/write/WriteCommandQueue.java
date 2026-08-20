package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.write.CubismWriteCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Accumulates the write commands staged by one open transaction until
 * commit hands them to the host in a single batch. Nothing here reaches
 * the Editor; the queue is pure staging.
 *
 * <p>All access is synchronized on the queue, so a transaction may be
 * staged from one thread and committed on the host thread.</p>
 */
public final class WriteCommandQueue {

    private final List<CubismWriteCommand> commands = new ArrayList<>();

    /**
     * Appends a command to the end of the staged batch. Commands are applied
     * in insertion order at commit.
     *
     * @param command command to stage
     * @throws NullPointerException if {@code command} is {@code null}
     */
    public synchronized void add(final CubismWriteCommand command) {
        commands.add(Objects.requireNonNull(command, "command"));
    }

    /**
     * @return an immutable snapshot of the commands staged so far; later
     *     additions do not affect a snapshot already handed out
     */
    public synchronized List<CubismWriteCommand> commands() {
        return List.copyOf(commands);
    }
}
