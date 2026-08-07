package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.ProjectContentKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectContentIdentityTest {

    @Test
    void modelAndAnimationIdentityUsesRawObjectIdentityNotNamesOrFiles() {
        final Object raw = new Object();

        assertEquals(
            "model:" + Integer.toUnsignedString(System.identityHashCode(raw), 16),
            ProjectContentIdentity.forLifecycleContent(ProjectContentKind.MODEL, raw)
        );
        assertEquals(
            "animation:" + Integer.toUnsignedString(System.identityHashCode(raw), 16),
            ProjectContentIdentity.forLifecycleContent(ProjectContentKind.ANIMATION, raw)
        );
    }
}
