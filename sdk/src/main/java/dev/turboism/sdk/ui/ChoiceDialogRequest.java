package dev.turboism.sdk.ui;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded single-choice dialog descriptor without host widget exposure. */
@PreviewApi
public record ChoiceDialogRequest(
    String id,
    String title,
    String notice,
    List<ChoiceDialogOption> options,
    Optional<String> selectedOptionId,
    String acceptLabel,
    String cancelLabel
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
    }

    private static String requireText(final String value, final String name, final int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be bounded non-blank text");
        }
        return value;
    }
}
