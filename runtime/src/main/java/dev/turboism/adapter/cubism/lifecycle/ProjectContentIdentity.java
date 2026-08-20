package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.ProjectContentKind;

import java.util.Objects;

/** Identity shared by lifecycle ingress and the verified project snapshot adapter. */
public final class ProjectContentIdentity {

    private ProjectContentIdentity() {
    }

    /**
     * Derives the lifecycle identity string for an open model or animation content object. The identity
     * is the content kind plus the object's identity hash in hexadecimal, so it distinguishes concurrent
     * documents within one host session but is not stable across sessions and must not be persisted.
     *
     * @param kind content kind; only {@code MODEL} and {@code ANIMATION} have lifecycle identity
     * @param content the host-side content object being identified
     * @return the session-scoped identity string, for example {@code model:1a2b3c4d}
     * @throws NullPointerException when {@code kind} or {@code content} is null
     * @throws IllegalArgumentException when {@code kind} is neither {@code MODEL} nor {@code ANIMATION}
     */
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
