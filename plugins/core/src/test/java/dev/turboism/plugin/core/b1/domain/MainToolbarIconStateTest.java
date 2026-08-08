package dev.turboism.plugin.core.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class MainToolbarIconStateTest {

    @Test
    void normalAndHoverShareTheFrozenCurrentColorResource() throws Exception {
        final MainToolbarIconDescriptor normal = MainToolbarIconState.NORMAL.descriptor();
        final MainToolbarIconDescriptor hover = MainToolbarIconState.HOVER.descriptor();
        assertEquals("icons/main-toolbar-home.svg", normal.resourcePath());
        assertEquals(normal.resourcePath(), hover.resourcePath());
        assertEquals(IconTintMode.CURRENT_COLOR, normal.tintMode());
        assertEquals("main-toolbar.home.aria-label", normal.ariaLabelKey());
        assertEquals("main-toolbar.home.tooltip", normal.tooltipKey());
        assertFalse(normal.resourcePath().startsWith("/"));
        try (InputStream input = MainToolbarIconStateTest.class.getClassLoader().getResourceAsStream(normal.resourcePath())) {
            final byte[] bytes = java.util.Objects.requireNonNull(input).readAllBytes();
            assertEquals(192, bytes.length);
            assertEquals("c2f841d2ac6cda6b60c47d71592c62a41142127936cf8a402bb4a4a8617e8b3e",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("currentColor"));
        }
    }
}
