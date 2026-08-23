package dev.turboism.sdk.ui;


import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded single-choice dialog descriptor without host widget exposure. */
public record ChoiceDialogRequest(
    String id,
    String title,
    String notice,
    List<ChoiceDialogOption> options,
    Optional<String> selectedOptionId,
    String acceptLabel,
    String cancelLabel,
    List<ChoiceDialogAction> actions,
    Optional<ChoiceDialogRefresher> refresher,
    String reloadLabel
) {
    public ChoiceDialogRequest {
        id = requireText(id, "id", 128);
        title = requireText(title, "title", 256);
        notice = Objects.requireNonNull(notice, "notice");
        if (notice.length() > 4_096) {
            throw new IllegalArgumentException("notice exceeds 4096 characters");
        }
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.isEmpty() || options.size() > 128) {
            throw new IllegalArgumentException("options must contain between 1 and 128 entries");
        }
        if (options.stream().map(ChoiceDialogOption::id).distinct().count() != options.size()) {
            throw new IllegalArgumentException("option ids must be unique");
        }
        selectedOptionId = Objects.requireNonNull(selectedOptionId, "selectedOptionId");
        if (selectedOptionId.isPresent()) {
            final String selected = selectedOptionId.orElseThrow();
            boolean found = false;
            for (ChoiceDialogOption option : options) {
                if (option.id().equals(selected)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("selectedOptionId must reference an option");
            }
        }
        acceptLabel = requireText(acceptLabel, "acceptLabel", 64);
        cancelLabel = requireText(cancelLabel, "cancelLabel", 64);
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        if (actions.size() > 4) {
            throw new IllegalArgumentException("actions must contain at most 4 entries");
        }
        if (actions.stream().map(ChoiceDialogAction::id).distinct().count() != actions.size()) {
            throw new IllegalArgumentException("action ids must be unique");
        }
        refresher = Objects.requireNonNull(refresher, "refresher");
        reloadLabel = Objects.requireNonNull(reloadLabel, "reloadLabel");
        if (reloadLabel.length() > 64) {
            throw new IllegalArgumentException("reloadLabel exceeds 64 characters");
        }
    }

    public ChoiceDialogRequest(
        final String id,
        final String title,
        final String notice,
        final List<ChoiceDialogOption> options,
        final Optional<String> selectedOptionId,
        final String acceptLabel,
        final String cancelLabel,
        final List<ChoiceDialogAction> actions
    ) {
        this(id, title, notice, options, selectedOptionId, acceptLabel, cancelLabel, actions, Optional.empty(), "");
    }

    public ChoiceDialogRequest(
        final String id,
        final String title,
        final String notice,
        final List<ChoiceDialogOption> options,
        final Optional<String> selectedOptionId,
        final String acceptLabel,
        final String cancelLabel
    ) {
        this(id, title, notice, options, selectedOptionId, acceptLabel, cancelLabel, List.of());
    }

    private static String requireText(final String value, final String name, final int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be bounded non-blank text");
        }
        return value;
    }
}
