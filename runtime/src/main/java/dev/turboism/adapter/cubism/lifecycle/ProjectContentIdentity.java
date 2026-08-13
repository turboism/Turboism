package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.ProjectContentKind;

import java.util.Objects;

/** Identity shared by lifecycle ingress and the verified project snapshot adapter. */
public final class ProjectContentIdentity {

    private ProjectContentIdentity() {
    }

    public static String forLifecycleContent(final ProjectContentKind kind, final Object content) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        if (kind != ProjectContentKind.MODEL && kind != ProjectContentKind.ANIMATION) {
            throw new IllegalArgumentException("Only model and animation content have lifecycle identity.");
        }
        return kind.name().toLowerCase(java.util.Locale.ROOT)
            + ":" + Integer.toUnsignedString(System.identityHashCode(content), 16);
    }
}
