package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.write.CubismWriteCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class WriteCommandQueue {

    private final List<CubismWriteCommand> commands = new ArrayList<>();

    public synchronized void add(final CubismWriteCommand command) {
        commands.add(Objects.requireNonNull(command, "command"));
    }

    public synchronized List<CubismWriteCommand> commands() {
        return List.copyOf(commands);
    }
}
