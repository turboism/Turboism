package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.adapter.cubism.command.ResolvedEditorFileCommand;

import java.util.Objects;
import java.util.Set;

/** Stable fail-closed command view rebound with the current exact host session. */
final class DynamicEditorCommandAdapter implements EditorCommandAdapter {
    private EditorCommandAdapter current = EditorCommandAdapter.unavailable();

    synchronized void connect(final EditorCommandAdapter adapter) {
        current = Objects.requireNonNull(adapter, "adapter");
    }

    synchronized void deactivate() {
        current = EditorCommandAdapter.unavailable();
    }

    @Override
    public synchronized Set<EditorCommand> available() {
        return current.available();
    }

    @Override
    public synchronized EditorCommandResult execute(final EditorCommand command) {
        return current.execute(command);
    }

    @Override
    public synchronized EditorCommandResult execute(final ResolvedEditorFileCommand command) {
        return current.execute(command);
    }

    @Override
    public synchronized EditorCommandResult execute(final EditorParameterizedRequest command) {
        return current.execute(command);
    }
}
