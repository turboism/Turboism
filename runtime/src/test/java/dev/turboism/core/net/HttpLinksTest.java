package dev.turboism.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class HttpLinksTest {

    @Test
    void admitsHttpAndHttpsUrisWithHosts() {
        assertTrue(HttpLinks.isAllowed("https://turboism.dev/docs"));
        assertTrue(HttpLinks.isAllowed("http://example.com"));
        assertTrue(HttpLinks.isAllowed(URI.create("HTTPS://example.com/path?q=1")));
    }

    @Test
    void refusesNonWebSchemes() {
        assertFalse(HttpLinks.isAllowed("file:///etc/passwd"));
        assertFalse(HttpLinks.isAllowed("smb://host/share"));
        assertFalse(HttpLinks.isAllowed("javascript:alert(1)"));
        assertFalse(HttpLinks.isAllowed("custom-scheme://host/thing"));
    }

    @Test
    void refusesMissingHostAndUnparseableInput() {
        assertFalse(HttpLinks.isAllowed("https:///no-host"));
        assertFalse(HttpLinks.isAllowed("not a uri"));
        assertFalse(HttpLinks.isAllowed(""));
        assertFalse(HttpLinks.isAllowed((String) null));
        assertFalse(HttpLinks.isAllowed((URI) null));
    }
}
