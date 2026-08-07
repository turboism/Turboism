package dev.turboism.runtime.log;

import dev.turboism.sdk.runtime.CubismLogService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CubismLogServiceHostTest {
    @Test
    void subscribersReceiveTheUnfilteredStreamUntilRegistrationCloses() {
        final CubismLogServiceHost host = new CubismLogServiceHost();
        final List<CubismLogService.LogEntry> entries = new ArrayList<>();
        final var registration = host.subscribe(entries::add);
        host.setFilter(new CubismLogService.LogFilter(false, false, true, "needle"));

        host.publish(CubismLogService.LogLevel.INFO, "not filtered for subscribers", 1L);
        assertEquals(1, entries.size());
        assertEquals("needle", host.filter().keyword());

        registration.close();
        host.publish(CubismLogService.LogLevel.ERROR, "needle", 2L);
        assertEquals(1, entries.size());

        host.close();
        host.close();
    }
}
