package dev.turboism.test.fake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FakeCubismHostTest {

    @Test
    void hostStartsAndStops() {
        FakeCubismHost host = new FakeCubismHost();
        assertFalse(host.isRunning());
        host.start();
        assertTrue(host.isRunning());
        host.stop();
        assertFalse(host.isRunning());
    }
}
