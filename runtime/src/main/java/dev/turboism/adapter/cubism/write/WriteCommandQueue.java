package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.cubism.write.WriteParameterCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class WriteCommandQueue {

    private final List<WriteParameterCommand> commands = new ArrayList<>();

    public synchronized void add(final WriteParameterCommand command) {
        commands.add(Objects.requireNonNull(command, "command"));
    }

    public synchronized List<WriteParameterCommand> commands() {
        return List.copyOf(commands);
    }
}
