package dev.turboism.adapter.cubism.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.ProjectContentKind;
import org.junit.jupiter.api.Test;

class ProjectContentIdentityTest {

    @Test
    void modelAndAnimationIdentityUsesRawObjectIdentityNotNamesOrFiles() {
        final Object raw = new Object();

        assertEquals(
                "model:" + Integer.toUnsignedString(System.identityHashCode(raw), 16),
                ProjectContentIdentity.forLifecycleContent(ProjectContentKind.MODEL, raw));
        assertEquals(
                "animation:" + Integer.toUnsignedString(System.identityHashCode(raw), 16),
                ProjectContentIdentity.forLifecycleContent(ProjectContentKind.ANIMATION, raw));
    }
}
