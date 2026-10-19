package dev.turboism.test.action;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording test fake for {@link ActionRegistry}.
 */
public class RecordingActionRegistry implements ActionRegistry {

    private final List<Action> actions = new CopyOnWriteArrayList<>();
    private final Map<String, Action> byId = new ConcurrentHashMap<>();

    @Override
    public Registration register(final String id, final Action action) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        actions.add(action);
        byId.put(id, action);
        return () -> {
            actions.remove(action);
            byId.remove(id, action);
        };
    }

    public List<Action> actions() {
        return List.copyOf(actions);
    }

    public List<String> ids() {
        return actions.stream().map(Action::id).toList();
    }

    public Optional<Action> find(final String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Action require(final String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("Action not found: " + id));
    }

    public void execute(final String id) {
        execute(id, new ActionContext() {});
    }

    public void execute(final String id, final ContextMenuSelection selection) {
        execute(id, new ActionContext() {
            @Override
            public Optional<ContextMenuSelection> contextMenuSelection() {
                return Optional.ofNullable(selection);
            }
        });
    }

    public void execute(final String id, final ActionContext context) {
        require(id).handler().accept(context);
    }
}
